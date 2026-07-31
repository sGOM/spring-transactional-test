package org.example.transactiontest.propagation

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.example.transactiontest.exception.OuterFailureException
import org.example.transactiontest.repository.LogsRepository
import org.example.transactiontest.service.OuterService
import org.example.transactiontest.service.SavepointService
import org.example.transactiontest.support.DatabaseCleaner
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.NestedTransactionNotSupportedException

/**
 * ## PROPAGATION_NESTED — 세이브포인트를 이용한 부분 롤백, 그리고 JPA 에서의 한계
 *
 * NESTED 는 REQUIRED 와 REQUIRES_NEW 의 중간에 있다.
 *
 * |               | 물리 트랜잭션 | 자식 실패 시           | 부모 실패 시    |
 * |---------------|-------------|----------------------|---------------|
 * | REQUIRED      | 공유(1개)    | 부모까지 강제 롤백      | 자식도 롤백     |
 * | NESTED        | 공유(1개)    | **세이브포인트까지만**  | 자식도 롤백     |
 * | REQUIRES_NEW  | 분리(2개)    | 자식만 롤백            | 자식은 살아남음  |
 *
 * 커넥션을 하나만 쓰면서 부분 롤백을 할 수 있다는 것이 장점이다.
 *
 * ### 그런데 JPA 에서는 쓸 수 없다
 * 이 스펙의 전반부는 **NESTED 가 JPA 에서 왜 실패하는지**를 두 단계로 확인한다.
 *
 * 1. `nestedTransactionAllowed` 가 기본 false → "does not allow nested transactions by default"
 *    (이 프로젝트는 `TransactionConfig` 에서 이미 true 로 켜 두었다)
 * 2. 그걸 켜도 `HibernateJpaDialect` 가 `SavepointManager` 를 제공하지 않는다
 *    → "JpaDialect does not support savepoints"
 *
 * 후반부는 [SavepointService] 를 통해 **JDBC 트랜잭션 매니저에서는 NESTED 가 정상 동작**함을
 * 보여 준다. 즉 NESTED 자체가 쓸모없는 것이 아니라, JPA 와 조합할 수 없는 것이다.
 *
 * 실무 결론: JPA 를 쓰면서 부분 롤백이 필요하면 REQUIRES_NEW 로 간다.
 */
@Suppress("UNUSED")
@SpringBootTest
class NestedPropagationTest(
    @Autowired private val outerService: OuterService,
    @Autowired private val savepointService: SavepointService,
    @Autowired private val logsRepository: LogsRepository,
    @Autowired private val databaseCleaner: DatabaseCleaner,
) : BehaviorSpec() {
    init {
        beforeTest { databaseCleaner.clean() }

        Given("JPA 트랜잭션 매니저(JpaTransactionManager + Hibernate)에서 NESTED 를 쓸 때") {
            When("nestedTransactionAllowed 를 켠 상태로 NESTED 자식을 호출하면") {
                Then("Hibernate 가 세이브포인트를 지원하지 않아 NestedTransactionNotSupportedException 이 발생한다") {
                    // 설정을 아무리 맞춰도 여기서 막힌다.
                    // JpaTransactionManager 는 JpaDialect.beginTransaction() 이 돌려준 객체가
                    // SavepointManager 일 때만 세이브포인트를 만들 수 있는데,
                    // HibernateJpaDialect 가 돌려주는 객체는 그렇지 않기 때문이다.
                    val exception = shouldThrow<NestedTransactionNotSupportedException> {
                        outerService.requiredCallingNested()
                    }

                    exception.message.shouldContain("JpaDialect does not support savepoints")

                    // 자식 진입에서 터졌으므로 바깥 트랜잭션도 통째로 롤백된다.
                    logsRepository.count() shouldBe 0
                }
            }
        }

        Given("JDBC 트랜잭션 매니저(DataSourceTransactionManager)에서 NESTED 를 쓸 때") {
            When("NESTED 자식이 예외를 던지고 바깥이 그것을 잡으면") {
                Then("세이브포인트까지만 롤백되어 자식 데이터만 사라지고 바깥은 이어서 커밋된다") {
                    // 같은 상황을 REQUIRED 로 하면 rollback-only 오염으로 전부 롤백됐다.
                    // NESTED 는 자식 진입 시 만들어 둔 세이브포인트로만 되돌리므로
                    // 바깥은 오염되지 않고, 예외를 잡아 복구 작업까지 이어갈 수 있다.
                    savepointService.outerCommitsWhenNestedFails()

                    logsRepository.countByMessage(SavepointService.INNER_MESSAGE) shouldBe 0
                    logsRepository.countByMessage(SavepointService.OUTER_MESSAGE) shouldBe 1
                    logsRepository.countByMessage(SavepointService.RECOVERY_MESSAGE) shouldBe 1
                }
            }

            When("NESTED 자식은 성공했지만 그 뒤 바깥이 실패하면") {
                Then("같은 물리 트랜잭션이므로 자식 작업까지 전부 롤백된다 (REQUIRES_NEW 와 갈리는 지점)") {
                    // REQUIRES_NEW 였다면 자식은 이미 커밋되어 살아남았을 것이다.
                    // NESTED 는 부모 트랜잭션의 일부일 뿐이므로 부모와 운명을 같이한다.
                    shouldThrow<OuterFailureException> {
                        savepointService.everythingRollsBackWhenOuterFails()
                    }

                    logsRepository.count() shouldBe 0
                }
            }

            When("세이브포인트를 두 겹으로 겹치고 가장 안쪽만 실패하면") {
                Then("가장 안쪽 세이브포인트 이후의 작업만 사라지고 나머지는 모두 커밋된다") {
                    savepointService.onlyInnermostSavepointRollsBack()

                    logsRepository.countByMessage(SavepointService.OUTER_MESSAGE) shouldBe 1
                    logsRepository.countByMessage(SavepointService.LEVEL1_MESSAGE) shouldBe 1
                    logsRepository.countByMessage(SavepointService.LEVEL2_MESSAGE) shouldBe 0
                }
            }
        }
    }
}
