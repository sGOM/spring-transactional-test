package org.example.transactiontest.propagation

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import jakarta.persistence.LockTimeoutException
import org.example.transactiontest.service.AccountService
import org.example.transactiontest.service.SelfDeadlockService
import org.example.transactiontest.support.DatabaseCleaner
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

/**
 * ## REQUIRES_NEW 가 자기 자신과 교착 상태에 빠지는 경우
 *
 * 앞선 스펙들에서 REQUIRES_NEW 의 장점(부분 롤백, 감사 로그 보존)을 봤다.
 * 여기서는 그 대가를 확인한다.
 *
 * ```
 * 부모 트랜잭션(커넥션 A): 계좌 행 UPDATE -> 행 잠금 획득, 커밋 전
 *   └─ 자식 REQUIRES_NEW(커넥션 B): 같은 행 UPDATE 시도
 *          -> A 가 커밋할 때까지 대기
 *          -> 그런데 A 는 B 가 끝나야 진행된다
 *          -> 영원히 풀리지 않는다
 * ```
 *
 * 두 트랜잭션이 **같은 스레드** 위에 있는데도 커넥션이 다르기 때문에,
 * DB 입장에서는 남남인 두 세션이 서로를 기다리는 상황이 된다.
 * 스레드가 하나뿐이라 어느 쪽도 양보할 수 없다.
 *
 * 실제로는 락 타임아웃으로 끝나지만, 타임아웃이 길게 잡힌 운영 환경에서는
 * 요청 스레드와 커넥션이 계속 묶이면서 커넥션 풀이 마르는 장애로 번진다.
 *
 * 흔한 발생 경로: "감사 로그는 실패해도 남아야 하니까" REQUIRES_NEW 를 붙였는데,
 * 그 로그 작업이 본 테이블을 함께 건드리는 경우.
 *
 * 참고: 테스트가 오래 멈추지 않도록 자식 트랜잭션의 커넥션에만
 * `SET LOCK_TIMEOUT 300` 을 걸어 빠르게 실패시킨다.
 */
@Suppress("UNUSED")
@SpringBootTest
class RequiresNewSelfDeadlockTest(
    @Autowired private val selfDeadlockService: SelfDeadlockService,
    @Autowired private val accountService: AccountService,
    @Autowired private val databaseCleaner: DatabaseCleaner,
) : BehaviorSpec() {
    init {
        beforeTest { databaseCleaner.clean() }

        Given("부모가 어떤 행을 잠근 채로 REQUIRES_NEW 자식을 호출할 때") {
            When("자식이 부모가 잠근 것과 같은 행을 수정하려 하면") {
                Then("자식이 락을 얻지 못하고 타임아웃되어 전체가 실패한다") {
                    // 같은 스레드인데도 커넥션이 달라 서로를 기다린다.
                    // 부모의 락은 부모가 커밋해야 풀리는데, 부모는 자식이 끝나야 커밋한다.
                    val id = accountService.openAccount(OWNER, 1_000)

                    // 예외가 Spring 의 DataAccessException 계열이 아니라 JPA 표준 예외인 이유:
                    // 예외 변환(PersistenceExceptionTranslator)은 리포지토리 프록시를 지날 때 일어나는데,
                    // 여기서는 서비스 코드의 entityManager.flush() 가 직접 던지기 때문이다.
                    shouldThrow<LockTimeoutException> {
                        selfDeadlockService.lockRowThenCallRequiresNew(id)
                    }

                    // 부모 트랜잭션도 함께 롤백되었으므로 입금이 하나도 반영되지 않았다.
                    accountService.findBalance(id) shouldBe 1_000
                }
            }

            When("자식이 부모와 다른 행을 수정하면") {
                Then("아무 문제 없이 둘 다 커밋된다") {
                    // REQUIRES_NEW 자체가 위험한 것이 아니다.
                    // "같은 자원을 두 트랜잭션이 동시에 잡는 것"이 문제다.
                    val lockedId = accountService.openAccount(OWNER, 1_000)
                    val otherId = accountService.openAccount(OTHER_OWNER, 500)

                    selfDeadlockService.lockRowThenCallRequiresNewOnAnotherRow(lockedId, otherId)

                    accountService.findBalance(lockedId) shouldBe 1_100
                    accountService.findBalance(otherId) shouldBe 501
                }
            }
        }
    }

    private companion object {
        const val OWNER = "kim"
        const val OTHER_OWNER = "lee"
    }
}
