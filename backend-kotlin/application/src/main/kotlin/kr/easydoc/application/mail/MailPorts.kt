package kr.easydoc.application.mail

/**
 * 벤더 중립 발신 메일. **본문은 평문 텍스트만** 담는다 — 사용자 문서 본문·쉬운 글·마스킹
 * 대응표를 여기 실으면 안 된다(CLAUDE.md 「사용자 문서 본문, 개인정보 ... 로그에 남기지
 * 않는다」와 같은 경계 — 여기서는 로그가 아니라 발신 채널 자체가 그 경계다).
 */
class OutboundMail(
    val to: EmailAddress,
    val subject: String,
    val textBody: String,
) {
    /** 수신자·본문 내용을 찍지 않는다 — 길이만 남긴다. */
    override fun toString(): String = "OutboundMail(subject=$subject, bodyLength=${textBody.length}자)"
}

/** [MailSender.send] 의 결과. */
sealed interface MailDelivery {
    /** 벤더가 접수했다. [providerMessageId] 는 벤더가 없으면(fake 등) `null`. */
    class Sent(val providerMessageId: String? = null) : MailDelivery

    /** 벤더가 거절했다 — 재시도해도 될지는 [reason] 이 가른다. */
    class Rejected(val reason: MailRejectReason) : MailDelivery
}

/** 메일 거절 사유. 열거가 아니라 **재시도 판단에 필요한 범주**만 가른다. */
enum class MailRejectReason {
    /** 수신 주소 자체가 유효하지 않다 — 재시도해도 소용없다. */
    INVALID_RECIPIENT,

    /** 벤더 쪽 일시 오류 — 나중에 재시도하면 성공할 수 있다. */
    PROVIDER_ERROR,

    /** 발송 한도 초과. */
    RATE_LIMITED,
}

/**
 * 메일 발송 포트. 구체 벤더(SES·Postmark 등)는 `infrastructure` 어댑터가 구현한다 —
 * 서비스·유스케이스는 이 인터페이스만 안다(CLAUDE.md 포트/어댑터 규칙).
 */
interface MailSender {
    fun send(message: OutboundMail): MailDelivery
}

/**
 * 발송한 메일을 다시 읽는 포트 — **e2e 진단 전용**이다. 실제 벤더(SES·SMTP 등)는 보낸
 * 메일을 되읽을 방법이 없으므로 `FakeMailSender` 만 구현한다. 어느 제품 유스케이스도 이
 * 포트에 의존하지 않는다 — `api` 모듈의 `e2e` profile 전용 진단 엔드포인트
 * (`E2eMailInboxController`) 하나만의 협력자다. `api` 모듈은 `infrastructure` 를
 * `runtimeOnly` 로만 의존해 `FakeMailSender` 를 컴파일 시점에 보지 못하므로, 그 컨트롤러가
 * 캐스팅 없이 의존할 수 있게 이 얇은 포트를 `application` 에 둔다.
 */
interface MailInbox {
    /** 그 수신자에게 보낸 가장 최근 메일. 없으면 `null`. */
    fun latestTo(recipient: EmailAddress): OutboundMail?
}
