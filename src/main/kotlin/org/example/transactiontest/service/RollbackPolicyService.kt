package org.example.transactiontest.service

import org.example.transactiontest.entity.Logs
import org.example.transactiontest.exception.BusinessCheckedException
import org.example.transactiontest.exception.InnerFailureException
import org.example.transactiontest.repository.LogsRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.interceptor.TransactionAspectSupport

/**
 * 어떤 예외가 롤백을 유발하는가.
 *
 * Spring 의 기본 규칙(`DefaultTransactionAttribute.rollbackOn`)은
 * `ex is RuntimeException || ex is Error` 일 때만 롤백이다.
 * Kotlin 은 체크 예외를 강제하지 않기 때문에 `Exception` 을 무심코 던지면
 * **커밋되어 버리는** 사고가 나기 쉽다.
 */
@Service
class RollbackPolicyService(
    private val logsRepository: LogsRepository,
) {
    /** 언체크 예외 -> 기본 규칙에 걸려 롤백된다. */
    @Transactional
    fun saveThenThrowUnchecked(message: String = MESSAGE) {
        logsRepository.save(Logs(message))
        throw InnerFailureException()
    }

    /** 체크 예외 -> 기본 규칙에 걸리지 않아 **커밋된다**. */
    @Transactional
    fun saveThenThrowChecked(message: String = MESSAGE) {
        logsRepository.save(Logs(message))
        throw BusinessCheckedException()
    }

    /** `rollbackFor` 로 규칙을 확장하면 체크 예외도 롤백된다. */
    @Transactional(rollbackFor = [BusinessCheckedException::class])
    fun saveThenThrowCheckedWithRollbackFor(message: String = MESSAGE) {
        logsRepository.save(Logs(message))
        throw BusinessCheckedException()
    }

    /** `noRollbackFor` 로 규칙을 줄이면 언체크 예외인데도 커밋된다. */
    @Transactional(noRollbackFor = [IllegalStateException::class])
    fun saveThenThrowNoRollbackFor(message: String = MESSAGE) {
        logsRepository.save(Logs(message))
        throw IllegalStateException("noRollbackFor 대상이라 커밋된다")
    }

    /** [Error] 도 기본 롤백 대상이다. */
    @Transactional
    fun saveThenThrowError(message: String = MESSAGE) {
        logsRepository.save(Logs(message))
        throw StackOverflowError("Error 도 롤백 대상")
    }

    /**
     * 예외를 던지지 않고 코드로 롤백을 지시하는 방법.
     * 반환값으로 실패를 표현해야 하는 API 에서 유용하다.
     */
    @Transactional
    fun saveThenMarkRollbackOnly(message: String = MESSAGE): String {
        logsRepository.save(Logs(message))
        TransactionAspectSupport.currentTransactionStatus().setRollbackOnly()
        return "예외 없이 정상 반환하지만 커밋되지 않는다"
    }

    companion object {
        const val MESSAGE = "ROLLBACK_POLICY"
    }
}
