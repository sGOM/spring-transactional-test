package org.example.transactiontest.repository

import jakarta.persistence.LockModeType
import org.example.transactiontest.entity.Account
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface AccountRepository : JpaRepository<Account, Long> {

    /**
     * 비관적 쓰기 락. `select ... for update` 로 나가며,
     * 다른 트랜잭션이 같은 행을 잠그려 하면 커밋/롤백될 때까지 **대기**한다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.id = :id")
    fun findByIdForUpdate(@Param("id") id: Long): Account?
}
