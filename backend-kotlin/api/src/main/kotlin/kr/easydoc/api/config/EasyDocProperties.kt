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
    val llm: LlmProperties = LlmProperties(),
) {
    /** 인증 설정. `jwtSecret` 미설정 시 인증 API를 쓸 수 없다(앱 기동 자체는 가능). */
    data class AuthProperties(
        val jwtSecret: Secret = Secret.EMPTY,
        val jwtExpireMinutes: Long = 60,
    )

    /** 문서 본문 암호화(Fernet) 키. 미설정 시 문서 저장 불가. */
    data class CryptoProperties(val fernetKey: Secret = Secret.EMPTY)

    /**
     * LLM 벤더 설정.
     *
     * `provider` 기본값이 anthropic 인 것은 선택이 아니라 미확정 상태를 그대로 둔 것이다 —
     * 벤더는 골든셋 벤치마크로 확정한다(master-plan 3.1). Python 기본값을 그대로 옮겼다.
     */
    data class LlmProperties(
        val provider: String = "anthropic",
        /** null 이면 provider 구현체의 기본 모델을 쓴다. */
        val model: String? = null,
        /** Anthropic 전용 사고 깊이. null 이면 파라미터를 보내지 않는다(API 기본값). */
        val effort: String? = null,
        val anthropicApiKey: Secret = Secret.EMPTY,
        val openaiApiKey: Secret = Secret.EMPTY,
    )
}
