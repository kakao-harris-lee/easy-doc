package kr.easydoc.api.e2e

import com.fasterxml.jackson.annotation.JsonProperty
import kr.easydoc.api.E2E_PROFILE
import kr.easydoc.application.mail.EmailAddress
import kr.easydoc.application.mail.MailInbox
import org.springframework.context.annotation.Profile
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * e2e 전용 진단 엔드포인트 — Playwright 스위트가 가입 뒤 보낸 인증 코드 메일을 읽는 통로다.
 *
 * **제품 API 가 아니다.** `contracts/easy-doc-v1.yaml` 에 없고, `AuthenticatedEndpoints`
 * 에도 없다(공개·보호 어느 쪽도 아니라 인증을 걸지 않는다 — 실제 API 키·비밀번호를 다루지
 * 않는 진단 자리라 이 엔드포인트 자체에 인증을 요구할 이유가 없다). `e2e` profile 이 켜진
 * 컨텍스트에만 조립되므로 `api`·`local`·prod 배포에는 아예 존재하지 않는다
 * (`E2eMailInboxControllerProfileTest` 가 그 부재를 고정한다).
 *
 * [MailInbox] 협력자에 의존한다(구체 어댑터 `FakeMailSender` 를 직접 참조하지 않는다) —
 * `api` 는 `infrastructure` 를 `runtimeOnly` 로만 의존해 그 모듈 타입을 컴파일 시점에
 * 보지 못한다(`ApiApplication.E2E_PROFILE` KDoc과 같은 경계).
 */
@RestController
@Profile(E2E_PROFILE)
class E2eMailInboxController(private val mailInbox: MailInbox) {
    @GetMapping("/__e2e/mail/latest")
    fun latest(
        @RequestParam to: String,
    ): ResponseEntity<LatestMailResponse> {
        val mail = mailInbox.latestTo(EmailAddress.of(to)) ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(LatestMailResponse(subject = mail.subject, textBody = mail.textBody))
    }
}

/** [E2eMailInboxController.latest] 응답. */
data class LatestMailResponse(
    @get:JsonProperty("subject") val subject: String,
    @get:JsonProperty("text_body") val textBody: String,
) {
    /** 본문에 인증 코드가 담긴다 — 찍지 않는다. **직렬화는 가리지 않는다**(JSON 에는 그대로 나간다). */
    override fun toString(): String = "LatestMailResponse(subject=$subject, textBodyLength=${textBody.length}자)"
}
