package kr.easydoc.infrastructure.notification

import kr.easydoc.application.notification.OperatorNotificationEvent
import kr.easydoc.application.notification.OperatorNotifications
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionHandler
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * 운영 알림을 호출자 스레드에서 떼어낸다.
 *
 * 알림은 best-effort 부가 기능이다 — 요청 스레드(결제 승인·회원 가입)나 worker 루프가
 * 외부 채널의 지연이나 실패로 느려지거나 깨지면 안 된다. 제출은 항상 즉시 반환하고,
 * 큐가 가득 차면 이벤트를 버리고 정제된 WARN 한 줄만 남긴다.
 *
 * Spring 은 [close] 를 destroy method 로 추론해 컨텍스트 종료 시 남은 큐를 비운다.
 */
class AsyncOperatorNotifications(
    private val delegate: OperatorNotifications,
    queueCapacity: Int,
    private val shutdownTimeout: Duration,
) : OperatorNotifications,
    AutoCloseable {
    private val log = LoggerFactory.getLogger(AsyncOperatorNotifications::class.java)

    private val executor =
        ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue<Runnable>(queueCapacity),
            ThreadFactory { runnable -> Thread(runnable, THREAD_NAME).apply { isDaemon = true } },
            // 큐가 가득 찼거나 이미 종료된 뒤의 제출은 던지지 않고 버린다.
            RejectedExecutionHandler { task, _ -> drop(task) },
        )

    // 제출 경로는 호출자 스레드다. 큐·executor 가 무엇을 던지든 호출자에게 새어 나가면 안 된다.
    @Suppress("TooGenericExceptionCaught", "InstanceOfCheckForException")
    override fun notify(event: OperatorNotificationEvent) {
        try {
            executor.execute(DeliveryTask(event.eventType) { deliver(event) })
        } catch (failure: Throwable) {
            // JVM 이 더 못 버티는 상태(OOM·StackOverflow)는 삼키지 않는다 — 호출자가 깨진
            // 런타임 위에서 계속 돌게 두는 쪽이 알림 하나를 잃는 것보다 나쁘다.
            if (failure is VirtualMachineError) throw failure
            log.warn("운영 알림 제출 실패: eventType={} 예외={}", event.eventType, failure::class.java.simpleName)
        }
    }

    @Suppress("TooGenericExceptionCaught", "InstanceOfCheckForException")
    private fun deliver(event: OperatorNotificationEvent) {
        try {
            delegate.notify(event)
        } catch (failure: Throwable) {
            if (failure is VirtualMachineError) throw failure
            log.warn("운영 알림 전달 실패: eventType={} 예외={}", event.eventType, failure::class.java.simpleName)
        }
    }

    override fun close() {
        executor.shutdown()
        try {
            if (!executor.awaitTermination(shutdownTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                val dropped = executor.shutdownNow().size
                log.warn("운영 알림 큐를 다 비우지 못하고 종료한다: 남은 이벤트={}", dropped)
            }
        } catch (interrupted: InterruptedException) {
            executor.shutdownNow()
            Thread.currentThread().interrupt()
            log.warn("운영 알림 종료 대기가 중단됐다: 예외={}", interrupted::class.java.simpleName)
        }
    }

    private fun drop(task: Runnable) {
        val eventType = (task as? DeliveryTask)?.eventType ?: UNKNOWN_EVENT_TYPE
        log.warn("운영 알림을 보내지 못하고 버린다(큐 포화 또는 종료): eventType={}", eventType)
    }

    private class DeliveryTask(
        val eventType: String,
        private val body: () -> Unit,
    ) : Runnable {
        override fun run() = body()
    }

    private companion object {
        const val THREAD_NAME = "operator-notifications"
        const val UNKNOWN_EVENT_TYPE = "unknown"
    }
}
