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

/**
 * 저장 암호화 AEAD 의 정확성 — `migration-safety-gate` **I-7 전건**.
 *
 * ## 왜 parity 하네스가 아니라 여기인가
 *
 * I-7 검증 1 이 못박은 자리다: *"Kotlin 자체 테스트로 확인한다 — parity 하네스로는 못 본다.
 * 암호문이 불투명해 비교기가 열 수 없고, 산출물의 `"tamper_rejected": true` 같은 필드는
 * 하네스의 자기 신고라 증거가 아니다."* 2026-08-12 재개발 전환으로 parity 의 `crypto`
 * 도메인이 사라졌고, 그 요구가 통째로 이 파일로 왔다.
 *
 * ## I-7 항목 ↔ 케이스
 *
 * | I-7 | 무엇을 요구하나 | 케이스 |
 * |---|---|---|
 * | 1 | round-trip (한글·ASCII·빈 값·긴 값·개행/탭) | `round-trip 이 성립한다` |
 * | 2 | 변조·다른 키·형식 아닌 바이트가 **전건 실패** | `변조된 바이트를 거부한다` · `다른 키를 거부한다` · `형식이 아닌 바이트를 거부한다` |
 * | 3 | 복호화 oracle 금지 (단일 예외·같은 메시지·원인 미노출) | `실패 갈래가 서로 구분되지 않는다` |
 * | 4 | nonce 재사용 금지 | `같은 평문을 두 번 암호화하면 결과가 다르다` + 음성 대조 `난수원을 고정하면 nonce 가 반복된다` |
 * | 5 | `encryption_scheme`·`key_version` 이 실제로 쓰인다(키 회전) | `키를 회전해도 옛 세대를 읽는다` · `결속값이 어긋나면 거부한다` |
 * | 6 | 즉흥 암호 금지(표준 AEAD) | `표준 AES-256-GCM 으로 열린다` |
 *
 * 여기에 저장소 공통 규율 둘을 더한다 — **로그에 평문·키·암호문이 0건**(`CLAUDE.md` 보안
 * 규칙, `PasswordHashLogLeakReachTest` 와 같은 방식)과 **쓰기 키 미설정은 503 갈래**.
 *
 * ## 키는 소스에 적지 않는다
 *
 * 실행 시점에 난수로 만든다. 테스트용이라도 난수꼴 리터럴은 스캐너 `SECRET-LITERAL` 의
 * 차단 대상이고(*"경로가 아니라 값의 모양이 기준"*), 무엇보다 **키를 소스에 적는 습관**
 * 자체가 이 저장소가 금지한 것이다.
 */
class AesGcmContentCipherTest {
    // ================================================================ I-7 검증 1

    @Test
    @DisplayName("I-7-1 round-trip — 한글·ASCII·빈 값·긴 값·개행/탭이 그대로 돌아온다")
    fun `round-trip 이 성립한다`() {
        val cipher = cipherWith(mapOf(1 to KEY_GEN_1), writeKeyVersion = 1)

        ROUND_TRIP_SAMPLES.forEach { (label, plain) ->
            val sealed = cipher.encrypt(PlainBody(plain), RECORD, EncryptedField.DOCUMENT_SOURCE_TEXT)
            val opened = cipher.decrypt(sealed, RECORD, EncryptedField.DOCUMENT_SOURCE_TEXT)

            assertThat(opened.value).describedAs("round-trip 실패: %s", label).isEqualTo(plain)
            // 암호문이 평문을 그대로 담고 있지 않다는 최소 확인. 「암호화했다」의 자기 신고를
            // 믿지 않는 자리다 — 실수로 통과 구현(identity)을 넣으면 여기서 걸린다.
            assertThat(String(sealed.bytes, Charsets.UTF_8))
                .describedAs("암호문에 평문이 보인다: %s", label)
                .doesNotContain(plain.take(MIN_VISIBLE_LENGTH).ifEmpty { "\u0000없는값" })
        }
    }

    @Test
    @DisplayName("I-7-1 왕복이 깨질 값은 **저장 전에 거부된다** — round-trip 이 정의역 전체에서 참이다 (X1)")
    fun `왕복이 깨질 값은 평문으로 만들어지지 않는다`() {
        // 게이트 25 X1: `String.toByteArray(UTF_8)` 가 짝 없는 서로게이트를 `?` 로 바꾼다.
        // 그 값이 암호화 경로에 들어오면 태그는 통과하는데 본문이 달라진다 — 인증된 손상이다.
        // 고치는 자리는 AEAD 가 아니라 **평문의 정의역**이고(`PlainBody` init), 그래서 여기서
        // 재는 것은 「cipher 가 거부한다」가 아니라 **「cipher 에 닿지 못한다」**이다.
        assertThatThrownBy { PlainBody(LONE_SURROGATE_BODY) }
            .describedAs("짝 없는 서로게이트가 평문 래퍼를 통과했다 — 저장하면 본문이 조용히 바뀐다")
            .isInstanceOf(InvalidInputException::class.java)
            .hasMessage(PlainBody.UNPAIRED_SURROGATE_MESSAGE)

        // 왜 그 값이 위험한지를 여기서도 붙잡아 둔다. 이 단언이 빨개지면(= JDK 가 보존하기
        // 시작하면) 위 거부의 근거가 사라진 것이고, 그때 다시 판정해야 한다.
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
        // nonce(12) + 태그(16) 는 평문이 비어도 붙는다.
        assertThat(sealed.bytes.size).isGreaterThanOrEqualTo(NONCE_BYTES + TAG_BYTES)
    }

    // ================================================================ I-7 검증 4

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
        // 둘 다 열려야 한다 — 「다르기만 하면 된다」가 아니다.
        assertThat(cipher.decrypt(second, RECORD, EncryptedField.DOCUMENT_SOURCE_TEXT).value).isEqualTo(plain.value)
    }

    @Test
    @DisplayName("음성 대조 — 난수원을 고정하면 nonce 가 반복된다 (위 케이스가 무엇을 재는지 확인)")
    fun `난수원을 고정하면 nonce 가 반복된다`() {
        // **이 케이스가 없으면 위 단언이 무엇 때문에 초록인지 알 수 없다.** nonce 가 매번
        // 다른 이유가 난수원이라는 것을 여기서 고정한다 — 구현이 난수원을 무시하고 다른
        // 방식으로 바이트를 다르게 만들고 있다면 여기가 초록이 아니라 빨개진다.
        val cipher = cipherWith(mapOf(1 to KEY_GEN_1), writeKeyVersion = 1, random = FixedNonceRandom())
        val plain = PlainBody("같은 문장을 두 번 올린다")

        val first = cipher.encrypt(plain, RECORD, EncryptedField.DOCUMENT_SOURCE_TEXT)
        val second = cipher.encrypt(plain, RECORD, EncryptedField.DOCUMENT_SOURCE_TEXT)

        assertThat(first.bytes.copyOf(NONCE_BYTES)).isEqualTo(second.bytes.copyOf(NONCE_BYTES))
        assertThat(first.bytes)
            .describedAs("nonce 를 고정했는데도 암호문이 다르다 — 난수가 nonce 말고 다른 곳에서 들어가고 있다")
            .isEqualTo(second.bytes)
    }

    // ================================================================ I-7 검증 2

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

    // ================================================================ I-7 검증 5 (결속)

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
        // ## 이 구성이 왜 필요한가 (게이트 25 X4 · R-1)
        //
        // 위 `결속값이 어긋나면 거부한다` 의 「키 세대 컬럼 조작」은 v1↔v2 의 **키가 달라서**도
        // 실패한다. 그래서 AAD 에서 `keyVersion` 축을 통째로 빼도 그 케이스는 초록이었다
        // (MUT-B 17/17 GREEN). 여기서는 **두 세대에 같은 키 재료**를 넣어 그 설명을 없앤다 —
        // 남는 설명은 AAD 의 세대 축뿐이다. 오설정으로 실제로 일어날 수 있는 형태이기도 하다.
        val shared = cipherWith(mapOf(1 to KEY_GEN_1, 2 to KEY_GEN_1), writeKeyVersion = 1)
        val sealedAtV1 = shared.encrypt(PlainBody("회전 라벨 결속"), RECORD, EncryptedField.DOCUMENT_SOURCE_TEXT)

        // 대조군 — 두 세대의 키가 실제로 같다. v2 로 쓴 봉투는 v2 로 열린다.
        val writerAtV2 = cipherWith(mapOf(1 to KEY_GEN_1, 2 to KEY_GEN_1), writeKeyVersion = 2)
        val sealedAtV2 = writerAtV2.encrypt(PlainBody("회전 라벨 결속"), RECORD, EncryptedField.DOCUMENT_SOURCE_TEXT)
        assertThat(shared.decrypt(sealedAtV2, RECORD, EncryptedField.DOCUMENT_SOURCE_TEXT).value)
            .describedAs("두 세대의 키가 서로 다르다 — 이 케이스는 AAD 가 아니라 키 차이를 재고 있다")
            .isEqualTo("회전 라벨 결속")

        // 본 단언 — 세대 라벨만 v1 → v2 로 바꾼다. 키는 같으므로 AAD 만이 둘을 가른다.
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
        // 방식 축은 제품 경로로 잴 수 없다 — `decrypt` 의 이른 관문이 AAD 에 닿기 전에 끊는다.
        // 그래서 봉투를 **JCA 로 직접** 연다. 아래 첫 단언(정본 AAD 로 열린다)이 「두 축이 AAD
        // 안에 있다」의 탐지자다: AAD 에서 축 하나를 빼면 조립이 어긋나 여기가 빨개진다.
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

        // 회전 = 새 세대를 설정에 더하고 쓰기 세대를 올린다. 옛 세대는 목록에 남긴다.
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

    // ================================================================ I-7 검증 3 (oracle)

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

    /**
     * **실패 갈래의 소요 시간이 갈리지 않는다** — 게이트 25 X3(codex 6.5배 · privacy-gate 2.84배).
     *
     * ## 이 케이스가 상설 회귀인 이유
     *
     * 타입·메시지·`cause` 가 같아도 **처리량이 다르면** 갈래가 새어 나간다. 종전 판은
     * 모르는 방식·없는 키 세대·길이 미달을 AEAD 에 닿기 전에 끊어, 그 셋이 태그 검증 실패보다
     * 몇 배 빨랐다. 위 `실패 갈래가 서로 구분되지 않는다` 는 그 상태를 **초록으로 통과시켰다** —
     * 바이트 축만 보기 때문이다. 그래서 시간 축을 따로 잰다.
     *
     * ## 측정 방법을 `AuthEndpointReachTest` M-3b 에 맞춘다
     *
     * 워밍업 [TIMING_WARMUP_ROUNDS] 라운드 폐기 · 고정 시드 교차 순서 · 표본 각
     * [TIMING_SAMPLES] · 중앙값 · 문턱 [MAX_TIMING_RATIO]. **이 저장소는 시간 축 게이트가
     * 흔들려 꺼진 선례를 갖고 있으므로** 방법을 임의로 줄이지 않는다. 순서를 섞는 이유도 같다 —
     * JIT 가 진행형으로 데워지므로 뒤에 몰린 갈래가 유리하고, 그 편향은 격차를 **줄이는**
     * 방향이라 마스킹이 된다.
     *
     * 본문을 짧게 두는 이유: 길이 미달 갈래는 최소 길이 더미 바이트로 대체되므로, 정상 봉투가
     * 길면 **AEAD 자신의 길이 비례 비용**이 비에 섞인다. 그 축은 이 케이스가 재는 것이 아니다.
     *
     * ## 이 케이스가 막지 못하는 것
     *
     * 네 갈래가 **함께** 느려지거나 빨라지는 변경은 비가 1 에 가까워 통과한다. 그 축은
     * `decrypt` 가 어느 갈래에서도 AEAD 를 정확히 한 번 부른다는 구조와 위 oracle 케이스가
     * 함께 지킨다 — 이 케이스는 그것들을 대체하지 않고 **더한다**.
     */
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
        // 초록일 때도 수치를 남긴다 — 문턱까지의 여유가 보이지 않으면 서서히 벌어지는
        // 드리프트를 아무도 모른 채 지나간다(M-3b 와 같은 규율).
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

    /** 실패 한 건에 걸리는 시간(나노초). **실패하지 않으면 잰 것이 다른 경로다.** */
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

    // ================================================================ I-7 검증 6

    @Test
    @DisplayName("I-7-6 표준 AES-256-GCM 으로 열린다 — 이 테스트가 독립적으로 재조립한다")
    fun `표준 AES-256-GCM 으로 열린다`() {
        // **제품 코드를 부르지 않고** JCA 로 직접 연다. 이것이 "즉흥 암호가 아니다"의 증거다 —
        // 프리미티브를 손으로 조립한 구현은 여기서 열리지 않는다. 형식(nonce 12 || 암호문 ||
        // 태그 16)과 associated data 문자열도 여기서 다시 만들므로, 어느 하나가 조용히
        // 바뀌면 이 케이스가 빨개진다.
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

    // ================================================================ 설정·로그

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
        // base64 가 아닌 값과 길이가 다른 값. 기동을 끊지 않는 규약은 AuthProperties 와 같다.
        val cipher =
            cipherWith(
                mapOf(
                    1 to Secret("!!not-base64!!"),
                    2 to Secret(Base64.getEncoder().encodeToString(ByteArray(SHORT_KEY_BYTES))),
                    3 to KEY_GEN_1,
                ),
                writeKeyVersion = 3,
            )

        // 살아 있는 세대로는 정상 동작한다.
        val sealed = cipher.encrypt(PlainBody("본문"), RECORD, EncryptedField.DOCUMENT_SOURCE_TEXT)
        assertThat(cipher.decrypt(sealed, RECORD, EncryptedField.DOCUMENT_SOURCE_TEXT).value).isEqualTo("본문")
        // 버려진 세대를 가리키는 행은 단일 예외로 실패한다(oracle 금지 규칙과 같은 갈래).
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
            // 잘못된 키 재료가 섞인 조립까지 포함한다 — 경고를 찍는 유일한 경로가 거기다.
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
        // `open` 의 두 번째 catch(`RuntimeException`)에 음성 통제가 없다는 codex D-3 지적의
        // 통제다. 이 케이스를 두면 그 catch 를 지웠을 때 `ProviderException` 이 그대로 올라와
        // 빨개진다. 사유와 이 방법을 고른 이유는 `ProviderExceptionProvider` KDoc.
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

        // 공급자를 되돌린 뒤 정상 경로가 살아 있는지도 본다. 전역 상태를 건드린 케이스가
        // 뒤 테스트를 조용히 망가뜨리는 것을 여기서 끊는다.
        assertThat(cipher.decrypt(sealed, RECORD, EncryptedField.DOCUMENT_SOURCE_TEXT).value)
            .describedAs("테스트 공급자가 JVM 에 남았다")
            .isEqualTo(TIMING_PROBE_BODY)
    }

    // ---------------------------------------------------------------- 도구

    private fun cipherWith(
        material: Map<Int, Secret>,
        writeKeyVersion: Int,
        random: SecureRandom = SecureRandom(),
    ) = AesGcmContentCipher(material, writeKeyVersion, random)

    /**
     * associated data 를 **제품 코드와 독립적으로** 다시 만든다.
     *
     * 형식이 한 조각이라도 바뀌면(축 삭제·구분자 변경·순서 변경) 이것으로 조립한 AAD 가
     * 어긋나 [openWithJca] 가 실패한다 — 그것이 「방식·키 세대가 AAD 에 실린다」의 탐지자다.
     */
    private fun aadOf(
        scheme: String,
        keyVersion: Int,
        field: EncryptedField = EncryptedField.CONVERSION_EASY_TEXT,
        record: UUID = RECORD,
    ): ByteArray = "easydoc-aead|$scheme|$keyVersion|${field.wireName}|$record".toByteArray(Charsets.UTF_8)

    /** 봉투를 **JCA 로 직접** 연다. 제품의 `decrypt` 를 부르지 않으므로 이른 관문을 지나지 않는다. */
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

        // ── 시간 축 회귀 상수 ───────────────────────────────────────────────
        //
        // 방법과 문턱을 `AuthEndpointReachTest` M-3b 와 맞춘다. 값을 임의로 줄이면
        // 케이스가 흔들리고, 흔들린 시간 축 게이트는 꺼지는 것으로 끝난다(원장 §1 ③).

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
         * 시간 축 케이스의 본문. **짧게 둔다** — 길이 미달 갈래는 최소 길이 더미로 대체되므로,
         * 정상 봉투가 길면 AEAD 의 길이 비례 비용이 비에 섞인다.
         */
        const val TIMING_PROBE_BODY = "안내"

        /** 암호문에서 평문 조각을 찾을 때 쓰는 최소 길이. 짧으면 우연히 겹친다. */
        const val MIN_VISIBLE_LENGTH = 4

        const val POSITIVE_CONTROL_MARKER = "AEAD-LOG-PROBE-4b91"

        /** 로그에 있으면 안 되는 평문. 합성값이다. */
        const val LEAK_PROBE_BODY = "민원인 홍길동 900101-1234567 안내"

        /**
         * 실행 시점에 만드는 32바이트 키 두 개. 소스에 키 리터럴을 적지 않는다.
         */
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
