package org.example.transactiontest.service

import org.example.transactiontest.entity.Logs
import org.example.transactiontest.exception.InnerFailureException
import org.example.transactiontest.repository.LogsRepository
import org.example.transactiontest.support.TxProbe
import org.example.transactiontest.support.TxSnapshot
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * `@Transactional` 이 **프록시 기반 AOP** 라는 사실에서 오는 함정.
 *
 * 스프링이 주입하는 것은 진짜 빈이 아니라 그것을 감싼 CGLIB 프록시다.
 * 트랜잭션은 "프록시를 통과할 때" 시작되므로, 같은 클래스 안에서 `this.method()` 로
 * 직접 호출하면 프록시를 거치지 않아 `@Transactional` 이 **무시된다**.
 *
 * `private` 메서드도 마찬가지다. CGLIB 는 서브클래싱으로 동작하므로 private 메서드를
 * 오버라이드할 수 없고, 따라서 어떤 경로로 호출하든 트랜잭션이 적용되지 않는다.
 */
@Service
class SelfInvocationService(
    private val logsRepository: LogsRepository,
    private val txProbe: TxProbe,
    /** 자기 자신을 프록시로 다시 얻기 위한 지연 조회. 생성자에서 바로 주입하면 순환 참조가 된다 */
    private val self: ObjectProvider<SelfInvocationService>,
) {
    /**
     * 내부 호출(self-invocation). `transactionalWork` 의 `@Transactional` 이 적용되지 않는다.
     * 따라서 저장은 자동 커밋되고, 이후 예외가 터져도 롤백되지 않는다.
     */
    fun callInternally(message: String = MESSAGE) {
        transactionalWork(message)
    }

    /**
     * 프록시를 거쳐 호출한다. 이제 `@Transactional` 이 정상 동작하므로 예외 발생 시 롤백된다.
     * (실무에서는 서비스 분리가 우선이고, 이 방식은 차선책이다)
     */
    fun callThroughProxy(message: String = MESSAGE) {
        self.getObject().transactionalWork(message)
    }

    @Transactional
    fun transactionalWork(message: String = MESSAGE) {
        logsRepository.save(Logs(message))
        throw InnerFailureException("self-invocation 실험용 예외")
    }

    /** 내부 호출 시 실제로 트랜잭션이 없다는 것을 스냅샷으로 증명한다. */
    fun snapshotViaInternalCall(): TxSnapshot = transactionalSnapshot()

    /** 프록시 경유 시 트랜잭션이 열린다는 것을 스냅샷으로 증명한다. */
    fun snapshotViaProxy(): TxSnapshot = self.getObject().transactionalSnapshot()

    @Transactional
    fun transactionalSnapshot(): TxSnapshot = txProbe.snapshot("self:@Transactional")

    companion object {
        const val MESSAGE = "SELF_INVOCATION"
    }
}
