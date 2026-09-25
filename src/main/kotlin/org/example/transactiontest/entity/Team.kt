package org.example.transactiontest.entity

import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.OneToMany
import jakarta.persistence.Table

/**
 * 양방향 연관관계의 주인이 아닌 쪽(inverse side).
 *
 * [members] 는 `mappedBy = "team"` 이므로 외래 키를 관리하지 않는다.
 * 이 컬렉션이 존재한다는 사실만으로 Team -> Member -> Team 순환 참조가 만들어지고,
 * 그 순환이 Jackson 직렬화에서 그대로 문제가 된다.
 */
@Entity
@Table(name = "team")
class Team(
    @Column(nullable = false)
    var name: String,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null

    @OneToMany(mappedBy = "team", cascade = [CascadeType.ALL])
    val members: MutableList<Member> = mutableListOf()

    /** 양방향 연관관계 편의 메서드. 양쪽을 함께 맞춰 주지 않으면 한쪽만 갱신된다. */
    fun addMember(member: Member) {
        members += member
        member.team = this
    }

    override fun toString(): String = "Team(id=$id, name='$name')"
}
