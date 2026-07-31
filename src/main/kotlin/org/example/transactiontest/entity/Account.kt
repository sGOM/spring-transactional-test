package org.example.transactiontest.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version

/**
 * 낙관적 락(@Version) / 비관적 락(SELECT ... FOR UPDATE) 실험용 엔티티.
 *
 * [version] 필드가 있으면 Hibernate 는 UPDATE 문에 `where id = ? and version = ?` 조건을 붙이고,
 * 갱신된 행이 0건이면 낙관적 락 예외를 던진다. 즉 "잃어버린 갱신(lost update)"을 감지할 수 있다.
 */
@Entity
@Table(name = "account")
class Account(
    @Column(nullable = false)
    var owner: String,

    @Column(nullable = false)
    var balance: Long,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null

    @Version
    var version: Long = 0
        protected set

    fun deposit(amount: Long) {
        require(amount > 0) { "입금액은 0보다 커야 한다: $amount" }
        balance += amount
    }

    fun withdraw(amount: Long) {
        require(amount > 0) { "출금액은 0보다 커야 한다: $amount" }
        check(balance >= amount) { "잔액 부족: balance=$balance, amount=$amount" }
        balance -= amount
    }

    override fun toString(): String = "Account(id=$id, owner='$owner', balance=$balance, version=$version)"
}
