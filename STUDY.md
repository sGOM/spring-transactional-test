# STUDY — Spring 트랜잭션 심화 학습 노트

이 문서는 저장소의 코드를 **읽는 순서**대로 따라가며 설명한다.

- 1부: 본문 코드(`src/main`) 구조 — 테스트가 무엇을 도구 삼아 동작을 증명하는가
- 2부: 테스트 코드(`src/test`) 12개 스펙, 64개 테스트 전부에 대한 상세 설명

각 테스트 설명은 **한눈에(쉬운 설명) → 자세히** 두 단으로 되어 있다.
결과 표만 빠르게 보고 싶으면 `README.md` 를, 실행 흐름과 근거 코드까지 보고 싶으면 이 문서를 본다.

---

# 1부. 본문 코드 구조

본문 코드는 "기능"을 위해 존재하지 않는다. **트랜잭션 경계를 여러 모양으로 만들어 보여 주기 위한 실험 장치**다.
그래서 클래스를 도메인이 아니라 *역할* 로 나눠 두었다.

```
src/main/kotlin/org/example/transactiontest/
├── support/      관찰 도구      — 지금 트랜잭션이 어떤 상태인지 들여다본다
├── entity/       실험 재료      — 엔티티마다 "일부러 다른 성질"을 갖는다
├── repository/   저장소         — 비관적 락 쿼리 하나만 커스텀
├── exception/    실패 신호      — 체크 / 언체크 예외
├── service/      실험 시나리오  — 전파 속성 조합을 메서드 이름으로 노출
├── event/        이벤트 실험
└── config/       NESTED 를 켜기 위한 설정(그리고 그것만으로 부족하다는 증거)
```

## 1-1. `support/` — 트랜잭션을 눈으로 보는 도구

이 프로젝트의 핵심 아이디어가 여기 있다.

> **물리 트랜잭션은 EntityManager 의 identity 로 식별한다.**

Spring 의 트랜잭션 정보는 전부 `TransactionSynchronizationManager` 의 ThreadLocal 에 들어 있다.
`JpaTransactionManager` 는 물리 트랜잭션을 시작할 때 `EntityManagerFactory` 를 key 로
`EntityManagerHolder` 를 이 ThreadLocal 에 바인딩한다.

- `TxProbe.kt:41` — 그 홀더에서 EntityManager 를 꺼내 `System.identityHashCode` 를 찍는다.
- `TxSnapshot.kt:30` — `sharesPhysicalTransactionWith()` 로 두 스냅샷이 같은 물리 트랜잭션인지 비교한다.

이 값 하나로 다음이 전부 구분된다.

| 상황 | entityManagerId |
|------|-----------------|
| REQUIRED 참여 | 부모와 **같음** (물리 트랜잭션 1개) |
| REQUIRES_NEW | 부모와 **다름** (물리 트랜잭션 2개) |
| NOT_SUPPORTED / 트랜잭션 없음 | **null** (바인딩된 것이 없음) |

스냅샷에는 `actualTransactionActive`(진짜 물리 트랜잭션이 열렸는가), `transactionName`
(물리 트랜잭션을 *시작한* 메서드 이름), `readOnly`, `isolationLevel` 도 함께 담긴다
(`TxSnapshot.kt:15`). 특히 `transactionName` 은 "참여했을 뿐"인지 "새로 열었는지"를
이름만으로 구분해 준다 — 참여했다면 이름이 부모 메서드의 것으로 남는다.

## 1-2. `entity/` — 엔티티마다 성질을 다르게 준 이유

| 엔티티 | 성질 | 어디에 쓰이는가 |
|--------|------|----------------|
| `Logs.kt` | 가장 단순, `IDENTITY` 전략 | 전파/롤백/팬텀 리드 |
| `Account.kt` | `@Version` **있음** (`Account.kt:30`) | 낙관적 락, 비관적 락 |
| `Counter.kt` | `@Version` **없음** | 잃어버린 갱신, 격리 수준 |

`Logs` 의 `GenerationType.IDENTITY`(`Logs.kt:27`)는 겉보기엔 사소하지만 결과를 여러 곳에서 바꾼다.
IDENTITY 는 DB 가 채번한 PK 를 알아야 영속성 컨텍스트에 등록할 수 있어서
**`save()` 호출 즉시 INSERT 가 DB 로 전송된다**(쓰기 지연이 동작하지 않는다).
그래서 `readOnly = true` 트랜잭션 안에서도 INSERT 가 그대로 나가고, 트랜잭션이 없으면 즉시 자동 커밋된다.

`Account` 와 `Counter` 는 "`@Version` 이 있고 없고"만 다른 쌍둥이다.
같은 동시성 시나리오를 두 엔티티로 각각 돌려서, 갱신 유실이 나는 쪽과 막히는 쪽을 나란히 보여 주기 위한 설계다.

## 1-3. `service/` — 시나리오를 메서드 이름으로 노출

전파 속성 실험은 **호출하는 쪽 / 호출당하는 쪽 / 그 사이** 세 계층으로 나눠 두었다.

- `InnerService.kt` — 호출당하는 쪽. 7가지 전파 속성을 모두 노출한다.
  각 메서드는 로그 한 건을 저장하고 스냅샷을 돌려주며, `...AndFail` 변형은 저장 직후 언체크 예외를 던진다.
- `OuterService.kt` — 호출하는 쪽. 메서드 이름 규칙은
  `<부모전파>Calling<자식전파>[AndCatch|AndRethrow|ThenOuterFails]` 다(`OuterService.kt:26`).
  이름만 읽으면 어떤 시나리오인지 알 수 있다.
- `MiddleService.kt` — 3계층 체인의 중간. **트랜잭션 기준점이 갈아끼워지는 상황**을 만들기 위해 존재한다.

나머지 서비스는 주제별 전용이다.

| 클래스 | 주제 |
|--------|------|
| `SavepointService.kt` | JDBC 트랜잭션 매니저로 NESTED 를 실증 (JPA 로는 불가능하므로) |
| `RollbackPolicyService.kt` | 어떤 예외가 롤백을 유발하는가 |
| `SelfInvocationService.kt` | 프록시를 거치지 않는 자기 호출 |
| `SelfDeadlockService.kt` | REQUIRES_NEW 가 자기 자신과 교착 |
| `PersistenceContextService.kt` | 1차 캐시, 쓰기 지연 |
| `ReadOnlyService.kt` | `readOnly = true` 의 실체 |
| `AccountService.kt` / `CounterService.kt` | 동시성 락 / 격리 수준 |

`AccountService` 와 `CounterService` 의 모든 메서드가 `REQUIRES_NEW` 인 것은 의도적이다
(`AccountService.kt:12`). 테스트가 여러 스레드에서 이 메서드들을 부르므로
"한 호출 = 독립된 트랜잭션 하나"라는 전제를 확실히 하기 위해서다.

자기 자신을 다시 호출해야 하는 클래스들(`SelfInvocationService`, `SelfDeadlockService`,
`PersistenceContextService`)은 `ObjectProvider<자기타입>` 을 주입받는다.
생성자에서 자기 자신을 바로 주입하면 순환 참조가 되기 때문에 지연 조회를 쓴다.

## 1-4. `config/` — NESTED 를 켜는 관문은 두 개다

`TransactionConfig.kt:46` 의 커스터마이저는 `JpaTransactionManager.isNestedTransactionAllowed = true` 로
**관문 1**을 연다. 하지만 그것으로 끝이 아니다.

1. `nestedTransactionAllowed` 기본값이 false → "does not allow nested transactions by default"
2. 그걸 켜도 `HibernateJpaDialect` 가 `SavepointManager` 를 제공하지 않는다 → "JpaDialect does not support savepoints"

즉 설정을 아무리 맞춰도 **JPA(Hibernate) + Spring 조합에서 NESTED 는 쓸 수 없다.**
이 두 단계를 `NestedPropagationTest` 가 실제로 확인한다.

## 1-5. `event/` — 커밋 이후에만 실행되는 부수효과

`OrderEventRecorder.kt:38~66` 에 리스너 6개가 phase 별로 달려 있고, 실행될 때마다
자기 이름을 `phases` 리스트에 기록한다. 테스트는 이 리스트를 읽어 "언제 무엇이 실행되었는지"를 검증한다.
`OrderService.kt` 는 트랜잭션 안/밖에서 이벤트를 발행하는 세 가지 경로를 제공한다.

## 1-6. 테스트 쪽 지원 도구 (`src/test/.../support`)

| 파일 | 역할 |
|------|------|
| `DatabaseCleaner.kt:19` | 매 테스트 시작 시 TRUNCATE. 테스트에 `@Transactional` 을 안 붙이므로 실제 커밋된 데이터를 지워야 한다 |
| `TxExecutor.kt:27` | 격리 수준을 지정해 **항상 새 물리 트랜잭션**을 여는 `TransactionTemplate` 래퍼 |
| `TxExecutor.kt:47` | `clearPersistenceContext()` — 격리 수준 실험의 필수 준비물 |
| `Concurrency.kt:15` | `Signal` — `CountDownLatch` 로 두 스레드의 진행 순서를 못 박는다 |
| `Concurrency.kt:41` | `Worker` — 별도 스레드에서 실행하고 예외를 `failure` 에 담아 둔다 |

`Signal.await()` 는 타임아웃되면 **조용히 통과하지 않고 `check` 로 실패시킨다**(`Concurrency.kt:25`).
"시간이 지났으니 통과"는 동시성 테스트를 무의미하게 만들기 때문이다.

## 1-7. 반복해서 등장하는 핵심 개념 5가지

이 다섯 개만 잡으면 나머지는 조합이다.

1. **논리 트랜잭션 vs 물리 트랜잭션**
   `@Transactional` 이 붙은 메서드 하나 = 논리 트랜잭션 1개.
   실제 커넥션/EntityManager 와 commit·rollback = 물리 트랜잭션.
   REQUIRED 로 참여하면 논리 2개 / 물리 1개가 된다. **물리가 하나면 부분 롤백은 존재하지 않는다.**

2. **rollback-only 오염**
   참여 중인 자식에서 예외가 프록시를 빠져나가면 공유 트랜잭션에 rollback-only 플래그가 찍힌다.
   부모가 예외를 잡고 정상 종료해도 커밋 시점에 `UnexpectedRollbackException` 이 터진다.
   → `UnexpectedRollbackException` 은 **"누군가 예외를 삼켰다"는 신호**다.

3. **중단(suspend) / 복원(resume)**
   REQUIRES_NEW 와 NOT_SUPPORTED 는 ThreadLocal 에 바인딩된 리소스를 잠시 떼어 보관하고
   새 것으로 갈아끼운다(또는 비워 둔다). 자식이 끝나면 원래대로 되돌린다.

4. **프록시 경계**
   트랜잭션은 "호출이 프록시를 통과할 때" 시작된다. `this.method()` 는 프록시를 거치지 않는다.

5. **1차 캐시 개입**
   트랜잭션 경계 == 영속성 컨텍스트 경계. 같은 트랜잭션에서 같은 PK 를 두 번 조회하면
   두 번째는 DB 로 가지 않는다. 그래서 격리 수준 실험에는 `clear()` 가 필수다.

---

# 2부. 테스트 코드 상세

## 공통 규칙 (모든 스펙에 적용)

세 가지 규칙이 모든 스펙에 똑같이 적용되어 있다. 이유를 모르면 코드가 이상해 보인다.

**1. 테스트 메서드에 `@Transactional` 을 붙이지 않는다.**
붙이면 서비스의 REQUIRED 가 테스트 트랜잭션에 참여해 버려서, 검증 대상인 커밋/롤백 경계가 사라진다.
대신 `beforeTest { databaseCleaner.clean() }` 로 실제 커밋된 데이터를 TRUNCATE 한다.

**2. 모든 실행과 검증은 `Then` 블록 안에 둔다.**
Kotest 의 `beforeTest` 는 컨테이너(`Given`/`When`)에도 걸린다. `When` 본문에서 실행하면
뒤이은 초기화가 데이터를 지워 버린다.

**3. 각 스펙 상단에 KDoc 으로 검증 항목을 번호로 나열한다.**
테스트를 추가·변경하면 이 KDoc 과 `README.md` 의 표도 함께 갱신한다.

---

## 1. `propagation/RequiredPropagationTest` — 논리 vs 물리 트랜잭션 (5개)

### 한눈에

REQUIRED 는 "진행 중인 트랜잭션이 있으면 참여, 없으면 새로 시작"하는 기본값이다.
여기서 **참여는 중첩이 아니다.** `@Transactional` 메서드가 2개여도 실제 커넥션은 1개다.
커넥션이 하나이므로 "안쪽만 롤백" 같은 건 존재하지 않는다.
안쪽이 예외를 던지면 트랜잭션 전체에 "이건 롤백해야 함" 도장이 찍히고,
바깥이 그 예외를 잡아 무마해도 커밋 시점에 `UnexpectedRollbackException` 이 터지며 전부 사라진다.

### 자세히

**시나리오 1 — 트랜잭션이 아예 없을 때 (비교군)** `RequiredPropagationTest.kt:47`

`outerService.noneCallingNoneAndCatch()`(`OuterService.kt:46`)는 서비스 계층에 `@Transactional` 이 없다.
그렇다고 트랜잭션이 0개인 것은 아니다. `SimpleJpaRepository.save()` 에 `@Transactional` 이 붙어 있어서
**save() 호출마다 짧은 트랜잭션이 열리고 즉시 커밋된다.**
그래서 뒤에서 예외가 터져도 되돌릴 대상 자체가 없고, 두 건 모두 DB 에 남는다(`:55`).

이 비교군이 있어야 뒤의 결과들이 "트랜잭션 덕분"임을 알 수 있다.

**시나리오 2 — REQUIRED → REQUIRED 정상** `:62`

`requiredCallingRequired()`(`OuterService.kt:61`)가 `innerService.required()`(`InnerService.kt:42`)를 부른다.
검증 포인트가 세 개다.

- `sharesPhysicalTransactionWith` 가 true (`:68`) → EntityManager 가 같다 = **물리 트랜잭션 1개**
- `inner.transactionName == outer.transactionName` (`:73`) → 안쪽은 새로 시작한 게 아니라 참여했을 뿐
- 트랜잭션 이름이 `requiredCallingRequired` (`:74`) → 물리 트랜잭션을 *연* 것은 바깥이다

논리 트랜잭션 2개 / 물리 트랜잭션 1개가 스냅샷으로 증명된다.

**시나리오 3 — 안쪽 예외를 바깥이 삼킴 (가장 중요)** `:80`

`requiredCallingRequiredAndCatch()`(`OuterService.kt:75`)의 흐름:

1. 바깥이 물리 트랜잭션을 연다
2. 바깥이 `OUTER` 저장
3. 안쪽(REQUIRED)이 참여 → `INNER` 저장 → `InnerFailureException`
4. **예외가 안쪽 프록시를 빠져나가는 순간 공유 트랜잭션에 rollback-only 마킹**
5. 바깥이 예외를 catch 하고, 복구 시도로 `OUTER_AFTER_CATCH` 를 추가 저장(`OuterService.kt:83`)
6. 바깥 메서드가 정상 종료 → 커밋 시도 → rollback-only 라서 롤백 + `UnexpectedRollbackException`

결과는 `count() == 0`(`:91`). **복구 작업까지 전부 무의미했다.**
`exception.message` 에 `rollback-only` 문자열이 들어 있는 것도 확인한다(`:88`).

실무에서 이 사고는 "try-catch 로 예외를 잡아 로그만 남기고 진행했는데
왜 커밋이 안 되지?" 형태로 나타난다. 예외를 삼킨 코드와 터지는 위치가 멀어서 원인을 찾기 어렵다.

**시나리오 4 — 예외를 그대로 전파** `:95`

`requiredCallingRequiredAndRethrow()`(`OuterService.kt:88`)는 예외를 잡지 않는다.
그러면 트랜잭션 매니저는 "정상 종료 후 커밋" 경로가 아니라 "예외로 인한 롤백" 경로를 타므로
`UnexpectedRollbackException` 을 만들지 않고 **원래 예외**가 그대로 나온다(`:100`).

시나리오 3과 4의 대비가 핵심이다.
→ `UnexpectedRollbackException` 이 보인다면 **중간에서 누군가 예외를 삼켰다는 뜻**이다.

**시나리오 5 — 부모가 없을 때** `:109`

`noneCallingRequiredAndFail()`(`OuterService.kt:227`)에서는 참여할 부모가 없어
안쪽이 물리 트랜잭션의 주인이 된다. 예외가 안쪽 프록시를 빠져나갈 때 그 자리에서 롤백이 끝나므로
오염될 바깥 트랜잭션 자체가 없다. 바깥은 예외를 잡고 멀쩡히 진행한다(`:117`).

---

## 2. `propagation/RequiresNewPropagationTest` — 물리 트랜잭션 분리 (4개)

### 한눈에

REQUIRES_NEW 는 진행 중인 트랜잭션을 **잠시 재워두고(suspend)** 완전히 새 커넥션으로 갈아끼운다.
그래서 두 트랜잭션은 서로에게 남남이다 — 자식이 롤백해도 부모는 멀쩡하고,
부모가 롤백해도 이미 커밋된 자식은 살아남는다.
대신 부모가 아직 커밋하지 않은 데이터를 **자식은 볼 수 없다**.
"방금 저장했는데 왜 안 보이지?"의 정체가 이것이다.

### 자세히

**시나리오 1 — 물리 트랜잭션이 2개라는 증거** `RequiresNewPropagationTest.kt:47`

`requiredCallingRequiresNew()`(`OuterService.kt:98`) → `innerService.requiresNew()`(`InnerService.kt:62`).

- `sharesPhysicalTransactionWith` 가 **false**(`:53`) → EntityManager 자체가 다르다
- `inner.transactionName` 이 `requiresNew`(`:57`) → 자식이 **스스로** 물리 트랜잭션을 시작했다

시나리오 1의 이 두 줄이 REQUIRED 와 갈리는 모든 차이의 뿌리다.

중단(suspend)의 실체는 이렇다. `JpaTransactionManager` 가 ThreadLocal 에 바인딩돼 있던
`EntityManagerHolder` 를 떼어내 `SuspendedResourcesHolder` 에 보관하고, 새 EntityManager 를 바인딩한다.
자식이 끝나면 보관해 둔 것을 원래 자리에 되돌린다(→ 스펙 4에서 복원을 직접 검증한다).

**시나리오 2 — 자식 실패 + 부모 catch** `:64`

`requiredCallingRequiresNewAndCatch()`(`OuterService.kt:109`).
**시나리오 1-3과 완전히 같은 코드 모양인데 결과가 정반대다.**

| | REQUIRED | REQUIRES_NEW |
|---|---|---|
| 예외를 잡으면 | `UnexpectedRollbackException`, 전부 롤백 | 자식만 롤백, 부모 커밋 |
| 이유 | 롤백 대상이 물리적으로 하나 | 롤백 대상이 물리적으로 분리 |

검증은 세 건을 나눠 센다(`:70~72`) — `INNER` 0건, `OUTER` 1건, `OUTER_AFTER_CATCH` 1건.
**예외를 잡고 복구 작업까지 이어갈 수 있다**는 것이 REQUIRES_NEW 의 실용적 가치다.

**시나리오 3 — 자식 커밋 후 부모 실패** `:76`

`requiredCallingRequiresNewThenOuterFails()`(`OuterService.kt:125`).
자식의 트랜잭션은 **자식 메서드가 끝나는 순간** 커밋된다. 그 뒤 부모가 실패해도 손댈 수 없다.
결과는 `OUTER` 0건 / `INNER` 1건(`:85~86`).

이것이 "실패해도 반드시 남아야 하는 감사 로그" 패턴이다.
다만 뒤집어 말하면 **원자성이 깨졌다**는 뜻이기도 하다(→ 스펙 4의 부분 커밋 시나리오).

**시나리오 4 — 부모의 미커밋 데이터가 자식에게 안 보인다** `:90`

`requiredCallingRequiresNewThatCountsRows()`(`OuterService.kt:136`)는
저장 후 `entityManager.flush()` 로 INSERT 문을 **DB 까지 보낸 뒤**(커밋은 안 함) 자식을 부른다.
자식은 별도 커넥션이고 H2 기본 격리 수준이 READ_COMMITTED 이므로 그 행을 볼 수 없다 → **0건**(`:99`).
부모 트랜잭션이 커밋된 뒤 세면 당연히 1건이다(`:102`).

실무 함정: 부모가 방금 만든 엔티티를 자식에게 인자로 넘기지 않고 "id 로 다시 조회"하게 만들면
여기서 조용히 NotFound 가 난다. 더 나쁜 경우가 스펙 5의 자기 교착이다.

---

## 3. `propagation/NestedPropagationTest` — 세이브포인트와 JPA 의 한계 (4개)

### 한눈에

NESTED 는 REQUIRED 와 REQUIRES_NEW 의 중간이다.
커넥션은 부모와 **하나만** 쓰면서, 자식 진입 시점에 세이브포인트를 찍어 두었다가
자식이 실패하면 **거기까지만** 되돌린다.
그런데 JPA(Hibernate)에서는 쓸 수 없다 — 관문이 두 개인데 하나는 열어도 다른 하나에서 막힌다.
이 스펙은 전반부에서 "왜 안 되는지"를, 후반부에서 JDBC 매니저로 "되면 어떤 모습인지"를 보여 준다.

### 자세히

세 전파 속성의 위치 관계:

| | 물리 트랜잭션 | 자식 실패 시 | 부모 실패 시 |
|---|---|---|---|
| REQUIRED | 공유(1개) | 부모까지 강제 롤백 | 자식도 롤백 |
| NESTED | 공유(1개) | **세이브포인트까지만** | 자식도 롤백 |
| REQUIRES_NEW | 분리(2개) | 자식만 롤백 | 자식은 살아남음 |

**시나리오 1 — JPA 에서 NESTED 는 실패한다** `NestedPropagationTest.kt:53`

`outerService.requiredCallingNested()`(`OuterService.kt:147`) → `innerService.nested()`(`InnerService.kt:85`).

관문 1은 이 프로젝트가 이미 열어 두었다 — `TransactionConfig.kt:46` 에서
`isNestedTransactionAllowed = true`. 열지 않았다면
`"Transaction manager does not allow nested transactions by default"` 였을 것이다.

그런데도 `NestedTransactionNotSupportedException` 이 터지고, 메시지는
`"JpaDialect does not support savepoints"` 다(`:64`).
`JpaTransactionManager` 는 `JpaDialect.beginTransaction()` 이 돌려준 객체가
`SavepointManager` 를 구현했을 때만 세이브포인트를 만들 수 있는데,
`HibernateJpaDialect` 가 돌려주는 객체는 그렇지 않기 때문이다(관문 2).

자식 진입 자체가 실패했으므로 바깥 트랜잭션도 통째로 롤백된다 → `count() == 0`(`:67`).

**후반부 — JDBC 트랜잭션 매니저에서는 정상 동작한다**

`SavepointService.kt:31` 은 `DataSourceTransactionManager` 를 **빈으로 등록하지 않고**
서비스 안에서 직접 만들어 쓴다. 빈으로 올리면 `@ConditionalOnMissingBean(TransactionManager)`
때문에 Spring Boot 가 JPA 트랜잭션 매니저를 아예 만들지 않기 때문이다(`SavepointService.kt:21~24`).
`DataSourceTransactionManager` 는 생성자에서 이미 `nestedTransactionAllowed = true` 다.

**시나리오 2 — NESTED 자식 실패 + catch** `:72`

`outerCommitsWhenNestedFails()`(`SavepointService.kt:42`).
같은 상황을 REQUIRED 로 했을 때(스펙 1-3)는 전부 롤백됐지만, 여기서는
세이브포인트까지만 되돌아간다 → `INNER` 0건, `OUTER` 1건, `AFTER_CATCH` 1건(`:80~82`).
**rollback-only 오염이 없다**는 것이 결정적 차이다.

**시나리오 3 — 자식 성공 후 부모 실패** `:86`

`everythingRollsBackWhenOuterFails()`(`SavepointService.kt:61`).
NESTED 는 부모 트랜잭션의 *일부*이므로 부모와 운명을 같이한다 → 전부 롤백(`:94`).
**REQUIRES_NEW 와 갈리는 지점이다** (스펙 2-3에서는 자식이 살아남았다).

**시나리오 4 — 세이브포인트 2겹** `:98`

`onlyInnermostSavepointRollsBack()`(`SavepointService.kt:70`).
세이브포인트는 스택처럼 쌓인다. 가장 안쪽만 실패시키면
`OUTER` 1건 / `LEVEL1` 1건 / `LEVEL2` 0건(`:102~104`).

> **실무 결론: JPA 를 쓰면서 부분 롤백이 필요하면 REQUIRES_NEW 로 간다.**
> NESTED 자체가 쓸모없는 게 아니라, JPA 와 조합할 수 없는 것이다.

---

## 4. `propagation/MixedPropagationChainTest` — 3계층 이상의 체인 (7개)

### 한눈에

계층이 깊어지면 헷갈리는 지점은 딱 하나다.

> **전파 속성은 "누가 나를 불렀는가"가 아니라
> "지금 스레드에 어떤 물리 트랜잭션이 바인딩돼 있는가"로 결정된다.**

중간 계층이 REQUIRES_NEW 로 트랜잭션을 갈아끼우면, 그 아래 REQUIRED 자식은
최초의 부모가 아니라 **중간 계층에 참여**한다. 나머지 결과는 전부 여기서 따라 나온다.

### 자세히

**시나리오 1 — 손자는 누구에게 참여하는가** `MixedPropagationChainTest.kt:52`

`requiredCallingRequiresNewCallingRequired()`(`OuterService.kt:251`)
→ `middleService.requiresNewCallingRequired()`(`MiddleService.kt:31`)
→ `innerService.required()`(`InnerService.kt:42`)

스냅샷 세 개를 구조 분해로 받아 비교한다(`:55`).

- `middle` vs `outer` → false (`:58`): 중간이 새 물리 트랜잭션을 열었다
- `inner` vs `middle` → **true** (`:62`): 손자는 중간에 참여했다
- `inner` vs `outer` → false (`:63`): 조부모와는 아무 관계가 없다

**물리 트랜잭션 2개(조부모 / 중간+손자), 논리 트랜잭션 3개.**

**시나리오 2 — rollback-only 오염의 범위** `:70`

`requiredCallingMiddleThatSwallowsGrandChildFailure()`(`OuterService.kt:267`)
→ `middleService.requiresNewCallingRequiredAndCatch()`(`MiddleService.kt:45`).

손자(REQUIRED)가 실패하고 **중간이 그 예외를 삼킨다.**
오염되는 것은 조부모의 트랜잭션이 아니라 **중간의 트랜잭션**이다.
따라서 `UnexpectedRollbackException` 은 중간 메서드가 끝나는 시점(중간 트랜잭션의 커밋 시점)에 터지고,
조부모는 그것을 잡을 수 있다(`OuterService.kt:272`).

결과: `MIDDLE` 0건, `INNER` 0건, `OUTER` **1건**(`:80~82`).

> **rollback-only 오염은 물리 트랜잭션 경계를 넘지 못한다.**

스펙 1-3과 나란히 놓고 보면 이해가 빠르다. 거기서는 오염이 최상위까지 번졌는데,
여기서는 중간에서 멈춘다. 차이는 "중간이 REQUIRES_NEW 인가"뿐이다.

**시나리오 3 — NOT_SUPPORTED 가 중간에 끼면** `:87`

`requiredCallingNotSupportedCallingRequired()`(`OuterService.kt:286`)
→ `middleService.notSupportedCallingRequired()`(`MiddleService.kt:59`).

중간에서 부모가 중단되어 `middle.actualTransactionActive == false`,
`middle.entityManagerId == null`(`:93~94`).
손자는 참여할 대상이 없으므로 **스스로 새 물리 트랜잭션을 연다**(`:97`) — 조부모와는 무관하다.
물리 트랜잭션 2개가 서로를 전혀 모르는 상태가 된다.

**시나리오 4 — REQUIRES_NEW 두 겹** `:105`

`requiredCallingTwoNestedRequiresNew()`(`OuterService.kt:294`).
스냅샷 3개의 `entityManagerId` 를 `distinct()` 해서 3개가 나오는지 본다(`:112`).
**물리 트랜잭션 3개** = 커넥션 3개를 동시에 점유한다는 뜻이기도 하다(커넥션 풀 관점의 비용).

**시나리오 5 — 중단과 복원** `:119`

`snapshotsAroundSuspendAndResume()`(`OuterService.kt:306`)는
자식 호출 **전 / 자식 / 후** 세 스냅샷을 찍는다.

- `inner` vs `before` → false: 자식은 다른 트랜잭션
- `after` vs `before` → **true**(`:127`), 이름도 동일(`:128`)

suspend 는 ThreadLocal 리소스를 떼어 보관하는 것이고 resume 은 되돌려 놓는 것이라는 사실이
identity 로 확인된다.

**시나리오 6 — 부분 커밋 (원자성 붕괴)** `:133`

`requiredCallingSeveralRequiresNewThenFails()`(`OuterService.kt:321`)는
`middleService.requiresNewSaving()`(`MiddleService.kt:75`)을 두 번 부른 뒤 실패한다.

결과: `STEP-1` 1건, `STEP-2` 1건, `OUTER` 0건(`:142~144`).
**"절반만 처리된" 상태**가 그대로 남는다.

REQUIRES_NEW 를 남용하면 트랜잭션이 공짜로 주던 원자성이 사라지고,
실패 시 되돌리는 **보상 트랜잭션(compensating transaction)** 을 직접 짜야 한다.
스펙 2-3의 "감사 로그 패턴"과 같은 동작인데, 상황에 따라 장점이 되기도 재앙이 되기도 한다.

**시나리오 7 — 형제 호출의 운명이 갈린다** `:149`

`requiredCallingRequiresNewThenFailingRequired()`(`OuterService.kt:336`)는
한 메서드 안에서 REQUIRES_NEW(성공) → REQUIRED(실패) 순으로 부른다.

결과: `SIBLING_REQUIRES_NEW` 1건 살아남고, `OUTER` 와 `INNER` 는 0건(`:158~160`).
같은 메서드 안의 두 자식이 서로 다른 결말을 맞는다.
**어떤 자식이 어떤 물리 트랜잭션에 속하는지 알아야만 결과를 예측할 수 있다.**

---

## 5. `propagation/RequiresNewSelfDeadlockTest` — 자기 자신과의 교착 (2개)

### 한눈에

REQUIRES_NEW 는 커넥션이 다르다. 그래서 부모가 잠근 행을 자식이 건드리면
**같은 스레드 위인데도** 서로를 기다린다.
부모의 락은 부모가 커밋해야 풀리는데, 부모는 자식이 끝나야 커밋한다 — 영원히 안 풀린다.
스레드가 하나뿐이라 어느 쪽도 양보할 수 없고, 실제로는 락 타임아웃으로 끝난다.

```
부모 트랜잭션(커넥션 A): 계좌 행 UPDATE -> 행 잠금 획득, 커밋 전
  └─ 자식 REQUIRES_NEW(커넥션 B): 같은 행 UPDATE 시도
         -> A 가 커밋할 때까지 대기
         -> 그런데 A 는 B 가 끝나야 진행된다
```

### 자세히

**시나리오 1 — 같은 행을 건드리면 타임아웃** `RequiresNewSelfDeadlockTest.kt:51`

`lockRowThenCallRequiresNew()`(`SelfDeadlockService.kt:38`)의 흐름:

1. 부모(REQUIRED)가 계좌를 조회하고 `deposit(100)`
2. `entityManager.flush()`(`SelfDeadlockService.kt:42`) → **UPDATE 문 전송 = 행 잠금 획득**, 커밋은 아직
3. `self.getObject().requiresNewUpdatingSameRow()` → 프록시를 거쳐야 REQUIRES_NEW 가 실제로 적용된다
   (`this.` 로 부르면 스펙 8의 자기 호출 함정에 걸려 아무 일도 안 일어난다)
4. 자식이 같은 행에 `flush()`(`SelfDeadlockService.kt:65`) → 여기서 멈춘다
5. `LockTimeoutException`, 부모까지 전체 롤백 → 잔액은 원래 값 1,000 그대로(`:65`)

테스트가 몇 초씩 멈추지 않도록 `shortenLockTimeout()`(`SelfDeadlockService.kt:75`)이
**자식 트랜잭션의 커넥션에만** `SET LOCK_TIMEOUT 300` 을 건다.
`SET LOCK_TIMEOUT` 은 H2 의 세션(커넥션) 단위 설정인데, `JpaTransactionManager` 는 DataSource 를
들고 있지 않아 `JdbcTemplate` 을 쓰면 풀에서 **다른 커넥션**을 꺼내온다.
그래서 `entityManager.unwrap(Session::class.java).doWork { ... }` 로 Hibernate 세션의
실제 커넥션을 직접 잡아야 한다.

잡히는 예외가 Spring 의 `DataAccessException` 계열이 아니라 JPA 표준 `LockTimeoutException` 인 이유도
테스트에 적혀 있다(`:57~59`). 예외 변환(`PersistenceExceptionTranslator`)은 **리포지토리 프록시를 지날 때**
일어나는데, 여기서는 서비스 코드의 `entityManager.flush()` 가 직접 던지기 때문이다.

**시나리오 2 — 다른 행이면 아무 문제 없다 (비교군)** `:69`

`lockRowThenCallRequiresNewOnAnotherRow()`(`SelfDeadlockService.kt:52`).
둘 다 정상 커밋되어 1,100 / 501 이 된다(`:78~79`).

> **REQUIRES_NEW 자체가 위험한 게 아니라, "같은 자원을 두 트랜잭션이 잡는 것"이 문제다.**

흔한 발생 경로: "감사 로그는 실패해도 남아야 하니까" REQUIRES_NEW 를 붙였는데,
그 로그 작업이 본 테이블을 함께 건드리는 경우.
운영에서 락 타임아웃이 길게 잡혀 있으면 요청 스레드와 커넥션이 계속 묶이면서
**커넥션 풀이 통째로 마르는 장애**로 번진다.

---

## 6. `propagation/OtherPropagationTest` — MANDATORY / NEVER / SUPPORTS / NOT_SUPPORTED (8개)

### 한눈에

나머지 네 개는 "새 트랜잭션을 만드는 방법"이 아니라 **호출 문맥에 대한 제약 혹은 회피**다.
MANDATORY 와 NEVER 는 "이 상태가 아니면 터뜨려라"는 방어 장치이고,
SUPPORTS 와 NOT_SUPPORTED 는 "없으면 없는 대로 하겠다"는 태도다.
후자 둘은 조심해야 한다 — **"트랜잭션 없이 실행"은 곧 자동 커밋**이라 되돌릴 방법이 없다.

| 전파 속성 | 부모 있음 | 부모 없음 |
|---|---|---|
| MANDATORY | 참여 | `IllegalTransactionStateException` |
| NEVER | `IllegalTransactionStateException` | 트랜잭션 없이 실행 |
| SUPPORTS | 참여 | 트랜잭션 없이 실행 |
| NOT_SUPPORTED | 부모 중단 후 없이 실행 | 트랜잭션 없이 실행 |

### 자세히

**MANDATORY** `OtherPropagationTest.kt:45`

- 부모 안에서 호출(`requiredCallingMandatory()`, `OuterService.kt:186`):
  새 트랜잭션을 만들지 않고 참여한다. `sharesPhysicalTransactionWith` true,
  트랜잭션 이름도 부모의 것 그대로(`:50~52`).
- 트랜잭션 없이 호출(`noneCallingMandatory()`, `OuterService.kt:192`):
  `IllegalTransactionStateException`, 저장도 안 됨(`:62~66`).

용도: "이 메서드는 절대 단독 호출되면 안 된다"를 런타임에 강제한다.
조용히 자동 커밋되는 것보다 즉시 터지는 편이 안전한 경우에 쓴다.

**NEVER** `:71`

- 트랜잭션 없이 호출: 정상 실행, `actualTransactionActive == false`(`:76`)
- 부모 안에서 호출: `IllegalTransactionStateException`(`:85`)

용도: 트랜잭션을 오래 붙잡으면 안 되는 작업(외부 API 호출 등)에 방어적으로 건다.

**SUPPORTS** `:94`

- 부모 안: 참여한다(`:99`)
- 트랜잭션 없이: `actualTransactionActive == false` **그리고 `entityManagerId == null`**(`:110~111`)

두 번째 단언이 중요하다. SUPPORTS 는 트랜잭션 동기화 자체는 켜지지만
**실제 물리 트랜잭션은 없다.** 이 상태의 `save()` 는 자동 커밋이라 롤백 대상이 되지 않는다.
"조회 전용이니까 SUPPORTS" 라고 붙여 놓고 그 안에서 쓰기를 하면 사고가 난다.

**NOT_SUPPORTED** `:118`

- 부모 안에서 호출(`requiredCallingNotSupported()`, `OuterService.kt:209`):
  부모는 살아 있지만(`outer.actualTransactionActive == true`)
  자식 쪽에서는 트랜잭션이 안 보인다(`inner.actualTransactionActive == false`,
  `inner.entityManagerId == null`, `:123~126`).
  부모의 EntityManager 는 잠시 떼어내 보관된 상태다.
- 자식 저장 후 부모 실패(`requiredCallingNotSupportedThenOuterFails()`, `OuterService.kt:220`):
  **자식 데이터만 살아남는다** — `OUTER` 0건, `INNER` 1건(`:140~141`).

핵심 검증이 마지막 시나리오다. NOT_SUPPORTED 안의 쓰기는 **롤백할 방법이 없다.**
"읽기 전용 집계"처럼 쓰기가 없는 작업에만 쓰는 것이 안전하다.

---

## 7. `rollback/RollbackPolicyTest` — 무엇이 롤백을 유발하는가 (6개)

### 한눈에

Spring 의 기본 규칙은 놀랄 만큼 단순하다.

```
ex is RuntimeException || ex is Error  ->  롤백
그 외 (체크 예외)                       ->  커밋
```

즉 **예외가 터졌다고 무조건 롤백되지 않는다.**
`Exception` 을 상속한 예외를 던지면 예외는 밖으로 나가는데 트랜잭션은 커밋된다.
Kotlin 에는 체크 예외 개념이 없어 컴파일러가 아무 경고도 하지 않으므로 특히 위험하다.

### 자세히

이 규칙은 EJB 시절 관례를 이어받은 것으로,
"체크 예외 = 호출자가 복구할 수 있는 비즈니스 상황이므로 작업을 확정한다"는 발상이다.
근거 코드는 `DefaultTransactionAttribute.rollbackOn` 이다.

**기본 규칙 3종** `RollbackPolicyTest.kt:45`

| 던진 예외 | 서비스 메서드 | 결과 |
|---|---|---|
| `InnerFailureException`(RuntimeException) | `RollbackPolicyService.kt:24` | 롤백, `count() == 0`(`:52`) |
| `BusinessCheckedException`(Exception) | `RollbackPolicyService.kt:31` | **커밋**, `count() == 1`(`:64`) |
| `StackOverflowError`(Error) | `RollbackPolicyService.kt:52` | 롤백, `count() == 0`(`:74`) |

가운데 줄이 이 스펙의 존재 이유다. `shouldThrow<BusinessCheckedException>` 으로
**예외는 정상적으로 전파되는데**(`:60`) 데이터는 남아 있다(`:64`).
"예외가 터졌으니 당연히 롤백됐겠지"라는 가정이 깨지는 지점이다.

Kotlin 에서 위험한 이유: `throws` 를 선언할 필요도, 호출부에서 잡을 의무도 없다.
그래서 `class MyException : Exception()` 을 무심코 만들어 던지면
**컴파일러는 조용한데 트랜잭션은 커밋된다.**
대응은 둘 중 하나다 — 도메인 예외의 부모를 `RuntimeException` 으로 두거나,
`@Transactional(rollbackFor = [Exception::class])` 을 명시한다.

**규칙을 직접 지정했을 때** `:79`

- `rollbackFor = [BusinessCheckedException::class]`(`RollbackPolicyService.kt:38`)
  → 체크 예외도 롤백된다(`:86`). **규칙 확장.**
- `noRollbackFor = [IllegalStateException::class]`(`RollbackPolicyService.kt:45`)
  → 언체크 예외인데도 커밋된다(`:97`). **규칙 축소.**

`noRollbackFor` 의 용도: "재고 부족" 처럼 예외로 흐름은 끊되
그때까지의 기록(시도 이력 등)은 남겨야 하는 경우.

**예외 없이 롤백하기** `:102`

`saveThenMarkRollbackOnly()`(`RollbackPolicyService.kt:62`)는
`TransactionAspectSupport.currentTransactionStatus().setRollbackOnly()` 를 호출한다.
예외 없이 정상 반환하지만(`:110`) 커밋되지 않는다(`:111`).

반환값으로 실패를 표현하는 API(`Result` 타입 등)에서 쓰는 방법이다.
단, **이 메서드가 다른 트랜잭션에 참여 중이었다면 부모까지 오염시켜
`UnexpectedRollbackException` 을 유발한다.** 스펙 1-3과 완전히 같은 메커니즘이다 —
rollback-only 플래그를 찍는 주체가 예외냐 코드냐만 다르다.

---

## 8. `proxy/SelfInvocationTest` — `@Transactional` 이 조용히 무시되는 경우 (5개)

### 한눈에

`@Transactional` 은 마법이 아니라 **프록시**다.
스프링이 주입해 주는 것은 원본 빈이 아니라 그것을 상속해 감싼 CGLIB 프록시이고,
트랜잭션은 "호출이 프록시를 통과할 때" 시작된다.

```
호출자 ──► [프록시: 트랜잭션 시작] ──► 원본 빈.method()
                                       └─ this.other()  ◄── 프록시를 거치지 않는다!
```

그래서 같은 클래스 안에서 `this.other()` 로 부르면 `@Transactional` 이 **아무 일도 하지 않는다.**
예외도 경고도 없이 그냥 무시된다.

### 자세히

**시나리오 1 — 주입받은 빈의 정체** `SelfInvocationTest.kt:52`

트랜잭션이 프록시에서만 걸린다는 사실의 *물리적 근거*를 먼저 확인한다.

- `AopUtils.isAopProxy` / `isCglibProxy` 둘 다 true(`:56~57`)
- `javaClass != SelfInvocationService::class.java`(`:60`) — 프록시는 원본을 상속한 별도 클래스
- `AopProxyUtils.ultimateTargetClass` 로 원본을 되찾을 수 있다(`:61`)

**시나리오 2·3 — 자기 호출** `:66`

`snapshotViaInternalCall()`(`SelfInvocationService.kt:52`)은 `this.transactionalSnapshot()` 을 부른다.
결과: `actualTransactionActive == false`(`:71`). **`@Transactional` 이 붙어 있는데 트랜잭션이 없다.**

`callInternally()`(`SelfInvocationService.kt:33`)는 저장 후 예외를 던지는데도
`count() == 1`(`:82`) — 트랜잭션이 없으니 자동 커밋되어 롤백할 대상이 없다.

이것이 운영에서 드러나는 형태다. **에러 로그는 정상적으로 찍히는데 데이터만 남아 있다.**
`@Transactional` 이 코드에 분명히 보이기 때문에 원인을 의심하기까지 오래 걸린다.

**시나리오 4·5 — 프록시를 거치면** `:87`

`ObjectProvider<SelfInvocationService>` 로 자기 자신을 지연 조회해
`self.getObject().transactionalWork()` 로 부른다(`SelfInvocationService.kt:41`).
생성자에서 자기 타입을 바로 주입하면 순환 참조가 되므로 `ObjectProvider` 를 쓴다.

이제 정상 동작한다 — `actualTransactionActive == true`,
트랜잭션 이름은 `transactionalSnapshot`(`:92~93`), 예외 시 롤백되어 `count() == 0`(`:103`).

**같은 이유로 걸리는 다른 함정들**

- `private` 메서드의 `@Transactional`: CGLIB 는 서브클래싱으로 동작하므로 오버라이드할 수 없다 → 무시
- `final` 메서드/클래스: 마찬가지. Kotlin 은 기본이 final 이라 `kotlin-spring` 플러그인이
  자동으로 `open` 처리해 준다. **이 플러그인이 없으면 `@Transactional` 이 통째로 먹통이 된다**
  (`build.gradle.kts:3` 의 `kotlin("plugin.spring")`).

**해결책 우선순위**

1. 트랜잭션 경계를 다른 빈으로 분리한다 (가장 권장)
2. 자기 자신을 프록시로 주입받아 호출한다 (`ObjectProvider` 로 순환 참조 회피)
3. `AopContext.currentProxy()` (`@EnableAspectJAutoProxy(exposeProxy = true)` 필요)

---

## 9. `event/TransactionalEventTest` — 커밋된 뒤에만 부수효과 실행하기 (6개)

### 한눈에

주문을 저장하고 이메일을 보내는 코드를 생각해 보자.
저장 직후에 메일을 보내면, 뒤이어 트랜잭션이 롤백됐을 때
**DB 에는 없는 주문에 대한 메일**이 이미 나가 버린다.
`@TransactionalEventListener` 는 이벤트 처리를 트랜잭션 동기화에 등록해
커밋/롤백이 확정된 뒤로 미룬다. 롤백되면 아예 실행되지 않는다.

| 리스너 | 실행 시점 |
|---|---|
| `@EventListener` | `publishEvent` 즉시 (같은 트랜잭션) |
| `BEFORE_COMMIT` | 커밋 직전 (여기서 던진 예외는 아직 롤백을 유발할 수 있다) |
| `AFTER_COMMIT` | 커밋 직후 |
| `AFTER_ROLLBACK` | 롤백 직후 |
| `AFTER_COMPLETION` | 커밋/롤백 무관 마지막 |

### 자세히

`OrderEventRecorder`(`OrderEventRecorder.kt:38~66`)의 리스너 6개가 실행될 때마다
자기 이름을 `phases` 리스트에 넣는다. 여러 스레드에서 접근할 수 있으므로 `CopyOnWriteArrayList` 다.
`beforeTest` 에서 DB 정리와 함께 `orderEventRecorder.reset()` 도 호출한다(`TransactionalEventTest.kt:51~54`).

**시나리오 1 — 커밋될 때의 순서** `:56`

`orderService.placeOrder("A-1")`(`OrderService.kt:21`).

- `phases.first() == "IMMEDIATE"`(`:67`) — `@EventListener` 는 `publishEvent` 시점에 곧바로,
  즉 커밋되기 한참 전에 실행된다.
- `BEFORE_COMMIT`, `AFTER_COMMIT`, `AFTER_COMPLETION` 이 모두 포함(`:69~73`)
- `AFTER_ROLLBACK` 은 없음(`:75`)
- `shouldContainInOrder(BEFORE_COMMIT, AFTER_COMMIT)`(`:78`) — **단계 사이의 순서는 보장된다**

`first()` 로 단정해도 안전한 이유가 테스트에 주석으로 적혀 있다(`:61~66`).
나머지 리스너가 전부 `@TransactionalEventListener` 라서 커밋 시점으로 미뤄지므로,
`publishEvent` 안에서 실행될 수 있는 리스너가 이것 하나뿐이기 때문이다.

**단, 같은 phase 안의 리스너끼리는 순서가 보장되지 않는다.** 순서가 중요하면 `@Order` 를 명시해야 한다.

**시나리오 2 — AFTER_COMMIT 에서 DB 쓰기** `:84`

`writeAuditAfterCommit()`(`OrderEventRecorder.kt:57`)에는
`@TransactionalEventListener(AFTER_COMMIT)` 과 `@Transactional(REQUIRES_NEW)` 이 함께 붙어 있다.

AFTER_COMMIT 시점에는 **원래 트랜잭션이 이미 끝나서 커밋할 대상이 없다.**
REQUIRES_NEW 로 새 트랜잭션을 열어야만 이 저장이 실제로 커밋된다 → `AUDIT:A-2` 1건(`:89`).
REQUIRES_NEW 없이 저장하면 조용히 사라진다.

**시나리오 3 — 롤백될 때** `:93`

`placeOrder("B-1", fail = true)` → `OuterFailureException`.

- `AFTER_COMMIT`, `AFTER_COMMIT_AUDIT` **미실행**(`:103~104`)
- `AFTER_ROLLBACK`, `AFTER_COMPLETION` 실행(`:105~106`)
- `BEFORE_COMMIT` **미실행**(`:109`) — 커밋 시도 자체를 하지 않았으므로
- DB 는 `count() == 0`(`:111`)

**핵심 가치**: 롤백된 주문에 메일이 나가는 사고를 구조적으로 막아 준다.

**주의**: `IMMEDIATE` 는 이미 실행되어 있다(`:101`).
`@EventListener` 는 트랜잭션과 무관하게 발행 즉시 동기 실행되므로, 여기서 메일을 보냈다면 이미 늦었다.

**시나리오 4 — 트랜잭션 없이 발행** `:116`

`placeOrderWithoutTransaction("C-1")`(`OrderService.kt:29`).
실행된 것은 `IMMEDIATE` 와 `AFTER_COMMIT_FALLBACK` 둘뿐이다(`:148~151`).
`fallbackExecution = true`(`OrderEventRecorder.kt:65`)가 없는 리스너들은 **조용히 무시된다.**

> 실무에서 "왜 리스너가 안 타지?"의 **1순위 원인**이다.
> 발행하는 메서드에 `@Transactional` 이 빠져 있으면 아무 일도 일어나지 않는다. 예외도 로그도 없다.

이 시나리오는 `shouldContainExactlyInAnyOrder` 로 **순서를 단언하지 않는다.**
이유가 테스트에 길게 주석으로 남아 있다(`:123~147`) — 요약하면:

- 트랜잭션이 없으므로 두 리스너가 같은 `publishEvent` 안에서 동기 실행된다
- 둘 사이 순서는 멀티캐스터의 리스너 목록 순서로 정해지는데,
  둘 다 `@Order` 가 없어 동률 → 안정 정렬이라 등록 순서 유지 →
  `EventListenerMethodProcessor` → `MethodIntrospector.selectMethods` → `Class.getDeclaredMethods()`
- **`getDeclaredMethods()` 의 반환 순서는 JVM 명세가 보장하지 않는다**
- 실제로 같은 JVM 실행 안에서 두 순서가 모두 관측되었다

순서를 고정하고 싶다면 테스트가 아니라 **리스너 쪽에 `@Order` 를 명시**해야 한다.
(이것이 CLAUDE.md 설계 규칙 7 — "순서가 보장되지 않는 동작은 복수 정답으로 단언한다"의 실제 사례다.)

**시나리오 5·6 — 저수준 메커니즘** `:156`

`placeOrderWithManualSynchronization()`(`OrderService.kt:37`)은
`TransactionSynchronizationManager.registerSynchronization()` 으로 콜백을 직접 등록한다.
`@TransactionalEventListener` 도 내부적으로는 이 메커니즘 위에 얹혀 있다.

- 커밋 시: `beforeCommit` → `afterCommit` → `afterCompletion(COMMITTED)`(`:164~168`)
- 롤백 시: `afterCompletion(ROLLED_BACK)` **하나만**(`:180`)

`shouldContainExactly` 로 순서까지 못 박을 수 있는 이유는, 여기서는 콜백이 하나뿐이라
"같은 phase 안의 순서 미보장" 문제가 애초에 생기지 않기 때문이다.

---

## 10. `persistence/PersistenceContextAndReadOnlyTest` — 영속성 컨텍스트와 readOnly (8개)

### 한눈에

JPA 에서 트랜잭션 이야기의 절반은 사실 **영속성 컨텍스트** 이야기다.
`JpaTransactionManager` 는 물리 트랜잭션을 시작할 때 EntityManager 를 하나 만들어
스레드에 바인딩하고 커밋/롤백 시 닫는다. 그래서 **트랜잭션 경계 == 영속성 컨텍스트 경계**다.
그리고 `readOnly = true` 는 흔한 오해와 달리 **쓰기 금지가 아니다.**

### 자세히

**시나리오 1 — 1차 캐시** `PersistenceContextAndReadOnlyTest.kt:44`

`readTwiceInSameTransaction()`(`PersistenceContextService.kt:33`)에서
같은 PK 를 두 번 조회하면 `first === second`(**동일 인스턴스**, `:51`).
두 번째 조회는 DB 로 나가지 않는다.

**시나리오 2 — 사이에 `clear()`** `:56`

`readTwiceWithClearInBetween()`(`PersistenceContextService.kt:49`).
`sameInstance == false`, `containedInPersistenceContext == false`(`:64~66`) —
`clear()` 로 준영속 상태가 된 첫 인스턴스는 더 이상 관리되지 않는다.
값 자체는 같다(`:67`).

**이 시나리오가 스펙 11의 전제 조건이다.** `clear()` 없이는 다른 트랜잭션이 커밋한 변경을
영원히 볼 수 없으므로, 격리 수준 실험 자체가 성립하지 않는다.

**시나리오 3 — REQUIRES_NEW 는 1차 캐시도 공유하지 않는다** `:71`

`readInOuterAndRequiresNew()`(`PersistenceContextService.kt:78`)는
`self.getObject().readInRequiresNew()` 로 프록시를 거쳐 호출한다
(자기 호출이면 REQUIRES_NEW 가 무시된다 — 스펙 8).
EntityManager 가 다르므로 다른 인스턴스가 나온다(`:75`).

**시나리오 4 — IDENTITY 전략의 쓰기 지연** `:80`

`idAssignedBeforeFlush()`(`PersistenceContextService.kt:68`)는
`save()` 직후 flush 전에 이미 PK 가 채워져 있음을 확인한다(`:86`).

IDENTITY 는 DB 가 채번한 PK 를 알아야 영속성 컨텍스트에 등록할 수 있으므로
**쓰기 지연(write-behind)이 동작하지 않고 `save()` 즉시 INSERT 가 나간다.**
이 특성이 아래 readOnly 시나리오와 스펙 6의 NOT_SUPPORTED 결과를 좌우한다.

**시나리오 5~8 — readOnly = true 의 실체** `:91`

| 시나리오 | 결과 | 근거 |
|---|---|---|
| 트랜잭션 상태 확인 | 물리 트랜잭션은 열려 있고 readOnly 플래그만 켜짐(`:96~97`) | `ReadOnlyService.kt:25` |
| 엔티티 필드 수정 | UPDATE 가 **안 나감** — 값이 `BEFORE` 그대로(`:107`) | `ReadOnlyService.kt:29` |
| 같은 수정을 쓰기 트랜잭션에서 (비교군) | 변경 감지 동작, `AFTER` 로 바뀜(`:117`) | `ReadOnlyService.kt:36` |
| `save()` 로 INSERT | **그대로 커밋된다**(`:127`) | `ReadOnlyService.kt:46` |

readOnly 가 실제로 하는 일은 Hibernate 세션의 **FlushMode 를 `MANUAL` 로 바꾸는 것**이다.
그 결과 변경 감지(dirty checking)로 인한 UPDATE 가 나가지 않고, 스냅샷 보관을 생략해 메모리도 아낀다.

하지만 마지막 줄이 결정적이다. IDENTITY 전략의 `save()` 는 flush 와 무관하게 즉시 INSERT 를 실행하므로
readOnly 트랜잭션 안에서도 **그대로 커밋된다.**

> **readOnly 는 "의도 표현 + 최적화 힌트"이지 안전장치가 아니다.**
> "readOnly 붙였으니 실수로 쓰기가 나갈 일은 없겠지"라고 믿으면 안 된다.

---

## 11. `isolation/IsolationLevelTest` — 격리 수준 (6개)

### 한눈에

격리 수준은 성능과 정합성 사이의 다이얼이다. 낮출수록 빠르지만 이상 현상이 허용된다.

| 격리 수준 | Dirty Read | Non-Repeatable Read | Phantom Read |
|---|---|---|---|
| READ_UNCOMMITTED | O | O | O |
| READ_COMMITTED | X | O | O |
| REPEATABLE_READ | X | X | (구현별) |
| SERIALIZABLE | X | X | X |

그런데 JPA 로 이걸 테스트하려면 **먼저 1차 캐시를 걷어내야 한다.**
안 그러면 두 번째 조회가 DB 로 가지 않아서 격리 수준이 무엇이든 항상 같은 값이 보인다.
이 스펙의 첫 시나리오가 바로 그 함정을 보여 주는 것이다.

### 자세히

**실행 방식**: 두 개의 동시 트랜잭션이 필요하므로 스레드를 두 개 쓴다.
트랜잭션은 스레드에 바인딩되기 때문이다.
`Thread.sleep` 대신 `Signal`(`Concurrency.kt:15`)로 진행 순서를 고정하고,
`txExecutor.newTransaction(isolation = ...)`(`TxExecutor.kt:27`)으로
테스트 안에서 트랜잭션 경계를 직접 그린다.

관찰 결과는 모두 **H2 2.x(MVStore)** 기준이다.

**시나리오 1 — 1차 캐시가 격리 수준을 가린다** `IsolationLevelTest.kt:62`

reader 가 `clear()` **없이** 같은 행을 두 번 읽는다(`:78~79`).
그 사이 writer 가 값을 100 → 200 으로 바꿔 커밋한다.

- `firstRead == 100`
- `secondRead == 100`(`:91`) — **DB 에는 200 이 커밋돼 있는데 보이지 않는다**
- 트랜잭션 종료 후 읽으면 200(`:92`)

격리 수준을 아무리 낮춰도 결과가 안 바뀐다. DB 를 아예 다시 읽지 않기 때문이다.
**이 전제를 모르면 이후의 격리 수준 실험이 전부 무의미해진다.**

**시나리오 2 — READ_COMMITTED + `clear()` → 반복 불가능한 읽기** `:97`

시나리오 1과 유일한 차이는 `txExecutor.clearPersistenceContext()` 한 줄(`:111`)이다.
이제 진짜로 DB 를 다시 읽고, `firstRead == 100` / `secondRead == 200`(`:125~126`).

**같은 트랜잭션 안인데 같은 행의 값이 달라졌다.**
"조회 → 검증 → 조회 → 처리" 흐름이라면 검증이 무의미해진다.

**시나리오 3 — READ_COMMITTED, 팬텀 리드** `:130`

`countByMessage()` 로 조건에 맞는 행을 두 번 센다. 그 사이 writer 가 한 건 INSERT + 커밋.
`firstCount == 0` / `secondCount == 1`(`:154~155`).

여기서는 `clear()` 가 없어도 된다. **count 쿼리는 1차 캐시를 타지 않고 항상 DB 로 나가기 때문**(`:139`).

**시나리오 4 — REPEATABLE_READ + `clear()` → 값이 안 변한다** `:160`

시나리오 2와 격리 수준만 다르다. `clear()` 를 했는데도 `secondRead == 100`(`:189`).
**이번에는 1차 캐시가 아니라 DB 자체가 막아 준 것이다.**
H2(MVStore)는 스냅샷 방식으로 REPEATABLE_READ 를 구현한다.
트랜잭션이 끝난 뒤에는 당연히 새 값 200 이 보인다(`:192`).

시나리오 1과 4는 **겉보기 결과가 똑같은데 원인이 완전히 다르다.**
1번은 "DB 를 안 읽어서", 4번은 "DB 가 옛 스냅샷을 줘서". 이 구분이 이 스펙의 핵심이다.

**시나리오 5 — REPEATABLE_READ, 팬텀도 막힌다** `:196`

`firstCount == 0` / `secondCount == 0`(`:222~223`).
표준 SQL 은 REPEATABLE_READ 에서 팬텀 리드를 허용하지만,
스냅샷 기반으로 구현한 DB(H2 MVStore, PostgreSQL 등)는 함께 막는다.

> **"격리 수준의 이름"이 아니라 "DB 의 실제 구현"을 확인해야 한다.**
> 표를 외워 놓고 다른 DB 로 옮기면 동작이 달라진다.

**시나리오 6 — READ_UNCOMMITTED, 더티 리드** `:228`

writer 가 `updateAndFlushThenRollback()`(`CounterService.kt:44`)로
값을 999 로 바꿔 **flush 만 하고 커밋은 안 한 채** 대기한다.
reader 가 READ_UNCOMMITTED 로 읽으면 `dirtyRead == 999`(`:256`).
그 뒤 writer 는 롤백하므로 실제로 남은 값은 100(`:258`).

**읽은 값이 곧 사라진다.** 존재한 적 없는 데이터로 판단을 내리게 되는 셈이다.

---

## 12. `lock/LostUpdateAndLockingTest` — 잃어버린 갱신과 두 가지 해법 (3개)

### 한눈에

트랜잭션이 원자성을 보장한다고 해서 동시성 문제가 사라지지는 않는다.

```
T1: 잔액 읽기 (1000)
T2: 잔액 읽기 (1000)
T2: 1000 + 200 = 1200 저장, 커밋
T1: 1000 + 100 = 1100 저장, 커밋      <- T2 의 입금 200 이 사라졌다
```

두 트랜잭션 모두 **정상 커밋**됐는데 결과는 틀렸다.
각자의 시선에서는 아무 규칙도 어기지 않았기 때문에 **격리 수준으로는 막을 수 없다.**
해법은 낙관적 락(`@Version`)과 비관적 락(`select ... for update`) 두 가지다.

### 자세히

**시나리오 1 — 갱신 유실 재현** `LostUpdateAndLockingTest.kt:56`

`@Version` 이 없는 `Counter` 로 `readThenWriteWithoutLock()`(`CounterService.kt:31`)을
두 스레드가 동시에 부른다. `Signal` 로 순서를 못 박아 **둘이 반드시 같은 값(0)을 읽게** 만든다(`:67~73`).

두 번의 `+10` 을 기대했지만 결과는 `10`(`:80`).

**예외도 없고 로그도 없다.** 그래서 가장 발견하기 어려운 버그다.
운영에서는 "포인트가 가끔 덜 쌓인다" 같은 형태로, 재현도 안 되는 채 보고된다.

**시나리오 2 — 낙관적 락** `:85`

완전히 같은 시나리오인데 엔티티만 `@Version` 이 있는 `Account` 로 바꿨다
(`depositWithOptimisticLock()`, `AccountService.kt:34`).

Hibernate 가 `update ... where id = ? and version = 0` 을 보내는데
이미 version 이 1로 올라가 있어 **0건이 갱신되고** 예외가 터진다.

- `second.failure == null`(`:110`) — 먼저 커밋한 쪽은 성공
- `first.failure` 가 `OptimisticLockingFailureException`(`:111`) — 늦은 쪽이 실패
- 잔액 1,200 / version 1(`:115~116`) — 성공한 입금만 반영

여기서 `runConcurrently(first, second)` 뒤에 `rethrowIfFailed()` 를 **부르지 않는다**(`:107`).
예외가 나는 것이 기대 동작이므로, 예외를 다시 던지는 대신 `failure` 를 직접 단언한다.

실무에서는 실패한 쪽을 **재시도**한다(Spring Retry 등).
낙관적 락은 "충돌이 드물다"는 가정 위에서만 이득이다 — 충돌이 잦으면 재시도 비용이 락 비용을 넘는다.

**시나리오 3 — 비관적 락** `:121`

`depositWithPessimisticLock()`(`AccountService.kt:45`)은
`findByIdForUpdate()`(`AccountRepository.kt:16`, `@Lock(PESSIMISTIC_WRITE)`)로 행을 선점한다.
`select ... for update` 가 나가고, 두 번째 트랜잭션은 첫 번째가 커밋할 때까지 **select 단계에서 대기**한다.

결과: 잔액 1,300(`:151`) — **두 입금이 모두 살아남았다.**

이 테스트의 타임라인 단언이 이 저장소에서 가장 섬세한 부분이다. 세 단계로 나뉜다.

1. **각 스레드 내부 순서**(`:154~162`) — 프로그램 순서라 항상 고정. `shouldContainExactly` 로 단언.
2. **두 스레드 사이에서 DB 가 보장하는 것**(`:168`) —
   `first:writing` 이 `second:locked` 보다 먼저인 것은 확정이다.
   second 의 락 대기는 first 가 커밋해야 풀리고, first 는 임계 구역을 끝낸 뒤에야 커밋하기 때문.
   **이것이 직렬화의 증거다.**
3. **정답이 여러 개인 지점**(`:170~191`) —
   `first:committed` 와 `second:locked` 사이에는 **어떤 happens-before 관계도 없다.**
   first 의 커밋이 곧 second 의 대기를 푸는 사건이라, 커밋 순간 두 스레드가 동시에 깨어나 각자 기록한다.
   보통은 할 일이 적은 first 가 이기지만 그건 확률일 뿐이다 —
   first 에 50ms 지연만 넣어도 반대 순서가 관측된다.
   그래서 `shouldContainExactlyInAnyOrder` 로 **두 순서 모두 정답으로 인정**한다.

원래는 `first:committed → second:locked` 로 단언되어 있었고, CI 부하/GC 로 깨지는
**플래키 테스트**였다(커밋 `289954c` 에서 수정).

**두 해법의 트레이드오프**

| | 낙관적 락 | 비관적 락 |
|---|---|---|
| 방식 | 일단 진행 후 version 으로 충돌 감지 | 읽는 시점에 행을 잠금 |
| 충돌 시 | 예외 → **재시도 필요** | 대기 후 순차 실행 |
| 비용 | 락 없음(빠름), 재시도 로직 필요 | 대기/데드락/처리량 저하 |
| 적합 | 충돌이 드문 경우 | 충돌이 잦거나 재시도가 곤란한 경우 |

---

# 부록. 자주 헷갈리는 쌍 비교

## REQUIRED vs REQUIRES_NEW vs NESTED

같은 상황(자식 실패 + 부모가 catch)에서 결과가 전부 다르다.

| | 스펙 | 결과 |
|---|---|---|
| REQUIRED | 1-3 | `UnexpectedRollbackException`, **전부 롤백** |
| REQUIRES_NEW | 2-2 | 자식만 롤백, 부모는 커밋 + 복구 작업까지 가능 |
| NESTED (JDBC) | 3-2 | 세이브포인트까지만 롤백, 부모 커밋 |

반대 상황(자식 성공 + 부모 실패)에서는:

| | 스펙 | 결과 |
|---|---|---|
| REQUIRED | — | 자식도 롤백 |
| REQUIRES_NEW | 2-3 | **자식은 살아남음** |
| NESTED (JDBC) | 3-3 | 자식도 롤백 |

## `UnexpectedRollbackException` 이 나오는 조건

1. 자식이 부모의 물리 트랜잭션에 **참여**했고 (REQUIRED / MANDATORY / SUPPORTS)
2. 자식에서 예외가 프록시를 빠져나가 rollback-only 가 찍혔고
3. 그 예외를 **누군가 삼켜서** 부모가 정상 종료로 커밋을 시도했을 때

셋 중 하나라도 빠지면 안 나온다. 예외를 그대로 전파하면 원래 예외가 나오고(스펙 1-4),
REQUIRES_NEW 로 분리돼 있으면 애초에 오염되지 않는다(스펙 2-2).
오염 범위는 **물리 트랜잭션 경계까지**다(스펙 4-2).

## "데이터가 롤백되지 않았다" 의 원인 후보

| 원인 | 스펙 | 단서 |
|---|---|---|
| 체크 예외를 던졌다 | 7 | 예외는 전파되는데 데이터가 남음 |
| 자기 호출로 `@Transactional` 이 무시됐다 | 8 | 트랜잭션 로그에 아무것도 안 찍힘 |
| REQUIRES_NEW 자식이 이미 커밋했다 | 2-3, 4-6 | 일부만 남아 있음 |
| NOT_SUPPORTED / SUPPORTS 로 트랜잭션 없이 실행됐다 | 6 | `entityManagerId == null` |
| `noRollbackFor` 에 걸렸다 | 7 | 설정을 확인 |

## "조회했는데 데이터가 없다" 의 원인 후보

| 원인 | 스펙 |
|---|---|
| REQUIRES_NEW 자식이 부모의 미커밋 데이터를 읽으려 함 | 2-4 |
| 1차 캐시 때문에 갱신된 값이 안 보임 | 10-1, 11-1 |
| REPEATABLE_READ 스냅샷이 옛 값을 줌 | 11-4 |

## 트랜잭션 경계를 직접 보고 싶을 때

`src/main/resources/application.yaml:29~30` 의 두 줄 주석을 해제한다.

```yaml
logging:
  level:
    org.springframework.transaction.interceptor: TRACE
    org.springframework.orm.jpa.JpaTransactionManager: DEBUG
```

로그에서 다음 문구를 찾으면 된다.

- `Creating new transaction with name [...]` — 물리 트랜잭션 시작
- `Participating in existing transaction` — REQUIRED 참여
- `Suspending current transaction, creating new transaction` — REQUIRES_NEW
- `Participating transaction failed - marking existing transaction as rollback-only` — 오염 발생 지점
- `Resuming suspended transaction` — 복원
