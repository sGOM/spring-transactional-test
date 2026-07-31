package org.example.transactiontest.service

import org.example.transactiontest.entity.Logs
import org.example.transactiontest.exception.InnerFailureException
import org.example.transactiontest.repository.LogsRepository
import org.example.transactiontest.support.TxProbe
import org.example.transactiontest.support.TxSnapshot
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * 3계층 호출 체인(Outer → Middle → Inner)의 **중간** 계층.
 *
 * 전파 속성은 "직전 호출자"가 아니라 **현재 스레드에 바인딩된 물리 트랜잭션**을 기준으로 판단된다.
 * 그래서 중간이 REQUIRES_NEW 로 새 트랜잭션을 열면, 그 다음 REQUIRED 자식은
 * 최초의 부모가 아니라 **중간이 연 트랜잭션**에 참여한다.
 *
 * 이 클래스는 그 "기준점이 갈아끼워지는" 상황을 만들기 위해 존재한다.
 */
@Service
class MiddleService(
    private val innerService: InnerService,
    private val logsRepository: LogsRepository,
    private val txProbe: TxProbe,
) {
    private val log = LoggerFactory.getLogger(MiddleService::class.java)

    /** REQUIRES_NEW 로 새 트랜잭션을 연 뒤 REQUIRED 자식을 부른다. 자식은 여기에 참여한다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun requiresNewCallingRequired(): List<TxSnapshot> {
        logsRepository.save(Logs(MIDDLE_MESSAGE))
        val inner = innerService.required()
        return listOf(txProbe.snapshot("middle:REQUIRES_NEW"), inner)
    }

    /**
     * REQUIRES_NEW 안에서 REQUIRED 자식이 실패하고, 그 예외를 **중간이 삼킨다.**
     *
     * 자식은 중간의 트랜잭션에 참여했으므로 `rollback-only` 로 오염되는 것은
     * 최초 부모가 아니라 **중간의 트랜잭션**이다.
     * 따라서 `UnexpectedRollbackException` 은 중간 메서드가 끝나는 시점에 터진다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun requiresNewCallingRequiredAndCatch() {
        logsRepository.save(Logs(MIDDLE_MESSAGE))
        try {
            innerService.requiredAndFail()
        } catch (e: InnerFailureException) {
            log.info("Middle(REQUIRES_NEW): 자식 예외를 삼켰지만 내 트랜잭션이 rollback-only 다. {}", e.message)
        }
    }

    /**
     * NOT_SUPPORTED 로 부모를 중단시킨 상태에서 REQUIRED 자식을 부른다.
     * 참여할 트랜잭션이 없으므로 자식이 **완전히 새로운 물리 트랜잭션**을 연다.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun notSupportedCallingRequired(): List<TxSnapshot> {
        val mine = txProbe.snapshot("middle:NOT_SUPPORTED")
        val inner = innerService.required()
        return listOf(mine, inner)
    }

    /** REQUIRES_NEW 를 두 겹으로 쌓는다. 물리 트랜잭션이 3개가 된다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun requiresNewCallingRequiresNew(): List<TxSnapshot> {
        logsRepository.save(Logs(MIDDLE_MESSAGE))
        val inner = innerService.requiresNew()
        return listOf(txProbe.snapshot("middle:REQUIRES_NEW"), inner)
    }

    /** 독립 커밋되는 단위 작업. 부분 커밋(원자성 붕괴) 시나리오에 쓴다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun requiresNewSaving(message: String) {
        logsRepository.save(Logs(message))
    }

    companion object {
        const val MIDDLE_MESSAGE = "MIDDLE"
    }
}
