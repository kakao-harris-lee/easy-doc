package kr.easydoc.infrastructure.crypto

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.IThrowableProxy
import ch.qos.logback.core.read.ListAppender
import kr.easydoc.core.crypto.EncryptedContent
import kr.easydoc.core.crypto.EncryptedField
import kr.easydoc.core.crypto.EncryptionScheme
import kr.easydoc.core.crypto.PlainBody
import kr.easydoc.core.exceptions.ConfigurationException
import kr.easydoc.core.exceptions.DecryptionFailedException
import kr.easydoc.core.exceptions.InvalidInputException
import kr.easydoc.core.security.Secret
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.security.Security
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

/** 저장 암호화 AEAD 의 정확성 — `migration-safety-gate` I-7 전건. */
class AesGcmContentCipherTest {
    @Test
    @DisplayName("I-7-1 round-trip — 한글·ASCII·빈 값·긴 값·개행/탭이 그대로 돌아온다")
    fun `round-trip 이 성립한다`() {
        val cipher = cipherWith(mapOf(1 to KEY_GEN_1), writeKeyVersion = 1)

        ROUND_TRIP_SAMPLES.forEach { (label, plain) ->
            val sealed = cipher.encrypt(PlainBody(plain), RECORD, EncryptedField.DOCUMENT_SOURCE_TEXT)
            val opened = cipher.decrypt(sealed, RECORD, EncryptedField.DOCUMENT_SOURCE_TEXT)

            assertThat(opened.value).describedAs("round-trip 실패: %s", label).isEqualTo(plain)

            assertThat(String(sealed.bytes, Charsets.UTF_8))
                .describedAs("암호문에 평문이 보인다: %s", label)
                .doesNotContain(plain.take(MIN_VISIBLE_LENGTH).ifEmpty { "\u0000없는값" })
        }
    }

    @Test
    @DisplayName("I-7-1 왕복이 깨질 값은 **저장 전에 거부된다** — round-trip 이 정의역 전체에서 참이다 (X1)")
    fun `왕복이 깨질 값은 평문으로 만들어지지 않는다`() {
        assertThatThrownBy { PlainBody(LONE_SURROGATE_BODY) }
            .describedAs("짝 없는 서로게이트가 평문 래퍼를 통과했다 — 저장하면 본문이 조용히 바뀐다")
            .isInstanceOf(InvalidInputException::class.java)
            .hasMessage(PlainBody.UNPAIRED_SURROGATE_MESSAGE)

        assertThat(String(LONE_SURROGATE_BODY.toByteArray(Charsets.UTF_8), Charsets.UTF_8))
            .isNotEqualTo(LONE_SURROGATE_BODY)
    }

    @Test
    @DisplayName("암호문 봉투가 행에 적힐 두 값을 함께 들고 나온다 (scheme·key_version)")
    fun `봉투가 방식과 키 세대를 싣는다`() {
        val cipher = cipherWith(mapOf(7 to KEY_GEN_1), writeKeyVersion = 7)

        val sealed = cipher.encrypt(PlainBody("안내문"), RECORD, EncryptedField.DOCUMENT_SOURCE_TEXT)

        assertThat(sealed.scheme).isEqualTo(EncryptionScheme.AES_256_GCM_V1)
        assertThat(sealed.keyVersion).isEqualTo(7)
        assertThat(cipher.writeScheme).isEqualTo(EncryptionScheme.AES_256_GCM_V1)
        assertThat(cipher.writeKeyVersion).isEqualTo(7)

        assertThat(sealed.bytes.size).isGreaterThanOrEqualTo(NONCE_BYTES + TAG_BYTES)
    }

    @Test
    @DisplayName("I-7-4 같은 평문을 두 번 암호화하면 nonce 도 암호문도 다르다")
    fun `같은 평문을 두 번 암호화하면 결과가 다르다`() {
        val cipher = cipherWith(mapOf(1 to KEY_GEN_1), writeKeyVersion = 1)
        val plain = PlainBody("같은 문장을 두 번 올린다")

        val first = cipher.encrypt(plain, RECORD, EncryptedField.DOCUMENT_SOURCE_TEXT)
        val second = cipher.encrypt(plain, RECORD, EncryptedField.DOCUMENT_SOURCE_TEXT)

        assertThat(first.bytes.copyOf(NONCE_BYTES))
            .describedAs("nonce 가 같다 — AES-GCM 에서 같은 키로 nonce 가 겹치면 평문이 복원된다")
            .isNotEqualTo(second.bytes.copyOf(NONCE_BYTES))
        assertThat(first.bytes).isNotEqualTo(second.bytes)

        assertThat(cipher.decrypt(second, RECORD, EncryptedField.DOCUMENT_SOURCE_TEXT).value).isEqualTo(plain.value)
    }

    @Test
    @DisplayName("음성 대조 — 난수원을 고정하면 nonce 가 반복된다 (위 케이스가 무엇을 재는지 확인)")
    fun `난수원을 고정하면 nonce 가 반복된다`() {
        val cipher = cipherWith(mapOf(1 to KEY_GEN_1), writeKeyVersion = 1, random = FixedNonceRandom())
        val plain = PlainBody("같은 문장을 두 번 올린다")

        val first = cipher.encrypt(plain, RECORD, EncryptedField.DOCUMENT_SOURCE_TEXT)
        val second = cipher.encrypt(plain, RECORD, EncryptedField.DOCUMENT_SOURCE_TEXT)

        assertThat(first.bytes.copyOf(NONCE_BYTES)).isEqualTo(second.bytes.copyOf(NONCE_BYTES))
        assertThat(first.bytes)
            .describedAs("nonce 를 고정했는데도 암호문이 다르다 — 난수가 nonce 말고 다른 곳에서 들어가고 있다")
            .isEqualTo(second.bytes)
    }

    @Test
    @DisplayName("I-7-2 nonce·암호문·태그 어느 한 바이트를 뒤집어도 거부한다")
    fun `변조된 바이트를 거부한다`() {
        val cipher = cipherWith(mapOf(1 to KEY_GEN_1), writeKeyVersion = 1)
        val sealed = cipher.encrypt(PlainBody("공공기관 안내문"), RECORD, EncryptedField.DOCUMENT_SOURCE_TEXT)

        val positions =
            mapOf(
                "nonce 첫 바이트" to 0,
                "암호문 첫 바이트" to NONCE_BYTES,
                "태그 마지막 바이트" to sealed.bytes.size - 1,
            )
        positions.forEach { (label, index) ->
            val tampered = sealed.bytes.copyOf().also { it[index] = (it[index].toInt() xor 1).toByte() }

            assertThatThrownBy {
                cipher.decrypt(
                    EncryptedContent(tampered, sealed.scheme, sealed.keyVersion),
                    RECORD,
                    EncryptedField.DOCUMENT_SOURCE_TEXT,
                )
            }.describedAs("%s 를 뒤집었는데 복호화가 통과했다 — 인증 암호화가 아니다", label)
                .isInstanceOf(DecryptionFailedException::class.java)
        }
    }

    @Test
    @DisplayName("I-7-2 같은 세대 번호라도 다른 키로는 열리지 않는다")
    fun `다른 키를 거부한다`() {
        val sealed =
            cipherWith(mapOf(1 to KEY_GEN_1), writeKeyVersion = 1)
                .encrypt(PlainBody("공공기관 안내문"), RECORD, EncryptedField.DOCUMENT_SOURCE_TEXT)
        val other = cipherWith(mapOf(1 to KEY_GEN_2), writeKeyVersion = 1)

        assertThatThrownBy { other.decrypt(sealed, RECORD, EncryptedField.DOCUMENT_SOURCE_TEXT) }
            .isInstanceOf(DecryptionFailedException::class.java)
    }

    @Test
    @DisplayName("I-7-2 암호문 형식이 아닌 바이트를 거부한다 (빈 값·nonce+태그 미만·평문 바이트)")
    fun `형식이 아닌 바이트를 거부한다`() {
        val cipher = cipherWith(mapOf(1 to KEY_GEN_1), writeKeyVersion = 1)

        listOf(
            "빈 바이트" to ByteArray(0),
            "nonce+태그 미만" to ByteArray(NONCE_BYTES + TAG_BYTES - 1),
            "그냥 평문 바이트" to "암호화하지 않고 그대로 넣은 본문".toByteArray(Charsets.UTF_8),
        ).forEach { (label, bytes) ->
            assertThatThrownBy {
                cipher.decrypt(
                    EncryptedContent(bytes, EncryptionScheme.AES_256_GCM_V1, 1),
                    RECORD,
                    EncryptedField.DOCUMENT_SOURCE_TEXT,
                )
            }.describedAs("%s 가 복호화를 통과했다", label)
                .isInstanceOf(DecryptionFailedException::class.java)
        }
    }

    @Test
    @DisplayName("I-7-5 결속값(행·컬럼·방식·키 세대)이 하나라도 다르면 거부한다 — 바꿔치기 방어")
    fun `결속값이 어긋나면 거부한다`() {
        val cipher = cipherWith(mapOf(1 to KEY_GEN_1, 2 to KEY_GEN_2), writeKeyVersion = 1)
        val sealed = cipher.encrypt(PlainBody("담당자 검수 전 초안"), RECORD, EncryptedField.CONVERSION_EASY_TEXT)

        val substitutions =
            mapOf(
                "다른 행으로 옮김" to { cipher.decrypt(sealed, OTHER_RECORD, EncryptedField.CONVERSION_EASY_TEXT) },
                "다른 컬럼으로 옮김" to { cipher.decrypt(sealed, RECORD, EncryptedField.CONVERSION_EDITED_TEXT) },
                "키 세대 컬럼 조작" to {
                    val relabelled = EncryptedContent(sealed.bytes, sealed.scheme, 2)
                    cipher.decrypt(relabelled, RECORD, EncryptedField.CONVERSION_EASY_TEXT)
                },
                "방식 컬럼 조작" to {
                    val relabelled = EncryptedContent(sealed.bytes, "fernet-v1", 1)
                    cipher.decrypt(relabelled, RECORD, EncryptedField.CONVERSION_EASY_TEXT)
                },
            )
        substitutions.forEach { (label, attempt) ->
            assertThatThrownBy { attempt() }
                .describedAs("%s 인데 복호화가 통과했다 — associated data 가 결속하지 않는다", label)
                .isInstanceOf(DecryptionFailedException::class.java)
        }
    }

    @Test
    @DisplayName("I-7-5 AAD 가 **키 세대**를 결속한다 — 두 세대가 같은 키 재료여도 라벨을 바꾸면 안 열린다 (X4)")
    fun `AAD 가 키 세대를 결속한다`() {
        val shared = cipherWith(mapOf(1 to KEY_GEN_1, 2 to KEY_GEN_1), writeKeyVersion = 1)
        val sealedAtV1 = shared.encrypt(PlainBody("회전 라벨 결속"), RECORD, EncryptedField.DOCUMENT_SOURCE_TEXT)

        val writerAtV2 = cipherWith(mapOf(1 to KEY_GEN_1, 2 to KEY_GEN_1), writeKeyVersion = 2)
        val sealedAtV2 = writerAtV2.encrypt(PlainBody("회전 라벨 결속"), RECORD, EncryptedField.DOCUMENT_SOURCE_TEXT)
        assertThat(shared.decrypt(sealedAtV2, RECORD, EncryptedField.DOCUMENT_SOURCE_TEXT).value)
            .describedAs("두 세대의 키가 서로 다르다 — 이 케이스는 AAD 가 아니라 키 차이를 재고 있다")
            .isEqualTo("회전 라벨 결속")

        assertThatThrownBy {
            shared.decrypt(
                EncryptedContent(sealedAtV1.bytes, sealedAtV1.scheme, 2),
                RECORD,
                EncryptedField.DOCUMENT_SOURCE_TEXT,
            )
        }.describedAs("세대 라벨을 갈아 끼웠는데 열렸다 — AAD 가 key_version 을 결속하지 않는다")
            .isInstanceOf(DecryptionFailedException::class.java)
    }

    @Test
    @DisplayName("I-7-5 AAD 문자열에 **방식·키 세대가 실제로 실린다** — JCA 로 직접 조립해 확인 (X4)")
    fun `AAD 가 방식과 키 세대를 싣는다`() {
        val cipher = cipherWith(mapOf(AAD_PROBE_VERSION to KEY_GEN_1), writeKeyVersion = AAD_PROBE_VERSION)
        val plain = "결속 대상 본문"
        val sealed = cipher.encrypt(PlainBody(plain), RECORD, EncryptedField.CONVERSION_EASY_TEXT)

        assertThat(openWithJca(sealed, aadOf(EncryptionScheme.AES_256_GCM_V1, AAD_PROBE_VERSION)))
            .describedAs("정본 AAD 로 열리지 않는다 — AAD 형식에서 방식·키 세대 축이 빠졌다")
            .isEqualTo(plain)

        listOf(
            "방식만 다르다" to aadOf("aes256gcm-v2", AAD_PROBE_VERSION),
            "키 세대만 다르다" to aadOf(EncryptionScheme.AES_256_GCM_V1, AAD_PROBE_VERSION + 1),
        ).forEach { (label, aad) ->
            assertThatThrownBy { openWithJca(sealed, aad) }
                .describedAs("%s 인데 열렸다 — 그 축이 AAD 에 결속돼 있지 않다", label)
                .isInstanceOf(GeneralSecurityException::class.java)
        }
    }

    @Test
    @DisplayName("I-7-5 키 회전 — 새 세대로 쓰면서 옛 세대로 쓴 행을 계속 읽는다")
    fun `키를 회전해도 옛 세대를 읽는다`() {
        val before = cipherWith(mapOf(1 to KEY_GEN_1), writeKeyVersion = 1)
        val oldRow = before.encrypt(PlainBody("회전 전에 저장한 본문"), RECORD, EncryptedField.DOCUMENT_SOURCE_TEXT)
        assertThat(oldRow.keyVersion).isEqualTo(1)

        val after = cipherWith(mapOf(1 to KEY_GEN_1, 2 to KEY_GEN_2), writeKeyVersion = 2)

        assertThat(after.decrypt(oldRow, RECORD, EncryptedField.DOCUMENT_SOURCE_TEXT).value)
            .describedAs("회전 뒤 옛 행이 열리지 않는다 — 그 행은 영원히 못 읽는다")
            .isEqualTo("회전 전에 저장한 본문")
        val newRow = after.encrypt(PlainBody("회전 후에 저장한 본문"), RECORD, EncryptedField.DOCUMENT_SOURCE_TEXT)
        assertThat(newRow.keyVersion).describedAs("새 쓰기가 아직 옛 세대를 쓴다").isEqualTo(2)
        assertThat(after.decrypt(newRow, RECORD, EncryptedField.DOCUMENT_SOURCE_TEXT).value)
            .isEqualTo("회전 후에 저장한 본문")
    }

    @Test
    @DisplayName("I-7-5 설정에 없는 키 세대를 가리키는 행은 거부한다")
    fun `모르는 키 세대를 거부한다`() {
        val cipher = cipherWith(mapOf(1 to KEY_GEN_1), writeKeyVersion = 1)
        val sealed = cipher.encrypt(PlainBody("본문"), RECORD, EncryptedField.DOCUMENT_SOURCE_TEXT)

        assertThatThrownBy {
            cipher.decrypt(
                EncryptedContent(sealed.bytes, sealed.scheme, UNKNOWN_KEY_VERSION),
                RECORD,
                EncryptedField.DOCUMENT_SOURCE_TEXT,
            )
        }.isInstanceOf(DecryptionFailedException::class.java)
    }

    @Test
    @DisplayName("I-7-3 실패 갈래가 서로 구분되지 않는다 — 같은 타입·같은 메시지·원인 없음")
    fun `실패 갈래가 서로 구분되지 않는다`() {
        val cipher = cipherWith(mapOf(1 to KEY_GEN_1), writeKeyVersion = 1)
        val sealed = cipher.encrypt(PlainBody("공공기관 안내문"), RECORD, EncryptedField.DOCUMENT_SOURCE_TEXT)
        val flipped = sealed.bytes.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }

        val failures =
            listOf<Pair<String, () -> Unit>>(
                "태그 불일치" to {
                    val tampered = EncryptedContent(flipped, sealed.scheme, 1)
                    cipher.decrypt(tampered, RECORD, EncryptedField.DOCUMENT_SOURCE_TEXT)
                },
                "모르는 키 세대" to {
                    cipher.decrypt(
                        EncryptedContent(sealed.bytes, sealed.scheme, UNKNOWN_KEY_VERSION),
                        RECORD,
                        EncryptedField.DOCUMENT_SOURCE_TEXT,
                    )
                },
                "길이 미달" to {
                    cipher.decrypt(
                        EncryptedContent(ByteArray(1), sealed.scheme, 1),
                        RECORD,
                        EncryptedField.DOCUMENT_SOURCE_TEXT,
                    )
                },
                "모르는 방식" to {
                    val relabelled = EncryptedContent(sealed.bytes, "fernet-v1", 1)
                    cipher.decrypt(relabelled, RECORD, EncryptedField.DOCUMENT_SOURCE_TEXT)
                },
                "다른 컬럼" to { cipher.decrypt(sealed, RECORD, EncryptedField.CONVERSION_EASY_TEXT) },
                "다른 행" to { cipher.decrypt(sealed, OTHER_RECORD, EncryptedField.DOCUMENT_SOURCE_TEXT) },
                "다른 키" to {
                    cipherWith(mapOf(1 to KEY_GEN_2), writeKeyVersion = 1)
                        .decrypt(sealed, RECORD, EncryptedField.DOCUMENT_SOURCE_TEXT)
                },
            )

        val observed =
            failures.map { (label, attempt) ->
                val thrown =
                    runCatching(attempt).exceptionOrNull()
                        ?: error("$label — 실패해야 하는데 통과했다")
                label to Triple(thrown::class.qualifiedName, thrown.message, thrown.cause?.let { it::class.simpleName })
            }

        assertThat(observed.map { it.second }.distinct())
            .describedAs("실패 갈래가 서로 다른 신호를 낸다 — 그것이 복호화 oracle 이다:\n%s", observed)
            .hasSize(1)
        assertThat(observed.first().second.third)
            .describedAs("원인 예외가 이어져 있다 — 트레이스백에 어느 단계에서 깨졌는지가 남는다")
            .isNull()
        assertThat(observed.first().second.second).isEqualTo(DecryptionFailedException.MESSAGE)
    }

    /** 실패 갈래의 소요 시간이 갈리지 않는다 — 게이트 25 X3(codex 6.5배 · privacy-gate 2.84배). */
    @Test
    @DisplayName("I-7-3 조기 분기와 태그 검증 실패의 소요 시간이 갈리지 않는다 (X3 상설 회귀)")
    fun `실패 갈래의 소요 시간이 갈리지 않는다`() {
        val cipher = cipherWith(mapOf(1 to KEY_GEN_1), writeKeyVersion = 1)
        val sealed = cipher.encrypt(PlainBody(TIMING_PROBE_BODY), RECORD, EncryptedField.DOCUMENT_SOURCE_TEXT)
        val flipped = sealed.bytes.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }

        val branches =
            mapOf(
                "태그 불일치" to EncryptedContent(flipped, sealed.scheme, 1),
                "모르는 방식" to EncryptedContent(sealed.bytes, "fernet-v1", 1),
                "모르는 키 세대" to EncryptedContent(sealed.bytes, sealed.scheme, UNKNOWN_KEY_VERSION),
                "길이 미달" to EncryptedContent(ByteArray(1), sealed.scheme, 1),
            )

        val medians = failureMedians(cipher, branches)
        val ratio = medians.values.max() / medians.values.min().coerceAtLeast(MIN_MEASURABLE_NANOS)

        println(
            "X3 %s → 비 %.3f (문턱 %.1f · 표본 각 %d · 워밍업 %d라운드)".format(
                medians.entries.joinToString(" / ") { "%s %.0fns".format(it.key, it.value) },
                ratio,
                MAX_TIMING_RATIO,
                TIMING_SAMPLES,
                TIMING_WARMUP_ROUNDS,
            ),
        )

        assertThat(ratio)
            .withFailMessage(
                "복호화 실패 갈래의 소요 시간이 갈린다 (비 %.2f배 · 문턱 %.1f): %s.\n" +
                    "  타입·메시지가 같아도 시간이 갈리면 「어디서 깨졌는지」가 그대로 새어 나가고,\n" +
                    "  「모르는 키 세대」가 빠른 갈래에 있으면 **서버에 설정된 키 세대를 셀 수 있다**.\n" +
                    "  `AesGcmContentCipher.decrypt` 가 어느 갈래에서도 `open()` 을 정확히 한 번 부르는지 먼저 보라.",
                ratio,
                MAX_TIMING_RATIO,
                medians,
            ).isLessThanOrEqualTo(MAX_TIMING_RATIO)
    }

    /** 네 갈래를 섞어 재고 각각의 중앙값(나노초)을 낸다. */
    private fun failureMedians(
        cipher: AesGcmContentCipher,
        branches: Map<String, EncryptedContent>,
    ): Map<String, Double> {
        val plan = (1..TIMING_SAMPLES).flatMap { branches.keys }.shuffled(Random(TIMING_SEED))
        repeat(TIMING_WARMUP_ROUNDS) { branches.values.forEach { failureNanos(cipher, it) } }

        val samples = plan.map { branch -> branch to failureNanos(cipher, branches.getValue(branch)) }
        return branches.keys.associateWith { branch -> medianOf(samples, branch) }
    }

    /** 한 갈래의 중앙값. 표본 수가 홀수라 실제 표본 하나가 나온다. */
    private fun medianOf(
        samples: List<Pair<String, Double>>,
        branch: String,
    ): Double {
        val sorted =
            samples
                .filter { it.first == branch }
                .map { it.second }
                .sorted()
        return sorted[sorted.size / 2]
    }

    /** 실패 한 건에 걸리는 시간(나노초). 실패하지 않으면 잰 것이 다른 경로다. */
    private fun failureNanos(
        cipher: AesGcmContentCipher,
        content: EncryptedContent,
    ): Double {
        val started = System.nanoTime()
        val thrown =
            runCatching {
                cipher.decrypt(content, RECORD, EncryptedField.DOCUMENT_SOURCE_TEXT)
            }.exceptionOrNull()
        val elapsed = System.nanoTime() - started
        check(thrown is DecryptionFailedException) { "실패해야 하는 봉투가 실패하지 않았다: $thrown" }
        return elapsed.toDouble()
    }

    @Test
    @DisplayName("I-7-6 표준 AES-256-GCM 으로 열린다 — 이 테스트가 독립적으로 재조립한다")
    fun `표준 AES-256-GCM 으로 열린다`() {
        val cipher = cipherWith(mapOf(3 to KEY_GEN_1), writeKeyVersion = 3)
        val plain = "표준 AEAD 로 열려야 한다"
        val sealed = cipher.encrypt(PlainBody(plain), RECORD, EncryptedField.CONVERSION_MASKED_ITEMS)

        val opened =
            openWithJca(
                sealed,
                aadOf(EncryptionScheme.AES_256_GCM_V1, 3, EncryptedField.CONVERSION_MASKED_ITEMS),
            )

        assertThat(opened).isEqualTo(plain)
    }

    @Test
    @DisplayName("쓰기 키가 없으면 503 갈래(설정 오류)로 끊고, 복호화 실패와 섞이지 않는다")
    fun `쓰기 키가 없으면 설정 오류다`() {
        val cipher = cipherWith(emptyMap(), writeKeyVersion = 1)

        assertThatThrownBy { cipher.encrypt(PlainBody("본문"), RECORD, EncryptedField.DOCUMENT_SOURCE_TEXT) }
            .describedAs("키 미설정이 500 이나 복호화 실패로 둔갑하면 배포 사고가 사용자 잘못으로 보인다")
            .isInstanceOf(ConfigurationException::class.java)
    }

    @Test
    @DisplayName("잘못된 키 재료는 기동을 막지 않고 그 세대만 뺀다 (값은 로그에 없다)")
    fun `잘못된 키 재료는 그 세대만 뺀다`() {
        val cipher =
            cipherWith(
                mapOf(
                    1 to Secret("!!not-base64!!"),
                    2 to Secret(Base64.getEncoder().encodeToString(ByteArray(SHORT_KEY_BYTES))),
                    3 to KEY_GEN_1,
                ),
                writeKeyVersion = 3,
            )

        val sealed = cipher.encrypt(PlainBody("본문"), RECORD, EncryptedField.DOCUMENT_SOURCE_TEXT)
        assertThat(cipher.decrypt(sealed, RECORD, EncryptedField.DOCUMENT_SOURCE_TEXT).value).isEqualTo("본문")

        val droppedGeneration = EncryptedContent(sealed.bytes, sealed.scheme, 1)
        assertThatThrownBy {
            cipher.decrypt(droppedGeneration, RECORD, EncryptedField.DOCUMENT_SOURCE_TEXT)
        }.isInstanceOf(DecryptionFailedException::class.java)
    }

    @Test
    @DisplayName("암호화·복호화·실패 전 구간의 로그에 평문·키·암호문이 0건 (양성 대조 포함)")
    fun `암호화 경로가 로그로 새지 않는다`() {
        val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as ch.qos.logback.classic.Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        root.addAppender(appender)
        try {
            LoggerFactory.getLogger(javaClass).warn(POSITIVE_CONTROL_MARKER)

            val cipher =
                cipherWith(
                    mapOf(1 to Secret("!!not-base64!!"), 2 to KEY_GEN_1),
                    writeKeyVersion = 2,
                )
            val sealed = cipher.encrypt(PlainBody(LEAK_PROBE_BODY), RECORD, EncryptedField.DOCUMENT_SOURCE_TEXT)
            cipher.decrypt(sealed, RECORD, EncryptedField.DOCUMENT_SOURCE_TEXT)
            runCatching { cipher.decrypt(sealed, OTHER_RECORD, EncryptedField.DOCUMENT_SOURCE_TEXT) }

            val captured = appender.list.joinToString("\n") { render(it) }

            assertThat(captured)
                .describedAs("표식이 캡처에 없다 — 이 케이스는 아무 로그도 보고 있지 않다")
                .contains(POSITIVE_CONTROL_MARKER)
            listOf(
                "평문 본문" to LEAK_PROBE_BODY,
                "키 재료(base64)" to KEY_GEN_1.reveal(),
                "암호문(base64)" to Base64.getEncoder().encodeToString(sealed.bytes),
                "깨진 키 재료" to "!!not-base64!!",
            ).forEach { (label, needle) ->
                assertThat(captured).describedAs("로그에 %s 가 실렸다", label).doesNotContain(needle)
            }
        } finally {
            root.detachAppender(appender)
            appender.stop()
        }
    }

    @Test
    @DisplayName("평문·암호문 래퍼의 toString 이 값을 내지 않는다")
    fun `래퍼가 값을 찍지 않는다`() {
        val body = "주민등록번호가 든 본문"
        val sealed =
            cipherWith(mapOf(1 to KEY_GEN_1), writeKeyVersion = 1)
                .encrypt(PlainBody(body), RECORD, EncryptedField.DOCUMENT_SOURCE_TEXT)

        assertThat(PlainBody(body).toString()).doesNotContain(body).contains("${body.length}자")
        assertThat(sealed.toString()).doesNotContain(Base64.getEncoder().encodeToString(sealed.bytes))
        assertThat(sealed.toString()).contains(EncryptionScheme.AES_256_GCM_V1)
    }

    @Test
    @DisplayName("같은 바이트를 담은 봉투는 서로 같다 — equals 가 참조 동일성이 아니다")
    fun `봉투 동등성이 값 기준이다`() {
        val bytes = ByteArray(NONCE_BYTES + TAG_BYTES) { it.toByte() }

        assertThat(EncryptedContent(bytes.copyOf(), EncryptionScheme.AES_256_GCM_V1, 1))
            .isEqualTo(EncryptedContent(bytes.copyOf(), EncryptionScheme.AES_256_GCM_V1, 1))
        assertThat(EncryptedContent(bytes.copyOf(), EncryptionScheme.AES_256_GCM_V1, 1).hashCode())
            .isEqualTo(EncryptedContent(bytes.copyOf(), EncryptionScheme.AES_256_GCM_V1, 1).hashCode())
        assertThat(EncryptedContent(bytes.copyOf(), EncryptionScheme.AES_256_GCM_V1, 1))
            .isNotEqualTo(EncryptedContent(bytes.copyOf(), EncryptionScheme.AES_256_GCM_V1, 2))
    }

    @Test
    @DisplayName("R-4 공급자의 **비검사** 실패도 밖으로 새지 않는다 — 예외 타입 축의 oracle 을 막는다")
    fun `공급자의 비검사 실패도 단일 예외가 된다`() {
        val cipher = cipherWith(mapOf(1 to KEY_GEN_1), writeKeyVersion = 1)
        val sealed = cipher.encrypt(PlainBody(TIMING_PROBE_BODY), RECORD, EncryptedField.DOCUMENT_SOURCE_TEXT)

        ProviderExceptionProvider.reachedCount = 0
        val probe = ProviderExceptionProvider()
        Security.insertProviderAt(probe, 1)
        try {
            assertThatThrownBy { cipher.decrypt(sealed, RECORD, EncryptedField.DOCUMENT_SOURCE_TEXT) }
                .describedAs("공급자의 RuntimeException 이 그대로 올라왔다 — 호출자가 갈래를 구분할 수 있게 된다")
                .isInstanceOf(DecryptionFailedException::class.java)
                .hasNoCause()
        } finally {
            Security.removeProvider(ProviderExceptionProvider.NAME)
        }

        assertThat(ProviderExceptionProvider.reachedCount)
            .describedAs("바꿔치기한 공급자가 한 번도 선택되지 않았다 — 이 케이스는 **아무것도 재지 않았다**")
            .isPositive()

        assertThat(cipher.decrypt(sealed, RECORD, EncryptedField.DOCUMENT_SOURCE_TEXT).value)
            .describedAs("테스트 공급자가 JVM 에 남았다")
            .isEqualTo(TIMING_PROBE_BODY)
    }

    private fun cipherWith(
        material: Map<Int, Secret>,
        writeKeyVersion: Int,
        random: SecureRandom = SecureRandom(),
    ) = AesGcmContentCipher(material, writeKeyVersion, random)

    /** associated data 를 제품 코드와 독립적으로 다시 만든다. */
    private fun aadOf(
        scheme: String,
        keyVersion: Int,
        field: EncryptedField = EncryptedField.CONVERSION_EASY_TEXT,
        record: UUID = RECORD,
    ): ByteArray = "easydoc-aead|$scheme|$keyVersion|${field.wireName}|$record".toByteArray(Charsets.UTF_8)

    /** 봉투를 JCA 로 직접 연다. 제품의 `decrypt` 를 부르지 않으므로 이른 관문을 지나지 않는다. */
    private fun openWithJca(
        sealed: EncryptedContent,
        aad: ByteArray,
        key: Secret = KEY_GEN_1,
    ): String {
        val jca = Cipher.getInstance("AES/GCM/NoPadding")
        jca.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(Base64.getDecoder().decode(key.reveal()), "AES"),
            GCMParameterSpec(TAG_BITS, sealed.bytes, 0, NONCE_BYTES),
        )
        jca.updateAAD(aad)
        return String(jca.doFinal(sealed.bytes, NONCE_BYTES, sealed.bytes.size - NONCE_BYTES), Charsets.UTF_8)
    }

    /** 로그 한 줄이 실제로 남기는 것 전부 — 메시지와 예외 체인(스택 프레임 포함). */
    private fun render(event: ILoggingEvent): String =
        buildString {
            append(event.loggerName).append(' ').append(event.formattedMessage)
            var throwable: IThrowableProxy? = event.throwableProxy
            while (throwable != null) {
                append('\n').append(throwable.className).append(": ").append(throwable.message)
                throwable.stackTraceElementProxyArray?.forEach { append('\n').append(it.steAsString) }
                throwable = throwable.cause
            }
        }

    /** nonce 를 항상 같은 값으로 주는 난수원. 음성 대조 전용이다. */
    private class FixedNonceRandom : SecureRandom() {
        override fun nextBytes(bytes: ByteArray) {
            bytes.fill(0)
        }
    }

    private companion object {
        val RECORD: UUID = UUID.fromString("11111111-1111-4111-8111-111111111111")
        val OTHER_RECORD: UUID = UUID.fromString("22222222-2222-4222-8222-222222222222")

        const val NONCE_BYTES = 12
        const val TAG_BITS = 128
        const val TAG_BYTES = TAG_BITS / 8

        /** AES-256 이 아닌 길이. 「32바이트가 아니면 쓰지 않는다」의 표본이다. */
        const val SHORT_KEY_BYTES = 16

        /** 설정에 없는 세대 번호. */
        const val UNKNOWN_KEY_VERSION = 99

        /** AAD 결속 케이스가 쓰는 세대. 1·2 와 겹치지 않게 둔다(다른 케이스와 섞이지 않도록). */
        const val AAD_PROBE_VERSION = 5

        /**
         * 짝 없는 상위 서로게이트를 담은 본문. `String.toByteArray(UTF_8)` 가 `?` 로 바꾼다 —
         * 게이트 25 X1 의 반례 그대로다.
         */
        const val LONE_SURROGATE_BODY = "x\uD800y"

        /** 각 갈래의 표본 수. 홀수라 중앙값이 실제 표본 하나다. */
        const val TIMING_SAMPLES = 2_001

        /** 버리는 워밍업 라운드(한 라운드 = 네 갈래 한 번씩). 클래스 적재·JIT 를 재지 않는다. */
        const val TIMING_WARMUP_ROUNDS = 5_000

        /** 교차 순서를 고정하는 시드. 실행마다 순서가 바뀌면 재현이 안 된다. */
        const val TIMING_SEED = 20_260_819L

        /** 갈래 간 중앙값 비의 상한. M-3b 와 같은 자(yardstick)다. */
        const val MAX_TIMING_RATIO = 1.5

        /** 0 나누기를 막는 하한(나노초). 이보다 짧으면 측정 분해능 아래다. */
        const val MIN_MEASURABLE_NANOS = 1.0

        /**
         * 시간 축 케이스의 본문. 짧게 둔다 — 길이 미달 갈래는 최소 길이 더미로 대체되므로,
         * 정상 봉투가 길면 AEAD 의 길이 비례 비용이 비에 섞인다.
         */
        const val TIMING_PROBE_BODY = "안내"

        /** 암호문에서 평문 조각을 찾을 때 쓰는 최소 길이. 짧으면 우연히 겹친다. */
        const val MIN_VISIBLE_LENGTH = 4

        const val POSITIVE_CONTROL_MARKER = "AEAD-LOG-PROBE-4b91"

        /** 로그에 있으면 안 되는 평문. 합성값이다. */
        const val LEAK_PROBE_BODY = "민원인 홍길동 900101-1234567 안내"

        /** 실행 시점에 만드는 32바이트 키 두 개. 소스에 키 리터럴을 적지 않는다. */
        val KEY_GEN_1: Secret = randomKey()
        val KEY_GEN_2: Secret = randomKey()

        fun randomKey(): Secret {
            val bytes = ByteArray(32)
            SecureRandom().nextBytes(bytes)
            return Secret(Base64.getEncoder().encodeToString(bytes))
        }

        /** round-trip 표본. I-7 검증 1 이 열거한 다섯 종류를 그대로 담는다. */
        val ROUND_TRIP_SAMPLES =
            listOf(
                "한글" to "행정복지센터에서 신청하세요.",
                "ASCII" to "Please visit the community center.",
                "빈 값" to "",
                "긴 값" to "가나다라마바사".repeat(5000),
                "개행·탭" to "첫째 줄\n\t들여쓴 둘째 줄\r\n셋째 줄",
                "유니코드 경계" to "이모지 🙂 와 결합 문자 각́",
            )
    }
}
