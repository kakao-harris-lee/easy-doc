package kr.easydoc.api.config

import kr.easydoc.core.security.Secret
import org.springframework.boot.context.properties.ConfigurationProperties

// 검증: `ConfigurationPropertiesBindingTest`.

/** 애플리케이션 설정. `app/config.py` 의 `Settings` 를 옮긴 것이다. */
@ConfigurationProperties(prefix = "easydoc")
data class EasyDocProperties(
    /** 브라우저에서 API를 부를 수 있는 오리진 목록. Python 기본값과 같다. */
    val corsOrigins: List<String> =
        listOf(
            "http://localhost:5173",
            "http://localhost:8080",
            "http://127.0.0.1:8080",
        ),
) {
    // ── `easydoc.crypto.fernet-key` 는 여기 없다 — 설정 자체가 사라졌다 ────────────
    //
    // 종전에 `CryptoProperties(fernetKey: Secret)` 이 있었다. 두 가지가 함께 무너졌다.
    //
    // ⑴ **가리키던 것이 없어졌다.** 저장 암호화는 Fernet 호환이 아니라 표준 AEAD 신규
    //    구현이다(master-plan 6.2 · 계획 §4.3 2차 개정 · `migration-safety-gate` I-7).
    //    Fernet 키를 담을 자리가 필요 없다.
    // ⑵ **아무도 조립할 수 없는 설정이었다** — `easydoc.auth.*`·`easydoc.llm.*` 이 아래
    //    두 문단에서 옮겨 간 것과 같은 이유다.
    //
    // 대체물은 `infrastructure/crypto/CryptoConfiguration.kt` 의 `EncryptionProperties`
    // (접두사 `easydoc.encryption`)다. **접두사를 물려받지 않는다** — 옛 이름을 재사용하면
    // 기존 배포의 Fernet 키가 AEAD 키로 읽히고, 32바이트가 아니므로 조용히 버려진다.
    // ────────────────────────────────────────────────────────────────────────────

    // ── `easydoc.auth.*` 은 여기 없다 — infrastructure 가 소유한다 ────────────────
    //
    // `easydoc.llm.*` 과 **같은 이유이고 같은 처분**이다(아래 문단). 인증 설정을 써서
    // 토큰 발급기·해시기를 조립할 수 있는 모듈은 `infrastructure` 뿐인데(`api` 는 그것을
    // `runtimeOnly` 로만 의존해 `JwtAccessTokens` 타입을 컴파일 시점에 보지 못한다),
    // `infrastructure` 는 `api` 를 의존할 수 없다. 즉 **아무도 조립할 수 없는 설정**이었다.
    //
    // 그래서 `infrastructure/auth/AuthConfiguration.kt` 의 `AuthProperties`(접두사
    // `easydoc.auth`)로 내렸다. YAML 키와 환경변수 이름은 그대로다. 두 곳에 같은 접두사를
    // 두면 소유자가 둘이 되므로 여기서는 **지운다.**
    // ────────────────────────────────────────────────────────────────────────────

    // ── `easydoc.llm.*` 은 여기 없다 — infrastructure 가 소유한다 ──────────────────
    //
    // 이전에는 `LlmProperties` 가 이 클래스의 중첩 타입이었다. 그런데 그 값을 써서
    // provider 를 조립할 수 있는 모듈은 `infrastructure` 뿐인데(`api` 는 그것을
    // `runtimeOnly` 로만 의존해 `AnthropicProvider` 타입을 컴파일 시점에 보지 못한다),
    // `infrastructure` 는 `api` 를 의존할 수 없다. 즉 **아무도 조립할 수 없는 설정**이었다.
    //
    // 그래서 설정을 구현 옆으로 내렸다: `infrastructure/llm/LlmProviderConfiguration.kt` 의
    // `LlmProperties`(접두사 `easydoc.llm`). YAML 키와 환경변수 이름은 그대로다.
    // 두 곳에 같은 접두사를 두면 소유자가 둘이 되므로 여기서는 **지운다.**
    //
    // 그 파일이 함께 지고 있는 조건 하나: **`baseUrl` 을 설정으로 열지 않는다**
    // (privacy-gate 판정 X-11). 문서 본문이 나가는 대상을 바꾸는 값이기 때문이다.
    // ────────────────────────────────────────────────────────────────────────────
}
