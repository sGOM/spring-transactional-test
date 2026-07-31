package org.example.transactiontest.exception

/**
 * 테스트에서 의도적으로 발생시키는 예외들.
 *
 * Spring 의 기본 롤백 규칙은 "예외 타입"으로 결정된다.
 * - [RuntimeException] 과 [Error] -> 롤백
 * - 그 외 체크 예외([Exception]) -> **커밋**
 *
 * Kotlin 에는 체크 예외 개념이 없어서 `throws` 선언 없이도 [Exception] 을 던질 수 있지만,
 * Spring 은 여전히 *타입* 으로 판단하기 때문에 롤백되지 않는다. 이 함정을 테스트로 고정한다.
 */

/** 내부(피호출) 서비스에서 발생시키는 언체크 예외 */
class InnerFailureException(
    message: String = "Inner 에서 의도적으로 발생시킨 런타임 예외",
) : RuntimeException(message)

/** 외부(호출) 서비스에서 발생시키는 언체크 예외 */
class OuterFailureException(
    message: String = "Outer 에서 의도적으로 발생시킨 런타임 예외",
) : RuntimeException(message)

/** 체크 예외. 기본 롤백 규칙에 걸리지 않는다 */
class BusinessCheckedException(
    message: String = "체크 예외 (기본 롤백 규칙에 걸리지 않는다)",
) : Exception(message)
