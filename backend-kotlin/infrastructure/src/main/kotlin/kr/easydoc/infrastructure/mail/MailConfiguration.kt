package kr.easydoc.infrastructure.mail

import kr.easydoc.application.mail.MailSender
import kr.easydoc.core.exceptions.ConfigurationException
import kr.easydoc.core.privacy.CONTENT_MASK
import kr.easydoc.core.security.Secret
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** `fake` 어댑터 이름. */
const val FAKE_MAIL_PROVIDER_NAME: String = "fake"

/** `smtp` 어댑터 이름 — 임시 relay(2026-09-04 사용자 결정: Daum, SES 전환 전까지). */
const val SMTP_MAIL_PROVIDER_NAME: String = "smtp"

/**
 * SMTP relay 접속 설정. 바인딩 접두사는 `easydoc.mail.smtp`. `provider=smtp` 일 때만 읽힌다.
 *
 * `ssl` 은 **접속 시점 암시적 TLS**(SMTPS, 보통 포트 465)를 뜻한다 — STARTTLS(평문으로 열고
 * 나중에 승격)가 아니다. 소비자 메일(Daum·Naver 등)의 앱 비밀번호 발급 계정을 임시 relay 로
 * 쓸 때 표준으로 요구하는 방식이다.
 */
@ConfigurationProperties(prefix = "easydoc.mail.smtp")
data class SmtpProperties(
    val host: String = "",
    val port: Int = DEFAULT_PORT,
    val ssl: Boolean = true,
    val username: String = "",
    val password: Secret = Secret.EMPTY,
) {
    /**
     * `username` 은 필드 이름이 민감 판정 토큰(`name`)에 걸린다 — 소비자 메일 계정에서는
     * 보통 메일 주소 로컬파트라 값 대신 길이만 남긴다. `password` 는 [Secret] 이 이미
     * 스스로를 가린다(`MailProperties.toString` 과 같은 규약).
     */
    override fun toString(): String =
        "SmtpProperties(host=$host, port=$port, ssl=$ssl, username=$CONTENT_MASK, password=$password)"

    private companion object {
        const val DEFAULT_PORT: Int = 465
    }
}

/**
 * 메일 발송 설정. 바인딩 접두사는 `easydoc.mail`.
 *
 * `provider` 는 `fake`·`smtp` 둘이다 — SES 등 다른 벤더 어댑터는 아직 없다(backlog §1.4
 * 「메일 발송 서비스」, SES 가 의도한 운영 provider 이고 `smtp` 는 그 전환 전까지의 임시
 * relay 다). `provider` 를 열거형이 아니라 `String` 으로 둔 것은
 * `easydoc.llm.provider`([kr.easydoc.infrastructure.llm.LlmProperties])와 같은 이유다 —
 * 지원하지 않는 값을 [ConfigurationException] 으로 조립 시점에 명확히 거절하는 쪽이, 열거형
 * 바인딩 실패의 불투명한 예외보다 운영자에게 낫다. 나중에 벤더가 늘어도
 * [MailConfiguration.mailSender] 의 `when` 갈래만 늘리면 된다 — "열어 둔다"는 뜻은 이
 * 선택지 구조를 가리키지, 미리 값을 정해 두는 것이 아니다.
 */
@ConfigurationProperties(prefix = "easydoc.mail")
data class MailProperties(
    val provider: String = FAKE_MAIL_PROVIDER_NAME,
    /** 발신 주소. `smtp` provider 는 보통 `smtp.username` 계정과 같은 주소여야 한다
     * (Daum·Naver 등 소비자 메일은 다른 발신자 주소를 거절한다). */
    val fromAddress: String = DEFAULT_FROM_ADDRESS,
    /** 발송 요청 timeout(ms). `smtp` provider 가 connect·read·write 세 자리 모두에 쓴다. */
    val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    val smtp: SmtpProperties = SmtpProperties(),
) {
    /**
     * `fromAddress` 는 필드 이름이 민감 판정 토큰(`address`)에 걸린다 — 실제로는 비밀이
     * 아닌 발신 주소 설정값이지만, 값 대신 길이만 남겨 `SensitiveToStringReachTest` census
     * 를 지킨다(다른 필드에 실제 비밀이 붙었을 때도 같은 규약을 지키게 하는 선례).
     */
    override fun toString(): String =
        "MailProperties(provider=$provider, fromAddress=$CONTENT_MASK, timeoutMs=$timeoutMs, smtp=$smtp)"

    private companion object {
        const val DEFAULT_FROM_ADDRESS: String = "no-reply@easydoc.kr"
        const val DEFAULT_TIMEOUT_MS: Long = 5_000
    }
}

/**
 * 메일 발송기 composition root. `easydoc.mail.provider` 로 구현체를 고른다.
 *
 * **`fake` 를 프로필로 가두지 않는다** — `easydoc.llm.provider=fake`
 * ([kr.easydoc.infrastructure.llm.LlmProviderConfiguration.requireFakeAllowed])와 달리, 메일
 * 어댑터는 자체 검증이 낮은 위험이다(외부 API 키를 흉내 낼 수 없다 — 잘못 켜면 SMTP 연결
 * 자체가 실패한다). 실제 벤더(SES) 어댑터가 생기면 그때 같은 형태의 게이트를 재검토한다.
 */
@Configuration(proxyBeanMethods = false)
class MailConfiguration {
    @Bean
    fun mailSender(properties: MailProperties): MailSender =
        when (properties.provider.lowercase()) {
            FAKE_MAIL_PROVIDER_NAME -> {
                FakeMailSender()
            }

            SMTP_MAIL_PROVIDER_NAME -> {
                requireSmtpConfigured(properties)
                SmtpMailSender.from(properties)
            }

            else -> {
                throw ConfigurationException(
                    "지원하지 않는 메일 provider 설정입니다 " +
                        "(가능: $FAKE_MAIL_PROVIDER_NAME, $SMTP_MAIL_PROVIDER_NAME)",
                )
            }
        }

    /**
     * `smtp` 는 host·username·password·from-address 넷이 전부 있어야 뜬다 — 하나라도
     * 비면 SMTP 접속 자체가 성립하지 않는다. 기동 시점에 막아 첫 발송 시도까지 오설정을
     * 미루지 않는다(다른 `require*` 게이트와 같은 fail-fast 원칙).
     */
    private fun requireSmtpConfigured(properties: MailProperties) {
        val missing =
            buildList {
                if (properties.fromAddress.isBlank()) add("easydoc.mail.from-address")
                if (properties.smtp.host.isBlank()) add("easydoc.mail.smtp.host")
                if (properties.smtp.username.isBlank()) add("easydoc.mail.smtp.username")
                if (properties.smtp.password.isBlank()) add("easydoc.mail.smtp.password")
            }
        if (missing.isNotEmpty()) {
            throw ConfigurationException(
                "easydoc.mail.provider=smtp 는 다음 설정이 필요합니다: ${missing.joinToString()}",
            )
        }
    }
}
