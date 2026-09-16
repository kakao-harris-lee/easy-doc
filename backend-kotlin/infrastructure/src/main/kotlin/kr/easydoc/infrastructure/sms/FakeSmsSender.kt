package kr.easydoc.infrastructure.sms

import kr.easydoc.application.auth.PhoneVerificationSmsOutbox
import kr.easydoc.application.auth.PhoneVerificationSmsSender
import kr.easydoc.application.auth.SentPhoneVerification
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 실제 네트워크 호출 없이 인증 코드를 메모리에 기록한다. `local`/`ci`/테스트 전용이며
 * `SmsConfiguration` 의 `fake` provider 가 이 클래스를 등록한다(`FakeMailSender` KDoc과
 * 같은 자리 — 지금은 유일하게 구현된 대역 어댑터다).
 *
 * [PhoneVerificationSmsOutbox] 도 구현한다 — `e2e` profile 의 진단 엔드포인트
 * (`E2eSmsOutboxController`)가 요청 직후 보낸 인증 코드를 되읽는 유일한 통로다.
 *
 * 기록은 `(전화번호, 코드)` 쌍이다. `data class` 로 감싸지 않는다 — 표준 라이브러리
 * `Pair` 는 `kr.easydoc.` 패키지 밖이라 `SensitiveToStringReachTest` census 대상이
 * 아니고, 그 census 를 피하려고 새 타입을 만드는 것 자체가 불필요하다.
 */
class FakeSmsSender :
    PhoneVerificationSmsSender,
    PhoneVerificationSmsOutbox {
    private val record = CopyOnWriteArrayList<Pair<String, SentPhoneVerification>>()

    override fun send(
        phoneNumber: String,
        code: String,
        validMinutes: Long,
    ) {
        record.add(phoneNumber to SentPhoneVerification(code, validMinutes))
    }

    override fun latestTo(phoneNumber: String): SentPhoneVerification? =
        record.lastOrNull { it.first == phoneNumber }?.second
}
