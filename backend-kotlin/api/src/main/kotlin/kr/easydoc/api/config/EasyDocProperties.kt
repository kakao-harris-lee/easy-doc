package kr.easydoc.api.config

import kr.easydoc.core.security.Secret
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 애플리케이션 설정. `app/config.py` 의 `Settings` 를 옮긴 것이다.
 *
 * ## 비밀값은 [Secret] 으로만 받는다
 *
 * pydantic 은 `SecretStr` 로 `repr`/`model_dump` 에서 자동 마스킹한다. Kotlin 데이터
 * 클래스의 기본 `toString()` 은 정반대로 모든 필드를 그대로 찍는다 — 설정 객체를
 * 기동 로그에 한 줄 남기는 순간 JWT 시크릿과 Fernet 키가 로그 수집기로 흘러간다.
 * 그래서 비밀값 필드의 타입을 `String` 이 아니라 [Secret] 으로 두어 **타입이 실수를
 * 막게** 한다.
 *
 * ## 기동은 막지 않는다
 *
 * `jwtSecret`·`fernetKey` 가 없어도 앱은 뜬다. Python 이 그렇게 동작하고
 * (`app/main.py` lifespan 주석: "DB가 떠 있지 않아도 기동은 되고 /health로 진단할 수
 * 있다"), 그 값이 필요한 요청만 503(`ConfigurationError`)으로 거절한다. 기동을 막으면
 * 배포 상태를 `/health` 로 진단한다는 설계 의도가 깨진다.
 *
 * ## Phase 1 범위
 *
 * 필드는 Python `Settings` 를 그대로 옮겼지만, 실제로 **쓰이는** 것은 아직 없다
 * (`/health` 는 설정을 읽지 않는다). 값 검증·사용은 각 기능이 붙는 Phase 에서 함께
 * 온다. 지금 세우는 것은 바인딩 경로와 마스킹 보장이다.
 *
 * `databaseUrl` 이 없는 이유: Spring Boot 는 `spring.datasource.*` 로 DataSource 를
 * 구성한다. Python 의 `DATABASE_URL`(SQLAlchemy async URL) 과 형식이 달라 그대로
 * 재사용할 수 없으므로, DB 접속은 Spring 표준 키로 두고 여기서 중복 선언하지 않는다.
 */
@ConfigurationProperties(prefix = "easydoc")
data class EasyDocProperties(
    /** 브라우저에서 API를 부를 수 있는 오리진 목록. Python 기본값과 같다. */
    val corsOrigins: List<String> = listOf("http://localhost:5173"),
    val auth: AuthProperties = AuthProperties(),
    val crypto: CryptoProperties = CryptoProperties(),
) {
    /** 인증 설정. `jwtSecret` 미설정 시 인증 API를 쓸 수 없다(앱 기동 자체는 가능). */
    data class AuthProperties(
        val jwtSecret: Secret = Secret.EMPTY,
        val jwtExpireMinutes: Long = 60,
    )

    /** 문서 본문 암호화(Fernet) 키. 미설정 시 문서 저장 불가. */
    data class CryptoProperties(val fernetKey: Secret = Secret.EMPTY)

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
