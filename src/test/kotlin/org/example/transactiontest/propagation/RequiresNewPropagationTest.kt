package org.example.transactiontest.propagation

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import org.example.transactiontest.exception.OuterFailureException
import org.example.transactiontest.repository.LogsRepository
import org.example.transactiontest.service.InnerService
import org.example.transactiontest.service.OuterService
import org.example.transactiontest.support.DatabaseCleaner
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

/**
 * ## PROPAGATION_REQUIRES_NEW — 물리 트랜잭션을 완전히 분리한다
 *
 * REQUIRES_NEW 는 진행 중인 트랜잭션을 **중단(suspend)** 시킨다.
 * 중단이란 ThreadLocal 에 바인딩돼 있던 EntityManager/커넥션을 잠시 떼어내 보관하고,
 * 새 EntityManager/커넥션으로 갈아끼우는 것을 말한다. 자식이 끝나면 원래 것을 되돌려 놓는다.
 *
 * 결과적으로 두 트랜잭션은 서로에게 **완전한 남**이다.
 * - 자식이 롤백해도 부모는 멀쩡하다 (rollback-only 오염이 없다)
 * - 부모가 롤백해도 이미 커밋된 자식은 살아남는다
 * - 부모가 아직 커밋하지 않은 데이터를 자식은 볼 수 없다 (커넥션이 다르므로)
 *
 * 마지막 항목은 실무에서 자주 사고를 낸다. "방금 저장했는데 왜 안 보이지?"의 원인이며,
 * 심할 경우 부모가 잠근 행을 자식이 다시 잠그려다 **자기 자신과 데드락**에 빠진다.
 *
 * 검증 항목
 * 1. 부모와 자식의 EntityManager 가 다르다 (= 물리 트랜잭션 2개)
 * 2. 자식 실패 + 부모 catch -> 자식만 롤백, 부모는 커밋 (REQUIRED 와 결정적으로 다른 점)
 * 3. 자식 성공 후 부모 실패 -> 자식은 살아남는다 (감사 로그 패턴)
 * 4. 부모의 미커밋 데이터는 자식에게 보이지 않는다
 */
@Suppress("UNUSED")
@SpringBootTest
class RequiresNewPropagationTest(
    @Autowired private val outerService: OuterService,
    @Autowired private val logsRepository: LogsRepository,
    @Autowired private val databaseCleaner: DatabaseCleaner,
) : BehaviorSpec() {
    init {
        beforeTest { databaseCleaner.clean() }

        Given("바깥(REQUIRED)이 안쪽(REQUIRES_NEW)을 호출할 때") {
            When("둘 다 정상적으로 끝나면") {
                Then("서로 다른 EntityManager 를 쓰는 별개의 물리 트랜잭션 두 개가 된다") {
                    val trace = outerService.requiredCallingRequiresNew()

                    // REQUIRED 와의 차이가 여기서 드러난다. EntityManager 자체가 다르다.
                    trace.inner.sharesPhysicalTransactionWith(trace.outer).shouldBeFalse()
                    trace.inner.actualTransactionActive.shouldBeTrue()

                    // 자식이 스스로 물리 트랜잭션을 시작했으므로 트랜잭션 이름도 자식의 것이다.
                    trace.inner.transactionName!!.substringAfterLast('.') shouldBe "requiresNew"
                    trace.outer.transactionName!!.substringAfterLast('.') shouldBe "requiredCallingRequiresNew"

                    logsRepository.count() shouldBe 2
                }
            }

            When("안쪽이 예외를 던지고 바깥이 그 예외를 잡으면") {
                Then("안쪽만 롤백되고 바깥은 rollback-only 오염 없이 정상 커밋된다") {
                    // 같은 상황을 REQUIRED 로 하면 UnexpectedRollbackException 이 터졌다.
                    // REQUIRES_NEW 는 롤백 대상이 물리적으로 분리돼 있어 예외를 잡고 복구까지 할 수 있다.
                    outerService.requiredCallingRequiresNewAndCatch()

                    logsRepository.countByMessage(InnerService.INNER_MESSAGE) shouldBe 0
                    logsRepository.countByMessage(OuterService.OUTER_MESSAGE) shouldBe 1
                    logsRepository.countByMessage(OuterService.RECOVERY_MESSAGE) shouldBe 1
                }
            }

            When("안쪽이 먼저 커밋된 뒤 바깥이 실패하면") {
                Then("이미 커밋된 안쪽 데이터는 바깥의 롤백에도 살아남는다") {
                    // 자식의 트랜잭션은 자식 메서드가 끝나는 순간 커밋된다.
                    // 그래서 뒤늦은 부모의 롤백은 자식에게 아무 영향을 주지 못한다.
                    // "실패해도 반드시 남겨야 하는 감사 로그"를 REQUIRES_NEW 로 남기는 이유.
                    shouldThrow<OuterFailureException> {
                        outerService.requiredCallingRequiresNewThenOuterFails()
                    }

                    logsRepository.countByMessage(OuterService.OUTER_MESSAGE) shouldBe 0
                    logsRepository.countByMessage(InnerService.INNER_MESSAGE) shouldBe 1
                }
            }

            When("바깥이 저장만 하고 아직 커밋하지 않은 상태에서 안쪽이 전체 건수를 세면") {
                Then("커넥션이 다르므로 바깥의 미커밋 데이터는 보이지 않아 0건으로 읽힌다") {
                    // 바깥은 flush 까지 해서 INSERT 문을 DB 로 보냈지만 아직 커밋하지 않았다.
                    // 자식은 별도 커넥션이고 기본 격리 수준이 READ_COMMITTED 이므로 그 행을 볼 수 없다.
                    //
                    // 실무 함정: 부모가 방금 만든 데이터를 REQUIRES_NEW 자식에게 넘기지 않고
                    // "id 로 다시 조회"하게 만들면 여기서 조용히 NotFound 가 난다.
                    val countSeenByInner = outerService.requiredCallingRequiresNewThatCountsRows()

                    countSeenByInner shouldBe 0

                    // 바깥 트랜잭션이 커밋된 뒤에는 당연히 보인다.
                    logsRepository.count() shouldBe 1
                }
            }
        }
    }
}
