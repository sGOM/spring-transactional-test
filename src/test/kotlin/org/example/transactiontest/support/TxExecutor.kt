package org.example.transactiontest.support

import jakarta.persistence.EntityManagerFactory
import org.springframework.orm.jpa.EntityManagerFactoryUtils
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.support.TransactionTemplate

/**
 * 격리 수준을 지정해 프로그래밍 방식 트랜잭션을 여는 도구.
 *
 * `@Transactional(isolation = ...)` 은 어노테이션이 붙은 메서드 단위라 실험 시나리오를
 * 잘게 쪼개기 어렵다. [TransactionTemplate] 을 쓰면 "이 블록만 REPEATABLE_READ 로"처럼
 * 테스트 안에서 경계를 직접 그릴 수 있다.
 */
@Component
class TxExecutor(
    private val transactionManager: PlatformTransactionManager,
    private val entityManagerFactory: EntityManagerFactory,
) {
    /**
     * 항상 **새 물리 트랜잭션**(PROPAGATION_REQUIRES_NEW)을 연다.
     * 호출자 스레드에 트랜잭션이 남아 있어도 그것과 섞이지 않게 하기 위해서다.
     */
    fun <T> newTransaction(
        isolation: Isolation = Isolation.DEFAULT,
        readOnly: Boolean = false,
        action: () -> T,
    ): T {
        val template = TransactionTemplate(transactionManager).apply {
            propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
            isolationLevel = isolation.value()
            isReadOnly = readOnly
        }
        @Suppress("UNCHECKED_CAST")
        return template.execute { action() } as T
    }

    /**
     * 현재 트랜잭션에 바인딩된 영속성 컨텍스트를 비운다.
     *
     * JPA 로 격리 수준을 실험할 때 반드시 필요하다. 이걸 하지 않으면 두 번째 조회가
     * DB 가 아니라 **1차 캐시**에서 반환되어, 격리 수준과 무관하게 항상 같은 값이 보인다.
     */
    fun clearPersistenceContext() {
        EntityManagerFactoryUtils.getTransactionalEntityManager(entityManagerFactory)?.clear()
    }
}
