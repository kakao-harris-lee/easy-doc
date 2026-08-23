package kr.easydoc.application.document

import kr.easydoc.application.auth.TransactionRunner
import kr.easydoc.application.crypto.ContentCipher
import kr.easydoc.core.crypto.EncryptedContent
import kr.easydoc.core.crypto.EncryptedField
import kr.easydoc.core.crypto.EncryptionScheme
import kr.easydoc.core.crypto.PlainBody
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

    private companion object {
        val CONVERSION: UUID = UUID.fromString("00000000-0000-4000-8000-0000000000c1")
        val DOCUMENT: UUID = UUID.fromString("00000000-0000-4000-8000-0000000000d1")
        const val OLD_VERSION = 1
        const val NEW_VERSION = 2

        fun sealed(
            plain: String,
            keyVersion: Int,
        ) = EncryptedContent(plain.toByteArray(Charsets.UTF_8), EncryptionScheme.AES_256_GCM_V1, keyVersion)

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
        val conversions = FakeConversionRepository()

        val rotation =
            EnvelopeRotation(
                documents = documents,
                conversions = conversions,
                cipher = cipher,
                transaction = transaction,
            )
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

        override fun encrypt(
            plain: PlainBody,
            record: UUID,
            field: EncryptedField,
        ): EncryptedContent {
            encryptCalls++
            return sealed(plain.value, writeKeyVersion)
        }

        override fun decrypt(
            content: EncryptedContent,
            record: UUID,
            field: EncryptedField,
        ): PlainBody {
            if (field == unopenable) throw DecryptionFailedException()
            bindings += record to field
            return PlainBody(String(content.bytes, Charsets.UTF_8))
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

        override fun lockSourceText(documentId: UUID): EncryptedContent? = sourceText

        override fun rewriteEnvelope(
            documentId: UUID,
            expected: EncryptedContent,
            sourceText: EncryptedContent,
        ): Boolean {
            rewrites += expected to sourceText
            return true
        }

        override fun deleteOwned(
            ownerId: UUID,
            documentId: UUID,
        ): Boolean = error("회전 경로가 문서를 지우지 않는다")
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
    }
}
