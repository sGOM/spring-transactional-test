package org.example.transactiontest.service

import org.example.transactiontest.entity.Account
import org.example.transactiontest.repository.AccountRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * 동시성 제어(락) 실험용 서비스.
 *
 * 모든 메서드가 `REQUIRES_NEW` 인 이유: 테스트가 여러 스레드에서 이 메서드들을 호출하며
 * "각 호출 = 독립된 하나의 트랜잭션"이라는 전제를 확실히 하기 위해서다.
 */
@Service
class AccountService(
    private val accountRepository: AccountRepository,
) {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun openAccount(owner: String, balance: Long): Long =
        accountRepository.save(Account(owner = owner, balance = balance)).id!!

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun findBalance(id: Long): Long = accountRepository.findById(id).orElseThrow().balance

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun findVersion(id: Long): Long = accountRepository.findById(id).orElseThrow().version

    /**
     * 락 없이 읽고 -> 잠시 대기 -> 수정한다.
     * `@Version` 이 있으므로, 대기하는 동안 다른 트랜잭션이 같은 행을 커밋했다면
     * 커밋 시점의 `update ... where version = ?` 이 0건을 갱신하고 낙관적 락 예외가 터진다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun depositWithOptimisticLock(id: Long, amount: Long, beforeWrite: () -> Unit = {}) {
        val account = accountRepository.findById(id).orElseThrow()
        beforeWrite()
        account.deposit(amount)
    }

    /**
     * 비관적 쓰기 락으로 행을 선점한 뒤 수정한다.
     * 다른 트랜잭션은 이 트랜잭션이 끝날 때까지 같은 행을 잠글 수 없어 **직렬화**된다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun depositWithPessimisticLock(id: Long, amount: Long, afterLock: () -> Unit = {}) {
        val account = accountRepository.findByIdForUpdate(id) ?: error("계좌 없음: $id")
        afterLock()
        account.deposit(amount)
    }
}
