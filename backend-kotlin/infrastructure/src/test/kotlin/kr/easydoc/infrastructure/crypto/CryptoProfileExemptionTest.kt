package kr.easydoc.infrastructure.crypto

import kr.easydoc.application.crypto.ContentCipher
import kr.easydoc.core.crypto.EncryptedField
import kr.easydoc.core.crypto.PlainBody
import kr.easydoc.core.exceptions.ConfigurationException
import kr.easydoc.core.security.Secret
import kr.easydoc.infrastructure.document.ROTATE_KEYS_PROFILE
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.test.context.runner.ContextConsumer
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.function.Supplier
import javax.crypto.spec.SecretKeySpec

/**
 * `migrate` 프로필의 암호화 키 면제 — 게이트 26 조치 2 (리더 판정 ④ · privacy-gate R-2 ·
 * cross 행 21).
 */
class CryptoProfileExemptionTest {
    @Test
    @DisplayName("migrate 프로필은 **키 없이 뜬다** — 스키마만 옮기는 잡이 본문 키를 쥐지 않는다")
    fun `migrate 는 키 없이 뜬다`() {
        runner(activeProfile = "migrate", keys = emptyList())
            .run(
                ContextConsumer { context: AssertableApplicationContext ->
                    assertThat(context)
                        .describedAs("migrate 가 본문 암호화 키를 요구한다 — 최소 권한에 어긋나고 스키마 적용을 막는다")
                        .hasNotFailed()
                },
            )
    }

    @Test
    @DisplayName("migrate 프로필에는 ContentCipher 빈이 **아예 없다** — 키 재료를 메모리에 들지 않는다")
    fun `migrate 는 cipher 를 만들지 않는다`() {
        runner(activeProfile = "migrate", keys = listOf(entryFor(1, KEY_GEN_1)))
            .run(
                ContextConsumer { context: AssertableApplicationContext ->
                    assertThat(context).doesNotHaveBean(ContentCipher::class.java)
                },
            )
    }

    @Test
    @DisplayName("api·worker·rotate-keys·프로필 미지정은 키가 없으면 **거부한다** — 면제는 migrate 하나뿐이다")
    fun `서비스 프로필은 키가 없으면 거부한다`() {
        // rotate-keys 는 write-key-version 이 키 링에 없으면 뜨지 않는다 — 회전 진입점 전용
        // 우회는 두지 않는다는 요구를 이 목록에 추가해 고정한다(`KeyRotationConfiguration` KDoc).
        listOf("api", "worker", ROTATE_KEYS_PROFILE, null).forEach { profile ->
            runner(activeProfile = profile, keys = emptyList())
                .run(
                    ContextConsumer { context: AssertableApplicationContext ->
                        assertThat(context)
                            .describedAs("프로필 %s 가 키 없이 떴다 — 면제가 migrate 밖으로 넓어졌다", profile ?: "(미지정)")
                            .hasFailed()
                        assertThat(context.startupFailure)
                            .describedAs("기동은 실패했는데 원인이 설정 오류가 아니다")
                            .rootCause()
                            .isInstanceOf(ConfigurationException::class.java)
                    },
                )
        }
    }

    @Test
    @DisplayName("api 프로필은 유효한 키로 조립되고 **그 빈이 실제로 왕복한다** (X9 — 컨텍스트 경유)")
    fun `api 프로필은 유효한 키로 조립되고 그 빈이 왕복한다`() {
        runner(activeProfile = "api", keys = listOf(entryFor(1, KEY_GEN_1)))
            .run(
                ContextConsumer { context: AssertableApplicationContext ->
                    assertThat(context).hasNotFailed()
                    val cipher = context.getBean(ContentCipher::class.java)
                    val sealed = cipher.encrypt(PlainBody(PROBE_BODY), RECORD, EncryptedField.DOCUMENT_SOURCE_TEXT)

                    assertThat(cipher.decrypt(sealed, RECORD, EncryptedField.DOCUMENT_SOURCE_TEXT).value)
                        .describedAs("Spring 이 조립한 빈이 왕복하지 않는다")
                        .isEqualTo(PROBE_BODY)
                },
            )
    }

    /** 설정 바인딩을 지나지 않고 [EncryptionProperties] 를 빈으로 직접 준다. */
    private fun runner(
        activeProfile: String?,
        keys: List<EncryptionKeyProperties>,
    ): ApplicationContextRunner {
        val base =
            ApplicationContextRunner()
                .withUserConfiguration(CryptoConfiguration::class.java)
                .withBean(
                    EncryptionProperties::class.java,
                    Supplier { EncryptionProperties(writeKeyVersion = 1, keys = keys) },
                )
        return if (activeProfile == null) base else base.withPropertyValues("spring.profiles.active=$activeProfile")
    }

    private fun entryFor(
        version: Int,
        key: Secret,
    ) = EncryptionKeyProperties(
        version = version,
        value = key,
        kcv = KeyCheckValue.of(SecretKeySpec(Base64.getDecoder().decode(key.reveal()), "AES")),
    )

    private companion object {
        val RECORD: UUID = UUID.fromString("22222222-2222-4222-8222-222222222222")

        const val PROBE_BODY = "행정복지센터 안내문"

        /** 실행 시점에 만드는 32바이트 키. 소스에 키 리터럴을 적지 않는다. */
        val KEY_GEN_1: Secret =
            Secret(
                Base64.getEncoder().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) }),
            )
    }
}
