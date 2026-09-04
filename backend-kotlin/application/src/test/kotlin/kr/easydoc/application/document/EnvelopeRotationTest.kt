package kr.easydoc.application.document

import kr.easydoc.application.auth.TransactionRunner
import kr.easydoc.application.crypto.ContentCipher
import kr.easydoc.core.crypto.EncryptedContent
import kr.easydoc.core.crypto.EncryptedField
import kr.easydoc.core.crypto.EncryptionScheme
import kr.easydoc.core.crypto.PlainBody
import kr.easydoc.core.crypto.PlainBytes
import kr.easydoc.core.document.Conversion
import kr.easydoc.core.document.ConversionStatus
import kr.easydoc.core.document.Document
import kr.easydoc.core.document.DocumentListing
import kr.easydoc.core.exceptions.DecryptionFailedException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.UUID

/** 행 단위 재암호화의 네 조건 — 게이트 25 X5 / privacy-gate F-5. */
class EnvelopeRotationTest {
    @Test
    @DisplayName("옛 세대 행을 새 세대로 다시 봉인한다 — 세 열이 **한 번의** 갱신으로 바뀐다")
    fun `옛 세대를 회전한다`() {
        val world = World()
        world.conversions.envelope = envelopeOf(OLD_VERSION, "초안", "대응표", "검수본")

        val outcome = world.rotation.rotateConversion(CONVERSION)

        assertThat(outcome).isEqualTo(RotationOutcome.ROTATED)
        assertThat(world.conversions.rewrites).hasSize(1)
        val rewrite = world.conversions.rewrites.single()
        assertThat(rewrite.expected.keyVersion).isEqualTo(OLD_VERSION)
        assertThat(rewrite.keyVersion).isEqualTo(NEW_VERSION)
        assertThat(rewrite.ciphertexts.easyText?.keyVersion).isEqualTo(NEW_VERSION)
        assertThat(rewrite.ciphertexts.maskedItems?.keyVersion).isEqualTo(NEW_VERSION)
        assertThat(rewrite.ciphertexts.editedText?.keyVersion).isEqualTo(NEW_VERSION)
    }

    @Test
    @DisplayName("**NULL 보존** — 대기 중 변환의 빈 열을 빈 문자열로 암호화하지 않는다")
    fun `NULL 을 보존한다`() {
        val world = World()
        world.conversions.envelope = envelopeOf(OLD_VERSION, null, null, null)

        val outcome = world.rotation.rotateConversion(CONVERSION)

        assertThat(outcome).isEqualTo(RotationOutcome.ROTATED)
        val rewrite = world.conversions.rewrites.single()
        assertThat(rewrite.ciphertexts.easyText).isNull()
        assertThat(rewrite.ciphertexts.maskedItems).isNull()
        assertThat(rewrite.ciphertexts.editedText).isNull()
        assertThat(world.cipher.encryptCalls)
            .describedAs("빈 열을 암호화했다 — 없던 내용을 지어낸 것이고 되돌릴 수 없다")
            .isZero()
        assertThat(rewrite.keyVersion).describedAs("봉투 두 값은 그래도 새 세대로 옮겨야 한다").isEqualTo(NEW_VERSION)
    }

    @Test
    @DisplayName("일부만 채워진 행도 채워진 열만 다시 봉인한다")
    fun `일부만 채워진 행`() {
        val world = World()
        world.conversions.envelope = envelopeOf(OLD_VERSION, "초안", null, null)

        world.rotation.rotateConversion(CONVERSION)

        val rewrite = world.conversions.rewrites.single()
        assertThat(rewrite.ciphertexts.easyText).isNotNull()
        assertThat(rewrite.ciphertexts.maskedItems).isNull()
        assertThat(rewrite.ciphertexts.editedText).isNull()
    }

    @Test
    @DisplayName("**실패 시 전체 중단** — 한 열이라도 열리지 않으면 UPDATE 를 부르지 않는다")
    fun `한 열이라도 실패하면 중단한다`() {
        val world = World(unopenable = EncryptedField.CONVERSION_MASKED_ITEMS)
        world.conversions.envelope = envelopeOf(OLD_VERSION, "초안", "대응표", "검수본")

        assertThatThrownBy { world.rotation.rotateConversion(CONVERSION) }
            .isInstanceOf(DecryptionFailedException::class.java)

        assertThat(world.conversions.rewrites)
            .describedAs("부분 회전 행은 영원히 열리지 않는다 — UPDATE 가 불리면 안 된다")
            .isEmpty()
        assertThat(world.transaction.failed).isEqualTo(1)
        assertThat(world.transaction.committed).isZero()
    }

    @Test
    @DisplayName("이미 쓰기 세대면 아무것도 하지 않는다 — 무의미한 재암호화가 돌지 않는다")
    fun `이미 최신이면 건너뛴다`() {
        val world = World()
        world.conversions.envelope = envelopeOf(NEW_VERSION, "초안", null, null)

        val outcome = world.rotation.rotateConversion(CONVERSION)

        assertThat(outcome).isEqualTo(RotationOutcome.ALREADY_CURRENT)
        assertThat(world.conversions.rewrites).isEmpty()
        assertThat(world.cipher.encryptCalls).isZero()
    }

    @Test
    @DisplayName("행이 없으면 MISSING — 「갱신 안 됨」과 구분한다")
    fun `없는 행은 MISSING 이다`() {
        val world = World()
        world.conversions.envelope = null

        assertThat(world.rotation.rotateConversion(CONVERSION)).isEqualTo(RotationOutcome.MISSING)
    }

    @Test
    @DisplayName("**낙관적 조건**에서 지면 CONTENDED — 잠금 전제가 성립하지 않았다는 신호다")
    fun `경합에서 지면 CONTENDED 다`() {
        val world = World()
        world.conversions.envelope = envelopeOf(OLD_VERSION, "초안", null, null)
        world.conversions.updated = false

        assertThat(world.rotation.rotateConversion(CONVERSION)).isEqualTo(RotationOutcome.CONTENDED)
    }

    @Test
    @DisplayName("쓰기 조건이 **읽어 온 행 그 자체**다 — 세대 정수 하나로 좁혀지지 않는다")
    fun `쓰기 조건이 읽은 행 전부다`() {
        val world = World()
        val loaded = envelopeOf(OLD_VERSION, "초안", "대응표", null)
        world.conversions.envelope = loaded

        world.rotation.rotateConversion(CONVERSION)

        val expected =
            world.conversions.rewrites
                .single()
                .expected
        assertThat(expected).isSameAs(loaded)
        assertThat(expected.ciphertexts.easyText).isEqualTo(loaded.ciphertexts.easyText)
        assertThat(expected.ciphertexts.maskedItems).isEqualTo(loaded.ciphertexts.maskedItems)
        assertThat(expected.ciphertexts.editedText)
            .describedAs("비어 있던 열의 `null` 도 조건이다 — 「비었다」가 「채워졌다」로 바뀐 것이 잡아야 할 사건이다")
            .isNull()
    }

    @Test
    @DisplayName("복호화·재암호화가 **행 식별자와 컬럼에 결속**된 채로 돈다")
    fun `결속을 유지한 채 회전한다`() {
        val world = World()
        world.conversions.envelope = envelopeOf(OLD_VERSION, "초안", "대응표", "검수본")

        world.rotation.rotateConversion(CONVERSION)

        assertThat(world.cipher.bindings).containsExactlyInAnyOrder(
            CONVERSION to EncryptedField.CONVERSION_EASY_TEXT,
            CONVERSION to EncryptedField.CONVERSION_MASKED_ITEMS,
            CONVERSION to EncryptedField.CONVERSION_EDITED_TEXT,
        )
    }

    @Test
    @DisplayName("문서 원문도 같은 규칙으로 회전한다 — 컬럼이 하나라 NULL 보존 조건이 없다")
    fun `문서 원문을 회전한다`() {
        val world = World()
        world.documents.sourceText = sealed("원문", OLD_VERSION)

        val outcome = world.rotation.rotateDocument(DOCUMENT)

        assertThat(outcome).isEqualTo(RotationOutcome.ROTATED)
        val rewrite = world.documents.rewrites.single()
        assertThat(rewrite.first)
            .describedAs("쓰기 조건은 읽어 온 암호문 그 자체다 — 세대 정수 하나가 아니다")
            .isEqualTo(world.documents.sourceText)
        assertThat(rewrite.first.keyVersion).isEqualTo(OLD_VERSION)
        assertThat(rewrite.second.keyVersion).isEqualTo(NEW_VERSION)
        assertThat(world.cipher.bindings).containsExactly(DOCUMENT to EncryptedField.DOCUMENT_SOURCE_TEXT)
    }

    @Test
    @DisplayName("문서 행이 없으면 MISSING, 이미 최신이면 ALREADY_CURRENT 다")
    fun `문서 회전의 나머지 갈래`() {
        val missing = World()
        missing.documents.sourceText = null
        assertThat(missing.rotation.rotateDocument(DOCUMENT)).isEqualTo(RotationOutcome.MISSING)

        val current = World()
        current.documents.sourceText = sealed("원문", NEW_VERSION)
        assertThat(current.rotation.rotateDocument(DOCUMENT)).isEqualTo(RotationOutcome.ALREADY_CURRENT)
        assertThat(current.documents.rewrites).isEmpty()
    }

    @Test
    @DisplayName("문서 원문이 열리지 않으면 UPDATE 를 부르지 않는다")
    fun `문서 복호화 실패는 중단한다`() {
        val world = World(unopenable = EncryptedField.DOCUMENT_SOURCE_TEXT)
        world.documents.sourceText = sealed("원문", OLD_VERSION)

        assertThatThrownBy { world.rotation.rotateDocument(DOCUMENT) }
            .isInstanceOf(DecryptionFailedException::class.java)

        assertThat(world.documents.rewrites).isEmpty()
        assertThat(world.transaction.failed).isEqualTo(1)
    }

    @Test
    @DisplayName("업로드 원본도 회전한다 — **바이트가 한 비트도 바뀌지 않는다**")
    fun `원본 바이트를 회전한다`() {
        val world = World()
        val original = sealedBytes(ORIGINAL_FILE, OLD_VERSION)
        world.originals.original = original

        val outcome = world.rotation.rotateDocumentOriginal(DOCUMENT)

        assertThat(outcome).isEqualTo(RotationOutcome.ROTATED)
        val rewrite = world.originals.rewrites.single()
        assertThat(rewrite.first)
            .describedAs("쓰기 조건은 잠근 채 읽은 암호문 그 자체다 — 세대 정수 하나가 아니다")
            .isSameAs(original)
        assertThat(rewrite.second.keyVersion).isEqualTo(NEW_VERSION)
        assertThat(rewrite.second.bytes)
            .describedAs(
                "회전이 원본 바이트를 바꿨다. 문자열 짝(UTF-8 왕복)을 타면 해석되지 않는 " +
                    "바이트가 U+FFFD 로 눌려 파일이 조용히 망가진다 — 되돌릴 수 없다",
            ).isEqualTo(ORIGINAL_FILE)
        assertThat(world.cipher.bindings)
            .describedAs("결속이 어긋나면 회전한 행이 열리지 않는다")
            .containsExactly(DOCUMENT to EncryptedField.DOCUMENT_ORIGINAL_BYTES)
    }

    @Test
    @DisplayName("**원본이 없으면 MISSING** — 붙여넣기 문서는 회전할 것이 없다")
    fun `원본이 없으면 MISSING 이다`() {
        val world = World()
        world.originals.original = null

        assertThat(world.rotation.rotateDocumentOriginal(DOCUMENT)).isEqualTo(RotationOutcome.MISSING)
        assertThat(world.originals.rewrites).isEmpty()
        assertThat(world.cipher.encryptCalls)
            .describedAs("없는 원본을 지어내 봉인했다")
            .isZero()
    }

    @Test
    @DisplayName("원본 회전의 나머지 갈래 — 이미 최신·경합")
    fun `원본 회전의 나머지 갈래`() {
        val current = World().apply { originals.original = sealedBytes(ORIGINAL_FILE, NEW_VERSION) }
        assertThat(current.rotation.rotateDocumentOriginal(DOCUMENT)).isEqualTo(RotationOutcome.ALREADY_CURRENT)
        assertThat(current.originals.rewrites).isEmpty()
        assertThat(current.cipher.encryptCalls).isZero()

        val contended =
            World().apply {
                originals.original = sealedBytes(ORIGINAL_FILE, OLD_VERSION)
                originals.updated = false
            }
        assertThat(contended.rotation.rotateDocumentOriginal(DOCUMENT)).isEqualTo(RotationOutcome.CONTENDED)
    }

    @Test
    @DisplayName("원본이 열리지 않으면 UPDATE 를 부르지 않는다")
    fun `원본 복호화 실패는 중단한다`() {
        val world = World(unopenable = EncryptedField.DOCUMENT_ORIGINAL_BYTES)
        world.originals.original = sealedBytes(ORIGINAL_FILE, OLD_VERSION)

        assertThatThrownBy { world.rotation.rotateDocumentOriginal(DOCUMENT) }
            .isInstanceOf(DecryptionFailedException::class.java)

        assertThat(world.originals.rewrites).isEmpty()
        assertThat(world.transaction.failed).isEqualTo(1)
        assertThat(world.transaction.committed).isZero()
    }

    @Test
    @DisplayName("원문 회전은 원본을 건드리지 않는다 — **두 봉투가 따로 돈다**")
    fun `원문 회전과 원본 회전이 서로를 건드리지 않는다`() {
        val world = World()
        world.sealEverythingAtOldVersion()

        world.rotation.rotateDocument(DOCUMENT)

        assertThat(world.originals.rewrites)
            .describedAs("봉투를 공유하지 않는다는 것이 V3 의 판단이다 — 한쪽 회전이 다른 표를 쓰면 안 된다")
            .isEmpty()
        assertThat(world.cipher.resealed).containsExactly(EncryptedField.DOCUMENT_SOURCE_TEXT)

        world.rotation.rotateDocumentOriginal(DOCUMENT)

        assertThat(world.documents.rewrites)
            .describedAs("원본 회전이 `documents` 를 한 번만 썼어야 한다 — 위 원문 회전 그 한 번이다")
            .hasSize(1)
    }

    @Test
    @DisplayName("봉인된 자유 의견도 회전한다 — v1 로 봉한 것이 v2 뒤에도 **같은 평문**으로 열린다")
    fun `피드백 의견을 회전한다`() {
        val world = World()
        val original = sealed(COMMENT_BODY, OLD_VERSION)
        world.feedback.comment = original

        val outcome = world.rotation.rotateFeedback(CONVERSION)

        assertThat(outcome).isEqualTo(RotationOutcome.ROTATED)
        val rewrite = world.feedback.rewrites.single()
        assertThat(rewrite.expected)
            .describedAs("쓰기 조건은 잠근 채 읽은 암호문 그 자체다 — 세대 정수 하나가 아니다")
            .isSameAs(original)
        assertThat(rewrite.comment.keyVersion).isEqualTo(NEW_VERSION)
        assertThat(rewrite.comment.scheme).isEqualTo(EncryptionScheme.AES_256_GCM_V1)
        assertThat(String(rewrite.comment.bytes, Charsets.UTF_8))
            .describedAs("회전이 평문을 바꾸면 판정 근거가 조용히 달라진다")
            .isEqualTo(COMMENT_BODY)
        assertThat(world.cipher.bindings)
            .describedAs("결속을 유지한 채 돌아야 한다 — AAD 가 어긋나면 회전한 행이 열리지 않는다")
            .containsExactly(CONVERSION to EncryptedField.CONVERSION_FEEDBACK_COMMENT)
    }

    @Test
    @DisplayName("**의견이 없는 행은 NOTHING_SEALED** — 빈 의견을 지어내 봉인하지 않는다")
    fun `의견이 없으면 아무것도 봉하지 않는다`() {
        val world = World()
        world.feedback.comment = null

        val outcome = world.rotation.rotateFeedback(CONVERSION)

        assertThat(outcome).isEqualTo(RotationOutcome.NOTHING_SEALED)
        assertThat(world.feedback.rewrites).isEmpty()
        assertThat(world.cipher.encryptCalls)
            .describedAs("빈 의견을 암호화했다 — 선택 항목이던 칸이 「빈 의견을 남겼다」로 바뀐다")
            .isZero()
    }

    @Test
    @DisplayName("피드백 회전의 나머지 갈래 — 행 없음·이미 최신·경합")
    fun `피드백 회전의 나머지 갈래`() {
        val missing = World().apply { feedback.rowExists = false }
        assertThat(missing.rotation.rotateFeedback(CONVERSION))
            .describedAs("행이 없는 것과 의견이 없는 것은 다른 결과다")
            .isEqualTo(RotationOutcome.MISSING)

        val current = World().apply { feedback.comment = sealed(COMMENT_BODY, NEW_VERSION) }
        assertThat(current.rotation.rotateFeedback(CONVERSION)).isEqualTo(RotationOutcome.ALREADY_CURRENT)
        assertThat(current.feedback.rewrites).isEmpty()

        val contended =
            World().apply {
                feedback.comment = sealed(COMMENT_BODY, OLD_VERSION)
                feedback.updated = false
            }
        assertThat(contended.rotation.rotateFeedback(CONVERSION)).isEqualTo(RotationOutcome.CONTENDED)
    }

    @Test
    @DisplayName("의견이 열리지 않으면 UPDATE 를 부르지 않는다")
    fun `피드백 복호화 실패는 중단한다`() {
        val world = World(unopenable = EncryptedField.CONVERSION_FEEDBACK_COMMENT)
        world.feedback.comment = sealed(COMMENT_BODY, OLD_VERSION)

        assertThatThrownBy { world.rotation.rotateFeedback(CONVERSION) }
            .isInstanceOf(DecryptionFailedException::class.java)

        assertThat(world.feedback.rewrites).isEmpty()
        assertThat(world.transaction.failed).isEqualTo(1)
        assertThat(world.transaction.committed).isZero()
    }

    /**
     * **회전 경로의 전수 대조.** [EncryptedField] 는 봉인된 열의 정본이고, 회전 경로가 없는
     * 열은 옛 세대를 설정에서 내리는 순간 **영원히 열리지 않는다**(AAD 에 `key_version` 이
     * 실린다). `EncryptionSchemeSchemaTest` 는 「이름 ↔ 실제 컬럼」만 재므로 이 공백을 못 본다.
     *
     * 새 봉인 열이 생겼을 때 이 파일이 빨개지는 **강제 수단은 두 겹이다**:
     * 1. [rotationOf] 의 `when` 에 `else` 가 없다 — 새 [EncryptedField] 를 더하면 **컴파일이
     *    깨진다.** 회전 경로를 정하지 않고는 열거형에 값을 더할 수 없다.
     * 2. 갈래를 아무렇게나 이어 붙여도 아래 단언이 잡는다 — 그 갈래를 돌린 뒤 **그 열이 실제로
     *    다시 봉해졌는지**를 암호 대역에서 확인한다.
     */
    @Test
    @DisplayName("**모든 봉인 열이 회전 경로를 가진다** — 새 열이 결속만 얻고 회전을 못 얻는 것을 막는다")
    fun `봉인된 열 전부가 회전 경로를 가진다`() {
        assertThat(EncryptedField.entries)
            .describedAs("봉인 열이 하나도 없다 — 이 대조가 0건을 훑고 통과한다")
            .isNotEmpty()

        val uncovered = EncryptedField.entries.filterNot { field -> field in rotationOf(field) }

        assertThat(uncovered)
            .withFailMessage {
                "회전 경로가 닿지 않는 봉인 열이 있다: ${uncovered.map { it.wireName }.sorted()}\n" +
                    "  키 세대를 올리고 옛 세대를 설정에서 내리면 그 열의 행들은 영원히 열리지 않는다.\n" +
                    "  `EnvelopeRotation` 에 그 열을 여는 갈래를 더하고 이 `when` 을 이어라."
            }.isEmpty()
    }

    /**
     * 그 열을 맡은 회전을 **실제로 돌리고**, 회전이 다시 봉한 열 전부를 돌려준다.
     *
     * `else` 가 없는 것이 이 검사의 절반이다 — 새 [EncryptedField] 는 여기서 컴파일을 깨뜨린다.
     */
    private fun rotationOf(field: EncryptedField): Set<EncryptedField> {
        val world = World()
        world.sealEverythingAtOldVersion()

        val outcome =
            when (field) {
                EncryptedField.DOCUMENT_SOURCE_TEXT -> world.rotation.rotateDocument(DOCUMENT)

                EncryptedField.DOCUMENT_ORIGINAL_BYTES -> world.rotation.rotateDocumentOriginal(DOCUMENT)

                EncryptedField.CONVERSION_EASY_TEXT,
                EncryptedField.CONVERSION_MASKED_ITEMS,
                EncryptedField.CONVERSION_EDITED_TEXT,
                -> world.rotation.rotateConversion(CONVERSION)

                EncryptedField.CONVERSION_FEEDBACK_COMMENT -> world.rotation.rotateFeedback(CONVERSION)
            }

        check(outcome == RotationOutcome.ROTATED) {
            "${field.wireName} 을 맡은 회전이 옛 세대 행을 회전하지 못했다: $outcome"
        }
        return world.cipher.resealed
    }

    private companion object {
        val CONVERSION: UUID = UUID.fromString("00000000-0000-4000-8000-0000000000c1")
        val DOCUMENT: UUID = UUID.fromString("00000000-0000-4000-8000-0000000000d1")
        const val OLD_VERSION = 1
        const val NEW_VERSION = 2

        /** 회전이 평문을 그대로 옮기는지 보는 표식. 값 자체는 성질과 무관하다. */
        const val COMMENT_BODY = "○○동 안내 부분이 어색합니다"

        /**
         * 원본 파일을 흉내 내는 바이트. **UTF-8 로 해석되지 않는 값을 일부러 섞는다** — 회전이
         * 문자열 짝을 타면 이 바이트가 U+FFFD 로 바뀌어 되돌아오지 않는다(zip 머리 `PK\x03\x04`
         * 뒤에 단독 0x80·0xFF 를 둔 모양이다).
         */
        val ORIGINAL_FILE: ByteArray =
            byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x80.toByte(), 0xFF.toByte(), 0x00, 0xC0.toByte())

        fun sealed(
            plain: String,
            keyVersion: Int,
        ) = EncryptedContent(plain.toByteArray(Charsets.UTF_8), EncryptionScheme.AES_256_GCM_V1, keyVersion)

        fun sealedBytes(
            plain: ByteArray,
            keyVersion: Int,
        ) = EncryptedContent(plain, EncryptionScheme.AES_256_GCM_V1, keyVersion)

        fun envelopeOf(
            keyVersion: Int,
            easy: String?,
            masked: String?,
            edited: String?,
        ) = ConversionEnvelope(
            conversionId = CONVERSION,
            scheme = EncryptionScheme.AES_256_GCM_V1,
            keyVersion = keyVersion,
            ciphertexts =
                ConversionCiphertexts(
                    easyText = easy?.let { sealed(it, keyVersion) },
                    maskedItems = masked?.let { sealed(it, keyVersion) },
                    editedText = edited?.let { sealed(it, keyVersion) },
                ),
        )
    }

    private class World(unopenable: EncryptedField? = null) {
        val transaction = RecordingTransactionRunner()
        val cipher = FakeRotatingCipher(unopenable)
        val documents = FakeDocumentRepository()
        val originals = FakeDocumentOriginalRepository()
        val conversions = FakeConversionRepository()
        val feedback = FakeRotatingFeedbackRepository()

        val rotation =
            EnvelopeRotation(
                stores =
                    SealedStores(
                        documents = documents,
                        originals = originals,
                        conversions = conversions,
                        feedback = feedback,
                    ),
                cipher = cipher,
                transaction = transaction,
            )

        /** 봉인된 열을 **전부** 옛 세대로 채운다. 전수 대조가 쓰는 자리다. */
        fun sealEverythingAtOldVersion() {
            documents.sourceText = sealed("원문", OLD_VERSION)
            originals.original = sealedBytes(ORIGINAL_FILE, OLD_VERSION)
            conversions.envelope = envelopeOf(OLD_VERSION, "초안", "대응표", "검수본")
            feedback.comment = sealed("의견", OLD_VERSION)
        }
    }

    /** [DocumentServiceTest] 의 것과 같은 이유로 `catch` 절을 쓰지 않는다. */
    private class RecordingTransactionRunner : TransactionRunner {
        var committed: Int = 0
            private set
        var failed: Int = 0
            private set

        override fun <T> inTransaction(block: () -> T): T {
            val result = runCatching(block)
            result.onSuccess { committed++ }.onFailure { failed++ }
            return result.getOrThrow()
        }
    }

    /** 쓰기 세대가 [NEW_VERSION] 인 암호 대역. */
    private class FakeRotatingCipher(private val unopenable: EncryptedField?) : ContentCipher {
        override val writeScheme: String = EncryptionScheme.AES_256_GCM_V1
        override val writeKeyVersion: Int = NEW_VERSION

        var encryptCalls: Int = 0
            private set
        val bindings = mutableListOf<Pair<UUID, EncryptedField>>()

        /** 회전이 **다시 봉한** 열. 전수 대조가 「경로가 이 열에 실제로 닿았는가」를 여기서 본다. */
        val resealed = mutableSetOf<EncryptedField>()

        /** 바이트 짝만 구현한다 — 문자열 짝은 [ContentCipher] 의 기본 구현을 탄다. */
        override fun encryptBytes(
            plain: PlainBytes,
            record: UUID,
            field: EncryptedField,
        ): EncryptedContent {
            encryptCalls++
            resealed += field
            return EncryptedContent(plain.value, EncryptionScheme.AES_256_GCM_V1, writeKeyVersion)
        }

        override fun decryptBytes(
            content: EncryptedContent,
            record: UUID,
            field: EncryptedField,
        ): PlainBytes {
            if (field == unopenable) throw DecryptionFailedException()
            bindings += record to field
            return PlainBytes(content.bytes)
        }
    }

    private class FakeDocumentRepository : DocumentRepository {
        var sourceText: EncryptedContent? = null
        val rewrites = mutableListOf<Pair<EncryptedContent, EncryptedContent>>()

        override fun insert(
            ownerId: UUID,
            draft: DocumentDraft,
            sourceText: EncryptedContent,
        ): Document = error("회전 경로가 문서를 만들지 않는다")

        override fun listOwned(
            ownerId: UUID,
            workspaceId: UUID?,
            limit: Int,
            offset: Int,
        ): List<DocumentListing> = error("회전 경로가 목록을 읽지 않는다")

        /** 회전 배치에는 「내 것」이 없다 — 부르면 이 파일의 케이스가 그 사실로 빨개진다. */
        override fun findOwnedSource(
            ownerId: UUID,
            documentId: UUID,
        ): StoredSourceText? = error("회전 경로가 소유자 조회 포트를 부르면 안 된다")

        override fun lockSourceText(documentId: UUID): EncryptedContent? = sourceText

        override fun rewriteEnvelope(
            documentId: UUID,
            expected: EncryptedContent,
            sourceText: EncryptedContent,
        ): Boolean {
            rewrites += expected to sourceText
            return true
        }

        override fun idsOlderThan(
            keyVersion: Int,
            after: UUID,
            limit: Int,
        ): List<UUID> = error("회전 배치의 후보 선정은 KeyRotationBatch 몫이다 — EnvelopeRotation 이 부르면 안 된다")

        override fun deleteOwned(
            ownerId: UUID,
            documentId: UUID,
        ): Boolean = error("회전 경로가 문서를 지우지 않는다")
    }

    private class FakeDocumentOriginalRepository : DocumentOriginalRepository {
        var original: EncryptedContent? = null
        var updated: Boolean = true
        val rewrites = mutableListOf<Pair<EncryptedContent, EncryptedContent>>()

        override fun insert(
            ownerId: UUID,
            documentId: UUID,
            original: StoredOriginal,
        ) = error("회전 경로가 원본을 만들지 않는다")

        /** 회전 배치에는 「내 것」이 없다 — 부르면 이 파일의 케이스가 그 사실로 빨개진다. */
        override fun findOwned(
            ownerId: UUID,
            documentId: UUID,
        ): StoredOriginal? = error("회전 경로가 소유자 조회 포트를 부르면 안 된다")

        override fun lockOriginal(documentId: UUID): EncryptedContent? = original

        override fun rewriteEnvelope(
            documentId: UUID,
            expected: EncryptedContent,
            original: EncryptedContent,
        ): Boolean {
            rewrites += expected to original
            return updated
        }

        override fun documentIdsOlderThan(
            keyVersion: Int,
            after: UUID,
            limit: Int,
        ): List<UUID> = error("회전 배치의 후보 선정은 KeyRotationBatch 몫이다 — EnvelopeRotation 이 부르면 안 된다")
    }

    private class Rewrite(
        val expected: ConversionEnvelope,
        val keyVersion: Int,
        val ciphertexts: ConversionCiphertexts,
    )

    private class FakeConversionRepository : ConversionRepository {
        var envelope: ConversionEnvelope? = null
        var updated: Boolean = true
        val rewrites = mutableListOf<Rewrite>()

        override fun insertPending(
            id: UUID,
            documentId: UUID,
            scheme: String,
            keyVersion: Int,
        ): Conversion = error("회전 경로가 변환을 만들지 않는다")

        /**
         * 회전은 이 포트를 쓰지 않는다. 회전 배치에는 「내 것」이 없고, 그래서 이 대역이
         * 언제나 `null` 을 돌려주는 것이 옳다 — 회전이 실수로 사용자 경로 포트를 부르면
         * 이 파일의 케이스가 「행이 없다」로 빨개진다.
         */
        override fun findOwnedResult(
            ownerId: UUID,
            conversionId: UUID,
        ): StoredConversion? = null

        override fun findOwnedExport(
            ownerId: UUID,
            conversionId: UUID,
        ): StoredExport? = error("회전 경로가 내보내기 포트를 부르면 안 된다")

        override fun lockEnvelope(conversionId: UUID): ConversionEnvelope? = envelope

        override fun rewriteEnvelope(
            expected: ConversionEnvelope,
            scheme: String,
            keyVersion: Int,
            ciphertexts: ConversionCiphertexts,
        ): Boolean {
            rewrites += Rewrite(expected, keyVersion, ciphertexts)
            return updated
        }

        /** 회전은 검수 포트를 쓰지 않는다 — 부르면 이 파일의 케이스가 그 사실로 빨개진다. */
        override fun lockOwnedForReview(
            ownerId: UUID,
            conversionId: UUID,
        ): LockedConversion? = error("회전 경로가 검수 저장 포트를 부르면 안 된다")

        override fun saveReview(
            ownerId: UUID,
            expected: ConversionEnvelope,
            requiredStatus: ConversionStatus,
            updated: ConversionEnvelope,
        ): Boolean = error("회전 경로가 검수 저장 포트를 부르면 안 된다")

        override fun idsOlderThan(
            keyVersion: Int,
            after: UUID,
            limit: Int,
        ): List<UUID> = error("회전 배치의 후보 선정은 KeyRotationBatch 몫이다 — EnvelopeRotation 이 부르면 안 된다")
    }

    private class FeedbackRewrite(
        val expected: EncryptedContent,
        val comment: EncryptedContent,
    )

    private class FakeRotatingFeedbackRepository : ConversionFeedbackRepository {
        /** 행이 없으면 [rowExists] 가 `false` 다 — 「행이 없다」와 「의견이 없다」는 다른 상태다. */
        var rowExists: Boolean = true
        var comment: EncryptedContent? = null
        var updated: Boolean = true
        val rewrites = mutableListOf<FeedbackRewrite>()

        override fun upsert(
            ownerId: UUID,
            feedback: StoredFeedback,
        ): java.time.Instant = error("회전 경로가 피드백 저장 포트를 부르면 안 된다")

        override fun lockComment(conversionId: UUID): LockedFeedbackComment? =
            if (rowExists) LockedFeedbackComment(comment) else null

        override fun rewriteComment(
            conversionId: UUID,
            expected: EncryptedContent,
            comment: EncryptedContent,
        ): Boolean {
            rewrites += FeedbackRewrite(expected, comment)
            return updated
        }

        override fun conversionIdsOlderThan(
            keyVersion: Int,
            after: UUID,
            limit: Int,
        ): List<UUID> = error("회전 배치의 후보 선정은 KeyRotationBatch 몫이다 — EnvelopeRotation 이 부르면 안 된다")
    }
}
