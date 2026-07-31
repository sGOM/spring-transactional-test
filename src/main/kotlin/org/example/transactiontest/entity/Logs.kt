package org.example.transactiontest.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table

/**
 * 전파 속성 / 롤백 정책 / 팬텀 리드 실험에 사용하는 가장 단순한 엔티티.
 *
 * 식별자 전략이 [GenerationType.IDENTITY] 라는 점이 중요하다.
 * IDENTITY 는 DB 가 채번한 PK 를 알아야 영속성 컨텍스트에 등록할 수 있으므로
 * `save()` 를 호출하는 즉시 INSERT 가 DB 로 전송된다(= 쓰기 지연이 동작하지 않는다).
 * 이 특성 때문에 세이브포인트(NESTED)나 readOnly 실험의 결과가 달라지므로
 * 관련 테스트에서 이 점을 반복해서 확인한다.
 */
@Entity
@Table(name = "logs")
class Logs(
    @Column(nullable = false)
    var message: String,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null

    override fun toString(): String = "Logs(id=$id, message='$message')"
}
