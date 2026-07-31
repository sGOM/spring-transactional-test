package org.example.transactiontest.event

import org.example.transactiontest.entity.Logs
import org.example.transactiontest.exception.OuterFailureException
import org.example.transactiontest.repository.LogsRepository
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

/**
 * 트랜잭션 이벤트 / 트랜잭션 동기화 콜백 실험용 서비스.
 */
@Service
class OrderService(
    private val logsRepository: LogsRepository,
    private val publisher: ApplicationEventPublisher,
) {
    /** 트랜잭션 안에서 주문을 저장하고 이벤트를 발행한다. [fail] 이면 커밋 직전에 실패시킨다. */
    @Transactional
    fun placeOrder(orderNo: String, fail: Boolean = false) {
        logsRepository.save(Logs("$ORDER_PREFIX$orderNo"))
        publisher.publishEvent(OrderPlacedEvent(orderNo))
        if (fail) throw OuterFailureException("주문 처리 실패: $orderNo")
    }

    /** 트랜잭션 **없이** 이벤트를 발행한다. `@TransactionalEventListener` 는 기본적으로 무시한다. */
    fun placeOrderWithoutTransaction(orderNo: String) {
        publisher.publishEvent(OrderPlacedEvent(orderNo))
    }

    /**
     * 어노테이션 대신 [TransactionSynchronizationManager] 에 콜백을 직접 등록하는 저수준 방식.
     * `@TransactionalEventListener` 도 내부적으로는 이 메커니즘을 쓴다.
     */
    @Transactional
    fun placeOrderWithManualSynchronization(orderNo: String, sink: MutableList<String>, fail: Boolean = false) {
        logsRepository.save(Logs("$ORDER_PREFIX$orderNo"))
        TransactionSynchronizationManager.registerSynchronization(
            object : TransactionSynchronization {
                override fun beforeCommit(readOnly: Boolean) {
                    sink += "beforeCommit"
                }

                override fun afterCommit() {
                    sink += "afterCommit"
                }

                override fun afterCompletion(status: Int) {
                    sink += when (status) {
                        TransactionSynchronization.STATUS_COMMITTED -> "afterCompletion(COMMITTED)"
                        TransactionSynchronization.STATUS_ROLLED_BACK -> "afterCompletion(ROLLED_BACK)"
                        else -> "afterCompletion(UNKNOWN)"
                    }
                }
            },
        )
        if (fail) throw OuterFailureException("주문 처리 실패: $orderNo")
    }

    companion object {
        const val ORDER_PREFIX = "ORDER:"
    }
}
