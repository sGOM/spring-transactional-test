package org.example.transactiontest.propagation

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.example.transactiontest.exception.InnerFailureException
import org.example.transactiontest.exception.OuterFailureException
import org.example.transactiontest.repository.LogsRepository
import org.example.transactiontest.service.InnerService
import org.example.transactiontest.service.MiddleService
import org.example.transactiontest.service.OuterService
import org.example.transactiontest.support.DatabaseCleaner
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

/**
 * ## 여러 트랜잭션이 복잡하게 섞인 경우 — 3계층 이상의 호출 체인
 *
 * 지금까지는 부모-자식 2계층만 봤다. 실무 코드는 서비스가 서비스를 부르고
 * 그 서비스가 또 다른 서비스를 부르며 계층이 쉽게 3~4단계로 깊어진다.
 * 이때 헷갈리기 시작하는 지점은 하나다.
 *
 * > **전파 속성은 "누가 나를 불렀는가"가 아니라
 * > "지금 스레드에 어떤 물리 트랜잭션이 바인딩돼 있는가"로 결정된다.**
 *
 * 그래서 중간 계층이 REQUIRES_NEW 로 트랜잭션을 갈아끼우면,
 * 그 아래의 REQUIRED 자식은 최초의 부모가 아니라 **중간 계층에 참여**한다.
 * 이 사실 하나에서 아래의 모든 결과가 따라 나온다.
 *
 * ### 이 스펙에서 확인하는 것
 * 1. 3계층 체인에서 손자가 실제로 참여하는 대상
 * 2. `rollback-only` 오염이 퍼지는 **범위** (물리 트랜잭션 경계를 넘지 못한다)
 * 3. 중단(suspend)된 트랜잭션이 자식 종료 후 정확히 복원되는지
 * 4. REQUIRES_NEW 남용으로 원자성이 무너지는 모습 (부분 커밋)
 * 5. 한 메서드 안에서 형제 호출의 결과가 갈리는 모습
 * 6. REQUIRES_NEW 가 **자기 자신과 교착 상태**에 빠지는 사고
 */
@Suppress("UNUSED")
@SpringBootTest
class MixedPropagationChainTest(
    @Autowired private val outerService: OuterService,
    @Autowired private val logsRepository: LogsRepository,
    @Autowired private val databaseCleaner: DatabaseCleaner,
) : BehaviorSpec() {
    init {
        beforeTest { databaseCleaner.clean() }

        Given("REQUIRED → REQUIRES_NEW → REQUIRED 체인") {
            When("세 계층의 트랜잭션 상태를 비교하면") {
                Then("손자는 조부모가 아니라 중간 계층의 트랜잭션에 참여한다") {
                    val (outer, middle, inner) = outerService.requiredCallingRequiresNewCallingRequired()

                    // 중간이 REQUIRES_NEW 로 새 물리 트랜잭션을 열었다.
                    middle.sharesPhysicalTransactionWith(outer).shouldBeFalse()

                    // 손자(REQUIRED)는 "현재 바인딩된" 트랜잭션 = 중간의 것에 참여한다.
                    // 최초 호출자인 조부모와는 아무 관계가 없다.
                    inner.sharesPhysicalTransactionWith(middle).shouldBeTrue()
                    inner.sharesPhysicalTransactionWith(outer).shouldBeFalse()

                    // 물리 트랜잭션은 2개(조부모 / 중간+손자), 논리 트랜잭션은 3개다.
                    logsRepository.count() shouldBe 3
                }
            }

            When("손자(REQUIRED)가 실패하고 중간 계층이 그 예외를 삼키면") {
                Then("UnexpectedRollbackException 은 중간에서 터지고, 조부모는 그것을 잡고 살아남는다") {
                    // 손자가 오염시킨 것은 조부모의 트랜잭션이 아니라 중간의 트랜잭션이다.
                    // 따라서 중간 메서드가 끝나는 시점(= 중간 트랜잭션 커밋 시점)에 예외가 터진다.
                    val caught = outerService.requiredCallingMiddleThatSwallowsGrandChildFailure()

                    caught shouldBe "UnexpectedRollbackException"

                    // 중간 + 손자는 통째로 롤백되었지만, 조부모는 예외를 잡고 정상 커밋했다.
                    // => rollback-only 오염은 물리 트랜잭션 경계를 넘지 못한다.
                    logsRepository.countByMessage(MiddleService.MIDDLE_MESSAGE) shouldBe 0
                    logsRepository.countByMessage(InnerService.INNER_MESSAGE) shouldBe 0
                    logsRepository.countByMessage(OuterService.OUTER_MESSAGE) shouldBe 1
                }
            }
        }

        Given("REQUIRED → NOT_SUPPORTED → REQUIRED 체인") {
            When("세 계층의 트랜잭션 상태를 비교하면") {
                Then("중간에서 부모가 중단되어, 손자가 조부모와 무관한 새 물리 트랜잭션을 연다") {
                    val (outer, middle, inner) = outerService.requiredCallingNotSupportedCallingRequired()

                    // 중간은 트랜잭션 없이 실행된다.
                    middle.actualTransactionActive.shouldBeFalse()
                    middle.entityManagerId.shouldBeNull()

                    // 손자는 참여할 트랜잭션이 없으므로 스스로 새로 연다.
                    inner.actualTransactionActive.shouldBeTrue()
                    inner.sharesPhysicalTransactionWith(outer).shouldBeFalse()

                    logsRepository.count() shouldBe 2
                }
            }
        }

        Given("REQUIRED → REQUIRES_NEW → REQUIRES_NEW 체인") {
            When("세 계층의 트랜잭션 상태를 비교하면") {
                Then("세 개 모두 서로 다른 물리 트랜잭션이 된다") {
                    val snapshots = outerService.requiredCallingTwoNestedRequiresNew()

                    snapshots shouldHaveSize 3
                    // EntityManager identity 가 전부 다르면 물리 트랜잭션 3개다.
                    snapshots.mapNotNull { it.entityManagerId }.distinct() shouldHaveSize 3

                    logsRepository.count() shouldBe 3
                }
            }
        }

        Given("중단(suspend)과 복원(resume)") {
            When("REQUIRES_NEW 자식을 호출한 뒤 부모로 돌아오면") {
                Then("부모는 중단 전과 완전히 동일한 물리 트랜잭션으로 복원된다") {
                    // suspend 는 ThreadLocal 에 바인딩된 리소스를 잠시 떼어내 보관하는 것이고,
                    // resume 은 그것을 원래 자리에 되돌려 놓는 것이다.
                    val (before, inner, after) = outerService.snapshotsAroundSuspendAndResume()

                    inner.sharesPhysicalTransactionWith(before).shouldBeFalse()
                    after.sharesPhysicalTransactionWith(before).shouldBeTrue()
                    after.transactionName shouldBe before.transactionName
                }
            }
        }

        Given("REQUIRES_NEW 로 독립 커밋되는 단위 작업을 여러 번 수행한 뒤 부모가 실패할 때") {
            When("결과를 확인하면") {
                Then("이미 커밋된 단위 작업들만 남아 '절반만 처리된' 상태가 된다") {
                    // REQUIRES_NEW 를 남용하면 트랜잭션이 보장해 주던 원자성이 사라진다.
                    // 이런 구조에서는 실패 시 되돌리는 보상 트랜잭션을 직접 짜야 한다.
                    shouldThrow<OuterFailureException> {
                        outerService.requiredCallingSeveralRequiresNewThenFails()
                    }

                    logsRepository.countByMessage(OuterService.STEP1_MESSAGE) shouldBe 1
                    logsRepository.countByMessage(OuterService.STEP2_MESSAGE) shouldBe 1
                    logsRepository.countByMessage(OuterService.OUTER_MESSAGE) shouldBe 0
                }
            }
        }

        Given("한 메서드에서 REQUIRES_NEW 자식과 REQUIRED 자식을 차례로 부를 때") {
            When("뒤에 부른 REQUIRED 자식이 실패하면") {
                Then("앞의 REQUIRES_NEW 는 살아남고 부모와 REQUIRED 자식만 롤백된다") {
                    // 같은 메서드 안에서 두 자식의 운명이 갈린다.
                    // 어떤 자식이 어떤 물리 트랜잭션에 속하는지를 알아야만 결과를 예측할 수 있다.
                    shouldThrow<InnerFailureException> {
                        outerService.requiredCallingRequiresNewThenFailingRequired()
                    }

                    logsRepository.countByMessage(OuterService.SIBLING_MESSAGE) shouldBe 1
                    logsRepository.countByMessage(OuterService.OUTER_MESSAGE) shouldBe 0
                    logsRepository.countByMessage(InnerService.INNER_MESSAGE) shouldBe 0
                }
            }
        }
    }
}
