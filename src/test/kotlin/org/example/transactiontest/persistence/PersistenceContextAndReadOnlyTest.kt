package org.example.transactiontest.persistence

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import org.example.transactiontest.entity.Logs
import org.example.transactiontest.repository.LogsRepository
import org.example.transactiontest.service.PersistenceContextService
import org.example.transactiontest.service.ReadOnlyService
import org.example.transactiontest.support.DatabaseCleaner
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

/**
 * ## 트랜잭션 경계 == 영속성 컨텍스트 경계, 그리고 readOnly 의 실체
 *
 * JPA 에서 트랜잭션 이야기를 할 때 절반은 사실 **영속성 컨텍스트** 이야기다.
 * `JpaTransactionManager` 는 물리 트랜잭션을 시작할 때 EntityManager 를 하나 만들어
 * 스레드에 바인딩하고, 커밋/롤백 시 닫는다. 그래서
 *
 * - 같은 트랜잭션 안 = 같은 EntityManager = 1차 캐시 공유 = 같은 PK 는 **동일 인스턴스**
 * - 트랜잭션이 다르면 = 다른 EntityManager = 다른 인스턴스
 *
 * ### readOnly = true 가 실제로 하는 일
 * Hibernate 세션의 FlushMode 를 `MANUAL` 로 바꾼다. 그 결과 **변경 감지로 인한 UPDATE 가
 * 나가지 않는다.** 스냅샷 보관을 생략할 수 있어 메모리도 아낀다.
 *
 * 하지만 readOnly 는 "쓰기 금지"가 아니다.
 * IDENTITY 전략의 `save()` 처럼 flush 와 무관하게 즉시 실행되는 INSERT 는 그대로 나가고
 * 그대로 커밋된다. 안전장치로 믿으면 안 되는 이유다.
 */
@Suppress("UNUSED")
@SpringBootTest
class PersistenceContextAndReadOnlyTest(
    @Autowired private val persistenceContextService: PersistenceContextService,
    @Autowired private val readOnlyService: ReadOnlyService,
    @Autowired private val logsRepository: LogsRepository,
    @Autowired private val databaseCleaner: DatabaseCleaner,
) : BehaviorSpec() {
    init {
        beforeTest { databaseCleaner.clean() }

        Given("같은 트랜잭션 안에서 같은 PK 를 두 번 조회할 때") {
            When("사이에 아무것도 하지 않으면") {
                Then("1차 캐시 덕분에 두 번째 조회는 DB 를 타지 않고 동일 인스턴스를 돌려준다") {
                    val id = logsRepository.save(Logs("IDENTITY")).id!!

                    val result = persistenceContextService.readTwiceInSameTransaction(id)

                    result.sameInstance.shouldBeTrue()
                    result.containedInPersistenceContext.shouldBeTrue()
                }
            }

            When("사이에 영속성 컨텍스트를 clear() 하면") {
                Then("두 번째 조회는 DB 를 다시 읽어 별개의 인스턴스를 돌려준다") {
                    // 격리 수준 테스트에서 clear() 가 반드시 필요한 이유가 바로 이것이다.
                    // clear() 없이는 다른 트랜잭션이 커밋한 변경을 영원히 볼 수 없다.
                    val id = logsRepository.save(Logs("IDENTITY")).id!!

                    val result = persistenceContextService.readTwiceWithClearInBetween(id)

                    result.sameInstance.shouldBeFalse()
                    // clear() 로 준영속 상태가 된 첫 번째 인스턴스는 더 이상 관리되지 않는다.
                    result.containedInPersistenceContext.shouldBeFalse()
                    result.first.message shouldBe result.second.message
                }
            }

            When("안쪽 호출이 REQUIRES_NEW 로 조회하면") {
                Then("EntityManager 가 다르므로 1차 캐시도 공유되지 않아 다른 인스턴스가 나온다") {
                    val id = logsRepository.save(Logs("IDENTITY")).id!!

                    persistenceContextService.readInOuterAndRequiresNew(id).shouldBeFalse()
                }
            }
        }

        Given("IDENTITY 전략의 쓰기 지연") {
            When("save() 직후 flush 하기 전에 PK 를 확인하면") {
                Then("이미 PK 가 채워져 있다 — INSERT 가 즉시 실행되었다는 뜻") {
                    // IDENTITY 는 DB 가 채번한 PK 를 알아야 영속성 컨텍스트에 등록할 수 있다.
                    // 그래서 쓰기 지연(write-behind)이 동작하지 않고 save() 즉시 INSERT 가 나간다.
                    // 이 특성이 NESTED 세이브포인트와 readOnly 실험의 결과를 좌우한다.
                    persistenceContextService.idAssignedBeforeFlush().shouldBeTrue()
                }
            }
        }

        Given("readOnly = true 트랜잭션") {
            When("트랜잭션 상태를 확인하면") {
                Then("물리 트랜잭션은 열려 있고 readOnly 플래그만 켜져 있다") {
                    val snapshot = readOnlyService.snapshot()

                    snapshot.actualTransactionActive.shouldBeTrue()
                    snapshot.readOnly.shouldBeTrue()
                }
            }

            When("조회한 엔티티의 필드를 수정하면") {
                Then("FlushMode 가 MANUAL 이라 변경 감지가 동작하지 않아 UPDATE 가 나가지 않는다") {
                    val id = logsRepository.save(Logs("BEFORE")).id!!

                    readOnlyService.renameInReadOnlyTransaction(id, "AFTER")

                    logsRepository.findById(id).orElseThrow().message shouldBe "BEFORE"
                }
            }

            When("같은 수정을 쓰기 트랜잭션에서 하면") {
                Then("변경 감지가 동작해 UPDATE 가 나간다 (비교군)") {
                    val id = logsRepository.save(Logs("BEFORE")).id!!

                    readOnlyService.renameInWritableTransaction(id, "AFTER")

                    logsRepository.findById(id).orElseThrow().message shouldBe "AFTER"
                }
            }

            When("readOnly 트랜잭션 안에서 save() 로 INSERT 하면") {
                Then("readOnly 는 쓰기를 막아주지 않으므로 그대로 커밋된다") {
                    // readOnly 는 "의도 표현 + 최적화 힌트"이지 안전장치가 아니다.
                    // IDENTITY 전략의 INSERT 는 flush 와 무관하게 즉시 실행되어 커밋까지 된다.
                    readOnlyService.insertInReadOnlyTransaction()

                    logsRepository.countByMessage(ReadOnlyService.MESSAGE) shouldBe 1
                }
            }
        }
    }
}
