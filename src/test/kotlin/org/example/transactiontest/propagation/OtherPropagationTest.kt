package org.example.transactiontest.propagation

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.example.transactiontest.exception.OuterFailureException
import org.example.transactiontest.repository.LogsRepository
import org.example.transactiontest.service.InnerService
import org.example.transactiontest.service.OuterService
import org.example.transactiontest.support.DatabaseCleaner
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.IllegalTransactionStateException

/**
 * ## MANDATORY / NEVER / SUPPORTS / NOT_SUPPORTED
 *
 * 나머지 네 개의 전파 속성은 "새 트랜잭션을 만드는 방법"이 아니라
 * **호출 문맥에 대한 제약 혹은 회피**에 가깝다.
 *
 * | 전파 속성       | 부모 트랜잭션 있음        | 부모 트랜잭션 없음          |
 * |----------------|-------------------------|---------------------------|
 * | MANDATORY      | 참여                     | 예외 (IllegalTransactionState) |
 * | NEVER          | 예외                     | 트랜잭션 없이 실행          |
 * | SUPPORTS       | 참여                     | 트랜잭션 없이 실행          |
 * | NOT_SUPPORTED  | 부모 중단 후 없이 실행     | 트랜잭션 없이 실행          |
 *
 * SUPPORTS 와 NOT_SUPPORTED 는 특히 조심해야 한다.
 * "트랜잭션 없이 실행"은 곧 **자동 커밋**을 의미하므로, 이 안에서 수행한 쓰기는
 * 부모가 롤백해도 되돌아가지 않는다.
 */
@Suppress("UNUSED")
@SpringBootTest
class OtherPropagationTest(
    @Autowired private val outerService: OuterService,
    @Autowired private val logsRepository: LogsRepository,
    @Autowired private val databaseCleaner: DatabaseCleaner,
) : BehaviorSpec() {
    init {
        beforeTest { databaseCleaner.clean() }

        Given("MANDATORY — 반드시 부모 트랜잭션 안에서 호출되어야 하는 메서드") {
            When("부모 트랜잭션(REQUIRED) 안에서 호출하면") {
                Then("새 트랜잭션을 만들지 않고 부모에 그대로 참여한다") {
                    val trace = outerService.requiredCallingMandatory()

                    trace.inner.sharesPhysicalTransactionWith(trace.outer).shouldBeTrue()
                    // 참여만 했으므로 트랜잭션 이름은 여전히 부모의 것이다.
                    trace.inner.transactionName shouldBe trace.outer.transactionName

                    logsRepository.count() shouldBe 2
                }
            }

            When("트랜잭션 없이 호출하면") {
                Then("IllegalTransactionStateException 이 발생하며 아무것도 저장되지 않는다") {
                    // "이 메서드는 절대 단독 호출되면 안 된다"를 런타임에 강제하는 장치다.
                    // 조용히 자동 커밋되는 것보다 즉시 터지는 편이 안전한 경우에 쓴다.
                    shouldThrow<IllegalTransactionStateException> {
                        outerService.noneCallingMandatory()
                    }

                    logsRepository.count() shouldBe 0
                }
            }
        }

        Given("NEVER — 트랜잭션 안에서 호출되면 안 되는 메서드") {
            When("트랜잭션 없이 호출하면") {
                Then("트랜잭션 없이 정상 실행된다") {
                    val snapshot = outerService.noneCallingNever()

                    snapshot.actualTransactionActive.shouldBeFalse()
                    logsRepository.countByMessage(InnerService.INNER_MESSAGE) shouldBe 1
                }
            }

            When("부모 트랜잭션 안에서 호출하면") {
                Then("IllegalTransactionStateException 이 발생한다") {
                    // 트랜잭션을 오래 붙잡으면 안 되는 작업(외부 API 호출 등)에
                    // 방어적으로 걸어두는 용도.
                    shouldThrow<IllegalTransactionStateException> {
                        outerService.requiredCallingNever()
                    }

                    logsRepository.count() shouldBe 0
                }
            }
        }

        Given("SUPPORTS — 있으면 참여하고 없으면 트랜잭션 없이 실행") {
            When("부모 트랜잭션 안에서 호출하면") {
                Then("부모의 물리 트랜잭션에 참여한다") {
                    val trace = outerService.requiredCallingSupports()

                    trace.inner.sharesPhysicalTransactionWith(trace.outer).shouldBeTrue()
                    logsRepository.count() shouldBe 2
                }
            }

            When("트랜잭션 없이 호출하면") {
                Then("물리 트랜잭션이 열리지 않은 채로 실행되고 저장은 즉시 커밋된다") {
                    val snapshot = outerService.noneCallingSupports()

                    // 트랜잭션 동기화 자체는 켜지지만 실제 물리 트랜잭션은 없다.
                    // 이 상태에서의 save() 는 자동 커밋이므로 롤백 대상이 되지 않는다.
                    snapshot.actualTransactionActive.shouldBeFalse()
                    snapshot.entityManagerId.shouldBeNull()

                    logsRepository.countByMessage(InnerService.INNER_MESSAGE) shouldBe 1
                }
            }
        }

        Given("NOT_SUPPORTED — 부모가 있어도 트랜잭션 없이 실행") {
            When("부모 트랜잭션 안에서 호출하면") {
                Then("부모 트랜잭션이 중단(suspend)되고 트랜잭션 없이 실행된다") {
                    val trace = outerService.requiredCallingNotSupported()

                    trace.outer.actualTransactionActive.shouldBeTrue()
                    trace.inner.actualTransactionActive.shouldBeFalse()
                    // 부모의 EntityManager 는 잠시 떼어내어 보관되므로 자식 쪽에서는 보이지 않는다.
                    trace.inner.entityManagerId.shouldBeNull()

                    logsRepository.count() shouldBe 2
                }
            }

            When("안쪽이 저장한 뒤 부모가 실패하면") {
                Then("트랜잭션 밖에서 즉시 커밋된 안쪽 데이터만 살아남는다") {
                    // NOT_SUPPORTED 안의 쓰기는 롤백할 방법이 없다.
                    // "읽기 전용 집계"처럼 쓰기가 없는 작업에만 쓰는 것이 안전하다.
                    shouldThrow<OuterFailureException> {
                        outerService.requiredCallingNotSupportedThenOuterFails()
                    }

                    logsRepository.countByMessage(OuterService.OUTER_MESSAGE) shouldBe 0
                    logsRepository.countByMessage(InnerService.INNER_MESSAGE) shouldBe 1
                }
            }
        }
    }
}
