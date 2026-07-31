package org.example.transactiontest.service

import org.example.transactiontest.entity.Logs
import org.example.transactiontest.exception.InnerFailureException
import org.example.transactiontest.repository.LogsRepository
import org.example.transactiontest.support.TxProbe
import org.example.transactiontest.support.TxSnapshot
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * 호출당하는 쪽(자식). 7가지 전파 속성을 모두 노출한다.
 *
 * 각 메서드는 로그를 한 건 저장한 뒤 현재 트랜잭션 스냅샷을 돌려주고,
 * `...AndFail` 변형은 저장 직후 언체크 예외를 던진다.
 */
@Service
class InnerService(
    private val logsRepository: LogsRepository,
    private val txProbe: TxProbe,
) {
    // ------------------------------------------------------------------
    // 트랜잭션 없음
    // ------------------------------------------------------------------

    /** 트랜잭션 경계가 없다. `save()` 는 각각 자동 커밋되는 짧은 트랜잭션으로 처리된다. */
    fun nonTransactional(message: String = INNER_MESSAGE): TxSnapshot {
        logsRepository.save(Logs(message))
        return txProbe.snapshot("inner:none")
    }

    fun nonTransactionalAndFail(message: String = INNER_MESSAGE) {
        logsRepository.save(Logs(message))
        throw InnerFailureException("Inner(No Tx) 실패")
    }

    // ------------------------------------------------------------------
    // REQUIRED : 진행 중인 트랜잭션이 있으면 참여, 없으면 새로 시작 (기본값)
    // ------------------------------------------------------------------

    @Transactional(propagation = Propagation.REQUIRED)
    fun required(message: String = INNER_MESSAGE): TxSnapshot {
        logsRepository.save(Logs(message))
        return txProbe.snapshot("inner:REQUIRED")
    }

    /**
     * 부모 트랜잭션에 참여한 상태에서 예외를 던진다.
     * 이 예외를 부모가 잡아도 참여 중인 물리 트랜잭션은 이미 `rollback-only` 로 마킹된 뒤다.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    fun requiredAndFail(message: String = INNER_MESSAGE) {
        logsRepository.save(Logs(message))
        throw InnerFailureException("Inner(REQUIRED) 실패")
    }

    // ------------------------------------------------------------------
    // REQUIRES_NEW : 부모를 잠시 중단(suspend)시키고 완전히 별개의 물리 트랜잭션을 연다
    // ------------------------------------------------------------------

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun requiresNew(message: String = INNER_MESSAGE): TxSnapshot {
        logsRepository.save(Logs(message))
        return txProbe.snapshot("inner:REQUIRES_NEW")
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun requiresNewAndFail(message: String = INNER_MESSAGE) {
        logsRepository.save(Logs(message))
        throw InnerFailureException("Inner(REQUIRES_NEW) 실패")
    }

    /**
     * 부모가 아직 커밋하지 않은 데이터를 별개의 트랜잭션에서 볼 수 있는지 확인한다.
     * 커넥션이 다르므로 부모의 미커밋 INSERT 는 보이지 않아야 한다(= 자기 자신이 넣은 것만 보인다).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun requiresNewCountAll(): Long = logsRepository.count()

    // ------------------------------------------------------------------
    // NESTED : 부모와 같은 물리 트랜잭션을 쓰되 세이브포인트를 잡는다
    // ------------------------------------------------------------------

    @Transactional(propagation = Propagation.NESTED)
    fun nested(message: String = INNER_MESSAGE): TxSnapshot {
        logsRepository.save(Logs(message))
        return txProbe.snapshot("inner:NESTED")
    }

    @Transactional(propagation = Propagation.NESTED)
    fun nestedAndFail(message: String = INNER_MESSAGE) {
        logsRepository.save(Logs(message))
        throw InnerFailureException("Inner(NESTED) 실패")
    }

    // ------------------------------------------------------------------
    // MANDATORY / NEVER / SUPPORTS / NOT_SUPPORTED
    // ------------------------------------------------------------------

    /** 반드시 부모 트랜잭션이 있어야 한다. 없으면 `IllegalTransactionStateException`. */
    @Transactional(propagation = Propagation.MANDATORY)
    fun mandatory(message: String = INNER_MESSAGE): TxSnapshot {
        logsRepository.save(Logs(message))
        return txProbe.snapshot("inner:MANDATORY")
    }

    /** 트랜잭션이 있으면 안 된다. 있으면 `IllegalTransactionStateException`. */
    @Transactional(propagation = Propagation.NEVER)
    fun never(message: String = INNER_MESSAGE): TxSnapshot {
        logsRepository.save(Logs(message))
        return txProbe.snapshot("inner:NEVER")
    }

    /** 있으면 참여, 없으면 트랜잭션 없이 실행. "있어도 되고 없어도 되는" 조회용. */
    @Transactional(propagation = Propagation.SUPPORTS)
    fun supports(message: String = INNER_MESSAGE): TxSnapshot {
        logsRepository.save(Logs(message))
        return txProbe.snapshot("inner:SUPPORTS")
    }

    /** 부모가 있으면 중단시키고, 트랜잭션 **없이** 실행한다. 저장은 즉시 커밋된다. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun notSupported(message: String = INNER_MESSAGE): TxSnapshot {
        logsRepository.save(Logs(message))
        return txProbe.snapshot("inner:NOT_SUPPORTED")
    }

    companion object {
        const val INNER_MESSAGE = "INNER"
    }
}
