package kr.easydoc.application.conversion

import kr.easydoc.application.mail.EmailAddress
import kr.easydoc.application.mail.MailDelivery
import kr.easydoc.application.mail.MailSender
import kr.easydoc.application.mail.OutboundMail
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * 알림 대상 조회 결과 — 문서 제목·소유자 이메일·이미 알림을 보냈는지.
 *
 * `documentTitle`·`ownerEmail` 은 민감 판정 토큰(`title`·`email`)에 걸린다 — 값을 찍지
 * 않는 `toString()` 을 직접 쥔다(`SensitiveToStringReachTest` R-10 축).
 */
class ConversionNotificationTarget(
    val documentTitle: String,
    val ownerEmail: EmailAddress,
    val alreadyNotified: Boolean,
) {
    override fun toString(): String =
        "ConversionNotificationTarget(titleLength=${documentTitle.length}자, alreadyNotified=$alreadyNotified)"
}

/**
 * 변환 완료 알림에 필요한 조회·표시 포트. `conversions.notified_at` 이 실물 저장이다
 * (`JdbcConversionNotificationStore`, migration V5).
 */
interface ConversionNotificationStore {
    /** 알림에 필요한 최소 정보. 변환·문서·사용자 행이 사라졌으면(파기 등) `null`. */
    fun findTarget(conversionId: UUID): ConversionNotificationTarget?

    /**
     * 아직 알림을 보내지 않은 변환만 표시한다(원자적 `UPDATE ... WHERE notified_at IS NULL`).
     * 이미 표시돼 있었거나 행이 없으면 `false`.
     */
    fun markNotified(conversionId: UUID): Boolean
}

/**
 * 변환 완료를 문서 소유자에게 메일로 알린다.
 *
 * **성공했을 때만 보낸다.** 실패 알림 메일은 이번 슬라이스 범위 밖이다 — 실패한 변환은
 * 사용자가 화면에서 바로 확인하고, 실패 메일까지 추가하면 두 알림 경로가 같은 사실을
 * 두 번 알리게 된다(팀리드 지침, 2026-09-04).
 *
 * **재실행 안전.** [ConversionNotificationStore.findTarget] 의 `alreadyNotified` 가
 * `true` 면 보내지 않고 돌아온다. 발송이 **성공한 뒤에만**
 * [ConversionNotificationStore.markNotified] 로 표시한다 — 발송이 거절되거나 예외가 나면
 * 표시하지 않는다. 지금은 이 결과를 재시도하는 도구가 없지만, 표시를 미리 하지 않는 것은
 * 나중에 재시도 도구가 생겼을 때 실패한 발송을 다시 집을 수 있게 남겨 두기 위해서다.
 *
 * **트랜잭션 밖에서 부른다.** 이 클래스 자신은 트랜잭션을 열지 않는다 — 호출자
 * ([ProcessConversionJob])가 완료 쓰기 커밋 **뒤에** 부른다(CLAUDE.md: 장시간 외부 호출을
 * DB 트랜잭션 안에서 실행하지 않는다).
 *
 * **실패는 변환을 막지 않는다.** 조회·발송·표시 세 단계 모두 예외를 삼키고 로그만 남긴다
 * (건수·ID만 — 수신 주소·본문 금지). 이미 커밋된 완료 결과를 이 클래스가 되돌릴 이유가 없다.
 */
class ConversionCompletedNotifier(
    private val store: ConversionNotificationStore,
    private val mailSender: MailSender,
    private val publicBaseUrl: String,
) {
    private val log = LoggerFactory.getLogger(ConversionCompletedNotifier::class.java)

    fun notify(conversionId: UUID) {
        val target = findTargetQuietly(conversionId)
        if (target == null || target.alreadyNotified) return

        val mail =
            OutboundMail(
                to = target.ownerEmail,
                subject = SUBJECT,
                textBody = body(target.documentTitle, conversionId),
            )
        when (val result = sendQuietly(conversionId, mail)) {
            null -> {
                Unit
            }

            is MailDelivery.Sent -> {
                markNotifiedQuietly(conversionId)
            }

            is MailDelivery.Rejected -> {
                log.warn("변환 완료 메일이 거절됐다: conversionId={} reason={}", conversionId, result.reason)
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun findTargetQuietly(conversionId: UUID): ConversionNotificationTarget? =
        try {
            store.findTarget(conversionId)
        } catch (exc: RuntimeException) {
            log.warn("알림 대상 조회 실패: conversionId={}", conversionId, exc)
            null
        }

    @Suppress("TooGenericExceptionCaught")
    private fun sendQuietly(
        conversionId: UUID,
        mail: OutboundMail,
    ): MailDelivery? =
        try {
            mailSender.send(mail)
        } catch (exc: RuntimeException) {
            log.warn("변환 완료 메일 발송 중 예외가 났다: conversionId={}", conversionId, exc)
            null
        }

    @Suppress("TooGenericExceptionCaught")
    private fun markNotifiedQuietly(conversionId: UUID) {
        try {
            if (!store.markNotified(conversionId)) {
                log.warn("알림 표시가 갱신되지 않았다(경합 또는 대상 소실): conversionId={}", conversionId)
            }
        } catch (exc: RuntimeException) {
            log.warn("알림 표시 갱신 실패: conversionId={}", conversionId, exc)
        }
    }

    private fun body(
        title: String,
        conversionId: UUID,
    ): String =
        "\"$title\" 문서 변환이 완료됐습니다.\n\n" +
            "결과 확인: $publicBaseUrl/conversions/$conversionId\n"

    private companion object {
        const val SUBJECT = "[쉬운 글] 변환이 완료됐습니다"
    }
}
