package kr.easydoc.infrastructure.crypto

import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** **키 검사값(KCV)** — 키를 드러내지 않고 "같은 키인가"만 대조하는 지문. */
object KeyCheckValue {
    /** 검사값의 16진수 길이(= 6바이트). */
    const val HEX_LENGTH: Int = 12

    /** 이 검사값의 용도 도메인. 다른 용도의 AEAD 산출물과 우연히 같아지지 않게 한다. */
    private const val DOMAIN = "easydoc-kcv-v1"

    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    private const val TAG_BITS = 128

    private const val NONCE_BYTES = 12

    private const val KCV_BYTES = HEX_LENGTH / 2

    /** [key] 의 검사값. 같은 키는 언제나 같은 값을, 다른 키는 사실상 언제나 다른 값을 낸다. */
    fun of(key: SecretKey): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, ByteArray(NONCE_BYTES)))
        cipher.updateAAD(DOMAIN.toByteArray(Charsets.UTF_8))
        val tag = cipher.doFinal(ByteArray(0))
        return tag.take(KCV_BYTES).joinToString("") { byte -> "%02x".format(byte) }
    }

    /** 설정에 적힌 값과 계산값이 같은가. 대소문자·앞뒤 공백은 무시한다. */
    fun matches(
        configured: String,
        computed: String,
    ): Boolean = configured.trim().lowercase() == computed
}
