package kr.easydoc.infrastructure.auth

import com.nimbusds.jose.JOSEException
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.MACSigner
import com.nimbusds.jose.crypto.MACVerifier
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import kr.easydoc.application.auth.AccessTokens
import kr.easydoc.application.auth.IssuedAccessToken
import kr.easydoc.core.exceptions.ConfigurationException
import kr.easydoc.core.exceptions.InvalidCredentialsException
import kr.easydoc.core.security.Secret
import java.text.ParseException
import java.time.Clock
import java.time.Duration
import java.util.Date
import java.util.UUID

/**
 * HS256 액세스 토큰 발급·검증 — `migration-safety-gate` I-9 와 계약 `x-auth` 의 구현.
 *
 * ## 클레임은 셋뿐이다
 *
 * `sub`(사용자 UUID) · `exp` · `typ`. 이메일 등 개인정보를 **넣지 않는다**
 * (`x-change-policy.invariants`). JWT 는 서명될 뿐 암호화되지 않아 누구나 읽을 수 있고,
 * 토큰은 브라우저 저장소·프록시 로그·오류 리포트를 거쳐 다닌다.
 *
 * ## clock skew 0 (필수조치 B)
 *
 * Nimbus `DefaultJWTClaimsVerifier` 의 기본 허용 오차는 **60초**이고 Spring Security
 * `JwtTimestampValidator` 도 같다. 그대로 두면 만료된 토큰이 **+59초까지 통과한다.**
 * 그래서 그 검증기들을 **쓰지 않고** 만료를 직접 판정한다 — `exp` 를 1초라도 지나면 401.
 * `exp` 없는 토큰을 금지한 조항과 같은 목적이다(만료가 강제되지 않으면 클레임을 요구한
 * 의미가 없다).
 *
 * ## 알고리즘 혼동 방어
 *
 * 서명을 검증하기 **전에** 헤더의 `alg` 가 HS256 인지 본다. `alg: none` 이나 HS256 키를
 * RS256 공개키처럼 쓰게 만드는 위조는 JVM JWT 라이브러리에서 반복적으로 나온 취약점
 * 유형이다(I-9 검증 1). [MACVerifier] 자신도 알고리즘을 확인하지만, **우리가 기대하는
 * 알고리즘을 명시적으로 못박는 자리**를 코드에 남긴다 — 검증기를 바꾸는 날 이 조건이
 * 함께 사라지지 않게 하기 위해서다.
 *
 * ## 실패를 구분하지 않는다
 *
 * 만료·위조·클레임 누락·용도 불일치가 전부 같은 [InvalidCredentialsException] 이다
 * (계약 `x-auth.failure_uniformity`). 예외에 원인을 담지 않고 원인 체인도 잇지 않는다 —
 * 스택 트레이스에 토큰 조각이 실릴 수 있다.
 *
 * 서명 키 문제만 [ConfigurationException] 으로 갈라 나간다(→ 503). 호출자 잘못이 아니라
 * 운영 설정 문제이고, 401 로 감추면 배포 사고가 "사용자가 토큰을 잘못 냈다"로 둔갑한다.
 */
class JwtAccessTokens(
    private val secret: Secret,
    private val lifetime: Duration,
    private val minSecretBytes: Int,
    private val clock: Clock,
) : AccessTokens {
    init {
        require(!lifetime.isZero && !lifetime.isNegative) { "액세스 토큰 유효 기간은 0보다 커야 합니다" }
        require(minSecretBytes > 0) { "서명 키 최소 길이는 1 이상이어야 합니다" }
    }

    override fun ensureConfigured() {
        signingKey()
    }

    override fun issue(userId: UUID): IssuedAccessToken {
        val key = signingKey()
        val issuedAt = clock.instant()
        val claims =
            JWTClaimsSet
                .Builder()
                .subject(userId.toString())
                .claim(TYP_CLAIM, ACCESS_TYP)
                // Nimbus 는 Date 를 초 단위로 직렬화한다. expires_in 과 같은 축이 되도록
                // 초 단위 Duration 만 받는다(위 require 와 설정의 분 단위 표기가 그 보장이다).
                .expirationTime(Date.from(issuedAt.plus(lifetime)))
                .build()

        val jwt = SignedJWT(JWSHeader(JWSAlgorithm.HS256), claims)
        try {
            jwt.sign(MACSigner(key))
        } catch (failure: JOSEException) {
            // 여기까지 오는 것은 키 길이 문제뿐이고 위에서 이미 걸렀다. 그래도 남는
            // 경로에서 예외 메시지가 새지 않게 고정 문구로 바꾼다.
            throw ConfigurationException(AUTH_NOT_CONFIGURED_MESSAGE).also { it.addSuppressed(failure) }
        }
        return IssuedAccessToken(jwt.serialize(), lifetime.toSeconds())
    }

    override fun verify(token: String): UUID {
        val key = signingKey()

        val jwt =
            try {
                SignedJWT.parse(token)
            } catch (_: ParseException) {
                throw invalidCredentials()
            }

        // ① 알고리즘 고정 — 서명 검증보다 먼저.
        if (jwt.header.algorithm != JWSAlgorithm.HS256) {
            throw invalidCredentials()
        }

        // ② 서명.
        val signatureValid =
            try {
                jwt.verify(MACVerifier(key))
            } catch (_: JOSEException) {
                false
            }
        if (!signatureValid) {
            throw invalidCredentials()
        }

        val claims =
            try {
                jwt.jwtClaimsSet
            } catch (_: ParseException) {
                throw invalidCredentials()
            }

        // ③ 만료 — 허용 오차 0. `exp` 가 없으면 영구 자격증명이 되므로 부재도 거부다.
        val expiresAt = claims.expirationTime ?: throw invalidCredentials()
        if (!clock.instant().isBefore(expiresAt.toInstant())) {
            throw invalidCredentials()
        }

        // ④ 용도.
        if (claims.getStringClaim(TYP_CLAIM) != ACCESS_TYP) {
            throw invalidCredentials()
        }

        // ⑤ 주체 — UUID 가 아니면 우리가 발급한 토큰이 아니다.
        val subject = claims.subject ?: throw invalidCredentials()
        return try {
            UUID.fromString(subject)
        } catch (_: IllegalArgumentException) {
            throw invalidCredentials()
        }
    }

    /**
     * 서명 키 바이트. 미설정이거나 [minSecretBytes] 미만이면 [ConfigurationException].
     *
     * **경고가 아니라 설정 오류로 끊는다**(I-9 검증 3). HS256 의 보안 강도는 키 길이에
     * 직접 걸려 있어, 짧은 키로 조용히 도는 배포는 위조 가능한 세션을 발급한다.
     * 기동은 막지 않는다 — 계약이 `/health` 로 배포 상태를 진단할 수 있어야 한다고 정했다.
     */
    private fun signingKey(): ByteArray {
        if (secret.isBlank()) {
            throw ConfigurationException(AUTH_NOT_CONFIGURED_MESSAGE)
        }
        val bytes = secret.reveal().toByteArray(Charsets.UTF_8)
        if (bytes.size < minSecretBytes) {
            // 길이도 값도 메시지에 넣지 않는다 — 이 문구가 그대로 응답 detail 이 된다.
            throw ConfigurationException(AUTH_NOT_CONFIGURED_MESSAGE)
        }
        return bytes
    }

    private fun invalidCredentials() = InvalidCredentialsException(INVALID_CREDENTIALS_MESSAGE)

    private companion object {
        const val TYP_CLAIM = "typ"
        const val ACCESS_TYP = "access"

        /** 계약 `components/responses/ServiceUnavailable` 의 `no_jwt` 예시와 같은 값. */
        const val AUTH_NOT_CONFIGURED_MESSAGE = "인증이 설정되지 않았습니다"

        /** 계약 `components/responses/Unauthorized` 의 `invalid_token` 예시와 같은 값. */
        const val INVALID_CREDENTIALS_MESSAGE = "이메일 또는 비밀번호가 올바르지 않습니다"
    }
}
