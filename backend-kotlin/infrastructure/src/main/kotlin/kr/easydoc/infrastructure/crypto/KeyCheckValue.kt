package kr.easydoc.infrastructure.crypto

import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * **키 검사값(KCV)** — 키를 드러내지 않고 "같은 키인가"만 대조하는 지문.
 *
 * ## 왜 필요한가 (게이트 25 X6 / privacy-gate F-3 「가장 위험」)
 *
 * 종전 조립은 키 재료가 **base64 32바이트이기만 하면** 아무 경고 없이 실었다. 그래서
 * *값이 틀린* 32바이트 키(옛 세대 키를 새 세대 자리에 붙여넣기, 스테이징 키를 운영에
 * 넣기, 회전 중 두 세대를 뒤바꾸기)는 기동에서도 쓰기에서도 조용하다. **틀린 키로 쓴
 * 행은 읽는 순간에야 드러나고, 그 사이에 쓴 문서는 되돌릴 수 없다.**
 *
 * KCV 는 그 침묵을 없앤다. 설정에 세대별 검사값을 함께 적고 기동 시 대조하면, 값이 다른
 * 키는 **한 건도 쓰기 전에** 기동 실패로 드러난다.
 *
 * ## 정의
 *
 * ```
 * KCV(key) = hex( AES-256-GCM(key, nonce = 0×12, aad = "easydoc-kcv-v1", plaintext = "")[0..5] )
 * ```
 *
 * 즉 **고정 입력에 대한 인증 태그의 앞 6바이트**를 소문자 16진수 12글자로 적는다.
 *
 * ## 고정 nonce 를 쓰는데 왜 안전한가
 *
 * AES-GCM 의 nonce 재사용 금지는 **같은 키로 서로 다른 평문을 암호화할 때** 두 평문의
 * XOR 이 드러나는 것을 막는 규칙이다. 여기서는 평문이 **비어 있고 언제나 같으며**, 산출물을
 * 암호문으로 저장하지도 전송하지도 않는다 — 드러날 다른 평문이 없다. 저장 암호화
 * ([AesGcmContentCipher])는 이것과 무관하게 매 호출 새 nonce 를 뽑는다.
 *
 * ## 왜 검사값을 설정에 적어도 되는가
 *
 * 키 검사값은 결제·HSM 관행에서 **공개 가능한 값**으로 쓰인다. 256비트 키에 대한 태그
 * 6바이트에서 키를 되찾으려면 키 공간 전수 탐색이 필요하고, 그 6바이트는 키의 어떤
 * 비트도 직접 담고 있지 않다. 그래서 이 값은 `.env` 가 아니라 **설정 파일·배포 매니페스트에
 * 적어도 되는 축**이고, 그래야 배포 파이프라인이 대조할 수 있다.
 *
 * 그럼에도 이 값을 **키와 같은 자리에 적지 않는다** — 검사값과 키가 같은 비밀 저장소에서
 * 함께 오면 둘 다 같은 사고로 바뀌고, 그러면 대조가 아무것도 확인하지 않는다.
 */
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
