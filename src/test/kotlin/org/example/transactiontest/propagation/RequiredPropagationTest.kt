package org.example.transactiontest.propagation

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import org.example.transactiontest.exception.InnerFailureException
import org.example.transactiontest.repository.LogsRepository
import org.example.transactiontest.service.InnerService
import org.example.transactiontest.service.OuterService
import org.example.transactiontest.support.DatabaseCleaner
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.UnexpectedRollbackException

/**
 * ## PROPAGATION_REQUIRED — 논리 트랜잭션 vs 물리 트랜잭션
 *
 * REQUIRED 는 기본값이며 "진행 중인 트랜잭션이 있으면 참여하고, 없으면 새로 시작"한다.
 * 여기서 중요한 것은 *참여* 가 곧 *중첩* 이 아니라는 점이다.
 *
 * - 논리 트랜잭션: `@Transactional` 이 붙은 메서드 하나하나 (여기서는 2개)
 * - 물리 트랜잭션: 실제 커넥션/EntityManager 와 commit·rollback (여기서는 1개)
 *
 * 물리 트랜잭션이 하나이므로 **부분 롤백이란 존재하지 않는다.** 참여한 안쪽 메서드에서
 * 예외가 프록시를 빠져나오는 순간 공유 트랜잭션에 `rollback-only` 플래그가 찍히고,
 * 바깥이 그 예외를 잡아 정상 종료하더라도 커밋 시점에
 * [UnexpectedRollbackException] 이 터지며 전부 롤백된다.
 *
 * 검증 항목
 * 1. 트랜잭션이 아예 없으면 원자성도 없다 (비교군)
 * 2. REQUIRED 참여 시 부모/자식이 같은 물리 트랜잭션(EntityManager)을 공유한다
 * 3. 예외를 삼켜도 rollback-only 오염 때문에 전부 롤백된다 (가장 흔한 실무 사고)
 * 4. 예외를 그대로 전파하면 UnexpectedRollbackException 이 아니라 원래 예외가 나온다
 * 5. 부모가 없으면 자식이 물리 트랜잭션의 주인이 되어 스스로 롤백한다
 */
@Suppress("UNUSED")
@SpringBootTest
class RequiredPropagationTest(
    @Autowired private val outerService: OuterService,
    @Autowired private val logsRepository: LogsRepository,
    @Autowired private val databaseCleaner: DatabaseCleaner,
) : BehaviorSpec() {
    init {
        beforeTest { databaseCleaner.clean() }

        Given("바깥/안쪽 모두 트랜잭션이 없을 때 (비교군)") {
            When("안쪽에서 예외가 발생하고 바깥이 그것을 잡으면") {
                Then("save() 마다 즉시 커밋되었으므로 두 건 모두 DB 에 남는다") {
                    // 트랜잭션 경계가 없으면 SimpleJpaRepository 의 @Transactional 이
                    // save() 호출마다 짧은 트랜잭션을 열고 곧바로 커밋한다.
                    // 그래서 나중에 예외가 터져도 되돌릴 대상 자체가 없다.
                    outerService.noneCallingNoneAndCatch()

                    logsRepository.count() shouldBe 2
                    logsRepository.existsByMessage(OuterService.OUTER_MESSAGE).shouldBeTrue()
                    logsRepository.existsByMessage(InnerService.INNER_MESSAGE).shouldBeTrue()
                }
            }
        }

        Given("바깥(REQUIRED)이 안쪽(REQUIRED)을 호출할 때") {
            When("둘 다 정상적으로 끝나면") {
                Then("두 메서드는 같은 물리 트랜잭션(EntityManager)을 공유하고 함께 커밋된다") {
                    val trace = outerService.requiredCallingRequired()

                    // 논리 트랜잭션은 2개지만 EntityManager 는 하나 = 물리 트랜잭션 1개
                    trace.inner.sharesPhysicalTransactionWith(trace.outer).shouldBeTrue()
                    trace.inner.actualTransactionActive.shouldBeTrue()

                    // 트랜잭션 이름은 물리 트랜잭션을 "시작한" 바깥 메서드의 것으로 유지된다.
                    // 안쪽은 새로 시작한 것이 아니라 참여했을 뿐이라는 증거.
                    trace.inner.transactionName shouldBe trace.outer.transactionName
                    trace.outer.transactionName!!.substringAfterLast('.') shouldBe "requiredCallingRequired"

                    logsRepository.count() shouldBe 2
                }
            }

            When("안쪽이 예외를 던지고 바깥이 그 예외를 try-catch 로 삼키면") {
                Then("바깥은 정상 종료하지만 커밋 시점에 UnexpectedRollbackException 이 터지고 전부 롤백된다") {
                    // 안쪽 프록시를 예외가 빠져나가는 순간 공유 트랜잭션에 rollback-only 가 찍힌다.
                    // 바깥은 예외를 잡고 추가로 save() 까지 했지만 아무 의미가 없다.
                    val exception = shouldThrow<UnexpectedRollbackException> {
                        outerService.requiredCallingRequiredAndCatch()
                    }

                    exception.message!!.contains("rollback-only") shouldBe true

                    // 바깥의 저장 + 안쪽의 저장 + catch 후 복구 시도까지 전부 사라진다.
                    logsRepository.count() shouldBe 0
                }
            }

            When("안쪽 예외를 바깥이 잡지 않고 그대로 전파하면") {
                Then("UnexpectedRollbackException 이 아니라 원래 예외가 밖으로 나오고 전부 롤백된다") {
                    // 바깥까지 예외가 올라가면 트랜잭션 매니저는 "정상 종료 후 커밋"이 아니라
                    // "예외로 인한 롤백" 경로를 타므로 UnexpectedRollbackException 을 만들지 않는다.
                    // 즉 UnexpectedRollbackException 은 "누군가 예외를 삼켰다"는 신호다.
                    shouldThrow<InnerFailureException> {
                        outerService.requiredCallingRequiredAndRethrow()
                    }

                    logsRepository.count() shouldBe 0
                }
            }
        }

        Given("바깥에 트랜잭션이 없고 안쪽만 REQUIRED 일 때") {
            When("안쪽이 예외를 던지고 바깥이 그것을 잡으면") {
                Then("안쪽이 물리 트랜잭션의 주인이므로 스스로 롤백하고 바깥은 영향을 받지 않는다") {
                    // 참여할 부모가 없으므로 안쪽이 트랜잭션을 새로 연다.
                    // 예외가 안쪽 프록시를 빠져나갈 때 그 자리에서 롤백이 끝나기 때문에
                    // rollback-only 로 오염될 바깥 트랜잭션 자체가 존재하지 않는다.
                    outerService.noneCallingRequiredAndFail()

                    logsRepository.count() shouldBe 0
                }
            }
        }
    }
}
