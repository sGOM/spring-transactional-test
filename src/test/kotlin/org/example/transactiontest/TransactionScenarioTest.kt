package org.example.transactiontest

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.example.transactiontest.repository.LogsRepository
import org.example.transactiontest.service.ServiceA
import org.example.transactiontest.service.ServiceB
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.UnexpectedRollbackException

@Suppress("UNUSED")
@SpringBootTest
class TransactionScenarioTest(
    @Autowired private val serviceA: ServiceA,
    @Autowired private val logsRepository: LogsRepository,
) : BehaviorSpec() {
    init {
        // 각 테스트마다 테이블 초기화
        afterTest { logsRepository.deleteAll() }

        // ==========================================
        // 시나리오 1: Non-Transactional (트랜잭션 없음)
        // ==========================================
        Given("Am(No Tx)와 Bm(No Tx)이 있을 때") {

            When("Am -> Bm 호출 중 예외가 발생하면") {
                // 메서드 실행 (내부에서 예외를 catch하므로 에러가 밖으로 던져지진 않음)
                serviceA.methodAmNonTransactional()

                Then("각각의 save()가 개별적으로 커밋되어, 롤백되지 않고 데이터가 모두 남아있어야 한다") {
                    val allLogs = logsRepository.findAll()

                    // Service 계층에 트랜잭션이 없으므로, Repository의 save()가 호출될 때마다
                    // 각각 별도의 짧은 트랜잭션이 열리고 -> 커밋되고 -> 닫힘.
                    // 나중에 예외가 터져도 이미 커밋된 건은 취소되지 않음.
                    allLogs.size shouldBe 2
                    (allLogs.find { it.message == ServiceA.MESSAGE }).shouldNotBeNull()
                    (allLogs.find { it.message == ServiceB.MESSAGE }).shouldNotBeNull()
                }
            }
        }

        // ==========================================
        // 시나리오 2: REQUIRED (한 트랜잭션)
        // ==========================================
        Given("Am(REQUIRED)와 Bm(REQUIRED)이 있을 때") {

            When("Am -> Bm 호출 중 Bm에서 예외가 발생하고 Am이 이를 잡으면") {

                Then("Am은 예외를 잡았지만, 트랜잭션이 'rollbackOnly'로 마킹되어 Am의 트랜잭션이 종료되면 UnexpectedRollbackException이 발생해야 한다") {
                    // Am 메서드에서는 Bm의 예외를 catch하기 때문에 예외를 던지지 않지만,
                    // Am 메서드 종료 후, 트랜잭션을 커밋하려는 순간 rollbackOnly flag로 인해 Am의 트랜잭션을 롤백하고 스프링이 예외를 던진다.
                    shouldThrow<UnexpectedRollbackException> {
                        serviceA.methodAmCallingRequired()
                    }

                    logsRepository.count() shouldBe 0
                }
            }
        }

        // ==========================================
        // 시나리오 3: REQUIRES_NEW (분리된 트랜잭션)
        // ==========================================
        Given("Am(REQUIRED)와 Bm(REQUIRES_NEW)이 있을 때") {

            When("Am -> Bm 호출 중 Bm에서 예외가 발생하고 Am이 이를 잡으면") {

                // 메서드 실행 (내부에서 예외를 catch하므로 에러가 밖으로 던져지진 않음)
                // Bm에서 예외가 발생하여 Bm의 트랜잭션은 롤백된다.
                // Am에서는 Bm이 전파한 예외를 catch. Bm과 별개의 트랜잭션이기 때문에
                serviceA.methodAmCallingRequiresNew()

                Then("Bm의 데이터는 롤백되어 사라져야 한다. 하지만 Am의 데이터는 정상적으로 커밋되어 남아있어야 한다") {
                    val allLogs = logsRepository.findAll()

                    // Bm에서 예외가 발생했으므로 Bm의 트랜잭션은 롤백된다.
                    allLogs.count() shouldBe 1
                    (allLogs.find { it.message == ServiceB.MESSAGE }).shouldBeNull()

                    // Am은 Bm에서 발생한 예외를 try-catch로 처리했고, 트랜잭션 역시 별개이므로 정상적으로 커밋된다.
                    (allLogs.first().message == ServiceA.MESSAGE).shouldNotBeNull()
                }
            }
        }
    }
}
