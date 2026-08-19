package kr.easydoc.infrastructure.crypto

import kr.easydoc.core.crypto.EncryptedContent
import kr.easydoc.core.crypto.EncryptedField
import kr.easydoc.core.crypto.PlainBody
import kr.easydoc.core.exceptions.ConfigurationException
import kr.easydoc.core.security.Secret
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import javax.crypto.spec.SecretKeySpec

/**
 * **저장 암호화 조립과 기동 자기점검** — 게이트 25 F-2·F-3·X8·X9.
 *
 * ## 왜 이 파일이 필요한가
 *
 * `CryptoConfiguration` 은 조립 코드인데 **테스트가 0건**이었다(R-2·F-6). 게다가 저장소의
 * 모든 Spring 컨텍스트가 키 0세대로 떴으므로, 통합 테스트가 붙어도 「초록인데 암호를 한 번도
 * 안 탔다」가 성립하는 구조였다. 여기서 그 두 빈자리를 함께 메운다 — 조립을 직접 부르고,
 * **실제 키로 왕복까지** 시킨다.
 *
 * ## 세 갈래가 같은 성질을 공유한다
 *
 * F-2(쓰기 키 부재) · F-3(키 값이 틀림) · X8(세대 번호가 도메인 밖)은 전부
 * **오설정이 쓰기 시점에는 조용하고 읽기 시점에 나타나는** 종류다. 그 사이에 저장된 행은
 * 되돌릴 수 없다. 그래서 셋 다 기동에서 끊는다.
 */
class CryptoStartupVerificationTest {
    // ================================================================ 정상 조립

    @Test
    @DisplayName("검사값이 맞는 설정은 조립되고, 그 빈으로 실제 왕복이 된다 (X9 — 실제 키 통합)")
    fun `올바른 설정은 조립되고 왕복한다`() {
        val cipher = assemble(listOf(entryFor(1, KEY_GEN_1)))

        val sealed = cipher.encrypt(PlainBody(PROBE_BODY), RECORD, EncryptedField.DOCUMENT_SOURCE_TEXT)
        assertThat(cipher.decrypt(sealed, RECORD, EncryptedField.DOCUMENT_SOURCE_TEXT).value).isEqualTo(PROBE_BODY)
        assertThat(sealed.keyVersion).isEqualTo(1)
    }

    @Test
    @DisplayName("회전 설정(두 세대 + 쓰기 세대 2)도 조립되고 옛 세대를 읽는다")
    fun `회전 설정이 조립된다`() {
        val cipher = assemble(listOf(entryFor(1, KEY_GEN_1), entryFor(2, KEY_GEN_2)), writeKeyVersion = 2)

        val sealed = cipher.encrypt(PlainBody(PROBE_BODY), RECORD, EncryptedField.CONVERSION_EASY_TEXT)
        assertThat(sealed.keyVersion).isEqualTo(2)
        assertThat(cipher.decrypt(sealed, RECORD, EncryptedField.CONVERSION_EASY_TEXT).value).isEqualTo(PROBE_BODY)
    }

    // ================================================================ F-2 쓰기 키 부재

    @Test
    @DisplayName("F-2 쓰기 세대의 키가 없으면 **기동이 실패한다** — 첫 업로드까지 조용하지 않다")
    fun `쓰기 키가 없으면 기동이 실패한다`() {
        // 종전에는 그대로 떴고 첫 업로드가 503 이 됐다. 그 503 은 사용자 화면에 나오고,
        // 배포 파이프라인은 아무것도 보지 못한다. `keys` 에 쓰기 세대가 없다는 것은
        // **기동 시점에 이미 아는 사실**이다.
        assertThatThrownBy { assemble(keys = emptyList()) }
            .isInstanceOf(ConfigurationException::class.java)
            .hasMessageContaining("쓰기 세대 v1")

        // 세대는 있는데 쓰기 세대만 없는 갈래도 같다.
        assertThatThrownBy { assemble(listOf(entryFor(1, KEY_GEN_1)), writeKeyVersion = 2) }
            .isInstanceOf(ConfigurationException::class.java)
            .hasMessageContaining("쓰기 세대 v2")
    }

    // ================================================================ F-3 KCV

    @Test
    @DisplayName("F-3 오타 키 — 값이 틀린 32바이트 키는 **기동에서** 잡힌다 (privacy-gate 「가장 위험」)")
    fun `값이 틀린 키는 기동에서 잡힌다`() {
        // 이 조합이 종전에 **아무 경고 없이** 실렸다. base64 32바이트이기만 하면 통과했고,
        // 그 키로 쓴 행은 옛 키로 열리지 않는다. 되돌릴 수 없는 종류다.
        val wrongKeyInV1Slot = EncryptionKeyProperties(version = 1, value = KEY_GEN_2, kcv = kcvOf(KEY_GEN_1))

        assertThatThrownBy { assemble(listOf(wrongKeyInV1Slot)) }
            .isInstanceOf(ConfigurationException::class.java)
            .hasMessageContaining("kcv 가 설정값과 다르다")
    }

    @Test
    @DisplayName("F-3 kcv 가 비어 있으면 기동이 실패하고, 계산값을 알려 준다 — 적어 넣을 방법이 있다")
    fun `kcv 가 없으면 기동이 실패한다`() {
        val withoutKcv = EncryptionKeyProperties(version = 1, value = KEY_GEN_1, kcv = "")

        assertThatThrownBy { assemble(listOf(withoutKcv)) }
            .isInstanceOf(ConfigurationException::class.java)
            .hasMessageContaining("kcv 가 없다")
            .hasMessageContaining(kcvOf(KEY_GEN_1))
    }

    @Test
    @DisplayName("F-3 값이 비지 않았는데 base64 32바이트가 아니면 기동이 실패한다 — 경고 한 줄로 넘기지 않는다")
    fun `깨진 키 재료는 기동을 막는다`() {
        listOf(
            "base64 가 아니다" to Secret("!!not-base64!!"),
            "길이가 다르다" to Secret(Base64.getEncoder().encodeToString(ByteArray(SHORT_KEY_BYTES))),
        ).forEach { (label, material) ->
            assertThatThrownBy { assemble(listOf(EncryptionKeyProperties(1, material, "0".repeat(12)))) }
                .describedAs("%s 인데 기동이 통과했다", label)
                .isInstanceOf(ConfigurationException::class.java)
        }
    }

    @Test
    @DisplayName("기동 실패 메시지에 키 재료가 한 조각도 없다")
    fun `기동 실패 메시지가 키를 담지 않는다`() {
        val message =
            runCatching { assemble(listOf(EncryptionKeyProperties(1, KEY_GEN_1, kcv = ""))) }
                .exceptionOrNull()
                ?.message
                .orEmpty()

        assertThat(message).describedAs("메시지가 비었다 — 이 케이스가 아무것도 보고 있지 않다").isNotEmpty()
        listOf(
            "키 재료(base64)" to KEY_GEN_1.reveal(),
            "키 재료(base64) 뒤 절반" to KEY_GEN_1.reveal().takeLast(KEY_FRAGMENT_LENGTH),
            "키 바이트(hex)" to Base64.getDecoder().decode(KEY_GEN_1.reveal()).joinToString("") { "%02x".format(it) },
        ).forEach { (label, needle) ->
            assertThat(message).describedAs("실패 메시지에 %s 가 실렸다", label).doesNotContain(needle)
        }
    }

    // ================================================================ X8 세대 번호 도메인

    @Test
    @DisplayName("X8 스키마 도메인 밖 세대 번호는 기동에서 잡힌다 (0·음수·smallint 초과)")
    fun `도메인 밖 세대 번호는 기동을 막는다`() {
        listOf(0, -1, Short.MAX_VALUE.toInt() + 1).forEach { version ->
            assertThatThrownBy { assemble(listOf(entryFor(version, KEY_GEN_1)), writeKeyVersion = version) }
                .describedAs("세대 번호 %d 가 기동을 통과했다", version)
                .isInstanceOf(ConfigurationException::class.java)
                .hasMessageContaining("스키마 도메인")
        }
    }

    @Test
    @DisplayName("X8 봉투도 도메인 밖 세대 번호를 들 수 없다 — DB 에 닿기 전에 끊는다")
    fun `봉투가 도메인 밖 세대 번호를 거부한다`() {
        val bytes = ByteArray(ENVELOPE_MIN_BYTES)

        listOf(0, -1, Short.MAX_VALUE.toInt() + 1).forEach { version ->
            assertThatThrownBy { EncryptedContent(bytes, "aes256gcm-v1", version) }
                .describedAs("세대 번호 %d 를 든 봉투가 만들어졌다", version)
                .isInstanceOf(IllegalArgumentException::class.java)
        }
        assertThatCode { EncryptedContent(bytes, "aes256gcm-v1", Short.MAX_VALUE.toInt()) }
            .describedAs("smallint 상한이 거부됐다 — 범위가 스키마보다 좁다")
            .doesNotThrowAnyException()
    }

    // ================================================================ 면제 스위치의 도달

    @Test
    @DisplayName("자기점검은 **기본이 켜짐**이다 — 끄는 것이 기본이면 이 게이트는 아무 데도 없다")
    fun `자기점검이 기본으로 켜져 있다`() {
        assertThat(EncryptionProperties().verifyOnStartup).isTrue()
    }

    @Test
    @DisplayName("면제 스위치가 **제품 설정에 새지 않았다** — 끄는 자리는 빌드 스크립트 하나뿐이다")
    fun `면제 스위치가 제품 설정에 없다`() {
        // 스위치를 두면서 그 스위치가 제품에 닿지 않는지 보는 탐지기를 함께 둔다
        // (`CLAUDE.md` 규칙 4 — 은폐형은 탐지형으로 갈아탄다).
        val (leaked, scanned) = productConfigsMentioning(SWITCH_TOKENS)

        assertThat(scanned)
            .describedAs("제품 설정 파일을 하나도 찾지 못했다 — 이 대조가 0건을 훑고 통과한다")
            .isNotEmpty()
        assertThat(leaked)
            .withFailMessage {
                "저장 암호화 자기점검 스위치가 제품 설정에 적혔다:\n" +
                    leaked.joinToString("\n") { "  - $it" } +
                    "\n  이 스위치를 끌 수 있는 자리는 `build.gradle.kts` 의 테스트 태스크 하나뿐이다.\n" +
                    "  제품에서 끄면 오설정이 첫 업로드까지 조용해지고, 그때 쓴 행은 되돌릴 수 없다."
            }.isEmpty()
    }

    // ---------------------------------------------------------------- 도구

    /** `CryptoConfiguration` 을 **실제로 불러** 빈을 만든다. 자기점검은 항상 켠 채로 잰다. */
    private fun assemble(
        keys: List<EncryptionKeyProperties>,
        writeKeyVersion: Int = 1,
    ) = CryptoConfiguration().contentCipher(
        EncryptionProperties(writeKeyVersion = writeKeyVersion, keys = keys, verifyOnStartup = true),
    )

    private fun entryFor(
        version: Int,
        key: Secret,
    ) = EncryptionKeyProperties(version = version, value = key, kcv = kcvOf(key))

    /** 설정에 적어야 할 검사값. 제품과 같은 계산을 쓴다 — 여기서 다시 만들면 두 값이 갈린다. */
    private fun kcvOf(key: Secret): String =
        KeyCheckValue.of(SecretKeySpec(Base64.getDecoder().decode(key.reveal()), "AES"))

    /**
     * 제품 설정 파일 중 [tokens] 를 언급하는 것. 파일 목록을 열거하지 않고 **훑는다** —
     * 배포 매니페스트가 하나 늘 때 이 대조가 조용히 좁아지지 않게 한다.
     */
    private fun productConfigsMentioning(tokens: List<String>): Pair<List<String>, List<File>> {
        val kotlinRoot =
            File(
                System.getProperty("easydoc.kotlin.source.root")
                    ?: error("easydoc.kotlin.source.root 이 없다 — 제품 설정을 찾을 기준점이 없다"),
            )
        val repositoryRoot = kotlinRoot.parentFile
        val scanned =
            (
                kotlinRoot
                    .walkTopDown()
                    .filter { it.isFile && it.path.contains("/src/main/resources/") && it.extension == "yml" }
                    .toList() +
                    listOfNotNull(
                        repositoryRoot.resolve("docker-compose.yml").takeIf { it.isFile },
                        repositoryRoot.resolve(".env.example").takeIf { it.isFile },
                        kotlinRoot.resolve("Dockerfile").takeIf { it.isFile },
                    )
            ).distinct()
        val leaked = scanned.filter { file -> tokens.any { it in file.readText() } }.map { it.path }
        return leaked to scanned
    }

    private companion object {
        val RECORD: UUID = UUID.fromString("11111111-1111-4111-8111-111111111111")

        const val PROBE_BODY = "행정복지센터 안내문"

        /** AES-256 이 아닌 길이. 「32바이트가 아니면 쓰지 않는다」의 표본이다. */
        const val SHORT_KEY_BYTES = 16

        /** nonce(12) + 태그(16). 봉투 도메인 케이스가 쓰는 최소 길이다. */
        const val ENVELOPE_MIN_BYTES = 28

        /** 메시지 누출 검사에서 볼 키 조각 길이. 짧으면 우연히 겹친다. */
        const val KEY_FRAGMENT_LENGTH = 12

        /** 제품 설정에 있으면 안 되는 표기. 설정 키와 환경변수 표기를 함께 본다. */
        val SWITCH_TOKENS = listOf("verify-on-startup", "verifyOnStartup", "VERIFY_ON_STARTUP")

        /** 실행 시점에 만드는 32바이트 키 두 개. 소스에 키 리터럴을 적지 않는다. */
        val KEY_GEN_1: Secret = randomKey()
        val KEY_GEN_2: Secret = randomKey()

        fun randomKey(): Secret {
            val bytes = ByteArray(32)
            SecureRandom().nextBytes(bytes)
            return Secret(Base64.getEncoder().encodeToString(bytes))
        }
    }
}
