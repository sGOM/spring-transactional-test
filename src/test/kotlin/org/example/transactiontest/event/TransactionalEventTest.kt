package org.example.transactiontest.event

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldContainInOrder
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.example.transactiontest.exception.OuterFailureException
import org.example.transactiontest.repository.LogsRepository
import org.example.transactiontest.support.DatabaseCleaner
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

/**
 * ## 트랜잭션 이벤트 — "커밋된 뒤에만" 부수효과를 실행하기
 *
 * 주문을 저장하고 이메일을 보내는 코드를 생각해 보자.
 * 저장 직후에 메일을 보내면, 뒤이어 트랜잭션이 롤백됐을 때
 * **DB 에는 없는 주문에 대한 메일**이 이미 나가버린다.
 *
 * `@TransactionalEventListener` 는 이벤트 처리를 트랜잭션 동기화에 등록해
 * 커밋/롤백이 확정된 뒤에 실행되도록 미룬다.
 *
 * | 리스너                                   | 실행 시점                      |
 * |----------------------------------------|------------------------------|
 * | `@EventListener`                        | publishEvent 즉시 (같은 트랜잭션) |
 * | `@TransactionalEventListener(BEFORE_COMMIT)`  | 커밋 직전 (여기서 던진 예외는 롤백 가능) |
 * | `@TransactionalEventListener(AFTER_COMMIT)`   | 커밋 직후                   |
 * | `@TransactionalEventListener(AFTER_ROLLBACK)` | 롤백 직후                   |
 * | `@TransactionalEventListener(AFTER_COMPLETION)`| 커밋/롤백 무관 마지막          |
 *
 * ### 함정 두 가지
 * 1. 트랜잭션 **밖**에서 발행한 이벤트는 `@TransactionalEventListener` 가 그냥 무시한다.
 *    (`fallbackExecution = true` 를 줘야 실행된다)
 * 2. AFTER_COMMIT 시점에는 커밋할 트랜잭션이 이미 끝났다.
 *    여기서 DB 를 쓰려면 `REQUIRES_NEW` 로 새 트랜잭션을 열어야 한다.
 */
@Suppress("UNUSED")
@SpringBootTest
class TransactionalEventTest(
    @Autowired private val orderService: OrderService,
    @Autowired private val orderEventRecorder: OrderEventRecorder,
    @Autowired private val logsRepository: LogsRepository,
    @Autowired private val databaseCleaner: DatabaseCleaner,
) : BehaviorSpec() {
    init {
        beforeTest {
            databaseCleaner.clean()
            orderEventRecorder.reset()
        }

        Given("트랜잭션 안에서 이벤트를 발행했을 때") {
            When("트랜잭션이 정상 커밋되면") {
                Then("@EventListener 가 가장 먼저, 트랜잭션 리스너는 커밋 전후에 실행된다") {
                    orderService.placeOrder("A-1")

                    // @EventListener 는 publishEvent 시점에 곧바로, 즉 커밋되기 한참 전에 실행된다.
                    orderEventRecorder.phases.first() shouldBe "IMMEDIATE"

                    orderEventRecorder.phases shouldContainAll listOf(
                        "BEFORE_COMMIT",
                        "AFTER_COMMIT",
                        "AFTER_COMPLETION",
                    )
                    // 커밋됐으므로 롤백 리스너는 호출되지 않는다.
                    orderEventRecorder.phases shouldNotContain "AFTER_ROLLBACK"

                    // 단계 사이의 순서는 보장된다: 커밋 직전 -> 커밋 직후.
                    orderEventRecorder.phases shouldContainInOrder listOf("BEFORE_COMMIT", "AFTER_COMMIT")

                    // 주의: 같은 phase 안에서 리스너들끼리의 순서는 보장되지 않는다.
                    // 순서가 중요하다면 @Order 를 명시해야 한다.
                }

                Then("AFTER_COMMIT 리스너가 REQUIRES_NEW 로 남긴 감사 로그는 실제로 커밋된다") {
                    orderService.placeOrder("A-2")

                    orderEventRecorder.phases shouldContain "AFTER_COMMIT_AUDIT"
                    // 원래 트랜잭션은 이미 끝났으므로, 새 트랜잭션을 열어야만 이 저장이 커밋된다.
                    logsRepository.countByMessage("${OrderEventRecorder.AUDIT_PREFIX}A-2") shouldBe 1
                }
            }

            When("트랜잭션이 롤백되면") {
                Then("AFTER_COMMIT 은 실행되지 않고 AFTER_ROLLBACK 만 실행된다") {
                    // 핵심 가치: 롤백된 주문에 대해 메일이 나가는 사고를 구조적으로 막아준다.
                    shouldThrow<OuterFailureException> {
                        orderService.placeOrder("B-1", fail = true)
                    }

                    // @EventListener 는 트랜잭션과 무관하므로 이미 실행되어 버렸다는 점에 주의.
                    orderEventRecorder.phases shouldContain "IMMEDIATE"

                    orderEventRecorder.phases shouldNotContain "AFTER_COMMIT"
                    orderEventRecorder.phases shouldNotContain "AFTER_COMMIT_AUDIT"
                    orderEventRecorder.phases shouldContain "AFTER_ROLLBACK"
                    orderEventRecorder.phases shouldContain "AFTER_COMPLETION"

                    // BEFORE_COMMIT 은 커밋 시도 자체를 하지 않았으므로 실행되지 않는다.
                    orderEventRecorder.phases shouldNotContain "BEFORE_COMMIT"

                    logsRepository.count() shouldBe 0
                }
            }
        }

        Given("트랜잭션 없이 이벤트를 발행했을 때") {
            When("리스너들의 실행 여부를 확인하면") {
                Then("fallbackExecution = true 인 리스너만 실행되고 나머지는 조용히 무시된다") {
                    // 실무에서 "왜 리스너가 안 타지?"의 1순위 원인.
                    // 발행하는 메서드에 @Transactional 이 빠져 있으면 아무 일도 일어나지 않는다.
                    orderService.placeOrderWithoutTransaction("C-1")

                    orderEventRecorder.phases shouldContainExactly listOf(
                        "IMMEDIATE",
                        "AFTER_COMMIT_FALLBACK",
                    )
                }
            }
        }

        Given("TransactionSynchronizationManager 에 콜백을 직접 등록했을 때") {
            When("트랜잭션이 커밋되면") {
                Then("beforeCommit -> afterCommit -> afterCompletion(COMMITTED) 순서로 호출된다") {
                    // @TransactionalEventListener 도 내부적으로는 이 저수준 메커니즘 위에 얹혀 있다.
                    val callbacks = mutableListOf<String>()

                    orderService.placeOrderWithManualSynchronization("D-1", callbacks)

                    callbacks shouldContainExactly listOf(
                        "beforeCommit",
                        "afterCommit",
                        "afterCompletion(COMMITTED)",
                    )
                }
            }

            When("트랜잭션이 롤백되면") {
                Then("afterCompletion(ROLLED_BACK) 만 호출된다") {
                    val callbacks = mutableListOf<String>()

                    shouldThrow<OuterFailureException> {
                        orderService.placeOrderWithManualSynchronization("D-2", callbacks, fail = true)
                    }

                    callbacks shouldContainExactly listOf("afterCompletion(ROLLED_BACK)")
                }
            }
        }
    }
}
