package org.example.transactiontest.service

import jakarta.persistence.EntityManager
import org.example.transactiontest.entity.Logs
import org.example.transactiontest.exception.InnerFailureException
import org.example.transactiontest.exception.OuterFailureException
import org.example.transactiontest.repository.LogsRepository
import org.example.transactiontest.support.TxProbe
import org.example.transactiontest.support.TxSnapshot
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/** 바깥/안쪽 트랜잭션 스냅샷을 한 번에 돌려주기 위한 묶음 */
data class TxTrace(
    val outer: TxSnapshot,
    val inner: TxSnapshot,
)

/**
 * 호출하는 쪽(부모). [InnerService] 를 여러 전파 속성으로 호출하며,
 * 내부 예외를 **잡는 경우 / 전파하는 경우**를 모두 노출한다.
 *
 * 메서드 이름 규칙: `<부모전파>Calling<자식전파>[AndCatch|AndRethrow|ThenOuterFails]`
 */
@Service
class OuterService(
    private val innerService: InnerService,
    private val logsRepository: LogsRepository,
    private val txProbe: TxProbe,
    private val entityManager: EntityManager,
) {
    private val log = LoggerFactory.getLogger(OuterService::class.java)

    // ==================================================================
    // 트랜잭션이 아예 없는 경우
    // ==================================================================

    /**
     * 서비스 계층에 트랜잭션이 없다. `save()` 호출마다 별도의 짧은 트랜잭션이 열리고 즉시 커밋된다.
     * 따라서 뒤에서 예외가 터져도 앞의 저장은 되돌아가지 않는다(= 원자성 없음).
     */
    fun noneCallingNoneAndCatch() {
        logsRepository.save(Logs(OUTER_MESSAGE))
        try {
            innerService.nonTransactionalAndFail()
        } catch (e: InnerFailureException) {
            log.info("Outer(No Tx): inner 예외를 잡음 - 이미 커밋된 데이터는 되돌릴 수 없다. {}", e.message)
        }
    }

    // ==================================================================
    // REQUIRED -> REQUIRED
    // ==================================================================

    /** 부모/자식이 같은 물리 트랜잭션을 공유하는지 스냅샷으로 확인한다. */
    @Transactional
    fun requiredCallingRequired(): TxTrace {
        logsRepository.save(Logs(OUTER_MESSAGE))
        val inner = innerService.required()
        return TxTrace(outer = txProbe.snapshot("outer:REQUIRED"), inner = inner)
    }

    /**
     * 자식(REQUIRED)이 던진 예외를 부모가 **삼킨다**.
     *
     * 자식은 부모와 같은 물리 트랜잭션에 참여했으므로, 예외가 자식의 프록시를 빠져나오는 순간
     * 공유 트랜잭션이 `rollback-only` 로 마킹된다. 부모가 예외를 잡고 정상 종료해도
     * 커밋 시점에 Spring 이 `UnexpectedRollbackException` 을 던지고 전부 롤백된다.
     */
    @Transactional
    fun requiredCallingRequiredAndCatch() {
        logsRepository.save(Logs(OUTER_MESSAGE))
        try {
            innerService.requiredAndFail()
        } catch (e: InnerFailureException) {
            log.info("Outer(REQUIRED): inner 예외를 잡았지만 트랜잭션은 이미 rollback-only 다. {}", e.message)
        }
        // 예외를 잡은 뒤 추가 작업을 해도 소용없다. 어차피 전부 롤백된다.
        logsRepository.save(Logs(RECOVERY_MESSAGE))
    }

    /** 자식 예외를 그대로 전파한다. 이때는 원래 예외가 밖으로 나오고 조용히 전부 롤백된다. */
    @Transactional
    fun requiredCallingRequiredAndRethrow() {
        logsRepository.save(Logs(OUTER_MESSAGE))
        innerService.requiredAndFail()
    }

    // ==================================================================
    // REQUIRED -> REQUIRES_NEW
    // ==================================================================

    @Transactional
    fun requiredCallingRequiresNew(): TxTrace {
        logsRepository.save(Logs(OUTER_MESSAGE))
        val inner = innerService.requiresNew()
        return TxTrace(outer = txProbe.snapshot("outer:REQUIRED"), inner = inner)
    }

    /**
     * 자식(REQUIRES_NEW)이 실패하고 부모가 예외를 잡는다.
     * 물리 트랜잭션이 분리되어 있으므로 자식만 롤백되고 부모는 정상 커밋된다.
     */
    @Transactional
    fun requiredCallingRequiresNewAndCatch() {
        logsRepository.save(Logs(OUTER_MESSAGE))
        try {
            innerService.requiresNewAndFail()
        } catch (e: InnerFailureException) {
            log.info("Outer(REQUIRED): 별개 트랜잭션이라 rollback-only 오염이 없다. {}", e.message)
        }
        logsRepository.save(Logs(RECOVERY_MESSAGE))
    }

    /**
     * 자식(REQUIRES_NEW)은 성공해서 **먼저 커밋**되고, 그 뒤 부모가 실패한다.
     * 자식의 데이터는 이미 커밋되었으므로 부모 롤백의 영향을 받지 않는다.
     * (감사 로그처럼 "실패해도 남겨야 하는" 기록에 쓰는 패턴)
     */
    @Transactional
    fun requiredCallingRequiresNewThenOuterFails() {
        logsRepository.save(Logs(OUTER_MESSAGE))
        innerService.requiresNew()
        throw OuterFailureException("Outer 가 자식 커밋 이후에 실패")
    }

    /**
     * 부모가 저장한(아직 커밋 전) 데이터를 자식(REQUIRES_NEW)이 볼 수 있는지 확인한다.
     * 커넥션이 다르므로 부모의 미커밋 데이터는 보이지 않는다.
     */
    @Transactional
    fun requiredCallingRequiresNewThatCountsRows(): Long {
        logsRepository.save(Logs(OUTER_MESSAGE))
        entityManager.flush() // 미커밋 상태로 DB 에 INSERT 만 보내둔다
        return innerService.requiresNewCountAll()
    }

    // ==================================================================
    // REQUIRED -> NESTED
    // ==================================================================

    @Transactional
    fun requiredCallingNested(): TxTrace {
        logsRepository.save(Logs(OUTER_MESSAGE))
        val inner = innerService.nested()
        return TxTrace(outer = txProbe.snapshot("outer:REQUIRED"), inner = inner)
    }

    /**
     * 자식(NESTED)이 실패하고 부모가 예외를 잡는다.
     * 세이브포인트까지만 롤백되므로 부모의 작업은 살아남고, 부모는 이어서 커밋할 수 있다.
     * REQUIRED 와 달리 `rollback-only` 오염이 없다는 것이 핵심.
     */
    @Transactional
    fun requiredCallingNestedAndCatch() {
        logsRepository.save(Logs(OUTER_MESSAGE))
        try {
            innerService.nestedAndFail()
        } catch (e: InnerFailureException) {
            log.info("Outer(REQUIRED): 세이브포인트까지만 롤백되었다. {}", e.message)
        }
        logsRepository.save(Logs(RECOVERY_MESSAGE))
    }

    /**
     * 자식(NESTED)은 성공했지만 부모가 실패한다.
     * NESTED 는 부모와 **같은 물리 트랜잭션**이므로 자식 작업까지 통째로 롤백된다.
     * (REQUIRES_NEW 와 갈리는 지점)
     */
    @Transactional
    fun requiredCallingNestedThenOuterFails() {
        logsRepository.save(Logs(OUTER_MESSAGE))
        innerService.nested()
        throw OuterFailureException("Outer 가 NESTED 자식 성공 이후에 실패")
    }

    // ==================================================================
    // MANDATORY / NEVER / SUPPORTS / NOT_SUPPORTED
    // ==================================================================

    @Transactional
    fun requiredCallingMandatory(): TxTrace {
        logsRepository.save(Logs(OUTER_MESSAGE))
        val inner = innerService.mandatory()
        return TxTrace(outer = txProbe.snapshot("outer:REQUIRED"), inner = inner)
    }

    fun noneCallingMandatory(): TxSnapshot = innerService.mandatory()

    @Transactional
    fun requiredCallingNever(): TxSnapshot = innerService.never()

    fun noneCallingNever(): TxSnapshot = innerService.never()

    @Transactional
    fun requiredCallingSupports(): TxTrace {
        logsRepository.save(Logs(OUTER_MESSAGE))
        val inner = innerService.supports()
        return TxTrace(outer = txProbe.snapshot("outer:REQUIRED"), inner = inner)
    }

    fun noneCallingSupports(): TxSnapshot = innerService.supports()

    @Transactional
    fun requiredCallingNotSupported(): TxTrace {
        logsRepository.save(Logs(OUTER_MESSAGE))
        val inner = innerService.notSupported()
        return TxTrace(outer = txProbe.snapshot("outer:REQUIRED"), inner = inner)
    }

    /**
     * 자식이 NOT_SUPPORTED 로 트랜잭션 없이 저장한 뒤 부모가 실패한다.
     * 자식의 저장은 트랜잭션 밖에서 즉시 커밋되었으므로 부모 롤백에도 살아남는다.
     */
    @Transactional
    fun requiredCallingNotSupportedThenOuterFails() {
        logsRepository.save(Logs(OUTER_MESSAGE))
        innerService.notSupported()
        throw OuterFailureException("Outer 가 NOT_SUPPORTED 자식 이후에 실패")
    }

    /** 부모 트랜잭션 없이 자식만 REQUIRED 로 호출한다. 자식이 물리 트랜잭션의 주인이 된다. */
    fun noneCallingRequiredAndFail() {
        try {
            innerService.requiredAndFail()
        } catch (e: InnerFailureException) {
            log.info("Outer(No Tx): 자식이 자기 트랜잭션을 롤백하고 예외를 전파했다. {}", e.message)
        }
    }

    /** 전파 속성 관찰 전용: 부모 트랜잭션 스냅샷만 찍는다. */
    @Transactional(propagation = Propagation.REQUIRED)
    fun snapshotOnly(): TxSnapshot = txProbe.snapshot("outer:REQUIRED")

    companion object {
        const val OUTER_MESSAGE = "OUTER"
        const val RECOVERY_MESSAGE = "OUTER_AFTER_CATCH"
    }
}
