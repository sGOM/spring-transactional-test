package org.example.transactiontest.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table

/**
 * 격리 수준 실험(더티 리드 / 반복 불가능한 읽기)과
 * 락이 없을 때의 "잃어버린 갱신" 재현에 사용하는 엔티티.
 *
 * [Account] 와 달리 `@Version` 이 **없다**. 그래서 두 트랜잭션이
 * read -> modify -> write 를 겹쳐 수행하면 갱신이 조용히 유실된다.
 */
@Entity
@Table(name = "counter")
class Counter(
    @Column(nullable = false, unique = true)
    var name: String,

    @Column(name = "counter_value", nullable = false)
    var value: Long = 0,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null

    fun increaseBy(amount: Long) {
        value += amount
    }

    override fun toString(): String = "Counter(id=$id, name='$name', value=$value)"
}
