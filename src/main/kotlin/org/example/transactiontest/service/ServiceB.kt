package org.example.transactiontest.service

import org.example.transactiontest.repository.LogsRepository
import org.example.transactiontest.entity.Logs
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Service
class ServiceB(
    private val logsRepository: LogsRepository,
) {
    // 상황 1: 트랜잭션 없음
    fun methodBmNonTransactional() {
        // insert 후 즉시 커밋됨
        logsRepository.save(Logs(message = MESSAGE))

        throw RuntimeException("Bm에서 에러 발생! (No Tx)")
    }

    // 상황 2: 부모(Am) 트랜잭션에 합류 (기본값)
    @Transactional
    fun methodBmRequired() {
        // insert, Am의 트랜잭션이 끝나면 커밋됨
        logsRepository.save(Logs(message = MESSAGE))

        throw RuntimeException("Bm에서 에러 발생!")
    }

    // 상황 3: 부모와 무관하게 독자적인 트랜잭션 실행
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun methodBmRequiresNew() {
        // insert, Bm의 트랜잭션이 끝나면 커밋됨
        logsRepository.save(Logs(message = MESSAGE))

        throw RuntimeException("Bm에서 에러 발생!")
    }

    companion object {
        const val MESSAGE = "Data from Bm"
    }
}
