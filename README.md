# Spring 트랜잭션 심화 테스트

Spring + JPA 의 트랜잭션 동작을 **실행 가능한 테스트로 증명**하는 프로젝트다.
전파 속성, 롤백 규칙, 프록시 AOP 의 한계, 트랜잭션 이벤트, 영속성 컨텍스트,
격리 수준, 동시성 락까지 각 주제를 "말로 설명하는 대신 깨지는 테스트로" 고정했다.

모든 테스트는 실제 DB(H2) 에 커밋/롤백을 일으키며, 격리 수준과 락 테스트는
두 개의 스레드를 `CountDownLatch` 로 동기화해 **결정적으로** 재현한다.

```bash
./gradlew test
```

## 함께 보는 문서

| 문서 | 내용 | 이럴 때 본다 |
|------|------|------------|
| **[STUDY.md](STUDY.md)** | 본문 코드 구조 + 12개 스펙 64개 테스트 전부의 상세 해설 | 특정 테스트가 **왜 그런 결과**가 나오는지 알고 싶을 때 |
| **[CONCEPTS.md](CONCEPTS.md)** | 실제 구현체(H2 / Hikari / Hibernate / `JpaTransactionManager`)와 트랜잭션 개념 정리 | 개념부터 잡고 싶거나, **공식 문서 원문**을 찾을 때 |
| [CLAUDE.md](CLAUDE.md) | 이 저장소에서 코드를 수정할 때 지켜야 할 설계 결정 | 테스트를 추가·변경할 때 |

아래 표는 **결과 요약**이다. 실행 흐름과 근거 코드(`파일:줄번호`)까지 따라가려면
[STUDY.md](STUDY.md) 의 해당 스펙 절을 본다.

## 기술 스택

| 항목 | 버전 |
|------|------|
| Kotlin | 2.2.21 |
| JDK | 17 |
| Spring Boot | 4.0.1 |
| Kotest | 5.9.1 |
| Kotest Spring Extension | 1.3.0 |
| H2 | Spring Boot 관리 버전 (2.x, MVStore) |

> 런타임에 실제로 로딩되는 구현체와 버전(H2 2.4.240, Hibernate 7.2.0.Final, Spring 7.0.2,
> `SessionImpl`, `HikariDataSource` 등)은 [CONCEPTS.md 1부](CONCEPTS.md#1부-이-프로젝트가-실제로-쓰는-구현체) 에
> 확인값으로 정리해 두었다.

---

## 테스트 목록

총 **64개** 테스트, 12개 스펙.
각 스펙의 상세 해설은 [STUDY.md](STUDY.md) 의 **같은 번호 절**에 있다
(예: 아래 3번 → `STUDY.md` 의 "3. NestedPropagationTest").

### 1. `propagation/RequiredPropagationTest` — 논리 트랜잭션 vs 물리 트랜잭션

REQUIRED 의 "참여"는 "중첩"이 아니다. 논리 트랜잭션은 2개여도 물리 트랜잭션은 1개다.

| 시나리오 | 결과 | 설명 |
|---------|------|------|
| 바깥/안쪽 모두 트랜잭션 없음 | 2건 모두 남음 | 트랜잭션 경계가 없으면 `save()` 마다 자동 커밋되어 원자성이 없다 |
| REQUIRED → REQUIRED (정상) | 커밋, EntityManager 동일 | 물리 트랜잭션이 하나임을 EntityManager identity 로 증명 |
| REQUIRED → REQUIRED, **예외를 바깥이 삼킴** | `UnexpectedRollbackException`, 전부 롤백 | 안쪽 프록시를 예외가 빠져나갈 때 공유 트랜잭션이 `rollback-only` 로 마킹된다. 예외를 잡고 복구 작업을 해도 소용없다 |
| REQUIRED → REQUIRED, 예외 전파 | 원래 예외, 전부 롤백 | `UnexpectedRollbackException` 은 "누군가 예외를 삼켰다"는 신호다 |
| 트랜잭션 없음 → REQUIRED | 안쪽만 롤백 | 참여할 부모가 없으면 안쪽이 물리 트랜잭션의 주인이 된다 |

### 2. `propagation/RequiresNewPropagationTest` — 물리 트랜잭션 분리

부모를 중단(suspend)시키고 새 EntityManager/커넥션으로 갈아끼운다.

| 시나리오 | 결과 | 설명 |
|---------|------|------|
| REQUIRED → REQUIRES_NEW (정상) | EntityManager 가 **다름** | 물리 트랜잭션이 2개라는 증거 |
| 안쪽 실패 + 바깥이 catch | 안쪽만 롤백, 바깥 커밋 | REQUIRED 와 결정적으로 갈리는 지점. `rollback-only` 오염이 없다 |
| 안쪽 커밋 후 바깥 실패 | 안쪽은 살아남음 | "실패해도 남겨야 하는" 감사 로그 패턴 |
| 바깥의 미커밋 데이터를 안쪽이 조회 | **0건** | 커넥션이 달라 부모의 미커밋 INSERT 가 보이지 않는다. "방금 저장했는데 왜 안 보이지?"의 원인 |

### 3. `propagation/NestedPropagationTest` — 세이브포인트, 그리고 JPA 의 한계

|               | 물리 트랜잭션 | 자식 실패 시 | 부모 실패 시 |
|---------------|-------------|------------|-------------|
| REQUIRED      | 공유(1개)    | 부모까지 강제 롤백 | 자식도 롤백 |
| NESTED        | 공유(1개)    | **세이브포인트까지만** | 자식도 롤백 |
| REQUIRES_NEW  | 분리(2개)    | 자식만 롤백 | 자식은 살아남음 |

| 시나리오 | 결과 | 설명 |
|---------|------|------|
| JPA + Hibernate 에서 NESTED 호출 | `NestedTransactionNotSupportedException` | **JPA 에서 NESTED 는 쓸 수 없다.** 관문이 두 개 있는데(① `nestedTransactionAllowed` 기본 false ② `HibernateJpaDialect` 가 `SavepointManager` 미제공), ①을 열어도 ②에서 막힌다 |
| JDBC 매니저, NESTED 자식 실패 + catch | 자식만 롤백, 바깥 커밋 | `DataSourceTransactionManager` 는 JDBC 세이브포인트를 그대로 쓰므로 정상 동작한다 |
| JDBC 매니저, 자식 성공 후 바깥 실패 | 전부 롤백 | 같은 물리 트랜잭션이라 부모와 운명을 같이한다 (REQUIRES_NEW 와 갈리는 지점) |
| 세이브포인트 2겹, 가장 안쪽만 실패 | 가장 안쪽만 롤백 | 세이브포인트는 스택처럼 쌓인다 |

> 실무 결론: JPA 를 쓰면서 부분 롤백이 필요하면 REQUIRES_NEW 로 간다.

### 4. `propagation/MixedPropagationChainTest` — 여러 트랜잭션이 복잡하게 섞인 경우

3계층 이상으로 깊어지면 헷갈리는 지점은 하나다.

> **전파 속성은 "누가 나를 불렀는가"가 아니라
> "지금 스레드에 어떤 물리 트랜잭션이 바인딩돼 있는가"로 결정된다.**

중간 계층이 REQUIRES_NEW 로 트랜잭션을 갈아끼우면, 그 아래 REQUIRED 자식은
최초의 부모가 아니라 **중간 계층에 참여**한다. 여기서 나머지 결과가 모두 따라 나온다.

| 시나리오 | 결과 | 설명 |
|---------|------|------|
| REQUIRED → REQUIRES_NEW → REQUIRED | 손자가 **중간**에 참여 | 물리 트랜잭션 2개(조부모 / 중간+손자), 논리 트랜잭션 3개 |
| 위 체인에서 손자 실패 + **중간이 예외를 삼킴** | `UnexpectedRollbackException` 이 **중간에서** 발생, 조부모는 잡고 커밋 | **rollback-only 오염은 물리 트랜잭션 경계를 넘지 못한다** |
| REQUIRED → NOT_SUPPORTED → REQUIRED | 손자가 조부모와 무관한 새 트랜잭션을 염 | 중간에서 부모가 중단되어 참여할 대상이 사라진다 |
| REQUIRED → REQUIRES_NEW → REQUIRES_NEW | 물리 트랜잭션 3개 | EntityManager identity 3개가 모두 다름 |
| REQUIRES_NEW 호출 전후의 부모 스냅샷 | 동일한 트랜잭션으로 복원 | suspend 는 ThreadLocal 리소스를 떼어 보관하고 resume 이 되돌려 놓는다 |
| REQUIRES_NEW 여러 번 커밋 후 부모 실패 | **부분 커밋** | 원자성이 무너진다. 보상 트랜잭션을 직접 짜야 한다 |
| 형제 호출: REQUIRES_NEW(성공) → REQUIRED(실패) | 앞은 살아남고 뒤는 부모까지 롤백 | 한 메서드 안에서 두 자식의 운명이 갈린다 |

### 5. `propagation/RequiresNewSelfDeadlockTest` — REQUIRES_NEW 가 자기 자신과 교착

```
부모 트랜잭션(커넥션 A): 계좌 행 UPDATE -> 행 잠금 획득, 커밋 전
  └─ 자식 REQUIRES_NEW(커넥션 B): 같은 행 UPDATE 시도
         -> A 가 커밋할 때까지 대기
         -> 그런데 A 는 B 가 끝나야 진행된다
         -> 영원히 풀리지 않는다
```

**같은 스레드** 위에 있는데도 커넥션이 다르기 때문에, DB 입장에서는 남남인 두 세션이
서로를 기다린다. 스레드가 하나뿐이라 어느 쪽도 양보할 수 없다.

| 시나리오 | 결과 | 설명 |
|---------|------|------|
| 자식이 부모가 잠근 **같은 행**을 수정 | `LockTimeoutException`, 전체 롤백 | 운영에서 타임아웃이 길면 요청 스레드와 커넥션이 묶여 풀이 마르는 장애가 된다 |
| 자식이 **다른 행**을 수정 | 둘 다 정상 커밋 | REQUIRES_NEW 자체가 문제가 아니라 "같은 자원을 두 트랜잭션이 잡는 것"이 문제다 |

> 흔한 발생 경로: "감사 로그는 실패해도 남아야 하니까" REQUIRES_NEW 를 붙였는데
> 그 로그 작업이 본 테이블을 함께 건드리는 경우.

### 6. `propagation/OtherPropagationTest` — MANDATORY / NEVER / SUPPORTS / NOT_SUPPORTED

| 전파 속성 | 부모 트랜잭션 있음 | 부모 트랜잭션 없음 |
|----------|-----------------|------------------|
| MANDATORY | 참여 | `IllegalTransactionStateException` |
| NEVER | `IllegalTransactionStateException` | 트랜잭션 없이 실행 |
| SUPPORTS | 참여 | 트랜잭션 없이 실행 |
| NOT_SUPPORTED | 부모 중단 후 없이 실행 | 트랜잭션 없이 실행 |

핵심 검증: NOT_SUPPORTED 안에서 저장한 데이터는 **부모가 롤백해도 살아남는다.**
"트랜잭션 없이 실행"은 곧 자동 커밋이므로, 이 안에서의 쓰기는 되돌릴 방법이 없다.

### 7. `rollback/RollbackPolicyTest` — 무엇이 롤백을 유발하는가

Spring 의 기본 규칙은 `RuntimeException` 과 `Error` 만 롤백한다.

| 던진 예외 | 결과 | 설명 |
|----------|------|------|
| `RuntimeException` | 롤백 | 기본 규칙 |
| **체크 예외 (`Exception`)** | **커밋** | Kotlin 에는 체크 예외 개념이 없어 컴파일러가 경고하지 않는다. 가장 놓치기 쉬운 함정 |
| `Error` | 롤백 | 기본 규칙 |
| 체크 예외 + `rollbackFor` | 롤백 | 규칙 확장 |
| 언체크 예외 + `noRollbackFor` | 커밋 | 규칙 축소 ("재고 부족" 처럼 기록은 남겨야 하는 경우) |
| 예외 없이 `setRollbackOnly()` | 커밋되지 않음 | 반환값으로 실패를 표현하는 API 용 |

### 8. `proxy/SelfInvocationTest` — `@Transactional` 이 조용히 무시되는 경우

```
호출자 ──► [프록시: 트랜잭션 시작] ──► 원본 빈.method()
                                       └─ this.other()  ◄── 프록시를 거치지 않는다!
```

| 시나리오 | 결과 | 설명 |
|---------|------|------|
| 주입받은 빈의 정체 확인 | CGLIB 프록시 | `AopUtils.isCglibProxy` 로 확인. 트랜잭션이 프록시에서만 걸리는 물리적 근거 |
| `this.transactionalMethod()` 호출 | 트랜잭션 **없음** | 예외도 경고도 없이 `@Transactional` 이 무시된다 |
| 위 상태에서 저장 후 예외 | **롤백되지 않음** | 운영에서 데이터가 남는 형태로 드러난다 |
| 자기 자신을 프록시로 주입해 호출 | 트랜잭션 정상 동작 | `ObjectProvider` 로 순환 참조 회피. 단, 서비스 분리가 우선 |

### 9. `event/TransactionalEventTest` — 커밋된 뒤에만 부수효과 실행하기

| 리스너 | 실행 시점 |
|-------|----------|
| `@EventListener` | `publishEvent` 즉시 (같은 트랜잭션) |
| `@TransactionalEventListener(BEFORE_COMMIT)` | 커밋 직전 |
| `@TransactionalEventListener(AFTER_COMMIT)` | 커밋 직후 |
| `@TransactionalEventListener(AFTER_ROLLBACK)` | 롤백 직후 |
| `@TransactionalEventListener(AFTER_COMPLETION)` | 커밋/롤백 무관 마지막 |

| 시나리오 | 결과 | 설명 |
|---------|------|------|
| 커밋 시 | BEFORE_COMMIT → AFTER_COMMIT 순서 보장 | 같은 phase 안의 리스너끼리는 순서 미보장 (`@Order` 필요) |
| AFTER_COMMIT 에서 `REQUIRES_NEW` 로 저장 | 커밋됨 | 원래 트랜잭션은 이미 끝났으므로 새 트랜잭션이 필요하다 |
| 롤백 시 | AFTER_COMMIT 미실행, AFTER_ROLLBACK 실행 | 롤백된 주문에 메일이 나가는 사고를 구조적으로 막아준다. 단 `@EventListener` 는 이미 실행된 뒤다 |
| **트랜잭션 없이 발행** | `fallbackExecution = true` 인 리스너만 실행 | "왜 리스너가 안 타지?"의 1순위 원인 |
| `TransactionSynchronizationManager` 직접 등록 | beforeCommit → afterCommit → afterCompletion | `@TransactionalEventListener` 가 얹혀 있는 저수준 메커니즘 |

### 10. `persistence/PersistenceContextAndReadOnlyTest` — 영속성 컨텍스트와 readOnly

| 시나리오 | 결과 | 설명 |
|---------|------|------|
| 같은 트랜잭션에서 같은 PK 2회 조회 | **동일 인스턴스** | 1차 캐시. 두 번째는 DB 를 타지 않는다 |
| 사이에 `clear()` | 다른 인스턴스 | 격리 수준 테스트에서 `clear()` 가 필수인 이유 |
| 안쪽이 `REQUIRES_NEW` 로 조회 | 다른 인스턴스 | EntityManager 가 달라 1차 캐시도 공유되지 않는다 |
| IDENTITY 전략에서 `save()` 직후 PK | 이미 채워져 있음 | **쓰기 지연이 동작하지 않는다.** PK 를 알아야 영속성 컨텍스트에 등록할 수 있어 INSERT 가 즉시 나간다 |
| `readOnly = true` 에서 엔티티 수정 | UPDATE 안 나감 | FlushMode 가 MANUAL 이라 변경 감지가 동작하지 않는다 |
| `readOnly = true` 에서 `save()` | **커밋됨** | readOnly 는 쓰기 금지가 아니다. 안전장치로 믿으면 안 된다 |

### 11. `isolation/IsolationLevelTest` — 격리 수준 (스레드 2개, H2 2.x 기준)

| 격리 수준 | Dirty Read | Non-Repeatable Read | Phantom Read |
|----------|-----------|--------------------|--------------|
| READ_UNCOMMITTED | O | O | O |
| READ_COMMITTED | X | O | O |
| REPEATABLE_READ | X | X | (구현별) |
| SERIALIZABLE | X | X | X |

| 시나리오 | 결과 | 설명 |
|---------|------|------|
| **`clear()` 없이 2회 조회** | 항상 첫 값 | **1차 캐시가 격리 수준을 가린다.** 이 전제를 모르면 격리 수준 실험이 전부 무의미해진다 |
| READ_COMMITTED + `clear()` | 값이 바뀜 | 반복 불가능한 읽기 재현 |
| READ_COMMITTED, 조건부 count 2회 | 건수 증가 | 팬텀 리드 재현 |
| REPEATABLE_READ + `clear()` | 값 그대로 | DB 스냅샷이 막아준다 |
| REPEATABLE_READ, count 2회 | 건수 그대로 | 표준 SQL 은 팬텀을 허용하지만 H2(MVStore) 는 스냅샷 방식이라 함께 막힌다. **"격리 수준의 이름"이 아니라 "DB 의 실제 구현"을 확인해야 한다** |
| READ_UNCOMMITTED | 미커밋 값이 읽힘 | 더티 리드 재현. 읽은 값은 곧 롤백되어 사라진다 |

### 12. `lock/LostUpdateAndLockingTest` — 잃어버린 갱신과 두 가지 해법

```
T1: 잔액 읽기 (1000)
T2: 잔액 읽기 (1000)
T2: 1000 + 200 = 1200 저장, 커밋
T1: 1000 + 100 = 1100 저장, 커밋      <- T2 의 입금 200 이 사라졌다
```

두 트랜잭션 모두 정상 커밋되지만 결과는 틀렸다. 격리 수준으로는 막을 수 없다.

| 시나리오 | 결과 | 설명 |
|---------|------|------|
| `@Version` 없는 엔티티, 동시 증가 | **갱신 유실** | 예외도 로그도 없다. 가장 발견하기 어려운 버그 |
| `@Version` 있는 엔티티, 동시 수정 | 늦은 쪽이 `OptimisticLockingFailureException` | `update ... where id = ? and version = ?` 이 0건을 갱신하면 예외. 실패한 쪽은 재시도해야 한다 |
| `select ... for update` (비관적 락) | 직렬화되어 둘 다 반영 | 두 번째는 첫 번째 커밋까지 select 에서 대기한다. 재시도는 없지만 대기/데드락을 감수해야 한다 |

---

## 프로젝트 구조

```
src/main/kotlin/org/example/transactiontest/
├── config/TransactionConfig.kt      # nestedTransactionAllowed 커스터마이저 + NESTED 한계 설명
├── entity/
│   ├── Logs.kt                      # 전파/롤백/팬텀 실험용 (IDENTITY 전략의 함정 포함)
│   ├── Account.kt                   # @Version — 낙관적/비관적 락
│   └── Counter.kt                   # @Version 없음 — 잃어버린 갱신 재현
├── repository/                      # findByIdForUpdate (PESSIMISTIC_WRITE) 포함
├── support/
│   ├── TxSnapshot.kt                # 트랜잭션 상태 스냅샷 (물리 트랜잭션 식별자 포함)
│   └── TxProbe.kt                   # TransactionSynchronizationManager 관찰 도구
├── exception/DemoExceptions.kt      # 체크 / 언체크 예외
├── event/                           # 트랜잭션 이벤트 발행·수신
└── service/
    ├── InnerService.kt              # 7가지 전파 속성을 모두 노출
    ├── OuterService.kt              # 호출 조합 (catch / rethrow / 이후 실패)
    ├── SavepointService.kt          # JDBC 트랜잭션 매니저로 NESTED 실증
    ├── RollbackPolicyService.kt
    ├── SelfInvocationService.kt
    ├── ReadOnlyService.kt
    ├── PersistenceContextService.kt
    ├── AccountService.kt
    └── CounterService.kt

src/test/kotlin/org/example/transactiontest/
├── support/
│   ├── DatabaseCleaner.kt           # 매 테스트 TRUNCATE
│   ├── TxExecutor.kt                # 격리 수준 지정 프로그래밍 방식 트랜잭션
│   └── Concurrency.kt               # Signal / Worker — 결정적 동시성 시나리오
├── propagation/  rollback/  proxy/  event/  persistence/  isolation/  lock/
```

각 파일이 어떤 역할을 맡고 그것이 테스트 결과를 어떻게 좌우하는지는
[STUDY.md 1부](STUDY.md) 에서 다룬다.

## 테스트를 읽기 전에 알아야 할 설계 결정

**1. 테스트 메서드에 `@Transactional` 을 붙이지 않는다.**
테스트를 트랜잭션으로 감싸면 서비스의 REQUIRED 가 테스트 트랜잭션에 참여해 버려서,
정작 검증하려는 커밋/롤백 경계가 사라진다. 대신 매 테스트 시작 시 `DatabaseCleaner` 로
실제 커밋된 데이터를 TRUNCATE 한다.

**2. 모든 실행과 검증은 `Then` 블록 안에 둔다.**
Kotest 의 `beforeTest` 는 컨테이너(`Given`/`When`)에도 걸리므로,
`When` 블록 본문에서 동작을 실행하면 뒤이은 초기화에 데이터가 지워질 수 있다.

**3. 물리 트랜잭션은 EntityManager 의 identity 로 식별한다.**
`TxProbe` 가 `TransactionSynchronizationManager` 에 바인딩된 `EntityManagerHolder` 를 들여다본다.
REQUIRED 는 값이 같고 REQUIRES_NEW 는 다르다 — 전파 속성의 차이를 눈으로 확인할 수 있다.

**4. 동시성 테스트는 `Thread.sleep` 대신 `Signal` 로 순서를 고정한다.**
`sleep` 기반 타이밍은 CI 에서 쉽게 깨진다. `CountDownLatch` 로 두 스레드의 진행 순서를
명시적으로 못 박고, 대기 시간이 지나면 조용히 통과하는 대신 실패시킨다.

## 트랜잭션 로그 보기

`src/main/resources/application.yaml` 의 아래 두 줄 주석을 해제하면
트랜잭션이 언제 열리고 / 참여하고 / 중단(suspend)되는지 직접 볼 수 있다.

```yaml
logging:
  level:
    org.springframework.transaction.interceptor: TRACE
    org.springframework.orm.jpa.JpaTransactionManager: DEBUG
```

로그에서 찾아야 할 문구(`Participating in existing transaction`,
`Suspending current transaction` 등)는 [STUDY.md 부록](STUDY.md) 에 정리되어 있다.

---

## 더 읽기

- **[STUDY.md](STUDY.md)** — 테스트 64개의 실행 흐름 해설. 스펙마다 "한눈에 → 자세히" 2단 구성이며,
  마지막에 `UnexpectedRollbackException` 발생 조건, "데이터가 롤백되지 않았다"의 원인 후보 등
  증상 → 원인 역인덱스가 붙어 있다.
- **[CONCEPTS.md](CONCEPTS.md)** — `@Transactional` 한 줄이 지나가는 7단계 계층(프록시 → 트랜잭션 매니저 →
  ThreadLocal → Hibernate 세션 → 커넥션 풀 → H2), 각 층의 실제 구현체, 그리고 전파·격리·락·이벤트
  개념 정리. Spring / Hibernate / H2 **공식 문서 링크와 원문 인용** 포함.
- [CLAUDE.md](CLAUDE.md) — 코드를 수정할 때 지켜야 할 설계 결정과 응답 규칙.
