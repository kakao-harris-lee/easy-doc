package kr.easydoc.infrastructure.crypto

import kr.easydoc.application.crypto.ContentCipher
import kr.easydoc.core.crypto.EncryptedContent
import kr.easydoc.core.crypto.EncryptedField
import kr.easydoc.core.crypto.EncryptionScheme
import kr.easydoc.core.crypto.PlainBody
import kr.easydoc.core.exceptions.ConfigurationException
import kr.easydoc.core.exceptions.DecryptionFailedException
import kr.easydoc.core.security.Secret
import org.slf4j.LoggerFactory
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 저장 암호화 어댑터 — **AES-256-GCM (JCA 표준)**. `migration-safety-gate` I-7 의 구현체다.
 *
 * ## 왜 Fernet 이 아닌가
 *
 * 이 자리는 원래 Python `cryptography` 의 Fernet 토큰을 JVM 에서 재현하는 일이었고,
 * 그러려면 `0x80` 버전 바이트 · 타임스탬프 · AES-128-CBC · HMAC-SHA256 을 **손으로
 * 조립**해야 했다(Phase 0 spike §1.2 의 선택지 1-b). 롤백을 포기하면서 읽어야 할 옛
 * 암호문이 사라졌고(master-plan 6.2 · 계획 §4.3 2차 개정), I-7 검증 6 이 요구하는
 * *"JDK/검증된 라이브러리의 표준 AEAD"* 를 그대로 고를 자유가 생겼다.
 *
 * 그래서 프리미티브를 조립하지 않는다. `AES/GCM/NoPadding` 한 줄이 nonce·태그·무결성을
 * 함께 다룬다 — CTR/CBC 로 조립하고 태그를 따로 붙이던 구현이 무결성 검사를 빠뜨려
 * "인증 없는 암호화"가 되는 사고가 I-7 검증 2 가 지목한 실제 사례다.
 *
 * ## 저장 형식
 *
 * ```
 * bytea = nonce(12바이트) || AES-256-GCM 출력(암호문 || 태그 16바이트)
 * ```
 *
 * 방식 이름과 키 세대는 바이트에 넣지 않는다 — `encryption_scheme`·`key_version` **컬럼**이
 * 이미 들고 있고, 그 둘이 아래 associated data 에 실려 결속되므로 컬럼을 고쳐도 열리지
 * 않는다. 같은 사실을 두 곳에 적으면 어긋날 자리가 생긴다.
 *
 * ## associated data — 무엇을 결속하는가
 *
 * ```
 * "easydoc-aead|{scheme}|{keyVersion}|{테이블.컬럼}|{행 UUID}"  (UTF-8)
 * ```
 *
 * 네 값 어느 하나라도 복호화 시점에 다르면 태그 검증이 실패한다. 구분자 `|` 가 모호하지
 * 않은 이유: scheme 은 CHECK 제약이 좁힌 소문자·숫자·하이픈이고, keyVersion 은 숫자,
 * 컬럼 이름은 [EncryptedField] 의 고정 상수, UUID 는 정규 표기라 **어느 조각에도 `|` 가
 * 들어갈 수 없다.** 그래서 서로 다른 네 값이 같은 문자열을 만들 수 없다.
 *
 * 무엇을 막는지는 [EncryptedField] KDoc 에 두 갈래로 적어 두었다(같은 행의 다른 컬럼 /
 * 다른 행의 같은 컬럼).
 *
 * ## nonce (I-7 검증 4 — 신규 항목)
 *
 * **매 암호화마다 [SecureRandom] 으로 96비트를 새로 뽑는다.** AES-GCM 은 같은 키로 nonce 가
 * 겹치면 두 평문의 XOR 이 복원되고 인증 키까지 위태로워진다. Fernet 시절에는 라이브러리가
 * IV 를 다뤄 이 검사가 없었다 — 직접 AEAD 를 쓰기 때문에 생긴 항목이다.
 *
 * 96비트를 쓰는 이유: GCM 이 그 길이만 IV 로 **그대로** 쓰고, 다른 길이는 GHASH 로 접어
 * 넣어 충돌 여지를 만든다. 난수원을 생성자로 여는 이유는 하나뿐이다 — **회귀 테스트가
 * nonce 를 고정해 이 방어가 실제로 작동하는지 음성 대조**할 수 있어야 한다.
 *
 * ## 실패를 구분하지 않는다 (I-7 검증 3)
 *
 * 복호화 실패는 전부 [DecryptionFailedException] 하나이고 메시지도 고정이다. 원인 예외를
 * `cause` 로 잇지 않는다 — 트레이스백에 어느 단계에서 깨졌는지가 남고, 그것이 로그로 나가면
 * 복호화 oracle 이 된다. `JwtAccessTokens` 의 「실패를 구분하지 않는다」 절과 같은 규율이다.
 *
 * ## 로그
 *
 * 평문·암호문·키 재료는 **한 조각도 로그에 넣지 않는다.** 기동 시 남기는 것은 적재한 키
 * **세대 수**와 쓰기 세대 번호뿐이다.
 */
class AesGcmContentCipher(
    keyMaterial: Map<Int, Secret>,
    override val writeKeyVersion: Int,
    private val random: SecureRandom = SecureRandom(),
) : ContentCipher {
    private val logger = LoggerFactory.getLogger(AesGcmContentCipher::class.java)

    override val writeScheme: String = EncryptionScheme.AES_256_GCM_V1

    /**
     * 세대 → 키. 못 읽는 재료는 **조용히 빼고 경고만 남긴다.**
     *
     * 여기서 기동을 끊지 않는 이유는 `AuthProperties` 「기동은 막지 않는다」와 같다 —
     * 설정이 비거나 잘못돼도 앱은 뜨고 `/health` 로 배포 상태를 진단할 수 있어야 하며,
     * 그 값이 필요한 요청만 거절된다(암호화는 503, 복호화는 아래 단일 예외).
     */
    private val keys: Map<Int, SecretKey> =
        keyMaterial
            .mapNotNull { (version, material) -> keyOf(version, material) }
            .toMap()

    init {
        logger.info("저장 암호화 키 {}세대를 적재했다. 쓰기 세대=v{}", keys.size, writeKeyVersion)
    }

    override fun encrypt(
        plain: PlainBody,
        record: UUID,
        field: EncryptedField,
    ): EncryptedContent {
        val key = keys[writeKeyVersion] ?: throw ConfigurationException(MISSING_WRITE_KEY_MESSAGE)
        val nonce = ByteArray(NONCE_BYTES)
        random.nextBytes(nonce)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(associatedData(writeScheme, writeKeyVersion, record, field))
        val sealed = cipher.doFinal(plain.value.toByteArray(Charsets.UTF_8))
        return EncryptedContent(nonce + sealed, writeScheme, writeKeyVersion)
    }

    override fun decrypt(
        content: EncryptedContent,
        record: UUID,
        field: EncryptedField,
    ): PlainBody {
        // 아래 세 갈래(모르는 방식 · 없는 키 세대 · 길이 미달)와 태그 검증 실패가 **같은
        // 예외**여야 한다. 하나라도 다른 신호를 내면 그것이 oracle 이다.
        if (content.scheme != EncryptionScheme.AES_256_GCM_V1) throw DecryptionFailedException()
        val key = keys[content.keyVersion] ?: throw DecryptionFailedException()
        if (content.bytes.size < NONCE_BYTES + TAG_BYTES) throw DecryptionFailedException()
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                key,
                GCMParameterSpec(TAG_BITS, content.bytes, 0, NONCE_BYTES),
            )
            cipher.updateAAD(associatedData(content.scheme, content.keyVersion, record, field))
            val opened = cipher.doFinal(content.bytes, NONCE_BYTES, content.bytes.size - NONCE_BYTES)
            PlainBody(String(opened, Charsets.UTF_8))
        } catch (ignored: GeneralSecurityException) {
            // 원인을 잇지 않는다(I-7 검증 3). 어느 단계에서 깨졌는지가 남으면 oracle 이다.
            throw DecryptionFailedException()
        }
    }

    /** base64 32바이트만 키로 받는다. 아니면 경고 한 줄을 남기고 그 세대를 뺀다. */
    private fun keyOf(
        version: Int,
        material: Secret,
    ): Pair<Int, SecretKey>? {
        val decoded = decodeBase64(material.reveal())
        // 경고에 값을 싣지 않는다 — 잘못된 키도 키다. 남기는 것은 세대 번호와 기대 길이뿐이다.
        return when {
            // **미설정과 오설정을 가른다.** `application.yml` 이 환경변수 자리표시자로 세대를
            // 미리 적어 두므로(`${EASYDOC_ENCRYPTION_KEY_V1:}`), 개발 기동마다 빈 값이 들어온다.
            // 그것을 경고로 찍으면 진짜 오설정 경고가 소음에 묻힌다. 「몇 세대를 적재했는가」는
            // 아래 생성자 로그가 이미 말한다.
            material.isBlank() -> {
                null
            }

            decoded == null -> {
                logger.warn("저장 암호화 키 v{} 가 base64 가 아니다. 이 세대를 쓰지 않는다.", version)
                null
            }

            decoded.size != KEY_BYTES -> {
                logger.warn(
                    "저장 암호화 키 v{} 의 길이가 {}바이트가 아니다(실제 {}). 이 세대를 쓰지 않는다.",
                    version,
                    KEY_BYTES,
                    decoded.size,
                )
                null
            }

            else -> {
                version to SecretKeySpec(decoded, KEY_ALGORITHM)
            }
        }
    }

    private fun decodeBase64(value: String): ByteArray? =
        try {
            Base64.getDecoder().decode(value)
        } catch (ignored: IllegalArgumentException) {
            null
        }

    private companion object {
        /** JDK 표준 AEAD. 프리미티브를 손으로 조립하지 않는다(I-7 검증 6). */
        const val TRANSFORMATION = "AES/GCM/NoPadding"

        const val KEY_ALGORITHM = "AES"

        /** AES-256. */
        const val KEY_BYTES = 32

        /** GCM 이 접지 않고 그대로 쓰는 유일한 IV 길이. */
        const val NONCE_BYTES = 12

        /** 태그 128비트 — GCM 이 허용하는 최대이자 표준 권고값. */
        const val TAG_BITS = 128

        const val TAG_BYTES = TAG_BITS / 8

        /** associated data 의 고정 머리. 다른 용도의 AEAD 가 생겨도 서로 섞이지 않는다. */
        const val AAD_PREFIX = "easydoc-aead"

        const val MISSING_WRITE_KEY_MESSAGE = "문서 암호화 키가 설정되어 있지 않습니다"

        /**
         * 암호문을 **행·컬럼·방식·키 세대**에 묶는다. 형식과 모호하지 않은 이유는 클래스 KDoc.
         *
         * 사용자 콘텐츠가 한 조각도 들어가지 않는다 — associated data 는 암호화되지 않고
         * 평문으로 다뤄지는 값이다.
         */
        fun associatedData(
            scheme: String,
            keyVersion: Int,
            record: UUID,
            field: EncryptedField,
        ): ByteArray = "$AAD_PREFIX|$scheme|$keyVersion|${field.wireName}|$record".toByteArray(Charsets.UTF_8)
    }
}
