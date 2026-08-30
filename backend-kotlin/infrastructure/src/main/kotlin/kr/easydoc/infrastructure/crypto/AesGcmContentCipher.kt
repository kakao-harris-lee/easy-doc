package kr.easydoc.infrastructure.crypto

import kr.easydoc.application.crypto.ContentCipher
import kr.easydoc.core.crypto.EncryptedContent
import kr.easydoc.core.crypto.EncryptedField
import kr.easydoc.core.crypto.EncryptionScheme
import kr.easydoc.core.crypto.PlainBytes
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

/** 저장 암호화 어댑터 — **AES-256-GCM (JCA 표준)**. `migration-safety-gate` I-7 의 구현체다. */
class AesGcmContentCipher(
    keyMaterial: Map<Int, Secret>,
    override val writeKeyVersion: Int,
    private val random: SecureRandom = SecureRandom(),
) : ContentCipher {
    private val logger = LoggerFactory.getLogger(AesGcmContentCipher::class.java)

    override val writeScheme: String = EncryptionScheme.AES_256_GCM_V1

    /** 세대 → 키. 못 읽는 재료는 **여기서는** 빼고 경고만 남긴다. */
    private val keys: Map<Int, SecretKey> =
        keyMaterial
            .mapNotNull { (version, material) -> keyOf(version, material) }
            .toMap()

    /**
     * 실제로 **적재에 성공한** 키 세대. 기동 자기점검이 「설정에 적힌 것」이 아니라
     * 「실린 것」을 묻는 통로다 — 둘이 갈리는 자리가 곧 조용한 오설정이다.
     */
    val loadedKeyVersions: Set<Int> get() = keys.keys

    /** 세대의 키 검사값([KeyCheckValue]). 적재되지 않은 세대면 null. */
    fun checkValueOf(version: Int): String? = keys[version]?.let { KeyCheckValue.of(it) }

    /**
     * **비용을 맞추기 위한 더미 키.** 설정에 없는 키 세대를 가리키는 봉투를 만났을 때 이
     * 키로 AEAD 를 한 번 돌린다 — 반드시 실패하므로 결과는 바뀌지 않고 시간만 같아진다.
     */
    private val uniformCostKey: SecretKey =
        SecretKeySpec(ByteArray(KEY_BYTES).also { random.nextBytes(it) }, KEY_ALGORITHM)

    init {
        logger.info("저장 암호화 키 {}세대를 적재했다. 쓰기 세대=v{}", keys.size, writeKeyVersion)
    }

    override fun encryptBytes(
        plain: PlainBytes,
        record: UUID,
        field: EncryptedField,
    ): EncryptedContent {
        val key = keys[writeKeyVersion] ?: throw ConfigurationException(MISSING_WRITE_KEY_MESSAGE)
        val nonce = ByteArray(NONCE_BYTES)
        random.nextBytes(nonce)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(associatedData(writeScheme, writeKeyVersion, record, field))
        val sealed = cipher.doFinal(plain.value)
        return EncryptedContent(nonce + sealed, writeScheme, writeKeyVersion)
    }

    override fun decryptBytes(
        content: EncryptedContent,
        record: UUID,
        field: EncryptedField,
    ): PlainBytes {
        // 아래 세 갈래(모르는 방식 · 없는 키 세대 · 길이 미달)와 태그 검증 실패가 **같은
        // 예외**여야 하고(I-7 검증 3), **같은 시간**을 써야 한다(게이트 25 X3).
        // 그래서 여기서 끊지 않고 판정만 모아 둔다 — 실제 끊는 자리는 아래 한 곳이다.
        val key = keys[content.keyVersion]
        val longEnough = content.bytes.size >= NONCE_BYTES + TAG_BYTES
        val rejected =
            content.scheme != EncryptionScheme.AES_256_GCM_V1 || key == null || !longEnough

        // 어느 갈래로 들어와도 AEAD 를 정확히 한 번 시도한다. 더미 키·더미 바이트는 태그
        // 검증에서 반드시 실패하므로 결과를 바꾸지 않고 **비용만** 맞춘다.
        val opened =
            open(
                key = key ?: uniformCostKey,
                bytes = if (longEnough) content.bytes else UNIFORM_COST_BYTES,
                aad = associatedData(content.scheme, content.keyVersion, record, field),
            )

        if (rejected || opened == null) throw DecryptionFailedException()
        return PlainBytes(opened)
    }

    /**
     * AEAD 를 한 번 시도하고 **열리면 평문 바이트, 아니면 null** 을 돌려준다. 예외를 밖으로
     * 내보내지 않는다.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun open(
        key: SecretKey,
        bytes: ByteArray,
        aad: ByteArray,
    ): ByteArray? =
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, bytes, 0, NONCE_BYTES))
            cipher.updateAAD(aad)
            cipher.doFinal(bytes, NONCE_BYTES, bytes.size - NONCE_BYTES)
        } catch (ignored: GeneralSecurityException) {
            // 원인을 잇지 않는다(I-7 검증 3). 어느 단계에서 깨졌는지가 남으면 oracle 이다.
            null
        } catch (ignored: RuntimeException) {
            // JCA 공급자의 비검사 실패(`ProviderException` 등). 위 KDoc 참고.
            null
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

        /** 길이가 모자란 봉투를 만났을 때 **대신 돌리는** 최소 길이 바이트(nonce + 태그). */
        val UNIFORM_COST_BYTES = ByteArray(NONCE_BYTES + TAG_BYTES)

        /** associated data 의 고정 머리. 다른 용도의 AEAD 가 생겨도 서로 섞이지 않는다. */
        const val AAD_PREFIX = "easydoc-aead"

        const val MISSING_WRITE_KEY_MESSAGE = "문서 암호화 키가 설정되어 있지 않습니다"

        /** 암호문을 **행·컬럼·방식·키 세대**에 묶는다. 형식과 모호하지 않은 이유는 클래스 KDoc. */
        fun associatedData(
            scheme: String,
            keyVersion: Int,
            record: UUID,
            field: EncryptedField,
        ): ByteArray = "$AAD_PREFIX|$scheme|$keyVersion|${field.wireName}|$record".toByteArray(Charsets.UTF_8)
    }
}
