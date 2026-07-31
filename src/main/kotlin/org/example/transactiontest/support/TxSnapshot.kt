package org.example.transactiontest.support

import org.springframework.transaction.TransactionDefinition

/**
 * 특정 시점의 "현재 스레드에 바인딩된 트랜잭션" 상태를 찍어둔 스냅샷.
 *
 * 전파 속성 테스트에서 가장 어려운 부분은 *논리 트랜잭션*과 *물리 트랜잭션*을 구분하는 것이다.
 * - REQUIRED 로 참여하면 논리 트랜잭션은 2개지만 물리 트랜잭션(=커넥션/EntityManager)은 1개다.
 * - REQUIRES_NEW 는 물리 트랜잭션 자체가 2개다.
 *
 * 이를 눈으로 확인하기 위해 [entityManagerId] (현재 트랜잭션에 바인딩된 EntityManager 의
 * identity hash) 를 함께 기록한다. 값이 같으면 같은 물리 트랜잭션, 다르면 다른 물리 트랜잭션이다.
 */
data class TxSnapshot(
    /** 어느 메서드에서 찍은 스냅샷인지 구분하는 라벨 */
    val label: String,
    /** 실제 물리 트랜잭션이 열려 있는가 (`PROPAGATION_SUPPORTS` 로 "참여만" 한 경우와 구분된다) */
    val actualTransactionActive: Boolean,
    /** 트랜잭션 이름. 보통 `클래스명.메서드명` 이라 어떤 메서드가 물리 트랜잭션을 열었는지 알 수 있다 */
    val transactionName: String?,
    /** `@Transactional(readOnly = true)` 여부 */
    val readOnly: Boolean,
    /** [TransactionDefinition] 의 격리 수준 상수. 지정하지 않으면 null(=DB 기본값) */
    val isolationLevel: Int?,
    /** 현재 트랜잭션에 바인딩된 EntityManager 의 identity hash. 물리 트랜잭션 식별자로 사용한다 */
    val entityManagerId: Int?,
) {
    /** 같은 물리 트랜잭션(= 같은 EntityManager / 커넥션) 위에서 실행되었는가 */
    fun sharesPhysicalTransactionWith(other: TxSnapshot): Boolean =
        entityManagerId != null && entityManagerId == other.entityManagerId

    override fun toString(): String = buildString {
        append("[$label] active=$actualTransactionActive")
        append(", name=${transactionName?.substringAfterLast('.')}")
        append(", readOnly=$readOnly")
        append(", isolation=${isolationLevel?.let(::isolationName) ?: "DEFAULT"}")
        append(", emId=$entityManagerId")
    }

    private fun isolationName(level: Int): String = when (level) {
        TransactionDefinition.ISOLATION_READ_UNCOMMITTED -> "READ_UNCOMMITTED"
        TransactionDefinition.ISOLATION_READ_COMMITTED -> "READ_COMMITTED"
        TransactionDefinition.ISOLATION_REPEATABLE_READ -> "REPEATABLE_READ"
        TransactionDefinition.ISOLATION_SERIALIZABLE -> "SERIALIZABLE"
        else -> "DEFAULT"
    }
}
