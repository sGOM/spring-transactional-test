package org.example.transactiontest.support

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/**
 * 테스트 간 격리를 위한 초기화 도구.
 *
 * 이 프로젝트의 테스트는 **테스트 메서드에 `@Transactional` 을 붙이지 않는다.**
 * 테스트를 트랜잭션으로 감싸면 서비스의 REQUIRED 가 테스트 트랜잭션에 참여해 버려서
 * 정작 검증하려는 커밋/롤백 경계가 사라지기 때문이다.
 *
 * 대신 실제로 커밋된 데이터를 매 테스트 시작 시점에 TRUNCATE 로 지운다.
 */
@Component
class DatabaseCleaner(
    private val jdbcTemplate: JdbcTemplate,
) {
    fun clean() {
        jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY FALSE")
        TABLES.forEach { jdbcTemplate.execute("TRUNCATE TABLE $it RESTART IDENTITY") }
        jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY TRUE")
    }

    private companion object {
        val TABLES = listOf("logs", "account", "counter")
    }
}
