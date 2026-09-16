package kr.easydoc.api.e2e

import com.fasterxml.jackson.annotation.JsonProperty
import kr.easydoc.api.E2E_PROFILE
import kr.easydoc.application.auth.PhoneVerificationSmsOutbox
import org.springframework.context.annotation.Profile
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * e2e 전용 진단 엔드포인트 — Playwright 스위트가 휴대폰 인증 요청 뒤 보낸 인증 코드를
 * 읽는 통로다(`E2eMailInboxController` 와 같은 자리).
 *
 * **제품 API 가 아니다.** `contracts/easy-doc-v1.yaml` 에 없고, `AuthenticatedEndpoints`
 * 에도 없다(실제 비밀번호·API 키를 다루지 않는 진단 자리라 인증을 요구할 이유가 없다).
 * `e2e` profile 이 켜진 컨텍스트에만 조립되므로 `api`·`local`·prod 배포에는 아예 존재하지
 * 않는다(`E2eSmsOutboxControllerProfileTest` 가 그 부재를 고정한다).
 *
 * [PhoneVerificationSmsOutbox] 협력자에 의존한다(구체 어댑터 `FakeSmsSender` 를 직접
 * 참조하지 않는다) — `api` 는 `infrastructure` 를 `runtimeOnly` 로만 의존해 그 모듈
 * 타입을 컴파일 시점에 보지 못한다(`E2eMailInboxController` KDoc과 같은 경계).
 *
 * 생성자 파라미터 이름을 `phoneVerificationSmsOutbox` bean 이름과 **똑같이** 맞춘다 —
 * `FakeSmsSender` 는 `PhoneVerificationSmsSender`·`PhoneVerificationSmsOutbox` 둘 다
 * 구현하므로, `phoneVerificationSmsSender` bean 이 먼저 인스턴스화되면 그 뒤로는 Spring
 * 이 실제 런타임 타입으로 매치를 재판정해 `PhoneVerificationSmsOutbox` 자리에도 후보로
 * 잡힌다. 이름이 bean 이름과 같으면 Spring 이 이름 일치로 모호성을 풀어 그 bean 을
 * 고른다(`E2eMailInboxController(mailInbox: MailInbox)`가 같은 이유로 파라미터 이름을
 * `mailInbox` 로 맞춘 것과 같은 규약) — 이름이 다르면 두 후보가 남아 기동이 실패한다.
 */
@RestController
@Profile(E2E_PROFILE)
class E2eSmsOutboxController(private val phoneVerificationSmsOutbox: PhoneVerificationSmsOutbox) {
    @GetMapping("/__e2e/sms/latest")
    fun latest(
        @RequestParam to: String,
    ): ResponseEntity<LatestSmsResponse> {
        val sent = phoneVerificationSmsOutbox.latestTo(to) ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(LatestSmsResponse(code = sent.code, validMinutes = sent.validMinutes))
    }
}

/** [E2eSmsOutboxController.latest] 응답. */
data class LatestSmsResponse(
    @get:JsonProperty("code") val code: String,
    @get:JsonProperty("valid_minutes") val validMinutes: Long,
) {
    /** 코드가 로그로 새지 않게 한다. **직렬화는 가리지 않는다**(JSON 에는 그대로 나간다). */
    override fun toString(): String = "LatestSmsResponse(codeLength=${code.length}자, validMinutes=$validMinutes)"
}
