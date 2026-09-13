package org.example.transactiontest.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table

/**
 * 양방향 연관관계의 주인(owning side). `team_id` 외래 키를 관리한다.
 *
 * [team] 은 [FetchType.LAZY] 라서 Member 를 조회해도 함께 SELECT 되지 않는다.
 * 대신 Hibernate 가 만든 **프록시**가 들어간다. 이 프록시를 영속성 컨텍스트가 닫힌 뒤에
 * 건드리면 `LazyInitializationException` 이 난다.
 */
@Entity
@Table(name = "member")
class Member(
    @Column(nullable = false)
    var name: String,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "team_id")
    var team: Team? = null

    override fun toString(): String = "Member(id=$id, name='$name')"
}
