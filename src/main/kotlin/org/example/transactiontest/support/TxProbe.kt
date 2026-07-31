package org.example.transactiontest.support

import jakarta.persistence.EntityManagerFactory
import org.slf4j.LoggerFactory
import org.springframework.orm.jpa.EntityManagerHolder
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronizationManager

/**
 * 현재 스레드의 트랜잭션 상태를 관찰하는 도구.
 *
 * Spring 의 트랜잭션 정보는 전부 [TransactionSynchronizationManager] 의 ThreadLocal 에 들어 있다.
 * 프록시가 트랜잭션을 어떻게 열고/참여시키고/중단(suspend)시켰는지는 이 값들만 보면 알 수 있다.
 */
@Component
class TxProbe(
    private val entityManagerFactory: EntityManagerFactory,
) {
    private val log = LoggerFactory.getLogger(TxProbe::class.java)

    fun snapshot(label: String): TxSnapshot {
        val snapshot = TxSnapshot(
            label = label,
            actualTransactionActive = TransactionSynchronizationManager.isActualTransactionActive(),
            transactionName = TransactionSynchronizationManager.getCurrentTransactionName(),
            readOnly = TransactionSynchronizationManager.isCurrentTransactionReadOnly(),
            isolationLevel = TransactionSynchronizationManager.getCurrentTransactionIsolationLevel(),
            entityManagerId = currentEntityManagerId(),
        )
        log.info("TX SNAPSHOT {}", snapshot)
        return snapshot
    }

    /**
     * 현재 트랜잭션에 바인딩된 EntityManager 의 identity hash.
     *
     * `JpaTransactionManager` 는 물리 트랜잭션을 시작할 때 EntityManagerFactory 를 key 로
     * [EntityManagerHolder] 를 ThreadLocal 에 바인딩한다. REQUIRES_NEW 로 기존 트랜잭션을
     * 중단(suspend)하면 홀더가 통째로 교체되므로 이 값이 달라진다.
     */
    private fun currentEntityManagerId(): Int? =
        (TransactionSynchronizationManager.getResource(entityManagerFactory) as? EntityManagerHolder)
            ?.entityManager
            ?.let { System.identityHashCode(it) }
}
