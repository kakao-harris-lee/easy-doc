package kr.easydoc.api.support

import tools.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 테스트가 토큰을 **직접 만들고 읽는** 도구.
 *
 * ## 왜 제품이 쓰는 라이브러리를 쓰지 않는가
 *
 * 검증기와 위조기가 같은 라이브러리를 쓰면 그 라이브러리의 해석이 곧 기준이 된다 —
 * 예를 들어 두 쪽 모두 `exp` 를 같은 방식으로 반올림하면 만료 경계가 어긋나 있어도
 * 서로 맞는다. 여기서는 JDK 의 `Mac`·`Base64` 만 써서 **바이트로** 토큰을 조립하고,
 * 그것을 제품 코드(Nimbus 경유)가 어떻게 판정하는지를 잰다.
 *
 * 또 한 가지 — `nimbus-jose-jwt` 는 `infrastructure` 의 `implementation` 의존이라
 * `api` 테스트의 컴파일 클래스패스에 올라오지 않는다. 어댑터 격리가 이 자리에서도
 * 지켜지고 있다는 뜻이고, 그것을 우회하려고 의존성을 늘리지 않는다.
 */
object TestJwt {
    private val encoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder: Base64.Decoder = Base64.getUrlDecoder()
    private val json = ObjectMapper()

    /** `{alg, typ?}` 헤더와 임의의 페이로드로 HS256 토큰을 만든다. */
    fun signHs256(
        secret: String,
        header: Map<String, Any?>,
        payload: Map<String, Any?>,
    ): String {
        val signingInput = "${encodeJson(header)}.${encodeJson(payload)}"
        return "$signingInput.${encoder.encodeToString(hmacSha256(secret, signingInput))}"
    }

    /** 서명만 망가뜨린다 — 헤더·페이로드는 그대로 두어 "서명 위조"만 재게 한다. */
    fun withBrokenSignature(token: String): String {
        val parts = token.split('.')
        require(parts.size == 3) { "JWT 가 세 조각이 아니다" }
        val flipped = decoder.decode(parts[2]).also { it[0] = (it[0].toInt() xor 0x01).toByte() }
        return "${parts[0]}.${parts[1]}.${encoder.encodeToString(flipped)}"
    }

    /** 헤더 클레임을 읽는다. 서명은 확인하지 않는다 — 읽기 전용 도구다. */
    fun header(token: String): Map<*, *> = segment(token, 0)

    /** 페이로드 클레임을 읽는다. */
    fun payload(token: String): Map<*, *> = segment(token, 1)

    private fun segment(
        token: String,
        index: Int,
    ): Map<*, *> {
        val parts = token.split('.')
        require(parts.size == 3) { "JWT 가 세 조각이 아니다" }
        return json.readValue(String(decoder.decode(parts[index]), StandardCharsets.UTF_8), Map::class.java)
    }

    private fun encodeJson(value: Map<String, Any?>): String =
        encoder.encodeToString(json.writeValueAsString(value).toByteArray(StandardCharsets.UTF_8))

    private fun hmacSha256(
        secret: String,
        signingInput: String,
    ): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(signingInput.toByteArray(StandardCharsets.UTF_8))
    }
}
