package org.example.transactiontest.service

import jakarta.persistence.EntityManager
import org.example.transactiontest.entity.Logs
import org.example.transactiontest.repository.LogsRepository
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/** 같은 트랜잭션 안에서 두 번 조회한 결과 */
data class IdentityCheck(
    val first: Logs,
    val second: Logs,
    val sameInstance: Boolean,
    val containedInPersistenceContext: Boolean,
)

/**
 * 트랜잭션 경계 == 영속성 컨텍스트 경계.
 *
 * - 같은 트랜잭션 안에서는 EntityManager 가 하나이므로 같은 PK 조회는 **동일 인스턴스**를 준다(1차 캐시).
 * - 트랜잭션이 다르면 영속성 컨텍스트도 다르므로 다른 인스턴스가 나온다.
 * - REQUIRES_NEW 는 EntityManager 자체가 새로 바인딩되므로 1차 캐시도 공유되지 않는다.
 */
@Service
class PersistenceContextService(
    private val logsRepository: LogsRepository,
    private val entityManager: EntityManager,
    /** REQUIRES_NEW 를 실제로 적용하려면 프록시를 거쳐야 한다 (self-invocation 이면 무시된다) */
    private val self: ObjectProvider<PersistenceContextService>,
) {
    @Transactional
    fun readTwiceInSameTransaction(id: Long): IdentityCheck {
        val first = logsRepository.findById(id).orElseThrow()
        val second = logsRepository.findById(id).orElseThrow()
        return IdentityCheck(
            first = first,
            second = second,
            sameInstance = first === second,
            containedInPersistenceContext = entityManager.contains(first),
        )
    }

    /**
     * 1차 캐시를 비우면 같은 트랜잭션 안에서도 DB 를 다시 읽는다.
     * 격리 수준 실험에서 `clear()` 가 반드시 필요한 이유.
     */
    @Transactional
    fun readTwiceWithClearInBetween(id: Long): IdentityCheck {
        val first = logsRepository.findById(id).orElseThrow()
        entityManager.clear()
        val second = logsRepository.findById(id).orElseThrow()
        return IdentityCheck(
            first = first,
            second = second,
            sameInstance = first === second,
            containedInPersistenceContext = entityManager.contains(first),
        )
    }

    /**
     * IDENTITY 전략에서는 쓰기 지연이 동작하지 않는다.
     * PK 를 알아야 영속성 컨텍스트에 등록할 수 있으므로 `save()` 즉시 INSERT 가 나간다.
     *
     * @return flush 하기 전에 이미 PK 가 채워져 있는지 여부
     */
    @Transactional
    fun idAssignedBeforeFlush(message: String = MESSAGE): Boolean {
        val saved = logsRepository.save(Logs(message))
        return saved.id != null
    }

    /**
     * REQUIRES_NEW 안에서 조회하면 부모의 1차 캐시를 쓰지 않고 DB 를 새로 읽는다.
     * @return 부모가 읽은 인스턴스와 자식이 읽은 인스턴스가 동일한가
     */
    @Transactional
    fun readInOuterAndRequiresNew(id: Long): Boolean {
        val inOuter = logsRepository.findById(id).orElseThrow()
        val inNew = self.getObject().readInRequiresNew(id)
        return inOuter === inNew
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun readInRequiresNew(id: Long): Logs = logsRepository.findById(id).orElseThrow()

    companion object {
        const val MESSAGE = "PERSISTENCE_CONTEXT"
    }
}
