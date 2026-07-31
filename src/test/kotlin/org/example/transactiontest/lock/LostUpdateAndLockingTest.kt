package org.example.transactiontest.lock

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldContainInOrder
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.example.transactiontest.service.AccountService
import org.example.transactiontest.service.CounterService
import org.example.transactiontest.support.DatabaseCleaner
import org.example.transactiontest.support.Signal
import org.example.transactiontest.support.runConcurrently
import org.example.transactiontest.support.worker
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.OptimisticLockingFailureException
import java.util.concurrent.CopyOnWriteArrayList

/**
 * ## 잃어버린 갱신(Lost Update)과 두 가지 해법
 *
 * 트랜잭션이 원자성을 보장한다고 해서 동시성 문제가 사라지지는 않는다.
 * 아래 순서는 두 트랜잭션 모두 **정상 커밋**되지만 결과는 틀렸다.
 *
 * ```
 * T1: 잔액 읽기 (1000)
 * T2: 잔액 읽기 (1000)
 * T2: 1000 + 200 = 1200 저장, 커밋
 * T1: 1000 + 100 = 1100 저장, 커밋      <- T2 의 입금 200 이 사라졌다
 * ```
 *
 * READ_COMMITTED 도 REPEATABLE_READ 도 이걸 막지 못한다.
 * 두 트랜잭션 각각의 시선에서는 아무 규칙도 어기지 않았기 때문이다.
 *
 * ### 해법 1. 낙관적 락 (`@Version`)
 * 충돌이 드물다고 가정하고 일단 진행한 뒤, UPDATE 문에
 * `where id = ? and version = ?` 를 붙여 **갱신된 행이 0건이면** 예외를 던진다.
 * 락을 잡지 않으므로 빠르지만, 실패한 쪽은 재시도해야 한다.
 *
 * ### 해법 2. 비관적 락 (`select ... for update`)
 * 읽는 시점에 행을 잠가 다른 트랜잭션을 **대기**시킨다.
 * 재시도가 필요 없지만 대기/데드락/처리량 저하를 감수해야 한다.
 */
@Suppress("UNUSED")
@SpringBootTest
class LostUpdateAndLockingTest(
    @Autowired private val accountService: AccountService,
    @Autowired private val counterService: CounterService,
    @Autowired private val databaseCleaner: DatabaseCleaner,
) : BehaviorSpec() {
    init {
        beforeTest { databaseCleaner.clean() }

        Given("락이 전혀 없는 엔티티(@Version 없음)를 두 트랜잭션이 동시에 증가시킬 때") {
            When("둘 다 같은 값을 읽은 뒤 차례로 커밋하면") {
                Then("나중에 쓴 트랜잭션이 앞의 갱신을 덮어써서 갱신이 유실된다") {
                    // 두 번의 +10 을 기대했지만 결과는 한 번만 반영된다.
                    // 예외도 없고 로그도 없다. 그래서 가장 발견하기 어려운 버그다.
                    val id = counterService.create(COUNTER_NAME, 0)
                    val firstRead = Signal("firstRead")
                    val secondCommitted = Signal("secondCommitted")

                    val first = worker("first") {
                        counterService.readThenWriteWithoutLock(id, 10) {
                            // 읽기는 끝났고 아직 쓰기 전이다. 그 사이 두 번째가 통째로 커밋되게 둔다.
                            firstRead.send()
                            secondCommitted.await()
                        }
                    }
                    val second = worker("second") {
                        firstRead.await() // 첫 번째와 같은 값(0)을 읽도록 순서를 맞춘다
                        counterService.readThenWriteWithoutLock(id, 10)
                        secondCommitted.send()
                    }

                    runConcurrently(first, second).forEach { it.rethrowIfFailed() }

                    counterService.readValue(id) shouldBe 10 // 기대값은 20
                }
            }
        }

        Given("@Version 이 붙은 엔티티를 두 트랜잭션이 동시에 수정할 때") {
            When("둘 다 같은 버전을 읽은 뒤 차례로 커밋하면") {
                Then("늦게 커밋하는 쪽이 낙관적 락 예외로 실패해 갱신 유실을 막는다") {
                    // 위와 완전히 같은 시나리오인데 결과가 다르다.
                    // Hibernate 가 `update ... where id = ? and version = 0` 을 보냈고,
                    // 이미 version 이 1로 올라가 있어 0건이 갱신되었기 때문이다.
                    val id = accountService.openAccount(OWNER, 1_000)
                    val firstRead = Signal("firstRead")
                    val secondCommitted = Signal("secondCommitted")

                    val first = worker("first") {
                        accountService.depositWithOptimisticLock(id, 100) {
                            firstRead.send()
                            secondCommitted.await()
                        }
                    }
                    val second = worker("second") {
                        firstRead.await() // 첫 번째와 같은 version(0)을 읽도록 순서를 맞춘다
                        accountService.depositWithOptimisticLock(id, 200)
                        secondCommitted.send()
                    }

                    runConcurrently(first, second)

                    // 먼저 커밋한 쪽만 성공한다.
                    second.failure shouldBe null
                    first.failure.shouldNotBeNull().shouldBeInstanceOf<OptimisticLockingFailureException>()

                    // 실패한 쪽의 변경은 롤백되었으므로 잔액은 성공한 입금만 반영한다.
                    // (실무에서는 여기서 재시도한다 — Spring Retry 등)
                    accountService.findBalance(id) shouldBe 1_200
                    accountService.findVersion(id) shouldBe 1
                }
            }
        }

        Given("비관적 쓰기 락(select ... for update)으로 행을 선점할 때") {
            When("두 트랜잭션이 동시에 같은 계좌에 입금하면") {
                Then("뒤에 온 쪽이 대기했다가 실행되어 두 입금이 모두 반영된다") {
                    // 낙관적 락과 달리 실패/재시도가 없다. 대신 두 번째 트랜잭션은
                    // 첫 번째가 커밋할 때까지 select 단계에서 멈춰 서 있는다.
                    val id = accountService.openAccount(OWNER, 1_000)
                    val timeline = CopyOnWriteArrayList<String>()
                    val firstAcquiredLock = Signal("firstAcquiredLock")

                    val first = worker("first") {
                        accountService.depositWithPessimisticLock(id, 100) {
                            timeline += "first:locked"
                            firstAcquiredLock.send()
                            // 두 번째가 락을 기다리는 상태로 진입할 시간을 준다
                            Thread.sleep(300)
                            timeline += "first:writing"
                        }
                        timeline += "first:committed"
                    }
                    val second = worker("second") {
                        firstAcquiredLock.await()
                        accountService.depositWithPessimisticLock(id, 200) {
                            timeline += "second:locked"
                        }
                        timeline += "second:committed"
                    }

                    runConcurrently(first, second).forEach { it.rethrowIfFailed() }

                    // 두 입금이 모두 살아남았다 = 잃어버린 갱신이 발생하지 않았다.
                    accountService.findBalance(id) shouldBe 1_300

                    // ── 각 스레드 내부의 순서는 프로그램 순서라 항상 고정된다.
                    timeline.filter { it.startsWith("first:") } shouldContainExactly listOf(
                        "first:locked",
                        "first:writing",
                        "first:committed",
                    )
                    timeline.filter { it.startsWith("second:") } shouldContainExactly listOf(
                        "second:locked",
                        "second:committed",
                    )

                    // ── 두 스레드 **사이**에서 DB 가 보장해 주는 것은 이 한 가지뿐이다.
                    // second 의 select ... for update 는 first 가 커밋할 때까지 풀리지 않는다.
                    // first 는 임계 구역(= "first:writing" 까지)을 끝낸 뒤에야 커밋하므로,
                    // "first:writing" 이 "second:locked" 보다 먼저인 것은 확정이다. = 직렬화의 증거.
                    timeline shouldContainInOrder listOf("first:writing", "second:locked")

                    // ── 여기서부터가 "정답이 여러 개"인 지점이다.
                    //
                    // "first:committed" 는 first 스레드가, "second:locked" 는 second 스레드가 찍는다.
                    // 그런데 second 의 락 대기를 풀어 주는 사건이 바로 **first 의 커밋**이다.
                    // 즉 커밋이 끝나는 순간 두 스레드가 동시에 깨어나 각자 리스트에 기록하며,
                    // 그 둘 사이에는 어떤 happens-before 관계도 없다.
                    //
                    //   first : 프록시 복귀(EntityManager 정리 등) -> "first:committed"
                    //   second: H2 가 대기 세션 깨움 -> 행 반환 -> 엔티티 로딩 -> "second:locked"
                    //
                    // 보통은 할 일이 적은 first 가 이기지만 그건 확률일 뿐 보장이 아니다.
                    // 실제로 first 스레드에 50ms 지연만 넣어도 아래 순서가 관측된다.
                    //   [first:locked, first:writing, second:locked, second:committed, first:committed]
                    // 따라서 두 순서 모두 정답으로 인정한다. 원래 있던
                    // `first:committed -> second:locked` 단언은 CI 부하/GC 로 깨지는 플래키 테스트였다.
                    timeline shouldContainExactlyInAnyOrder listOf(
                        "first:locked",
                        "first:writing",
                        "first:committed",
                        "second:locked",
                        "second:committed",
                    )
                }
            }
        }
    }

    private companion object {
        const val COUNTER_NAME = "lost-update-counter"
        const val OWNER = "kim"
    }
}
