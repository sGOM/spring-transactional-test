# CONCEPTS — 구현체와 트랜잭션 핵심 개념

이 문서는 `STUDY.md`(테스트별 해설)와 짝을 이루는 **개념 정리본**이다. 두 부분으로 되어 있다.

- **1부. 이 프로젝트가 실제로 쓰는 구현체** — `DataSource`, `EntityManager`,
  `PlatformTransactionManager` 같은 인터페이스 뒤에 런타임에 무엇이 들어오는가
- **2부. 트랜잭션 개념** — 물리/논리 트랜잭션, 전파, 격리, 락, 영속성 컨텍스트

## 이 문서의 사실 확인 방법

1부의 구현체 목록은 **추측이 아니라 실제로 찍어 본 값**이다.
`DataSource`, `EntityManagerFactory`, `EntityManager`, `PlatformTransactionManager`,
`LogsRepository` 를 주입받아 `javaClass.name` 과 JDBC 메타데이터를 출력하는 임시 스펙을
`@SpringBootTest` 로 한 번 돌린 뒤 삭제했다. 이 문서의 클래스 이름과 버전은 그 출력값이다.

개념 설명에는 **공식 문서 링크**를 붙이고, 이해에 도움이 되는 대목은 원문을 인용문으로 옮겼다.
인용은 모두 영어 원문 그대로이며, 아래에 한국어 요약을 덧붙였다.

---

# 1부. 이 프로젝트가 실제로 쓰는 구현체

## 1-1. 전체 계층 — `@Transactional` 한 줄이 지나가는 경로

```
  @Transactional 이 붙은 서비스 메서드 호출
        │
        ▼
  [1] CGLIB 프록시                      (스프링이 주입한 것은 원본 빈이 아니다)
        │
        ▼
  [2] TransactionInterceptor            (AOP Advice. 트랜잭션 시작/커밋/롤백 결정)
        │
        ▼
  [3] JpaTransactionManager             (PlatformTransactionManager 구현체)
        │        │
        │        └─▶ HibernateJpaDialect      (JpaDialect 구현체. 격리수준/세이브포인트 담당)
        │
        ▼
  [4] TransactionSynchronizationManager (ThreadLocal. EntityManagerHolder 를 바인딩)
        │
        ▼
  [5] SessionImpl                       (Hibernate 의 EntityManager 구현체 = 영속성 컨텍스트)
        │
        ▼
  [6] HikariProxyConnection             (커넥션 풀이 씌운 래퍼)
        │
        ▼
  [7] H2 2.4.240 (MVStore, 인메모리)     (실제 commit / rollback / 락)
```

**트랜잭션의 "진짜 경계"는 [7]에 있고, [1]~[4]는 그 경계를 언제 열고 닫을지 결정하는 층이다.**
이 프로젝트의 테스트가 헷갈리는 이유도 여기 있다 — 논리적인 층([1]~[3])과
물리적인 층([5]~[7])이 1:1로 대응하지 않기 때문이다.

## 1-2. 구현체 대조표 (런타임 확인값)

| 인터페이스 / 역할 | 실제 구현체 | 비고 |
|---|---|---|
| `javax.sql.DataSource` | `com.zaxxer.hikari.HikariDataSource` | Spring Boot 기본 커넥션 풀 |
| `java.sql.Connection` | `com.zaxxer.hikari.pool.HikariProxyConnection` | H2 커넥션을 감싼 풀 래퍼 |
| DB | **H2 2.4.240** (`jdbc:h2:mem:testdb`) | 인메모리, MVStore |
| JDBC Driver | H2 JDBC Driver 2.4.240 | |
| `jakarta.persistence.EntityManagerFactory` (빈) | JDK 동적 프록시 | `SessionFactory` + `EntityManagerFactoryInfo` 구현 |
| └ native EMF | `org.hibernate.internal.SessionFactoryImpl` | |
| `jakarta.persistence.EntityManager` (주입된 것) | JDK 동적 프록시 | `SharedEntityManagerCreator` 산물 |
| └ 트랜잭션 안의 실제 EM | `org.hibernate.internal.SessionImpl` | **영속성 컨텍스트 본체** |
| PersistenceProvider | `SpringHibernateJpaPersistenceProvider` | |
| `PlatformTransactionManager` | `org.springframework.orm.jpa.JpaTransactionManager` | `AbstractPlatformTransactionManager` 상속 |
| `JpaDialect` | `org.springframework.orm.jpa.vendor.HibernateJpaDialect` | **NESTED 가 막히는 지점** |
| 트랜잭션 리소스 홀더 | `org.springframework.orm.jpa.EntityManagerHolder` | ThreadLocal 에 바인딩되는 객체 |
| `JpaRepository` | JDK 동적 프록시 → `SimpleJpaRepository` | 인터페이스 기반 프록시 |
| Spring Framework | 7.0.2 | |
| Hibernate ORM | 7.2.0.Final | |

추가 확인값:

- 풀에서 갓 꺼낸 커넥션의 기본 격리 수준 = **2** = `Connection.TRANSACTION_READ_COMMITTED`
- 갓 꺼낸 커넥션의 `autoCommit` = **true** (트랜잭션이 시작되면 false 로 바뀐다)
- `JpaTransactionManager.isNestedTransactionAllowed` = **true** (`TransactionConfig.kt:46` 의 커스터마이저 효과)
- `JpaTransactionManager.dataSource` = `HikariDataSource`

## 1-3. `HikariDataSource` — 커넥션 풀

📖 [HikariCP 공식 저장소](https://github.com/brettwooldridge/HikariCP) ·
📖 [Spring Boot — Connection Pooling](https://docs.spring.io/spring-boot/reference/data/sql.html#data.sql.datasource.connection-pool)

Spring Boot 는 클래스패스에 HikariCP 가 있으면 이것을 기본 `DataSource` 로 쓴다.
`spring-boot-starter-data-jpa` 가 HikariCP 를 끌고 오므로 별도 설정이 필요 없다.

**트랜잭션 관점에서 커넥션 풀이 중요한 이유**는 `application.yaml:11~13` 의 주석에 적혀 있다.

```yaml
hikari:
  maximum-pool-size: 10
  connection-timeout: 20000
```

> 동시성 테스트에서는 스레드 두 개가 각자 커넥션을 잡은 채로 대기한다.
> 풀이 작으면 "락 대기"가 아니라 "커넥션 고갈"로 멈추므로 넉넉하게 잡는다.

이것은 실험 편의가 아니라 **트랜잭션의 본질적 성질**이다.
`REQUIRES_NEW` 는 물리 트랜잭션을 하나 더 여는 것이고, 물리 트랜잭션 하나는 커넥션 하나를 뜻한다.
`STUDY.md` 스펙 4-4(REQUIRES_NEW 3겹)는 **커넥션을 동시에 3개 점유한다**.
운영에서 REQUIRES_NEW 를 깊게 중첩하면 커넥션 풀 고갈로 이어지는 이유다.

`Connection` 구현체가 `HikariProxyConnection` 인 것도 알아둘 만하다.
`close()` 를 호출해도 실제로 닫히지 않고 **풀로 반납**되며, 이때 HikariCP 가
`autoCommit` / 격리 수준 등을 원래대로 되돌린다. 그래서 `TxExecutor` 로 격리 수준을
바꿔 쓴 뒤에도 다음 테스트가 오염되지 않는다.

## 1-4. H2 2.4.240 — 실제 커밋과 락이 일어나는 곳

📖 [H2 Features](https://h2database.com/html/features.html) ·
📖 [H2 Advanced — Transaction Isolation](https://h2database.com/html/advanced.html#transaction_isolation) ·
📖 [H2 MVStore](https://h2database.com/html/mvstore.html)

접속 URL(`application.yaml:6`)의 각 옵션은 전부 트랜잭션 실험을 위한 것이다.

| 옵션 | 의미 |
|---|---|
| `jdbc:h2:mem:testdb` | 인메모리 DB. 파일 I/O 없이 빠르게 커밋/롤백 |
| `DB_CLOSE_DELAY=-1` | 마지막 커넥션이 닫혀도 DB 유지 (JVM 종료까지) |
| `DB_CLOSE_ON_EXIT=FALSE` | JVM 종료 훅에서 닫지 않음 |
| `LOCK_TIMEOUT=10000` | 비관적 락 테스트에서 대기 스레드가 성급히 타임아웃되지 않도록 10초 |

`LOCK_TIMEOUT` 은 H2 의 **세션(커넥션) 단위** 설정이다.
📖 [H2 SET LOCK_TIMEOUT](https://h2database.com/html/commands.html#set_lock_timeout)

> "If a connection cannot get a lock on an object, the connection waits for some amount of time (the lock timeout)."
> — H2 Advanced

이 성질 때문에 `SelfDeadlockService.kt:75` 는 `JdbcTemplate` 을 쓰지 못한다.
`JpaTransactionManager` 는 DataSource 를 직접 쓰지 않으므로 `JdbcTemplate` 은 풀에서
**다른 커넥션**을 꺼내오고, 거기에 `SET LOCK_TIMEOUT` 을 걸어봐야 정작 락을 기다리는
세션에는 적용되지 않는다. 그래서 Hibernate 세션의 실제 커넥션을 직접 잡는다.

```kotlin
entityManager.unwrap(Session::class.java).doWork { connection ->
    connection.createStatement().use { it.execute("SET LOCK_TIMEOUT $LOCK_TIMEOUT_MILLIS") }
}
```

**MVStore** 는 H2 2.x 의 기본 스토리지 엔진으로, 다중 버전(MVCC) 방식이다.
이 사실이 `IsolationLevelTest` 의 결과를 좌우한다 (→ 2-8에서 자세히).

## 1-5. `JpaTransactionManager` — 트랜잭션을 실제로 여닫는 주체

📖 [Javadoc — JpaTransactionManager](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/orm/jpa/JpaTransactionManager.html) ·
📖 [Javadoc — AbstractPlatformTransactionManager](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/transaction/support/AbstractPlatformTransactionManager.html)

Spring Boot 가 JPA 스타터를 보고 자동 구성한 것이다. 하는 일은 네 가지다.

1. **물리 트랜잭션 시작** — `EntityManagerFactory` 에서 `EntityManager` 를 하나 만들고
   JDBC 트랜잭션을 시작한다(`autoCommit = false`)
2. **스레드 바인딩** — 만든 EntityManager 를 `EntityManagerHolder` 에 담아
   `TransactionSynchronizationManager` 의 ThreadLocal 에 등록한다
3. **참여 / 중단 판단** — 이미 바인딩된 것이 있으면 전파 속성에 따라 참여하거나 중단(suspend)한다
4. **커밋 / 롤백** — 끝날 때 flush 하고 커밋하거나 롤백한 뒤 EntityManager 를 닫는다

전파·중단·복원 로직 자체는 부모 클래스인 `AbstractPlatformTransactionManager` 에 들어 있다.
그래서 `DataSourceTransactionManager` 든 `JpaTransactionManager` 든
**전파 속성의 의미는 완전히 동일하다** — 다른 것은 "자원"이 커넥션이냐 EntityManager 냐뿐이다.

### `nestedTransactionAllowed` — 켜도 소용없는 스위치

`AbstractPlatformTransactionManager.nestedTransactionAllowed` 의 기본값은 `false` 다.
`TransactionConfig.kt:46` 의 커스터마이저가 이것을 `true` 로 바꾼다(런타임 확인됨).

그런데도 NESTED 는 실패한다. `JpaTransactionManager` 는
`JpaDialect.beginTransaction()` 이 돌려준 객체가 `SavepointManager` 를 구현했을 때만
세이브포인트를 만들 수 있는데, `HibernateJpaDialect` 가 돌려주는 객체는 그렇지 않기 때문이다.

```
관문 1: nestedTransactionAllowed = false
        → "Transaction manager does not allow nested transactions by default"
관문 2: HibernateJpaDialect 가 SavepointManager 미제공     ← 여기서 막힌다
        → "JpaDialect does not support savepoints"
```

`NestedPropagationTest.kt:64` 가 두 번째 메시지를 그대로 단언한다.

> **결론: JPA(Hibernate) + Spring 조합에서 NESTED 는 쓸 수 없다.**
> 부분 롤백이 필요하면 REQUIRES_NEW 를 쓰거나, `SavepointService` 처럼
> `DataSourceTransactionManager` 를 쓴다(이쪽은 생성자에서 이미 `nestedTransactionAllowed = true`).

### 두 번째 트랜잭션 매니저를 빈으로 올리지 않은 이유

`SavepointService.kt:31` 은 `DataSourceTransactionManager` 를 **서비스 안에서 직접 생성**한다.
빈으로 등록하면 Spring Boot 의 `@ConditionalOnMissingBean(TransactionManager)` 때문에
JPA 트랜잭션 매니저가 아예 만들어지지 않아, 나머지 테스트가 전부 무너지기 때문이다.

## 1-6. `SessionImpl` — EntityManager 이자 영속성 컨텍스트

📖 [Jakarta Persistence Spec](https://jakarta.ee/specifications/persistence/3.2/) ·
📖 [Hibernate 7 User Guide](https://docs.hibernate.org/orm/7.0/userguide/html_single/Hibernate_User_Guide.html)

여기서 프록시가 **두 겹**이라는 점을 짚어야 한다.

```
@Autowired EntityManager  →  JDK 동적 프록시 (SharedEntityManagerCreator)
                                  │  호출 때마다 "현재 스레드에 바인딩된" EM 을 찾아 위임
                                  ▼
                             SessionImpl  ← 진짜 영속성 컨텍스트, 트랜잭션마다 새로 만들어진다
```

주입받은 `EntityManager` 는 싱글턴 빈인데도 스레드마다 다른 트랜잭션에서 안전하게 쓸 수 있다.
프록시가 매 호출마다 `TransactionSynchronizationManager` 에서 현재 스레드의
`EntityManagerHolder` 를 찾아 위임하기 때문이다.

**`TxProbe` 가 하는 일이 정확히 이 위임 대상을 들여다보는 것이다**(`TxProbe.kt:41`).

```kotlin
(TransactionSynchronizationManager.getResource(entityManagerFactory) as? EntityManagerHolder)
    ?.entityManager
    ?.let { System.identityHashCode(it) }
```

`SessionImpl` 인스턴스는 물리 트랜잭션마다 새로 만들어지므로,
그 identity hash 가 곧 **물리 트랜잭션의 식별자**가 된다. REQUIRED 는 값이 같고
REQUIRES_NEW 는 달라진다. 이 프로젝트 전체가 이 한 가지 사실 위에 서 있다.

Hibernate 문서는 영속성 컨텍스트를 이렇게 설명한다.

> "you can think of it as a cache of data which has been read in the current transaction.
> Thus, in the architecture of Hibernate, it's sometimes called the *first-level cache*"
> — [Hibernate 7 Introduction](https://docs.hibernate.org/orm/7.0/introduction/html_single/Hibernate_Introduction.html)

> the Session "maintains a generally 'repeatable read' persistence context (first level cache)"
> — [Hibernate 7 User Guide](https://docs.hibernate.org/orm/7.0/userguide/html_single/Hibernate_User_Guide.html)

두 번째 인용의 **"generally repeatable read"** 가 `IsolationLevelTest` 첫 시나리오의 정체다.
DB 격리 수준이 무엇이든, 같은 세션에서 같은 PK 를 다시 읽으면 1차 캐시가 같은 값을 돌려준다.
그래서 격리 수준을 관찰하려면 `clear()` 로 이 층을 먼저 걷어내야 한다.

## 1-7. `SimpleJpaRepository` — 리포지토리도 트랜잭션 경계다

📖 [Spring Data JPA — Transactionality](https://docs.spring.io/spring-data/jpa/reference/jpa/transactions.html)

`LogsRepository` 는 인터페이스뿐인데 런타임에는 JDK 동적 프록시이고,
실제 대상은 `SimpleJpaRepository` 다(확인값).

> "By default, methods inherited from `CrudRepository` inherit the transactional configuration
> from `SimpleJpaRepository`. For read operations, the transaction configuration `readOnly` flag
> is set to `true`. All others are configured with a plain `@Transactional` so that default
> transaction configuration applies."
> — Spring Data JPA Reference

**이 사실을 모르면 `RequiredPropagationTest` 의 비교군 결과를 설명할 수 없다.**
서비스에 `@Transactional` 이 하나도 없어도 트랜잭션이 0개인 게 아니라,
`save()` 호출마다 짧은 트랜잭션이 열리고 **즉시 커밋**된다.
그래서 뒤에서 예외가 터져도 되돌릴 대상이 없다(`RequiredPropagationTest.kt:49`).

같은 이유로, `@Transactional` 이 없는 코드에서 `save()` 를 두 번 하면
**원자성이 없다** — 첫 번째만 커밋되고 두 번째가 실패하는 상태가 실제로 가능하다.

---

# 2부. 트랜잭션 개념

## 2-1. 트랜잭션이란 — ACID

📖 [Spring Framework — Transaction Management](https://docs.spring.io/spring-framework/reference/data-access/transaction.html)

| 성질 | 의미 | 이 저장소에서 깨지는 사례 |
|---|---|---|
| **A**tomicity (원자성) | 전부 성공하거나 전부 실패 | REQUIRES_NEW 남용 → 부분 커밋 (`STUDY.md` 4-6) |
| **C**onsistency (일관성) | 제약조건을 지킨 상태로만 남는다 | — |
| **I**solation (격리성) | 동시 트랜잭션이 서로 간섭하지 않는다 | 격리 수준을 낮추면 의도적으로 포기 (`IsolationLevelTest`) |
| **D**urability (지속성) | 커밋된 것은 유지된다 | 인메모리 DB 라 JVM 종료 시 소멸(실험용) |

여기서 **원자성과 격리성이 이 프로젝트의 주제**다.
그리고 두 성질 모두 "트랜잭션만 걸면 공짜로 얻어진다"는 오해가 있다 —
`MixedPropagationChainTest`(원자성 붕괴)와 `LostUpdateAndLockingTest`(격리 수준으로 못 막는 문제)가
그 오해를 깨는 테스트다.

## 2-2. 논리 트랜잭션 vs 물리 트랜잭션 — 가장 중요한 구분

📖 [Spring — Transaction Propagation](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/tx-propagation.html)

> "When the propagation setting is `PROPAGATION_REQUIRED`, a **logical transaction scope** is created
> for each method upon which the setting is applied. Each such logical transaction scope can determine
> rollback-only status individually, with an outer transaction scope being logically independent from
> the inner transaction scope. In the case of standard `PROPAGATION_REQUIRED` behavior,
> **all these scopes are mapped to the same physical transaction**."
> — Spring Framework Reference

정리하면 이렇다.

| | 논리 트랜잭션 | 물리 트랜잭션 |
|---|---|---|
| 단위 | `@Transactional` 이 붙은 메서드 하나 | 실제 커넥션 / EntityManager |
| 개수 세는 법 | 애노테이션 개수 | `TxProbe` 의 `entityManagerId` 개수 |
| 커밋/롤백 | 하지 않는다 (rollback-only 표시만 가능) | 여기서만 일어난다 |

**물리 트랜잭션이 하나면 부분 롤백은 존재하지 않는다.**
REQUIRED 로 참여한 자식이 "나만 롤백"하는 것은 원리적으로 불가능하다.
할 수 있는 것은 공유 트랜잭션에 "이건 롤백해야 함" 표시를 남기는 것뿐이다.

## 2-3. 트랜잭션 동기화 — 모든 것은 ThreadLocal 에 있다

📖 [Javadoc — TransactionSynchronizationManager](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/transaction/support/TransactionSynchronizationManager.html)

Spring 의 트랜잭션 상태는 전부 `TransactionSynchronizationManager` 의 ThreadLocal 에 들어 있다.

```
ThreadLocal
 ├─ resources          : EntityManagerFactory → EntityManagerHolder   ← 물리 트랜잭션의 실체
 ├─ synchronizations   : TransactionSynchronization 목록              ← 이벤트 리스너가 얹히는 곳
 ├─ currentTransactionName
 ├─ currentTransactionReadOnly
 ├─ currentTransactionIsolationLevel
 └─ actualTransactionActive
```

`TxSnapshot`(`TxSnapshot.kt:15`)의 필드가 이 목록과 정확히 일치하는 것은 우연이 아니다.
스냅샷은 **이 ThreadLocal 을 특정 시점에 찍은 사진**이다.

여기서 두 가지 중요한 결론이 나온다.

**1. 트랜잭션은 스레드에 묶인다.**
그래서 "동시에 도는 두 트랜잭션"을 만들려면 스레드가 두 개여야 한다.
`Concurrency.kt:41` 의 `Worker` 가 존재하는 이유이며, 격리 수준·락 테스트가
전부 스레드 2개로 짜인 이유다. 같은 스레드에서 `REQUIRES_NEW` 를 부르면
동시 실행이 아니라 **중첩 실행**이 되고, 그 결과가 스펙 5의 자기 교착이다.

**2. suspend/resume 은 이 ThreadLocal 을 갈아끼우는 것이다.**
REQUIRES_NEW 는 기존 `EntityManagerHolder` 를 떼어내 보관하고 새 것을 바인딩한다.
자식이 끝나면 보관해 둔 것을 되돌린다. `STUDY.md` 4-5가 이 복원을 identity 로 검증한다.

## 2-4. Spring 트랜잭션 추상화 3인방

📖 [Spring — Understanding the Spring Framework Transaction Abstraction](https://docs.spring.io/spring-framework/reference/data-access/transaction/strategies.html)

| 인터페이스 | 역할 | 이 프로젝트의 구현/사용처 |
|---|---|---|
| `PlatformTransactionManager` | 트랜잭션을 여닫는 전략 | `JpaTransactionManager` |
| `TransactionDefinition` | "어떻게" 열 것인가 (전파/격리/타임아웃/readOnly) | `@Transactional` 속성, `TxExecutor.kt:32` |
| `TransactionStatus` | 현재 트랜잭션 제어/조회 | `setRollbackOnly()` (`RollbackPolicyService.kt:65`) |

> "This is primarily a service provider interface (SPI), although you can use it programmatically
> from your application code. Because `PlatformTransactionManager` is an interface,
> it can be easily mocked or stubbed as necessary."
> — Spring Framework Reference

`TransactionDefinition` 이 정의하는 네 가지 축:

> "**Propagation**: Typically, all code within a transaction scope runs in that transaction.
> However, you can specify the behavior if a transactional method is run when a transaction
> context already exists."
>
> "**Isolation**: The degree to which this transaction is isolated from the work of other
> transactions. For example, can this transaction see uncommitted writes from other transactions?"
>
> "**Timeout**: How long this transaction runs before timing out and being automatically
> rolled back by the underlying transaction infrastructure."
>
> "**Read-only status**: You can use a read-only transaction when your code reads but does not
> modify data."
> — Spring Framework Reference

이 저장소는 이 네 축 중 **Propagation, Isolation, Read-only** 세 개를 테스트로 고정했다
(Timeout 은 다루지 않는다. 확인값으로 `defaultTimeout = -1` = 무제한).

`@Transactional` 을 쓰는 선언적 방식 외에 `TransactionTemplate` 을 쓰는 프로그래밍 방식도 있다.
📖 [Programmatic Transaction Management](https://docs.spring.io/spring-framework/reference/data-access/transaction/programmatic.html)
`TxExecutor.kt:27` 과 `SavepointService.kt:91` 이 후자를 쓴다.
어노테이션은 메서드 단위라 "이 블록만 REPEATABLE_READ" 같은 실험을 만들기 어렵기 때문이다.

## 2-5. 전파 속성 7가지

📖 [Javadoc — Propagation](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/transaction/annotation/Propagation.html) ·
📖 [Transaction Propagation (Reference)](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/tx-propagation.html)

| 전파 속성 | 부모 있음 | 부모 없음 | 물리 트랜잭션 |
|---|---|---|---|
| `REQUIRED` (기본) | 참여 | 새로 시작 | 부모와 공유 |
| `REQUIRES_NEW` | 부모 중단 후 새로 시작 | 새로 시작 | **분리** |
| `NESTED` | 세이브포인트 | 새로 시작 | 부모와 공유 |
| `MANDATORY` | 참여 | **예외** | 부모와 공유 |
| `NEVER` | **예외** | 트랜잭션 없이 | 없음 |
| `SUPPORTS` | 참여 | 트랜잭션 없이 | 부모와 공유 / 없음 |
| `NOT_SUPPORTED` | 부모 중단 후 없이 | 트랜잭션 없이 | 없음 |

핵심 세 개의 공식 설명:

> "`PROPAGATION_REQUIRES_NEW`, in contrast to `PROPAGATION_REQUIRED`, always uses an
> **independent physical transaction** for each affected transaction scope, never participating in
> an existing transaction for an outer scope. In such an arrangement, the underlying resource
> transactions are different and, hence, can commit or roll back independently, with an outer
> transaction not affected by an inner transaction's rollback status and with an inner
> transaction's **locks released immediately after its completion**."
> — Spring Framework Reference

마지막 구절("자식의 락은 자식이 끝나는 즉시 풀린다")의 뒷면이 스펙 5의 교착이다.
자식은 락을 즉시 풀지만, **부모의 락은 부모가 커밋해야 풀린다.**
그래서 자식이 부모가 잡은 행을 원하면 영원히 기다린다.

> "`PROPAGATION_NESTED` uses a **single physical transaction with multiple savepoints**
> that it can roll back to. Such partial rollbacks let an inner transaction scope trigger a rollback
> for its scope, with the outer transaction being able to continue the physical transaction
> despite some operations having been rolled back."
> — Spring Framework Reference

### 전파 속성을 결정하는 기준

> **"누가 나를 불렀는가"가 아니라 "지금 스레드에 어떤 물리 트랜잭션이 바인딩돼 있는가"**

3계층 이상에서 헷갈리는 지점이 전부 여기서 나온다.
중간이 REQUIRES_NEW 로 갈아끼우면 손자의 REQUIRED 는 조부모가 아니라 **중간에 참여**한다
(`MixedPropagationChainTest.kt:62`).

## 2-6. 롤백 규칙과 rollback-only 오염

📖 [Spring — Rolling Back a Declarative Transaction](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/rolling-back.html) ·
📖 [Javadoc — UnexpectedRollbackException](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/transaction/UnexpectedRollbackException.html)

### 기본 규칙 — 예외 "타입"이 정한다

> "Any `RuntimeException` or `Error` triggers rollback, and any checked `Exception` does not."
> — Spring Framework Reference

구현은 `DefaultTransactionAttribute.rollbackOn` 이며, EJB 시절 관례를 이어받은 것이다
("체크 예외 = 호출자가 복구할 수 있는 비즈니스 상황이므로 작업을 확정한다").

**Kotlin 에서 특히 위험하다.** Kotlin 에는 체크 예외 개념이 없어
`class MyException : Exception()` 을 만들어 던져도 컴파일러가 아무 경고를 하지 않는데,
Spring 은 여전히 *타입*으로 판단하므로 **커밋되어 버린다**(`RollbackPolicyTest.kt:56`).

대응은 둘 중 하나다.
- 도메인 예외의 부모를 `RuntimeException` 으로 둔다
- `@Transactional(rollbackFor = [Exception::class])` 을 명시한다

Spring 6.2+ 에서는 전역 설정도 가능하다 — `@EnableTransactionManagement(rollbackOn = ALL_EXCEPTIONS)`.

### rollback-only 오염과 `UnexpectedRollbackException`

> "However, in the case where an inner transaction scope sets the rollback-only marker,
> the outer transaction has not decided on the rollback itself, so the rollback
> (silently triggered by the inner transaction scope) is **unexpected**.
> A corresponding `UnexpectedRollbackException` is thrown at that point.
> This is expected behavior so that **the caller of a transaction can never be misled to assume
> that a commit was performed when it really was not.**"
> — Spring Framework Reference

이 인용이 이 예외를 이해하는 열쇠다. `UnexpectedRollbackException` 은 버그가 아니라
**"커밋됐다고 착각하지 못하게 막는 안전장치"** 다.

발생 조건은 셋 다 충족될 때다.

1. 자식이 부모의 물리 트랜잭션에 **참여**했고 (REQUIRED / MANDATORY / SUPPORTS)
2. 자식에서 예외가 프록시를 빠져나가 rollback-only 가 찍혔고
3. 그 예외를 **누군가 삼켜서** 부모가 정상 종료로 커밋을 시도했을 때

하나라도 빠지면 안 나온다.
예외를 그대로 전파하면 원래 예외가 나오고, REQUIRES_NEW 로 분리돼 있으면 애초에 오염되지 않는다.

> **오염 범위는 물리 트랜잭션 경계까지다.** 중간에 REQUIRES_NEW 가 끼어 있으면
> 예외는 그 지점에서 터지고 그 위는 무사하다 (`MixedPropagationChainTest.kt:70`).

예외 없이 코드로 롤백을 지시하려면 `TransactionStatus.setRollbackOnly()` 를 쓴다.
단, 참여 중인 상태에서 부르면 위와 똑같이 부모를 오염시킨다.

## 2-7. 프록시 AOP — `@Transactional` 이 무시되는 조건

📖 [Spring — Using @Transactional](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html) ·
📖 [Understanding AOP Proxies](https://docs.spring.io/spring-framework/reference/core/aop/proxying.html)

> "In proxy mode (which is the default), **only external method calls coming in through the proxy
> are intercepted**. This means that self-invocation (in effect, a method within the target object
> calling another method of the target object) does not lead to an actual transaction at runtime
> even if the invoked method is marked with `@Transactional`."
> — Spring Framework Reference

이 프로젝트에서 확인된 사실:
`LogsRepository` 는 **JDK 동적 프록시**(인터페이스 기반),
`@Service` 클래스들은 **CGLIB 프록시**(클래스 기반)다(`SelfInvocationTest.kt:57`).

### 메서드 가시성 규칙 (Spring 6.0 이후 바뀐 부분)

> "The `@Transactional` annotation is typically used on methods with `public` visibility.
> **As of 6.0, `protected` or package-visible methods can also be made transactional for
> class-based proxies by default.** Note that transactional methods in interface-based proxies
> must always be `public` and defined in the proxied interface."
> — Spring Framework Reference

즉 "public 이 아니면 무조건 안 된다"는 흔한 설명은 6.0 기준으로는 부정확하다.
`protected` / package-private 는 CGLIB 프록시에서 동작한다.
다만 **`private` 은 여전히 불가능하다** — CGLIB 는 서브클래싱으로 동작하는데
private 메서드는 오버라이드할 수 없기 때문이다.

### Kotlin 사용자가 반드시 알아야 할 것

Kotlin 은 클래스와 메서드가 **기본 final** 이다. CGLIB 는 final 클래스를 상속할 수 없으므로,
`kotlin("plugin.spring")`(`build.gradle.kts:3`)이 `@Component`/`@Transactional` 등이 붙은
클래스를 자동으로 `open` 처리해 준다. **이 플러그인이 없으면 `@Transactional` 이 통째로 먹통이 된다.**

### 초기화 코드에서의 주의

> "Also, the proxy must be fully initialized to provide the expected behavior, so you should not
> rely on this feature in your initialization code — for example, in a `@PostConstruct` method."
> — Spring Framework Reference

### 해결책 (우선순위 순)

1. 트랜잭션 경계를 **다른 빈으로 분리**한다 (가장 권장)
2. 자기 자신을 프록시로 주입받아 호출한다 — `ObjectProvider<Self>` 로 순환 참조 회피
   (`SelfInvocationService.kt:27`)
3. `AopContext.currentProxy()` — `@EnableAspectJAutoProxy(exposeProxy = true)` 필요
4. AspectJ 위빙 모드로 전환 — `@EnableTransactionManagement(mode = ASPECTJ)`

## 2-8. 격리 수준과 이상 현상

📖 [H2 — Transaction Isolation](https://h2database.com/html/advanced.html#transaction_isolation) ·
📖 [Javadoc — Isolation](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/transaction/annotation/Isolation.html) ·
📖 [PostgreSQL — Transaction Isolation](https://www.postgresql.org/docs/current/transaction-iso.html) (이상 현상 설명이 특히 잘 되어 있다)

### 세 가지 이상 현상 — H2 공식 정의

> **Dirty Read**: "A connection can read uncommitted changes made by another connection."
>
> **Non-Repeatable Read**: "A connection reads a row, another connection changes a row and commits,
> and the first connection re-reads the same row and gets the new result."
>
> **Phantom Read**: "A connection reads a set of rows using a condition, another connection inserts
> a row that falls in this condition and commits, then the first connection re-reads using the
> same condition and gets the new row."
> — H2 Advanced

### H2 가 공식적으로 밝힌 각 수준의 동작

| 격리 수준 | H2 공식 문서의 서술 |
|---|---|
| READ UNCOMMITTED | "Dirty reads, non-repeatable reads, and phantom reads are possible." |
| READ COMMITTED | "Dirty reads aren't possible; non-repeatable reads and phantom reads are possible." (**기본값**) |
| REPEATABLE READ | "Dirty reads and non-repeatable reads aren't possible, phantom reads are possible." |
| SNAPSHOT | "Dirty reads, non-repeatable reads, and phantom reads aren't possible." |
| SERIALIZABLE | 위와 같으나 "currently doesn't ensure equivalence of concurrent and serializable execution of transactions that perform write operations." |

> H2 는 표준 4단계 외에 **SNAPSHOT** 이라는 수준을 따로 갖고 있다.
> 그리고 SERIALIZABLE 이 완전하지 않다는 점을 스스로 밝히고 있다.

### ⚠️ 문서와 관측이 엇갈리는 지점

`IsolationLevelTest` 는 **REPEATABLE_READ 에서 팬텀 리드가 막히는 것**을 관측하고,
그 결과를 단언한 채 통과한다(`IsolationLevelTest.kt:196`, 이 문서 작성 시점에 재실행하여 확인).
그런데 위 표에서 보듯 H2 공식 문서는 REPEATABLE READ 에서 "phantom reads are possible" 이라고 적고 있다.

- **문서**: REPEATABLE READ 는 팬텀을 허용한다
- **관측** (H2 2.4.240, MVStore, 이 프로젝트 설정): 팬텀이 막힌다

MVStore 가 다중 버전(MVCC) 스토리지라 트랜잭션 시작 시점의 스냅샷을 보여 주기 때문으로 보인다.
어느 쪽이 "맞다"고 단정하기보다, 이 사례 자체를 교훈으로 받아들이는 편이 낫다.

> **격리 수준은 "이름"이 아니라 "쓰는 DB 의 실제 구현"으로 판단해야 한다.**
> 같은 REPEATABLE READ 라도 MySQL(InnoDB), PostgreSQL, H2 가 각각 다르게 동작한다.
> 표를 외워서 옮겨 심으면 깨진다.

### JPA 로 격리 수준을 실험할 때의 함정

**1차 캐시가 격리 수준을 가린다.** 이것을 모르면 실험 자체가 성립하지 않는다.

```
같은 트랜잭션에서 같은 PK 를 두 번 조회
  → 두 번째는 DB 로 가지 않고 SessionImpl 의 1차 캐시에서 반환
  → 격리 수준이 무엇이든 항상 같은 값
```

그래서 재조회 전에 `EntityManager.clear()` 가 필요하다(`TxExecutor.kt:47`).
단, **count 쿼리는 1차 캐시를 타지 않고 항상 DB 로 나가므로** 팬텀 리드 실험에는 `clear()` 가 필요 없다
(`IsolationLevelTest.kt:139`).

이 차이 때문에 겉보기 결과가 같아도 원인이 전혀 다른 두 시나리오가 생긴다.

| 시나리오 | 결과 | 원인 |
|---|---|---|
| `clear()` 없이 재조회 | 값이 안 변함 | **DB 를 안 읽어서** (1차 캐시) |
| REPEATABLE_READ + `clear()` | 값이 안 변함 | **DB 가 옛 스냅샷을 줘서** |

## 2-9. 잃어버린 갱신과 락

📖 [Jakarta Persistence — Locking](https://jakarta.ee/specifications/persistence/3.2/) ·
📖 [Spring Data JPA — @Lock](https://docs.spring.io/spring-data/jpa/reference/jpa/locking.html) ·
📖 [Javadoc — LockModeType](https://jakarta.ee/specifications/persistence/3.2/apidocs/jakarta.persistence/jakarta/persistence/lockmodetype)

**잃어버린 갱신(Lost Update)은 격리 수준으로 막을 수 없다.**

```
T1: 잔액 읽기 (1000)
T2: 잔액 읽기 (1000)
T2: 1000 + 200 = 1200 저장, 커밋
T1: 1000 + 100 = 1100 저장, 커밋      <- T2 의 입금 200 이 사라졌다
```

두 트랜잭션 모두 정상 커밋됐고, 각자의 시선에서는 어떤 격리 규칙도 어기지 않았다.
**예외도 로그도 없어서 가장 발견하기 어려운 버그**다.

| | 낙관적 락 | 비관적 락 |
|---|---|---|
| 선언 | `@Version` (`Account.kt:30`) | `@Lock(PESSIMISTIC_WRITE)` (`AccountRepository.kt:16`) |
| SQL | `update ... where id = ? and version = ?` | `select ... for update` |
| 충돌 감지 | 갱신된 행이 **0건**이면 예외 | 애초에 대기시켜 충돌을 없앰 |
| 실패 시 | `OptimisticLockingFailureException` → **재시도 필요** | 없음 (대기 후 실행) |
| 비용 | 락 없음(빠름), 재시도 로직 필요 | 대기 / 데드락 / 처리량 저하 |
| 적합 | 충돌이 드문 경우 | 충돌이 잦거나 재시도가 곤란한 경우 |

`@Version` 필드는 Hibernate 가 UPDATE 마다 자동 증가시키고 WHERE 절에 넣는다.
`Counter` 에는 일부러 `@Version` 을 빼 두어, 같은 시나리오에서 갱신이 조용히 유실되는 모습을
나란히 보여 준다.

## 2-10. 영속성 컨텍스트와 `readOnly`

📖 [Javadoc — FlushModeType](https://jakarta.ee/specifications/persistence/3.2/apidocs/jakarta.persistence/jakarta/persistence/flushmodetype) ·
📖 [Hibernate — Flushing](https://docs.hibernate.org/orm/7.0/userguide/html_single/Hibernate_User_Guide.html)

### 트랜잭션 경계 == 영속성 컨텍스트 경계

`JpaTransactionManager` 는 물리 트랜잭션을 시작할 때 `SessionImpl` 을 만들고 커밋/롤백 시 닫는다.
따라서

- 같은 트랜잭션 = 같은 `SessionImpl` = 1차 캐시 공유 = 같은 PK 는 **동일 인스턴스**
- REQUIRES_NEW = 다른 `SessionImpl` = 1차 캐시도 **공유되지 않는다**

`application.yaml:20` 의 `open-in-view: false` 도 이 등식을 위한 설정이다.
OSIV 를 켜면 영속성 컨텍스트가 요청 끝까지 살아 있어 트랜잭션 경계와 어긋난다.

### `readOnly = true` 가 실제로 하는 일

**Hibernate 세션의 FlushMode 를 `MANUAL` 로 바꾼다.** 그 결과:

- 변경 감지(dirty checking)로 인한 **UPDATE 가 나가지 않는다**
- 스냅샷 보관을 생략해 메모리를 아낀다
- JDBC 커넥션에 read-only 힌트가 전달될 수 있다(드라이버/DB 에 따라 다름)

> ⚠️ **readOnly 는 "쓰기 금지"가 아니다.**
> `IDENTITY` 전략의 `save()` 는 flush 와 무관하게 즉시 INSERT 를 실행하므로
> readOnly 트랜잭션 안에서도 **그대로 커밋된다**(`PersistenceContextAndReadOnlyTest.kt:121`).
> 의도 표현 + 최적화 힌트이지 **안전장치가 아니다.**

### `IDENTITY` 전략과 쓰기 지연

`Logs`/`Account`/`Counter` 모두 `GenerationType.IDENTITY` 다.
IDENTITY 는 **DB 가 채번한 PK 를 알아야 영속성 컨텍스트에 등록할 수 있으므로**
`save()` 호출 즉시 INSERT 가 전송된다 — 쓰기 지연(write-behind)이 동작하지 않는다.

이 성질이 여러 테스트의 결과를 좌우한다.

| 영향받는 곳 | 결과 |
|---|---|
| readOnly 트랜잭션의 `save()` | INSERT 가 나가고 커밋됨 |
| 트랜잭션 없는 `save()` | 즉시 자동 커밋 (롤백 불가) |
| NOT_SUPPORTED 안의 `save()` | 부모가 롤백해도 살아남음 |

## 2-11. 트랜잭션 이벤트

📖 [Spring — Transaction-bound Events](https://docs.spring.io/spring-framework/reference/data-access/transaction/event.html) ·
📖 [Javadoc — TransactionalEventListener](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/transaction/event/TransactionalEventListener.html)

| 리스너 | 실행 시점 |
|---|---|
| `@EventListener` | `publishEvent` **즉시** (같은 트랜잭션 안, 동기) |
| `@TransactionalEventListener(BEFORE_COMMIT)` | 커밋 직전 (여기서 던진 예외는 아직 롤백을 유발할 수 있다) |
| `@TransactionalEventListener(AFTER_COMMIT)` | 커밋 직후 (**기본값**) |
| `@TransactionalEventListener(AFTER_ROLLBACK)` | 롤백 직후 |
| `@TransactionalEventListener(AFTER_COMPLETION)` | 커밋/롤백 무관 마지막 |

동작 원리는 2-3에서 본 `TransactionSynchronizationManager` 의 `synchronizations` 목록이다.
`@TransactionalEventListener` 는 그 위에 얹힌 편의 장치이며,
`OrderService.kt:37` 이 같은 일을 저수준 API 로 직접 하는 모습을 보여 준다.

### 함정 1 — 트랜잭션이 없으면 아예 실행되지 않는다

> "If no transaction is running, the listener is not invoked at all,
> since we cannot honor the required semantics."
> — Spring Framework Reference

실무에서 **"왜 리스너가 안 타지?"의 1순위 원인**이다. 예외도 경고도 없다.
발행하는 메서드에 `@Transactional` 이 빠져 있는지부터 확인해야 한다.
꼭 받아야 한다면 `fallbackExecution = true` 를 준다(`OrderEventRecorder.kt:65`).

### 함정 2 — AFTER_COMMIT 에는 커밋할 트랜잭션이 없다

원래 트랜잭션은 이미 끝났으므로 여기서 DB 를 쓰려면
`@Transactional(propagation = REQUIRES_NEW)` 로 새 트랜잭션을 열어야 한다
(`OrderEventRecorder.kt:57`). 그냥 저장하면 조용히 사라진다.

### 함정 3 — 같은 phase 안의 순서는 보장되지 않는다

단계 **사이**의 순서(BEFORE_COMMIT → AFTER_COMMIT)는 보장되지만,
같은 phase 에 달린 리스너들끼리의 순서는 보장되지 않는다.
그 순서는 결국 `Class.getDeclaredMethods()` 의 반환 순서에 의존하는데
**JVM 명세가 이를 보장하지 않기 때문**이다(`TransactionalEventTest.kt:130~147`에 추적 경로가 적혀 있다).

순서가 필요하면 테스트를 조이지 말고 **리스너 쪽에 `@Order` 를 명시**한다.

---

# 부록. 공식 문서 링크 모음

## Spring Framework

| 주제 | 링크 |
|---|---|
| 트랜잭션 관리 전체 | https://docs.spring.io/spring-framework/reference/data-access/transaction.html |
| 트랜잭션 추상화(SPI) | https://docs.spring.io/spring-framework/reference/data-access/transaction/strategies.html |
| 선언적 트랜잭션 / `@Transactional` | https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html |
| **전파 속성** | https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/tx-propagation.html |
| 롤백 규칙 | https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/rolling-back.html |
| 프로그래밍 방식 | https://docs.spring.io/spring-framework/reference/data-access/transaction/programmatic.html |
| 트랜잭션 이벤트 | https://docs.spring.io/spring-framework/reference/data-access/transaction/event.html |
| AOP 프록시 이해 | https://docs.spring.io/spring-framework/reference/core/aop/proxying.html |

## Javadoc

| 클래스 | 링크 |
|---|---|
| `TransactionDefinition` | https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/transaction/TransactionDefinition.html |
| `PlatformTransactionManager` | https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/transaction/PlatformTransactionManager.html |
| `AbstractPlatformTransactionManager` | https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/transaction/support/AbstractPlatformTransactionManager.html |
| `JpaTransactionManager` | https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/orm/jpa/JpaTransactionManager.html |
| `TransactionSynchronizationManager` | https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/transaction/support/TransactionSynchronizationManager.html |
| `UnexpectedRollbackException` | https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/transaction/UnexpectedRollbackException.html |
| `Propagation` / `Isolation` | https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/transaction/annotation/Propagation.html |

## Spring Data JPA / Hibernate / Jakarta

| 주제 | 링크 |
|---|---|
| 리포지토리 트랜잭션 | https://docs.spring.io/spring-data/jpa/reference/jpa/transactions.html |
| `@Lock` (락) | https://docs.spring.io/spring-data/jpa/reference/jpa/locking.html |
| Hibernate 7 User Guide | https://docs.hibernate.org/orm/7.0/userguide/html_single/Hibernate_User_Guide.html |
| Hibernate 7 Introduction | https://docs.hibernate.org/orm/7.0/introduction/html_single/Hibernate_Introduction.html |
| Jakarta Persistence 3.2 | https://jakarta.ee/specifications/persistence/3.2/ |

## DB / 인프라

| 주제 | 링크 |
|---|---|
| H2 격리 수준 | https://h2database.com/html/advanced.html#transaction_isolation |
| H2 `SET LOCK_TIMEOUT` | https://h2database.com/html/commands.html#set_lock_timeout |
| H2 MVStore | https://h2database.com/html/mvstore.html |
| HikariCP | https://github.com/brettwooldridge/HikariCP |
| PostgreSQL 격리 수준(개념 참고용) | https://www.postgresql.org/docs/current/transaction-iso.html |

---

## 함께 보기

- `README.md` — 테스트별 기대 결과 표
- `STUDY.md` — 12개 스펙 64개 테스트의 실행 흐름 해설
- `CLAUDE.md` — 이 저장소에서 코드를 수정할 때 지켜야 할 설계 결정

