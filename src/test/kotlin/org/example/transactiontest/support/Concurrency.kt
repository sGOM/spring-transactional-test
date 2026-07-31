package org.example.transactiontest.support

import org.slf4j.LoggerFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 동시성 시나리오를 **결정적으로** 만들기 위한 최소한의 도구들.
 *
 * 격리 수준이나 락 테스트는 `Thread.sleep` 으로 타이밍을 맞추면 CI 에서 쉽게 깨진다.
 * 여기서는 [Signal] 로 두 스레드의 진행 순서를 명시적으로 고정한다.
 */

/** 한 번만 열리는 게이트. 대기 시간이 지나면 조용히 통과하지 않고 실패시킨다. */
class Signal(private val name: String) {
    private val latch = CountDownLatch(1)

    fun send() {
        log.info("SIGNAL send   : {}", name)
        latch.countDown()
    }

    fun await(timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS) {
        log.info("SIGNAL await  : {}", name)
        check(latch.await(timeoutMillis, TimeUnit.MILLISECONDS)) {
            "신호 '$name' 를 ${timeoutMillis}ms 안에 받지 못했다. 시나리오가 교착 상태일 수 있다."
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(Signal::class.java)
        const val DEFAULT_TIMEOUT_MILLIS = 10_000L
    }
}

/**
 * 별도 스레드에서 블록을 실행하고, 그 안에서 터진 예외를 [failure] 에 담아둔다.
 *
 * 트랜잭션은 스레드에 바인딩되므로 "두 개의 동시 트랜잭션"을 만들려면 스레드가 두 개여야 한다.
 */
class Worker(
    private val name: String,
    private val body: () -> Unit,
) {
    @Volatile
    var failure: Throwable? = null
        private set

    private val thread = Thread({
        try {
            body()
        } catch (t: Throwable) {
            failure = t
        }
    }, name).apply { isDaemon = true }

    fun start(): Worker = apply { thread.start() }

    fun join(timeoutMillis: Long = 15_000L): Worker = apply {
        thread.join(timeoutMillis)
        check(!thread.isAlive) { "worker '$name' 가 ${timeoutMillis}ms 안에 끝나지 않았다." }
    }

    /** 예외가 났다면 그대로 다시 던진다. */
    fun rethrowIfFailed(): Worker = apply { failure?.let { throw it } }
}

fun worker(name: String, body: () -> Unit): Worker = Worker(name, body)

/** 모든 워커를 동시에 시작하고 전부 끝날 때까지 기다린다. */
fun runConcurrently(vararg workers: Worker): List<Worker> {
    workers.forEach { it.start() }
    workers.forEach { it.join() }
    return workers.toList()
}
