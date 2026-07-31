package org.example.transactiontest.proxy

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.example.transactiontest.exception.InnerFailureException
import org.example.transactiontest.repository.LogsRepository
import org.example.transactiontest.service.SelfInvocationService
import org.example.transactiontest.support.DatabaseCleaner
import org.springframework.aop.framework.AopProxyUtils
import org.springframework.aop.support.AopUtils
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

/**
 * ## `@Transactional` 이 조용히 무시되는 경우 — 프록시 AOP 의 한계
 *
 * `@Transactional` 은 마법이 아니라 **프록시**다.
 * 스프링이 주입해 주는 것은 원본 빈이 아니라 그것을 상속해 감싼 CGLIB 프록시이고,
 * 트랜잭션은 "호출이 프록시를 통과할 때" 시작된다.
 *
 * ```
 * 호출자 ──► [프록시: 트랜잭션 시작] ──► 원본 빈.method()
 *                                        └─ this.other()  ◄── 프록시를 거치지 않는다!
 * ```
 *
 * 따라서 같은 클래스 안에서 `this.other()` 로 부르면 `@Transactional` 이 **아무 일도 하지 않는다.**
 * 예외도 없고 경고도 없이 그냥 무시되기 때문에, 운영에서 데이터가 롤백되지 않는 형태로 드러난다.
 *
 * 같은 이유로 `private`/`final` 메서드에 붙인 `@Transactional` 도 동작하지 않는다.
 * (Kotlin 은 클래스/메서드가 기본 final 이라 `kotlin-spring` 플러그인이 자동으로 open 처리해 준다.
 *  이 플러그인이 없으면 `@Transactional` 자체가 통째로 먹통이 된다.)
 *
 * 해결책은 우선순위 순으로:
 * 1. 트랜잭션 경계를 다른 빈으로 분리한다 (가장 권장)
 * 2. 자기 자신을 프록시로 주입받아 호출한다 (`ObjectProvider` 로 순환 참조 회피)
 * 3. `AopContext.currentProxy()` (`@EnableAspectJAutoProxy(exposeProxy = true)` 필요)
 */
@Suppress("UNUSED")
@SpringBootTest
class SelfInvocationTest(
    @Autowired private val selfInvocationService: SelfInvocationService,
    @Autowired private val logsRepository: LogsRepository,
    @Autowired private val databaseCleaner: DatabaseCleaner,
) : BehaviorSpec() {
    init {
        beforeTest { databaseCleaner.clean() }

        Given("주입받은 빈의 정체") {
            When("주입된 인스턴스를 들여다보면") {
                Then("원본 클래스가 아니라 CGLIB 프록시다") {
                    // 트랜잭션이 "프록시를 통과할 때"만 걸린다는 사실의 물리적 근거.
                    AopUtils.isAopProxy(selfInvocationService).shouldBeTrue()
                    AopUtils.isCglibProxy(selfInvocationService).shouldBeTrue()

                    // 프록시 클래스는 원본을 상속한 별도 클래스다.
                    selfInvocationService.javaClass shouldNotBe SelfInvocationService::class.java
                    AopProxyUtils.ultimateTargetClass(selfInvocationService) shouldBe SelfInvocationService::class.java
                }
            }
        }

        Given("같은 클래스 안에서 this 로 @Transactional 메서드를 호출할 때") {
            When("트랜잭션 상태를 확인하면") {
                Then("프록시를 거치지 않았으므로 물리 트랜잭션이 열려 있지 않다") {
                    val snapshot = selfInvocationService.snapshotViaInternalCall()

                    snapshot.actualTransactionActive.shouldBeFalse()
                }
            }

            When("그 메서드 안에서 저장 후 예외를 던지면") {
                Then("트랜잭션이 없으므로 저장은 자동 커밋되어 롤백되지 않는다") {
                    // @Transactional 이 붙어 있는데도 롤백되지 않는다. 이것이 자기 호출 함정이다.
                    shouldThrow<InnerFailureException> {
                        selfInvocationService.callInternally()
                    }

                    logsRepository.count() shouldBe 1
                }
            }
        }

        Given("자기 자신을 프록시로 주입받아 호출할 때") {
            When("트랜잭션 상태를 확인하면") {
                Then("정상적으로 물리 트랜잭션이 열린다") {
                    val snapshot = selfInvocationService.snapshotViaProxy()

                    snapshot.actualTransactionActive.shouldBeTrue()
                    snapshot.transactionName!!.substringAfterLast('.') shouldBe "transactionalSnapshot"
                }
            }

            When("그 메서드 안에서 저장 후 예외를 던지면") {
                Then("이번에는 @Transactional 이 적용되어 정상적으로 롤백된다") {
                    shouldThrow<InnerFailureException> {
                        selfInvocationService.callThroughProxy()
                    }

                    logsRepository.count() shouldBe 0
                }
            }
        }
    }
}
