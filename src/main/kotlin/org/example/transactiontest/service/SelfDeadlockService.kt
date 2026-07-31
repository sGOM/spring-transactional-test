package org.example.transactiontest.service

import jakarta.persistence.EntityManager
import org.example.transactiontest.repository.AccountRepository
import org.hibernate.Session
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * REQUIRES_NEW 를 잘못 쓰면 **자기 자신과 교착 상태**에 빠진다.
 *
 * ```
 * 부모 트랜잭션(커넥션 A): 계좌 행을 UPDATE -> 행 잠금 획득, 아직 커밋 전
 *   └─ 자식 REQUIRES_NEW(커넥션 B): 같은 행을 UPDATE 시도 -> A 가 커밋할 때까지 대기
 *        그런데 A 는 B 가 끝나기를 기다리는 중이다 -> 영원히 풀리지 않는다
 * ```
 *
 * 두 트랜잭션이 같은 스레드 위에 있는데도 커넥션이 다르기 때문에
 * DB 입장에서는 완전히 남남인 두 세션이 서로를 기다리는 상황이 된다.
 * 실제로는 락 타임아웃이 나서 예외로 끝나지만, 타임아웃이 길게 잡힌 운영 환경에서는
 * 커넥션 풀이 통째로 마르는 장애로 번진다.
 *
 * 이 시나리오는 감사 로그를 REQUIRES_NEW 로 남기다가
 * 그 로그가 본 테이블을 건드리는 순간 터지는 형태로 자주 발생한다.
 */
@Service
class SelfDeadlockService(
    private val accountRepository: AccountRepository,
    private val entityManager: EntityManager,
    private val self: ObjectProvider<SelfDeadlockService>,
) {
    /**
     * 부모가 행을 잠근 채로 REQUIRES_NEW 자식을 호출한다.
     * 자식은 같은 행을 수정하려다 락 타임아웃으로 실패한다.
     */
    @Transactional
    fun lockRowThenCallRequiresNew(accountId: Long) {
        val account = accountRepository.findById(accountId).orElseThrow()
        account.deposit(100)
        entityManager.flush() // UPDATE 전송 -> 행 잠금 획득 (커밋은 아직)

        self.getObject().requiresNewUpdatingSameRow(accountId)
    }

    /**
     * 비교군. 자식이 부모와 **다른 행**을 건드리면 아무 문제도 없다.
     * 즉 REQUIRES_NEW 자체가 문제가 아니라 "같은 자원을 두 트랜잭션이 잡는 것"이 문제다.
     */
    @Transactional
    fun lockRowThenCallRequiresNewOnAnotherRow(lockedId: Long, otherId: Long) {
        val account = accountRepository.findById(lockedId).orElseThrow()
        account.deposit(100)
        entityManager.flush()

        self.getObject().requiresNewUpdatingSameRow(otherId)
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun requiresNewUpdatingSameRow(accountId: Long) {
        shortenLockTimeout()
        val account = accountRepository.findById(accountId).orElseThrow()
        account.deposit(1)
        entityManager.flush() // 부모가 잠근 행이면 여기서 멈춘다
    }

    /**
     * 테스트가 몇 초씩 멈추지 않도록 **이 트랜잭션의 커넥션에만** 짧은 락 타임아웃을 건다.
     *
     * `SET LOCK_TIMEOUT` 은 H2 의 세션(커넥션) 단위 설정이다.
     * `JpaTransactionManager` 는 DataSource 를 들고 있지 않아 JdbcTemplate 을 쓰면
     * 풀에서 **다른 커넥션**을 꺼내오므로, Hibernate 세션의 실제 커넥션을 직접 잡아야 한다.
     */
    private fun shortenLockTimeout() {
        entityManager.unwrap(Session::class.java).doWork { connection ->
            connection.createStatement().use { it.execute("SET LOCK_TIMEOUT $LOCK_TIMEOUT_MILLIS") }
        }
    }

    companion object {
        const val LOCK_TIMEOUT_MILLIS = 300
    }
}
