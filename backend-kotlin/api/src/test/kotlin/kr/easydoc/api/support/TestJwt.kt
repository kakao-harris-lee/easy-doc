package kr.easydoc.api.support

import tools.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** 테스트가 토큰을 직접 만들고 읽는 도구. */
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
