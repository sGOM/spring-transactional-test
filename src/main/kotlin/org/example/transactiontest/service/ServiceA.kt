package org.example.transactiontest.service

import org.example.transactiontest.repository.LogsRepository
import org.example.transactiontest.entity.Logs
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class ServiceA(
    private val serviceB: ServiceB,
    private val logsRepository: LogsRepository,
) {
    private val logger: Logger = LoggerFactory.getLogger(ServiceA::class.java)

    // CASE 1: Transaction 없이 Transaction이 없는 Bm 호출
    fun methodAmNonTransactional() {
        // insert 후 즉시 커밋됨
        logsRepository.save(Logs(message = MESSAGE))

        try {
            serviceB.methodBmNonTransactional()
        } catch (_: Exception) {
            logger.info("Am (No Tx): Bm의 에러를 잡음. 하지만 이미 저장된 데이터는 롤백되지 않음.")
        }
    }

    // CASE 2: Bm(REQUIRED)을 호출하고 예외를 잡음
    @Transactional
    fun methodAmCallingRequired() {
        // insert, Am의 트랜잭션이 끝나면 커밋됨
        logsRepository.save(Logs(message = MESSAGE))

        try {
            serviceB.methodBmRequired()
        } catch (_: Exception) {
            logger.info("Am: Bm(Required)의 에러를 잡음. 하지만 트랜잭션은 롤백 전용으로 마킹됨.")
        }
    }

    // 케이스 2 테스트용: Bm(REQUIRES_NEW)을 호출하고 예외를 잡음
    @Transactional
    fun methodAmCallingRequiresNew() {
        // insert, Am의 트랜잭션이 끝나면 커밋됨
        logsRepository.save(Logs(message = MESSAGE))

        try {
            serviceB.methodBmRequiresNew()
        } catch (_: Exception) {
            logger.info("Am: Bm(Requires_New)의 에러를 잡음. Bm의 트랜잭션은 롤백되었지만, Am의 트랜잭션은 계속 진행됨.")
        }
    }

    companion object {
        const val MESSAGE = "Data from Am"
    }
}
