package kr.easydoc.core.security

import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * HMAC-SHA256 한 곳 — 키는 [Secret] 로만 받아 `reveal()` 호출 지점을 여기 하나로 모은다.
 * `core` 는 Spring·DB 없이 JDK `javax.crypto` 만 쓴다.
 */
object HmacSha256 {
    private const val ALGORITHM = "HmacSHA256"

    /** 키·메시지 모두 UTF-8. `Mac` 은 스레드 안전하지 않으므로 호출마다 새로 만든다. */
    fun sign(
        key: Secret,
        message: String,
    ): ByteArray {
        val mac = Mac.getInstance(ALGORITHM)
        mac.init(SecretKeySpec(key.reveal().toByteArray(Charsets.UTF_8), ALGORITHM))
        return mac.doFinal(message.toByteArray(Charsets.UTF_8))
    }

    /** 소문자 16진 64자. */
    fun hex(
        key: Secret,
        message: String,
    ): String = sign(key, message).joinToString(separator = "") { byte -> "%02x".format(byte) }

    /** 표준 Base64. */
    fun base64(
        key: Secret,
        message: String,
    ): String = Base64.getEncoder().encodeToString(sign(key, message))
}
