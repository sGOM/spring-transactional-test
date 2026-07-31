package org.example.transactiontest.service

import org.example.transactiontest.entity.Logs
import org.example.transactiontest.repository.LogsRepository
import org.example.transactiontest.support.TxProbe
import org.example.transactiontest.support.TxSnapshot
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * `@Transactional(readOnly = true)` 가 실제로 하는 일.
 *
 * JPA 에서 readOnly 는 Hibernate 세션의 FlushMode 를 `MANUAL` 로 바꾼다.
 * 그 결과 **변경 감지(dirty checking)로 인한 UPDATE 가 나가지 않는다.**
 *
 * 다만 readOnly 는 "DB 차원의 쓰기 금지"가 아니다.
 * IDENTITY 전략의 `save()` 처럼 flush 와 무관하게 즉시 실행되는 INSERT 는 그대로 나간다.
 * 즉 readOnly 는 성능/의도 표현 장치이지 안전장치가 아니다.
 */
@Service
class ReadOnlyService(
    private val logsRepository: LogsRepository,
    private val txProbe: TxProbe,
) {
    @Transactional(readOnly = true)
    fun snapshot(): TxSnapshot = txProbe.snapshot("readOnly=true")

    /** readOnly 트랜잭션에서 엔티티를 수정한다. 변경 감지가 동작하지 않아 UPDATE 가 나가지 않는다. */
    @Transactional(readOnly = true)
    fun renameInReadOnlyTransaction(id: Long, newMessage: String) {
        val logs = logsRepository.findById(id).orElseThrow()
        logs.message = newMessage
    }

    /** 비교군. 쓰기 트랜잭션에서는 변경 감지로 UPDATE 가 나간다. */
    @Transactional
    fun renameInWritableTransaction(id: Long, newMessage: String) {
        val logs = logsRepository.findById(id).orElseThrow()
        logs.message = newMessage
    }

    /**
     * readOnly 트랜잭션에서의 INSERT.
     * IDENTITY 전략이라 `save()` 즉시 INSERT 가 전송되고, 그대로 커밋된다.
     */
    @Transactional(readOnly = true)
    fun insertInReadOnlyTransaction(message: String = MESSAGE): Long? =
        logsRepository.save(Logs(message)).id

    companion object {
        const val MESSAGE = "READ_ONLY_INSERT"
    }
}
