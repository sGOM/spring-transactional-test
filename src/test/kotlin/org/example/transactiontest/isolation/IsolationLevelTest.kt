package org.example.transactiontest.isolation

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.example.transactiontest.entity.Logs
import org.example.transactiontest.repository.CounterRepository
import org.example.transactiontest.repository.LogsRepository
import org.example.transactiontest.service.CounterService
import org.example.transactiontest.support.DatabaseCleaner
import org.example.transactiontest.support.Signal
import org.example.transactiontest.support.TxExecutor
import org.example.transactiontest.support.runConcurrently
import org.example.transactiontest.support.worker
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.annotation.Isolation
import java.util.concurrent.atomic.AtomicLong

/**
 * ## 격리 수준 — 동시에 도는 트랜잭션이 서로를 얼마나 보는가
 *
 * 격리 수준은 성능과 정합성 사이의 다이얼이다. 낮출수록 빠르지만 아래 이상 현상이 허용된다.
 *
 * | 이상 현상            | 설명                                                    |
 * |--------------------|--------------------------------------------------------|
 * | Dirty Read         | 아직 커밋되지 않은 남의 변경을 읽는다                        |
 * | Non-Repeatable Read| 같은 행을 두 번 읽었는데 값이 달라진다                       |
 * | Phantom Read       | 같은 조건으로 두 번 조회했는데 행의 개수가 달라진다             |
 *
 * | 격리 수준           | Dirty | Non-Repeatable | Phantom |
 * |-------------------|-------|----------------|---------|
 * | READ_UNCOMMITTED  |  O    |       O        |    O    |
 * | READ_COMMITTED    |  X    |       O        |    O    |
 * | REPEATABLE_READ   |  X    |       X        |  (구현별) |
 * | SERIALIZABLE      |  X    |       X        |    X    |
 *
 * ### JPA 로 이걸 테스트할 때 반드시 알아야 할 것
 * **1차 캐시가 격리 수준을 가린다.** 같은 트랜잭션에서 같은 PK 를 두 번 조회하면
 * 두 번째는 DB 로 가지 않고 영속성 컨텍스트에서 반환된다. 그래서 격리 수준이 무엇이든
 * 항상 같은 값이 보인다. DB 의 격리 수준을 관찰하려면 `EntityManager.clear()` 가 필요하다.
 * 이 스펙의 첫 번째 시나리오가 바로 그것을 확인한다.
 *
 * ### 실행 방식
 * 두 개의 동시 트랜잭션이 필요하므로 스레드를 두 개 쓴다.
 * `Thread.sleep` 대신 [Signal] 로 진행 순서를 고정해 결과가 흔들리지 않게 했다.
 *
 * 참고: 여기서 관찰되는 결과는 **H2 2.x(MVStore)** 기준이다.
 * 격리 수준의 구체적 동작은 DB 구현마다 다르다.
 */
@Suppress("UNUSED")
@SpringBootTest
class IsolationLevelTest(
    @Autowired private val counterService: CounterService,
    @Autowired private val counterRepository: CounterRepository,
    @Autowired private val logsRepository: LogsRepository,
    @Autowired private val txExecutor: TxExecutor,
    @Autowired private val databaseCleaner: DatabaseCleaner,
) : BehaviorSpec() {
    init {
        beforeTest { databaseCleaner.clean() }

        Given("JPA 1차 캐시와 격리 수준") {
            When("한 트랜잭션 안에서 clear() 없이 같은 행을 두 번 읽으면") {
                Then("그 사이 다른 트랜잭션이 커밋해도 1차 캐시 때문에 첫 값이 그대로 보인다") {
                    // 격리 수준을 가장 낮게 잡아도 결과가 바뀌지 않는다.
                    // DB 를 아예 다시 읽지 않기 때문이다. 격리 수준 실험의 전제 조건.
                    val id = counterService.create(COUNTER_NAME, 100)
                    val firstRead = AtomicLong()
                    val secondRead = AtomicLong()
                    val readerLoadedOnce = Signal("readerLoadedOnce")
                    val writerCommitted = Signal("writerCommitted")

                    val reader = worker("reader") {
                        txExecutor.newTransaction(isolation = Isolation.READ_COMMITTED) {
                            firstRead.set(counterRepository.findById(id).orElseThrow().value)
                            readerLoadedOnce.send()
                            writerCommitted.await()
                            // clear() 를 하지 않는다 = 1차 캐시에서 반환된다
                            secondRead.set(counterRepository.findById(id).orElseThrow().value)
                        }
                    }
                    val writer = worker("writer") {
                        readerLoadedOnce.await()
                        counterService.updateAndCommit(id, 200)
                        writerCommitted.send()
                    }

                    runConcurrently(reader, writer).forEach { it.rethrowIfFailed() }

                    firstRead.get() shouldBe 100
                    secondRead.get() shouldBe 100 // DB 에는 200 이 커밋되어 있지만 보이지 않는다
                    counterService.readValue(id) shouldBe 200
                }
            }
        }

        Given("READ_COMMITTED (H2 기본값)") {
            When("읽기 트랜잭션 도중 다른 트랜잭션이 값을 바꿔 커밋하고, 영속성 컨텍스트를 비우고 다시 읽으면") {
                Then("두 번째 읽기에서 바뀐 값이 보인다 — 반복 불가능한 읽기") {
                    val id = counterService.create(COUNTER_NAME, 100)
                    val firstRead = AtomicLong()
                    val secondRead = AtomicLong()
                    val readerLoadedOnce = Signal("readerLoadedOnce")
                    val writerCommitted = Signal("writerCommitted")

                    val reader = worker("reader") {
                        txExecutor.newTransaction(isolation = Isolation.READ_COMMITTED) {
                            firstRead.set(counterRepository.findById(id).orElseThrow().value)
                            readerLoadedOnce.send()
                            writerCommitted.await()
                            txExecutor.clearPersistenceContext() // 이제 진짜로 DB 를 다시 읽는다
                            secondRead.set(counterRepository.findById(id).orElseThrow().value)
                        }
                    }
                    val writer = worker("writer") {
                        readerLoadedOnce.await()
                        counterService.updateAndCommit(id, 200)
                        writerCommitted.send()
                    }

                    runConcurrently(reader, writer).forEach { it.rethrowIfFailed() }

                    // 같은 트랜잭션 안인데 같은 행의 값이 달라졌다.
                    // "조회 -> 검증 -> 조회 -> 처리" 흐름이라면 검증이 무의미해진다.
                    firstRead.get() shouldBe 100
                    secondRead.get() shouldBe 200
                }
            }

            When("읽기 트랜잭션 도중 다른 트랜잭션이 조건에 맞는 행을 새로 INSERT 하고 커밋하면") {
                Then("두 번째 조회에서 행 개수가 늘어난다 — 팬텀 리드") {
                    val firstCount = AtomicLong()
                    val secondCount = AtomicLong()
                    val readerCountedOnce = Signal("readerCountedOnce")
                    val writerCommitted = Signal("writerCommitted")

                    val reader = worker("reader") {
                        txExecutor.newTransaction(isolation = Isolation.READ_COMMITTED) {
                            // count 쿼리는 1차 캐시를 타지 않고 항상 DB 로 나간다.
                            firstCount.set(logsRepository.countByMessage(PHANTOM_MESSAGE))
                            readerCountedOnce.send()
                            writerCommitted.await()
                            secondCount.set(logsRepository.countByMessage(PHANTOM_MESSAGE))
                        }
                    }
                    val writer = worker("writer") {
                        readerCountedOnce.await()
                        txExecutor.newTransaction { logsRepository.save(Logs(PHANTOM_MESSAGE)) }
                        writerCommitted.send()
                    }

                    runConcurrently(reader, writer).forEach { it.rethrowIfFailed() }

                    firstCount.get() shouldBe 0
                    secondCount.get() shouldBe 1
                }
            }
        }

        Given("REPEATABLE_READ") {
            When("읽기 트랜잭션 도중 다른 트랜잭션이 값을 바꿔 커밋하고, 영속성 컨텍스트를 비우고 다시 읽으면") {
                Then("DB 를 다시 읽어도 트랜잭션 시작 시점의 스냅샷이 보여 값이 변하지 않는다") {
                    // 1차 캐시를 비웠는데도 값이 그대로다. 이번에는 DB 자체가 막아준 것이다.
                    // H2(MVStore)는 스냅샷 방식으로 REPEATABLE_READ 를 구현한다.
                    val id = counterService.create(COUNTER_NAME, 100)
                    val firstRead = AtomicLong()
                    val secondRead = AtomicLong()
                    val readerLoadedOnce = Signal("readerLoadedOnce")
                    val writerCommitted = Signal("writerCommitted")

                    val reader = worker("reader") {
                        txExecutor.newTransaction(isolation = Isolation.REPEATABLE_READ) {
                            firstRead.set(counterRepository.findById(id).orElseThrow().value)
                            readerLoadedOnce.send()
                            writerCommitted.await()
                            txExecutor.clearPersistenceContext()
                            secondRead.set(counterRepository.findById(id).orElseThrow().value)
                        }
                    }
                    val writer = worker("writer") {
                        readerLoadedOnce.await()
                        counterService.updateAndCommit(id, 200)
                        writerCommitted.send()
                    }

                    runConcurrently(reader, writer).forEach { it.rethrowIfFailed() }

                    firstRead.get() shouldBe 100
                    secondRead.get() shouldBe 100

                    // 트랜잭션이 끝난 뒤에는 당연히 새 값이 보인다.
                    counterService.readValue(id) shouldBe 200
                }
            }

            When("읽기 트랜잭션 도중 다른 트랜잭션이 행을 새로 INSERT 하고 커밋하면") {
                Then("H2 의 스냅샷 격리 덕분에 팬텀 리드까지 함께 막힌다") {
                    // 표준 SQL 은 REPEATABLE_READ 에서 팬텀 리드를 허용하지만,
                    // 스냅샷 기반으로 구현한 DB(H2 MVStore, PostgreSQL 등)는 함께 막힌다.
                    // "격리 수준의 이름"이 아니라 "DB 의 실제 구현"을 확인해야 하는 이유.
                    val firstCount = AtomicLong()
                    val secondCount = AtomicLong()
                    val readerCountedOnce = Signal("readerCountedOnce")
                    val writerCommitted = Signal("writerCommitted")

                    val reader = worker("reader") {
                        txExecutor.newTransaction(isolation = Isolation.REPEATABLE_READ) {
                            firstCount.set(logsRepository.countByMessage(PHANTOM_MESSAGE))
                            readerCountedOnce.send()
                            writerCommitted.await()
                            secondCount.set(logsRepository.countByMessage(PHANTOM_MESSAGE))
                        }
                    }
                    val writer = worker("writer") {
                        readerCountedOnce.await()
                        txExecutor.newTransaction { logsRepository.save(Logs(PHANTOM_MESSAGE)) }
                        writerCommitted.send()
                    }

                    runConcurrently(reader, writer).forEach { it.rethrowIfFailed() }

                    firstCount.get() shouldBe 0
                    secondCount.get() shouldBe 0
                }
            }
        }

        Given("READ_UNCOMMITTED") {
            When("다른 트랜잭션이 아직 커밋하지 않은(그리고 결국 롤백할) 변경을 읽으면") {
                Then("커밋되지 않은 값이 그대로 읽힌다 — 더티 리드") {
                    // 읽은 값이 곧 롤백되어 사라진다. 존재한 적 없는 데이터로 판단을 내리게 되는 셈이다.
                    val id = counterService.create(COUNTER_NAME, 100)
                    val dirtyRead = AtomicLong()
                    val uncommittedFlushed = Signal("uncommittedFlushed")
                    val readerFinished = Signal("readerFinished")

                    val writer = worker("writer") {
                        runCatching {
                            counterService.updateAndFlushThenRollback(id, 999) {
                                // 아직 커밋 전이지만 UPDATE 는 DB 로 나간 상태
                                uncommittedFlushed.send()
                                readerFinished.await()
                            }
                        }
                    }
                    val reader = worker("reader") {
                        uncommittedFlushed.await()
                        txExecutor.newTransaction(isolation = Isolation.READ_UNCOMMITTED) {
                            dirtyRead.set(counterRepository.findById(id).orElseThrow().value)
                        }
                        readerFinished.send()
                    }

                    runConcurrently(writer, reader).forEach { it.rethrowIfFailed() }

                    dirtyRead.get() shouldBe 999
                    // 쓰기 트랜잭션은 롤백되었으므로 실제로 남은 값은 원래 값이다.
                    counterService.readValue(id) shouldBe 100
                }
            }
        }
    }

    private companion object {
        const val COUNTER_NAME = "isolation-counter"
        const val PHANTOM_MESSAGE = "PHANTOM"
    }
}
