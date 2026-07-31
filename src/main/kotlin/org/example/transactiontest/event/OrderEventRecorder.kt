package org.example.transactiontest.event

import org.example.transactiontest.entity.Logs
import org.example.transactiontest.repository.LogsRepository
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import java.util.concurrent.CopyOnWriteArrayList

/**
 * `@TransactionalEventListener` 의 실행 시점을 기록한다.
 *
 * - [EventListener] : `publishEvent` 호출 즉시 **같은 트랜잭션 안에서** 동기 실행된다.
 * - [TransactionalEventListener] : 트랜잭션 동기화에 등록되어 커밋/롤백 시점에 실행된다.
 *   - BEFORE_COMMIT : 커밋 직전. 여기서 던진 예외는 아직 롤백을 유발할 수 있다.
 *   - AFTER_COMMIT  : 커밋 직후. 이메일 발송 같은 "커밋된 뒤에만 해야 하는" 부수효과용.
 *   - AFTER_ROLLBACK: 롤백 직후.
 *   - AFTER_COMPLETION : 커밋/롤백 무관하게 마지막에.
 *
 * 중요: 트랜잭션이 없는 상태에서 발행된 이벤트는 `@TransactionalEventListener` 가
 * **아예 실행하지 않는다**(`fallbackExecution = true` 를 주지 않는 한).
 */
@Component
class OrderEventRecorder(
    private val logsRepository: LogsRepository,
) {
    private val log = LoggerFactory.getLogger(OrderEventRecorder::class.java)

    /** 실행된 콜백을 순서대로 기록한다. 여러 스레드에서 접근할 수 있으므로 동시성 컬렉션을 쓴다 */
    val phases: MutableList<String> = CopyOnWriteArrayList()

    fun reset() = phases.clear()

    @EventListener
    fun onImmediate(event: OrderPlacedEvent) = record("IMMEDIATE", event)

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    fun onBeforeCommit(event: OrderPlacedEvent) = record("BEFORE_COMMIT", event)

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onAfterCommit(event: OrderPlacedEvent) = record("AFTER_COMMIT", event)

    @TransactionalEventListener(phase = TransactionPhase.AFTER_ROLLBACK)
    fun onAfterRollback(event: OrderPlacedEvent) = record("AFTER_ROLLBACK", event)

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMPLETION)
    fun onAfterCompletion(event: OrderPlacedEvent) = record("AFTER_COMPLETION", event)

    /**
     * 커밋 이후에 DB 쓰기를 하려면 새 트랜잭션이 필요하다.
     * AFTER_COMMIT 시점의 원래 트랜잭션은 이미 끝나 커밋할 대상이 없기 때문이다.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun writeAuditAfterCommit(event: OrderPlacedEvent) {
        logsRepository.save(Logs("$AUDIT_PREFIX${event.orderNo}"))
        record("AFTER_COMMIT_AUDIT", event)
    }

    /** 트랜잭션 밖에서 발행된 이벤트도 받고 싶다면 `fallbackExecution = true` */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    fun onAfterCommitWithFallback(event: OrderPlacedEvent) = record("AFTER_COMMIT_FALLBACK", event)

    private fun record(phase: String, event: OrderPlacedEvent) {
        log.info("EVENT {} <- {}", phase, event)
        phases += phase
    }

    companion object {
        const val AUDIT_PREFIX = "AUDIT:"
    }
}
