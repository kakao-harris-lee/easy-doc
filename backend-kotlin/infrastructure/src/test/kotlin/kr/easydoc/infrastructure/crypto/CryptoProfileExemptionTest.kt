package kr.easydoc.infrastructure.crypto

import kr.easydoc.application.crypto.ContentCipher
import kr.easydoc.core.crypto.EncryptedField
import kr.easydoc.core.crypto.PlainBody
import kr.easydoc.core.exceptions.ConfigurationException
import kr.easydoc.core.security.Secret
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
 * **`migrate` 프로필의 암호화 키 면제** — 게이트 26 조치 2 (리더 판정 ④ · privacy-gate R-2 ·
 * cross 행 21).
 *
 * ## 무엇이 결정됐나
 *
 * 게이트 25 는 저장 암호화 오설정을 **기동에서** 끊기로 했다(F-2·F-3). 그 조립은 프로필을
 * 가리지 않았으므로 스키마만 옮기는 `migrate` 실행까지 본문 암호화 키를 요구하게 됐다.
 * 리더는 그 판정을 뒤집었다 —
 *
 * - 기동 fail-fast 는 **강제형** 장치이고, 강제형의 범위를 근거에 맞추는 것은 `CLAUDE.md`
 *   규칙 4 ⑴(「범위는 근거를 넘지 않는다」)이지 은폐가 아니다. 근거(X7/F-2)는 **서비스
 *   경로**의 오설정 침묵을 겨눴고 `migrate` 에는 그 경로가 없다.
 * - 「비용 0」이라던 근거(compose 가 `env_file` 을 공유한다)가 실은 privacy-gate R-2 가
 *   지적한 **비용 그 자체**였다 — 아무것도 암호화하지 않는 서비스가 본문 키를 든다.
 *
 * ## 왜 이 파일이 있는가 — **조용한 면제가 아니라 고정된 면제**
 *
 * 프로필마다 안전 수준이 다른 것은 그 자체로 다음 사고의 자리다. 그래서 면제의 **양쪽
 * 방향**을 여기서 고정한다. 면제가 조용히 넓어지면(다른 프로필도 키 없이 뜨기 시작하면)
 * 이 테스트가 빨개진다.
 *
 * ## 왜 `ApplicationContextRunner` 인가
 *
 * 프로필 조합마다 실제 Spring 컨텍스트를 조립해 **기동 성공/실패 자체**를 재야 하는데,
 * `@SpringBootTest` 로는 「기동이 실패한다」를 재기 어렵다(컨텍스트 적재 실패가 곧 테스트
 * 오류다). 이 러너는 실패를 값으로 돌려준다.
 *
 * 함께 닫히는 것이 하나 있다 — privacy-gate R-1·F-6/X9 가 적은 *"조립된 빈을 실제 키로
 * 쓰는 경로가 0"*. [`api 프로필은 유효한 키로 조립되고 그 빈이 왕복한다`] 가 그 경로다.
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
        // 「검증만 건너뛴다」가 아니라 「조립하지 않는다」인 것이 요점이다. 유효한 키가
        // 환경에 있더라도 migrate 는 그것을 SecretKey 로 만들지 않는다.
        runner(activeProfile = "migrate", keys = listOf(entryFor(1, KEY_GEN_1)))
            .run(
                ContextConsumer { context: AssertableApplicationContext ->
                    assertThat(context).doesNotHaveBean(ContentCipher::class.java)
                },
            )
    }

    @Test
    @DisplayName("api·worker·프로필 미지정은 키가 없으면 **거부한다** — 면제는 migrate 하나뿐이다")
    fun `서비스 프로필은 키가 없으면 거부한다`() {
        listOf("api", "worker", null).forEach { profile ->
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

    // ---------------------------------------------------------------- 도구

    /**
     * 설정 바인딩을 지나지 않고 [EncryptionProperties] 를 **빈으로 직접 준다.**
     *
     * 바인딩(placeholder·`Secret` 변환)이 실제로 도는지는 `ConfigurationPropertiesBindingTest`
     * 가 이미 잰다. 여기서 재려는 것은 **프로필 조건**뿐이므로 축을 섞지 않는다.
     */
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
