package org.example.transactiontest.service

import jakarta.persistence.EntityManager
import org.example.transactiontest.entity.Counter
import org.example.transactiontest.repository.CounterRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * 격리 수준 / 잃어버린 갱신 실험용 서비스.
 *
 * [Counter] 에는 `@Version` 이 없으므로 read-modify-write 가 겹치면 갱신이 조용히 사라진다.
 */
@Service
class CounterService(
    private val counterRepository: CounterRepository,
    private val entityManager: EntityManager,
) {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun create(name: String, value: Long): Long =
        counterRepository.save(Counter(name = name, value = value)).id!!

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun readValue(id: Long): Long = counterRepository.findById(id).orElseThrow().value

    /**
     * 락 없이 읽고 -> 대기 -> "읽었던 값 + amount" 로 덮어쓴다.
     * 두 스레드가 겹치면 나중에 쓴 쪽이 먼저 쓴 쪽의 갱신을 덮어써서 **잃어버린 갱신**이 발생한다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun readThenWriteWithoutLock(id: Long, amount: Long, beforeWrite: () -> Unit = {}) {
        val counter = counterRepository.findById(id).orElseThrow()
        val readValue = counter.value
        beforeWrite()
        counter.value = readValue + amount
        entityManager.flush()
    }

    /**
     * 다른 트랜잭션이 관찰할 수 있도록, 커밋하지 **않은** 변경을 DB 로 flush 해두고 대기한 뒤 롤백한다.
     * 더티 리드 실험의 writer 역할.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun updateAndFlushThenRollback(id: Long, newValue: Long, whileUncommitted: () -> Unit) {
        val counter = counterRepository.findById(id).orElseThrow()
        counter.value = newValue
        entityManager.flush() // 아직 커밋 전. 다른 커넥션에서 보이면 더티 리드
        whileUncommitted()
        throw IllegalStateException("더티 리드 실험용 롤백")
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun updateAndCommit(id: Long, newValue: Long) {
        counterRepository.findById(id).orElseThrow().value = newValue
    }
}
