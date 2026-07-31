package org.example.transactiontest.rollback

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.example.transactiontest.exception.BusinessCheckedException
import org.example.transactiontest.exception.InnerFailureException
import org.example.transactiontest.repository.LogsRepository
import org.example.transactiontest.service.RollbackPolicyService
import org.example.transactiontest.support.DatabaseCleaner
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

/**
 * ## 무엇이 롤백을 유발하는가 — 예외 "타입"이 정한다
 *
 * Spring 의 기본 규칙(`DefaultTransactionAttribute.rollbackOn`)은 단순하다.
 *
 * ```
 * ex is RuntimeException || ex is Error  ->  롤백
 * 그 외 (체크 예외)                       ->  커밋
 * ```
 *
 * 이 규칙은 EJB 시절의 관례를 이어받은 것으로,
 * "체크 예외 = 호출자가 복구할 수 있는 비즈니스 상황이므로 작업을 확정한다"는 발상이다.
 *
 * ### Kotlin 에서 특히 위험한 이유
 * Kotlin 에는 체크 예외 개념이 없다. `throws` 를 선언할 필요도, 호출부에서 잡을 의무도 없다.
 * 그래서 `class MyException : Exception()` 을 무심코 만들어 던지면
 * **컴파일러는 아무 경고도 하지 않는데 트랜잭션은 커밋되어 버린다.**
 *
 * 대응: 도메인 예외의 부모를 `RuntimeException` 으로 두거나,
 * `@Transactional(rollbackFor = [Exception::class])` 을 명시한다.
 */
@Suppress("UNUSED")
@SpringBootTest
class RollbackPolicyTest(
    @Autowired private val rollbackPolicyService: RollbackPolicyService,
    @Autowired private val logsRepository: LogsRepository,
    @Autowired private val databaseCleaner: DatabaseCleaner,
) : BehaviorSpec() {
    init {
        beforeTest { databaseCleaner.clean() }

        Given("기본 롤백 규칙") {
            When("언체크 예외(RuntimeException)를 던지면") {
                Then("롤백된다") {
                    shouldThrow<InnerFailureException> {
                        rollbackPolicyService.saveThenThrowUnchecked()
                    }

                    logsRepository.count() shouldBe 0
                }
            }

            When("체크 예외(Exception)를 던지면") {
                Then("롤백되지 않고 그대로 커밋된다 — Kotlin 에서 가장 놓치기 쉬운 함정") {
                    // 예외는 정상적으로 밖으로 전파되지만 트랜잭션은 커밋된다.
                    // "예외가 터졌으니 당연히 롤백됐겠지"라는 가정이 깨지는 지점.
                    shouldThrow<BusinessCheckedException> {
                        rollbackPolicyService.saveThenThrowChecked()
                    }

                    logsRepository.count() shouldBe 1
                }
            }

            When("Error 를 던지면") {
                Then("Error 도 기본 롤백 대상이므로 롤백된다") {
                    shouldThrow<StackOverflowError> {
                        rollbackPolicyService.saveThenThrowError()
                    }

                    logsRepository.count() shouldBe 0
                }
            }
        }

        Given("롤백 규칙을 직접 지정했을 때") {
            When("rollbackFor 로 체크 예외를 롤백 대상에 추가하면") {
                Then("체크 예외에서도 롤백된다") {
                    shouldThrow<BusinessCheckedException> {
                        rollbackPolicyService.saveThenThrowCheckedWithRollbackFor()
                    }

                    logsRepository.count() shouldBe 0
                }
            }

            When("noRollbackFor 로 언체크 예외를 롤백 대상에서 제외하면") {
                Then("언체크 예외인데도 커밋된다") {
                    // "재고 부족" 처럼 예외로 흐름은 끊되 그때까지의 기록은 남겨야 하는 경우에 쓴다.
                    shouldThrow<IllegalStateException> {
                        rollbackPolicyService.saveThenThrowNoRollbackFor()
                    }

                    logsRepository.count() shouldBe 1
                }
            }
        }

        Given("예외를 쓰지 않고 롤백하고 싶을 때") {
            When("setRollbackOnly() 로 직접 롤백을 지시하면") {
                Then("예외 없이 정상 반환하지만 커밋되지 않는다") {
                    // 반환값으로 실패를 표현하는 API(Result 타입 등)에서 쓰는 방법.
                    // 단, 이 메서드가 다른 트랜잭션에 참여 중이었다면 부모까지 오염시켜
                    // UnexpectedRollbackException 을 유발한다는 점은 REQUIRED 와 동일하다.
                    val result = rollbackPolicyService.saveThenMarkRollbackOnly()

                    result shouldBe "예외 없이 정상 반환하지만 커밋되지 않는다"
                    logsRepository.count() shouldBe 0
                }
            }
        }
    }
}
