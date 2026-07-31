package org.example.transactiontest.service

import org.example.transactiontest.exception.InnerFailureException
import org.example.transactiontest.exception.OuterFailureException
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.stereotype.Service
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import javax.sql.DataSource

/**
 * NESTED(세이브포인트)가 **실제로 동작하는** 환경을 만들어 비교하기 위한 서비스.
 *
 * [org.springframework.orm.jpa.JpaTransactionManager] + Hibernate 조합은 세이브포인트를 지원하지 않는다.
 * (자세한 이유는 [org.example.transactiontest.config.TransactionConfig] 참고)
 * 반면 [DataSourceTransactionManager] 는 JDBC `Connection.setSavepoint()` 를 그대로 쓰므로
 * NESTED 가 정상 동작한다.
 *
 * 여기서는 JPA 자동 구성을 건드리지 않기 위해 트랜잭션 매니저를 **빈으로 등록하지 않고**
 * 이 서비스 안에서 직접 만들어 쓴다.
 * (빈으로 올리면 `@ConditionalOnMissingBean(TransactionManager)` 때문에
 *  Spring Boot 가 JPA 트랜잭션 매니저를 아예 만들지 않는다)
 */
@Service
class SavepointService(dataSource: DataSource) {
    private val log = LoggerFactory.getLogger(SavepointService::class.java)

    /** `DataSourceTransactionManager` 는 생성자에서 이미 nestedTransactionAllowed = true 로 켜져 있다. */
    private val transactionManager = DataSourceTransactionManager(dataSource)
    private val jdbcTemplate = JdbcTemplate(dataSource)

    private val required = template(TransactionDefinition.PROPAGATION_REQUIRED)
    private val nested = template(TransactionDefinition.PROPAGATION_NESTED)

    /**
     * 바깥 저장 -> NESTED 자식이 저장 후 실패 -> 세이브포인트까지만 롤백 -> 바깥은 이어서 커밋.
     *
     * REQUIRED 였다면 여기서 `rollback-only` 오염으로 전부 롤백됐을 것이다.
     */
    fun outerCommitsWhenNestedFails() {
        required.executeWithoutResult {
            insert(OUTER_MESSAGE)
            try {
                nested.executeWithoutResult {
                    insert(INNER_MESSAGE)
                    throw InnerFailureException("NESTED 자식 실패")
                }
            } catch (e: InnerFailureException) {
                log.info("세이브포인트까지만 롤백되었다. 바깥은 계속 진행할 수 있다. {}", e.message)
            }
            insert(RECOVERY_MESSAGE)
        }
    }

    /**
     * NESTED 자식은 성공했지만 바깥이 실패 -> 같은 물리 트랜잭션이므로 자식까지 전부 롤백.
     * REQUIRES_NEW 였다면 자식은 살아남았을 것이다.
     */
    fun everythingRollsBackWhenOuterFails() {
        required.executeWithoutResult {
            insert(OUTER_MESSAGE)
            nested.executeWithoutResult { insert(INNER_MESSAGE) }
            throw OuterFailureException("NESTED 자식 성공 이후 바깥이 실패")
        }
    }

    /** 세이브포인트가 여러 개 겹칠 때: 안쪽 것만 되돌아가고 바깥쪽 세이브포인트 이후 작업은 남는다. */
    fun onlyInnermostSavepointRollsBack() {
        required.executeWithoutResult {
            insert(OUTER_MESSAGE)
            nested.executeWithoutResult {
                insert(LEVEL1_MESSAGE)
                try {
                    nested.executeWithoutResult {
                        insert(LEVEL2_MESSAGE)
                        throw InnerFailureException("가장 안쪽 실패")
                    }
                } catch (e: InnerFailureException) {
                    log.info("두 번째 세이브포인트만 롤백되었다. {}", e.message)
                }
            }
        }
    }

    private fun insert(message: String) {
        jdbcTemplate.update("insert into logs (message) values (?)", message)
    }

    private fun template(propagation: Int) =
        TransactionTemplate(transactionManager).apply { propagationBehavior = propagation }

    companion object {
        const val OUTER_MESSAGE = "SAVEPOINT_OUTER"
        const val INNER_MESSAGE = "SAVEPOINT_INNER"
        const val RECOVERY_MESSAGE = "SAVEPOINT_AFTER_CATCH"
        const val LEVEL1_MESSAGE = "SAVEPOINT_LEVEL1"
        const val LEVEL2_MESSAGE = "SAVEPOINT_LEVEL2"
    }
}
