package org.example.transactiontest.config

import org.springframework.boot.transaction.autoconfigure.TransactionManagerCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.orm.jpa.JpaTransactionManager

/**
 * `PROPAGATION_NESTED` 를 켜기 위한 설정 — 그리고 그것만으로는 부족하다는 사실.
 *
 * NESTED 를 JPA 에서 쓰려면 관문이 **두 개** 있다.
 *
 * ### 관문 1. `nestedTransactionAllowed`
 * `AbstractPlatformTransactionManager.nestedTransactionAllowed` 의 기본값은 false 다.
 * 그래서 Spring Boot 가 자동 구성한 [JpaTransactionManager] 를 그대로 쓰면
 * `NestedTransactionNotSupportedException: Transaction manager does not allow nested
 * transactions by default` 가 터진다. 아래 커스터마이저가 이 관문을 연다.
 *
 * (`DataSourceTransactionManager` 는 생성자에서 이미 true 로 켜 두기 때문에
 *  순수 JDBC 를 쓸 때는 이 설정이 필요 없다.)
 *
 * ### 관문 2. JpaDialect 의 세이브포인트 지원 — 여기서 막힌다
 * 관문 1을 열어도 Hibernate 에서는 여전히 실패한다.
 * `JpaTransactionManager` 는 `JpaDialect.beginTransaction()` 이 돌려준 객체가
 * `SavepointManager` 를 구현한 경우에만 세이브포인트를 쓸 수 있는데,
 * `HibernateJpaDialect` 가 돌려주는 객체는 그렇지 않다. 그래서
 * `NestedTransactionNotSupportedException: JpaDialect does not support savepoints` 가 뜬다.
 *
 * 결론: **JPA(Hibernate) + Spring 조합에서 NESTED 는 사용할 수 없다.**
 * 부분 롤백이 필요하면 REQUIRES_NEW 를 쓰거나,
 * [org.example.transactiontest.service.SavepointService] 처럼 JDBC 트랜잭션 매니저를 쓴다.
 *
 * 이 두 단계는 `NestedPropagationTest` 에서 실제로 확인한다.
 */
@Configuration(proxyBeanMethods = false)
class TransactionConfig {

    @Bean
    fun nestedTransactionEnabler(): TransactionManagerCustomizer<JpaTransactionManager> =
        NestedTransactionEnabler()

    /**
     * 람다(SAM) 대신 명시적 클래스로 만든 이유: Spring Boot 는 제네릭 타입을 보고
     * 어떤 트랜잭션 매니저에 적용할 커스터마이저인지 판단하는데, 람다는 타입이 지워질 수 있다.
     */
    class NestedTransactionEnabler : TransactionManagerCustomizer<JpaTransactionManager> {
        override fun customize(transactionManager: JpaTransactionManager) {
            transactionManager.isNestedTransactionAllowed = true
        }
    }
}
