package kr.easydoc.infrastructure.auth

import kr.easydoc.core.exceptions.ConfigurationException
import kr.easydoc.core.exceptions.InvalidCredentialsException
import kr.easydoc.core.security.Secret
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * **HS256 토큰의 발급·검증** (`migration-safety-gate` I-9, 계약 `x-auth`).
 *
 * ## 만료 경계를 시계로 잰다
 *
 * `Clock` 을 주입해 `exp` 직전·직후를 결정적으로 만든다. `Thread.sleep` 으로 재면 CI 부하에
 * 따라 결과가 흔들리고, 흔들리는 테스트는 곧 꺼진다.
 *
 * **허용 오차 0 이 이 파일의 요점이다.** Nimbus 의 `DefaultJWTClaimsVerifier` 와 Spring 의
 * `JwtTimestampValidator` 는 기본 60초를 허용한다. 그 검증기를 쓰는 구현으로 바꾸면
 * `만료 직후 1초` 케이스가 통과해 버리고, 이 테스트가 그것을 잡는 유일한 자리다.
 */
class JwtAccessTokensTest {
    @Test
    @DisplayName("발급한 토큰을 검증하면 sub 가 돌아온다")
    fun `발급과 검증이 맞물린다`() {
        val userId = UUID.randomUUID()
        val tokens = tokens()

        assertThat(tokens.verify(tokens.issue(userId).token)).isEqualTo(userId)
    }

    @Test
    @DisplayName("expires_in 이 설정한 유효 기간의 초 단위 값이다")
    fun `유효 기간이 초로 나간다`() {
        assertThat(tokens(lifetime = Duration.ofMinutes(LIFETIME_MINUTES)).issue(UUID.randomUUID()).expiresInSeconds)
            .isEqualTo(LIFETIME_MINUTES * 60)
    }

    @Test
    @DisplayName("페이로드에 계약이 정한 세 클레임만 있다 — 이메일 등 개인정보 0")
    fun `클레임이 셋뿐이다`() {
        val token = tokens().issue(UUID.randomUUID()).token

        assertThat(claimsOf(token).keys).containsExactlyInAnyOrder("sub", "exp", "typ")
    }

    // ---------------------------------------------------------------- 만료 (skew 0)

    @Test
    @DisplayName("exp 직전 1초에는 통과한다")
    fun `만료 직전에는 통과한다`() {
        val issuedAt = Instant.parse("2026-08-19T00:00:00Z")
        val token = tokens(clock = fixed(issuedAt)).issue(UUID.randomUUID()).token

        val justBefore = tokens(clock = fixed(issuedAt.plus(LIFETIME).minusSeconds(1)))

        assertThat(justBefore.verify(token)).isNotNull()
    }

    @Test
    @DisplayName("exp 를 1초 지나면 거부한다 — 허용 오차 0")
    fun `만료 직후에는 거부한다`() {
        val issuedAt = Instant.parse("2026-08-19T00:00:00Z")
        val token = tokens(clock = fixed(issuedAt)).issue(UUID.randomUUID()).token

        // Nimbus·Spring 기본값(60초)을 그대로 쓰면 여기서 통과해 버린다.
        val justAfter = tokens(clock = fixed(issuedAt.plus(LIFETIME).plusSeconds(1)))

        assertThatThrownBy { justAfter.verify(token) }.isInstanceOf(InvalidCredentialsException::class.java)
    }

    @Test
    @DisplayName("exp 와 정확히 같은 시각도 거부한다 — 「exp 이전」이 조건이다")
    fun `만료 시각 정각은 거부한다`() {
        val issuedAt = Instant.parse("2026-08-19T00:00:00Z")
        val token = tokens(clock = fixed(issuedAt)).issue(UUID.randomUUID()).token

        val atExpiry = tokens(clock = fixed(issuedAt.plus(LIFETIME)))

        assertThatThrownBy { atExpiry.verify(token) }.isInstanceOf(InvalidCredentialsException::class.java)
    }

    // ---------------------------------------------------------------- 위조

    @Test
    @DisplayName("다른 키로 서명된 토큰을 거부한다")
    fun `다른 키를 거부한다`() {
        val token = tokens(secret = OTHER_SECRET).issue(UUID.randomUUID()).token

        assertThatThrownBy { tokens().verify(token) }.isInstanceOf(InvalidCredentialsException::class.java)
    }

    @Test
    @DisplayName("alg: none 토큰을 거부한다 — 알고리즘 혼동")
    fun `알고리즘 none 을 거부한다`() {
        val unsigned = "${encode("""{"alg":"none"}""")}.${encode(validClaimsJson())}."

        assertThatThrownBy { tokens().verify(unsigned) }.isInstanceOf(InvalidCredentialsException::class.java)
    }

    @Test
    @DisplayName("HS256 이 아닌 alg 헤더를 서명 검증 전에 거부한다")
    fun `다른 알고리즘 헤더를 거부한다`() {
        // 헤더만 HS512 로 바꾸고 서명은 HS256 키로 만든다 — 알고리즘을 안 보면 통과할 수 있다.
        val forged = signWith(SECRET, """{"alg":"HS512"}""", validClaimsJson())

        assertThatThrownBy { tokens().verify(forged) }.isInstanceOf(InvalidCredentialsException::class.java)
    }

    @Test
    @DisplayName("exp · typ · sub 를 하나씩 뺀 토큰을 전부 거부한다")
    fun `필수 클레임이 빠지면 거부한다`() {
        val now = Instant.parse("2026-08-19T00:00:00Z")
        val exp = now.plus(LIFETIME).epochSecond
        val subject = UUID.randomUUID()
        val cases =
            mapOf(
                "exp 없음" to """{"sub":"$subject","typ":"access"}""",
                "typ 없음" to """{"sub":"$subject","exp":$exp}""",
                "sub 없음" to """{"typ":"access","exp":$exp}""",
            )

        cases.forEach { (label, claims) ->
            assertThatThrownBy { tokens(clock = fixed(now)).verify(signWith(SECRET, HS256_HEADER, claims)) }
                .withFailMessage("%s 토큰이 거부되지 않았다", label)
                .isInstanceOf(InvalidCredentialsException::class.java)
        }
    }

    @Test
    @DisplayName("typ 가 access 가 아니거나 sub 가 UUID 가 아니면 거부한다")
    fun `용도와 주체 형식을 검사한다`() {
        val now = Instant.parse("2026-08-19T00:00:00Z")
        val exp = now.plus(LIFETIME).epochSecond

        listOf(
            """{"sub":"${UUID.randomUUID()}","typ":"refresh","exp":$exp}""",
            """{"sub":"not-a-uuid","typ":"access","exp":$exp}""",
        ).forEach { claims ->
            assertThatThrownBy { tokens(clock = fixed(now)).verify(signWith(SECRET, HS256_HEADER, claims)) }
                .isInstanceOf(InvalidCredentialsException::class.java)
        }
    }

    @Test
    @DisplayName("JWT 가 아닌 문자열을 거부한다")
    fun `형식이 아닌 값을 거부한다`() {
        assertThatThrownBy { tokens().verify("이건 토큰이 아니다") }.isInstanceOf(InvalidCredentialsException::class.java)
    }

    // ---------------------------------------------------------------- 설정

    @Test
    @DisplayName("서명 키가 없거나 계약 하한 미만이면 발급·검증·설정 확인이 전부 ConfigurationException")
    fun `짧은 키는 설정 오류다`() {
        // 경고가 아니라 오류로 끊는다 — 짧은 키로 조용히 도는 배포는 위조 가능한 세션을 발급한다.
        listOf(Secret.EMPTY, Secret("짧음")).forEach { weak ->
            val weakTokens = tokens(secret = weak)
            assertThatThrownBy { weakTokens.ensureConfigured() }.isInstanceOf(ConfigurationException::class.java)
            assertThatThrownBy { weakTokens.issue(UUID.randomUUID()) }.isInstanceOf(ConfigurationException::class.java)
            assertThatThrownBy { weakTokens.verify("a.b.c") }.isInstanceOf(ConfigurationException::class.java)
        }
    }

    @Test
    @DisplayName("설정 오류 메시지에 서명 키가 실리지 않는다")
    fun `설정 오류가 키를 노출하지 않는다`() {
        val weak = "짧은키표식"

        val failure = runCatching { tokens(secret = Secret(weak)).ensureConfigured() }.exceptionOrNull()

        assertThat(failure?.message).isNotNull().doesNotContain(weak)
    }

    // ---------------------------------------------------------------- 도구

    private fun tokens(
        secret: Secret = Secret(SECRET),
        lifetime: Duration = LIFETIME,
        clock: Clock = Clock.systemUTC(),
    ) = JwtAccessTokens(secret, lifetime, MIN_SECRET_BYTES, clock)

    private fun fixed(instant: Instant): Clock = Clock.fixed(instant, ZoneOffset.UTC)

    private fun validClaimsJson(): String =
        """{"sub":"${UUID.randomUUID()}","typ":"access","exp":${Instant.now().plus(LIFETIME).epochSecond}}"""

    private fun claimsOf(token: String): Map<*, *> =
        ObjectMapper().readValue(
            String(Base64.getUrlDecoder().decode(token.split('.')[1]), Charsets.UTF_8),
            Map::class.java,
        )

    private fun signWith(
        secret: String,
        headerJson: String,
        claimsJson: String,
    ): String {
        val signingInput = "${encode(headerJson)}.${encode(claimsJson)}"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return "$signingInput.${Base64.getUrlEncoder().withoutPadding().encodeToString(
            mac.doFinal(signingInput.toByteArray()),
        )}"
    }

    private fun encode(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))

    private companion object {
        const val SECRET = "test-only-signing-key-0123456789-abcdef"
        val OTHER_SECRET = Secret("another-test-only-signing-key-0123456789")
        const val MIN_SECRET_BYTES = 32
        const val LIFETIME_MINUTES = 60L
        val LIFETIME: Duration = Duration.ofMinutes(LIFETIME_MINUTES)
        const val HS256_HEADER = """{"alg":"HS256"}"""
    }
}
