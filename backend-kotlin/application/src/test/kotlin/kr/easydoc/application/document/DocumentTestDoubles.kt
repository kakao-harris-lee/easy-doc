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
import kr.easydoc.core.document.MaskedItemView
import kr.easydoc.core.document.ReflectionOutcome
import kr.easydoc.core.document.SourceFormat
import kr.easydoc.core.easyread.ExportFile
import kr.easydoc.core.easyread.exportContentLines
import kr.easydoc.core.privacy.MaskCategory
import kr.easydoc.core.security.Secret
import java.time.Instant
import java.util.UUID

/** 트랜잭션 경계를 깊이로 드러내는 대역. */
internal class RecordingTransactionRunner : TransactionRunner {
    var depth: Int = 0
        private set
    var started: Int = 0
        private set
    var committed: Int = 0
        private set
    var failed: Int = 0
        private set

    /**
     * **몇 번째 바깥 경계인가.** 깊이만으로는 「같은 트랜잭션 안인가」를 잴 수 없다 — 저장이
     * 연 경계가 닫힌 뒤 조회가 자기 경계를 새로 열어도 깊이는 다시 1 이 되기 때문이다.
     * 경계가 **바닥에서** 열릴 때만 앞으로 가는 이 수가 두 일이 같은 경계에 있었는지를 말한다.
     */
    var epoch: Int = 0
        private set

    override fun <T> inTransaction(block: () -> T): T {
        started++
        if (depth == 0) epoch++
        depth++
        val result = runCatching(block)
        depth--
        result.onSuccess { committed++ }.onFailure { failed++ }
        return result.getOrThrow()
    }
}

/** 결속 인자를 그대로 기록하는 암호 대역. 실제 암호는 여기서 재지 않는다. */
internal class FakeContentCipher(
    override val writeKeyVersion: Int,
    private val transaction: RecordingTransactionRunner? = null,
) : ContentCipher {
    override val writeScheme: String = EncryptionScheme.AES_256_GCM_V1

    val sealed = mutableListOf<Triple<String, UUID, EncryptedField>>()

    /** 복호화의 결속 인자. 이것이 갈리면 실물에서 태그 검증이 실패한다. */
    val decryptions = mutableListOf<Pair<UUID, EncryptedField>>()

    /** 복호화가 불린 시점의 트랜잭션 깊이. 0 이면 경계 밖이다. */
    val depthWhenDecrypted = mutableListOf<Int>()

    /**
     * 복호화가 불린 시점의 **바깥 경계 번호**([RecordingTransactionRunner.epoch]).
     * [decryptions] 와 자리마다 짝이다 — 두 일이 **같은** 트랜잭션에 있었는지는 깊이가 아니라
     * 이 수가 말한다.
     */
    val epochWhenDecrypted = mutableListOf<Int>()

    /** **봉인이 불린 시점의** 트랜잭션 깊이. 0 이면 경계 밖이다 — 10MB 원본이 재는 축이다. */
    val depthWhenSealed = mutableListOf<Int>()

    /**
     * **바이트 짝만 구현한다** — 문자열 짝은 [ContentCipher] 의 기본 구현이 이 위로 흐른다.
     * 제품 어댑터와 같은 모양이라 대역이 문자열/바이트로 다르게 굴 여지가 없다.
     */
    override fun encryptBytes(
        plain: PlainBytes,
        record: UUID,
        field: EncryptedField,
    ): EncryptedContent {
        // 기록은 그대로 문자열이다 — 이 대역이 봉하는 것은 전부 텍스트 경로이고, 원본 바이트를
        // 재는 자리는 크기와 결속뿐이라 UTF-8 로 되읽어도 단언이 달라지지 않는다.
        sealed += Triple(String(plain.value, Charsets.UTF_8), record, field)
        depthWhenSealed += transaction?.depth ?: 0
        return EncryptedContent(plain.value, writeScheme, writeKeyVersion)
    }

    override fun decryptBytes(
        content: EncryptedContent,
        record: UUID,
        field: EncryptedField,
    ): PlainBytes {
        decryptions += record to field
        depthWhenDecrypted += transaction?.depth ?: 0
        epochWhenDecrypted += transaction?.epoch ?: 0
        return PlainBytes(content.bytes)
    }

    /** **배경을 심을 때만 쓴다** — 옛 세대로 봉인된 행을 만든다. 제품 포트에는 이 갈래가 없다. */
    fun encryptAs(
        plain: PlainBody,
        keyVersion: Int,
    ): EncryptedContent = EncryptedContent(plain.value.toByteArray(Charsets.UTF_8), writeScheme, keyVersion)
}

/**
 * 원본 저장 대역 — 업로드 팔이 **무엇을 언제 썼는지**만 기록한다.
 *
 * [insertFailure] 가 있으면 저장이 던진다. 「원본 저장이 실패하면 업로드가 조용히 성공하지
 * 않는다」를 재는 자리라 `queue` 대역이 실패를 흉내 내는 것과 같은 모양이다.
 */
internal class FakeDocumentOriginalRepository(
    private val transaction: RecordingTransactionRunner,
    private val insertFailure: RuntimeException? = null,
) : DocumentOriginalRepository {
    val rows = mutableMapOf<UUID, StoredOriginal>()

    /** `user_id` 술어에 실제로 들어간 값. 소유자가 저장소까지 갔는가를 재는 재료다. */
    val owners = mutableMapOf<UUID, UUID>()

    /** 저장이 불린 시점의 트랜잭션 깊이. 0 이면 경계 밖이다. */
    val depthWhenInserted = mutableListOf<Int>()

    override fun insert(
        ownerId: UUID,
        documentId: UUID,
        original: StoredOriginal,
    ) {
        requireStorable(documentId, original)
        depthWhenInserted += transaction.depth
        insertFailure?.let { throw it }
        rows[documentId] = original
        owners[documentId] = ownerId
    }

    /**
     * **V3 가 거절하는 행은 이 대역도 받지 않는다 — 그리고 V3 가 받는 행을 함부로 막지도 않는다.**
     *
     * 스키마가 막는 행을 대역이 받아 주면 그 상태에서 통과한 테스트는 실물에서 재현되지
     * 않는다. 반대로 스키마가 받는 행을 대역이 막으면 실물에 있는 갈래를 영영 못 재게 되는데,
     * 그쪽은 빨강이 나지 않으므로 더 조용하다. `V3__document_originals.sql` 의 제약 넷이
     * 기준이다 — `pk_document_originals`(문서 한 건에 원본도 하나) ·
     * `ck_document_originals_byte_size_positive` ·
     * `ck_document_originals_encryption_scheme_valid` ·
     * `ck_document_originals_key_version_positive`.
     *
     * **세대 번호만 여기서 다시 세우지 않는다.** [EncryptedContent] 의 생성자가 이미
     * `KEY_VERSION_RANGE`(=`1..Short.MAX_VALUE`)로 끊으므로 세대가 0 이하인 [StoredOriginal] 은
     * 이 대역에 닿기 전에 **만들어지지 못한다.** 여기 `require` 를 더 두면 절대 타지 않는
     * 갈래가 하나 늘 뿐이라, 대응은 검사가 아니라 이 문단이 진다.
     *
     * 크기 하한을 **여기서** 지는 이유: `PlainBytes` 가 빈 배열을 일부러 거절하지 않고
     * (`core/crypto/StoredContent.kt` 의 주석 — 「봉할 바이트」 일반의 성질이 아니다)
     * [StoredOriginal] 에도 검사가 없어, 코드 경로에서 그 하한을 지는 자리가 DB 뿐이다.
     *
     * **암호문 길이는 여기서 재지 않는다 — 이 대역이 모사하는 층은 V3 하나다.** 세 층의 규칙이
     * 서로 다르다: `file_bytes_encrypted bytea NOT NULL` 은 길이 0 을 금지하지 않고(빈 bytea 는
     * NULL 이 아니다), 암호화 계층의 하한은 `NONCE_BYTES + TAG_BYTES`(=28)이며, 그 사이 어디에도
     * 「1바이트 이상」이라는 규칙은 없다. 그리고 **이 대역은 암호화 계층을 모사할 수 없다** —
     * 여기 닿는 암호문을 만드는 것은 [FakeContentCipher] 이고, 그것은 봉투를 씌우지 않고 길이를
     * 보존한다. 그 성질이 `DocumentServiceTest` 의 「원본이 UTF-8 왕복으로 눌리면 파일이 조용히
     * 망가진다」를 재게 해 준다. 8바이트 원본이 그대로 8바이트 「암호문」이 되므로 여기서
     * 28바이트를 요구하면 실물에 없는 빨강이 나고, 대역에 봉투 흉내를 시키면 저 단언이 죽는다.
     *
     * 그래서 암호화 계층의 하한은 **그것이 사는 자리**에서 진다 — `AesGcmContentCipherTest` 의
     * 「봉인 결과가 nonce+태그 이상이다」와 「nonce+태그 미만은 열지 않는다」 두 케이스다.
     * 여기 남기는 것은 V3 의 제약뿐이다.
     */
    private fun requireStorable(
        documentId: UUID,
        original: StoredOriginal,
    ) {
        require(documentId !in rows) { "pk_document_originals: 문서 한 건에 원본 행도 하나다" }
        require(original.byteSize > 0) { "ck_document_originals_byte_size_positive: 0바이트 원본은 없다" }
        require(original.bytes.scheme == EncryptionScheme.AES_256_GCM_V1) {
            "ck_document_originals_encryption_scheme_valid: ${original.bytes.scheme}"
        }
    }

    override fun findOwned(
        ownerId: UUID,
        documentId: UUID,
    ): StoredOriginal? = if (owners[documentId] == ownerId) rows[documentId] else null

    override fun lockOriginal(documentId: UUID): EncryptedContent? = error(ROTATION_PORT_MESSAGE)

    override fun rewriteEnvelope(
        documentId: UUID,
        expected: EncryptedContent,
        original: EncryptedContent,
    ): Boolean = error(ROTATION_PORT_MESSAGE)

    override fun documentIdsOlderThan(
        keyVersion: Int,
        after: UUID,
        limit: Int,
    ): List<UUID> = error(ROTATION_PORT_MESSAGE)

    private companion object {
        const val ROTATION_PORT_MESSAGE = "업로드 경로가 회전 포트를 부르면 안 된다"
    }
}

/**
 * `conversions` 저장소 대역.
 *
 * [originals] 를 함께 드는 것이 요점이다 — 실물 조회에서 `has_stored_original` 은 **컬럼이
 * 아니라** 같은 질의 안의 `EXISTS (SELECT 1 FROM document_originals …)` 이다
 * (`JdbcConversionRepository.FIND_OWNED_SQL`). 케이스가 손으로 세우는 값으로 두면
 * 「행은 없는데 있다고 말하는 변환」이 만들어지고, 그 조합은 한 트랜잭션 안에서 실물이 낼 수
 * 없다. 여기서는 [findOwnedResult] 가 **읽을 때마다 다시 센다.**
 */
internal class FakeConversionRepository(
    private val transaction: RecordingTransactionRunner,
    private val originals: FakeDocumentOriginalRepository,
) : ConversionRepository {
    val inserted = mutableListOf<Pair<UUID, Pair<String, Int>>>()
    val depthWhenInserted = mutableListOf<Int>()

    override fun insertPending(
        id: UUID,
        documentId: UUID,
        scheme: String,
        keyVersion: Int,
    ): Conversion {
        inserted += id to (scheme to keyVersion)
        depthWhenInserted += transaction.depth
        return Conversion(
            id = id,
            documentId = documentId,
            status = ConversionStatus.PENDING,
            failureCode = null,
            createdAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
        )
    }

    /** 소유자를 키의 일부로 든다. */
    val owned = mutableMapOf<Pair<UUID, UUID>, StoredConversion>()
    val reads = mutableListOf<Pair<UUID, UUID>>()
    val depthWhenRead = mutableListOf<Int>()

    override fun findOwnedResult(
        ownerId: UUID,
        conversionId: UUID,
    ): StoredConversion? {
        reads += ownerId to conversionId
        depthWhenRead += transaction.depth
        return owned[ownerId to conversionId]?.let(::asRead)
    }

    /**
     * 조회가 돌려주는 행 — **원본 유무를 컬럼에서 읽지 않고 `document_originals` 에서 다시 센다.**
     *
     * 형식과 원본의 짝도 여기서 끊는다. 붙여넣기(`text`)에는 원본 파일이 **없다** —
     * `DocumentService` 의 `UploadContent` 가 「형식이 `text` 인데 원본이 있다」를 표현할 값이
     * 아니라 결함으로 못박았고, 행을 만드는 것은 업로드 팔 하나뿐이다. 반대 조합(형식은
     * DOCX 인데 원본이 없다)은 표가 서기 전 업로드라 **실재한다** — 그래서 막지 않는다.
     */
    private fun asRead(stored: StoredConversion): StoredConversion {
        val hasOriginal = originals.rows.containsKey(stored.documentId)
        check(!(hasOriginal && stored.sourceFormat == SourceFormat.TEXT)) {
            "붙여넣기 문서에 원본 행을 심었다 — `document_originals` 에 행을 만드는 것은 업로드 팔뿐이다"
        }
        return stored.copy(hasStoredOriginal = hasOriginal)
    }

    /** 내보내기 전용 조회. 제목은 [titles] 가 없으면 기본 이름을 쓴다. */
    val titles = mutableMapOf<UUID, String>()

    override fun findOwnedExport(
        ownerId: UUID,
        conversionId: UUID,
    ): StoredExport? {
        reads += ownerId to conversionId
        depthWhenRead += transaction.depth
        val stored = owned[ownerId to conversionId] ?: return null
        return StoredExport(asRead(stored), titles[conversionId] ?: DEFAULT_EXPORT_TITLE)
    }

    override fun lockEnvelope(conversionId: UUID): ConversionEnvelope? = null

    override fun rewriteEnvelope(
        expected: ConversionEnvelope,
        scheme: String,
        keyVersion: Int,
        ciphertexts: ConversionCiphertexts,
    ): Boolean = false

    /** 회전 배치 후보 포트 — 이 대역을 쓰는 케이스가 회전을 재지 않는다. */
    override fun idsOlderThan(
        keyVersion: Int,
        after: UUID,
        limit: Int,
    ): List<UUID> = emptyList()

    /** 검수 저장이 잠그고 읽는 행. 케이스가 심는다. */
    val lockedForReview = mutableMapOf<Pair<UUID, UUID>, LockedConversion>()
    val depthWhenLocked = mutableListOf<Int>()

    override fun lockOwnedForReview(
        ownerId: UUID,
        conversionId: UUID,
    ): LockedConversion? {
        depthWhenLocked += transaction.depth
        return lockedForReview[ownerId to conversionId]
    }

    /** 검수 저장 호출 기록. **0행 갈래**는 [saveReviewSucceeds] 로 만든다. */
    val savedReviews = mutableListOf<SavedReview>()
    var saveReviewSucceeds: Boolean = true

    /** `reviewed_at = now()` 를 흉내 내는 시계 — 저장마다 앞으로 간다. */
    private var reviewClock: Instant = Instant.EPOCH

    override fun saveReview(
        ownerId: UUID,
        expected: ConversionEnvelope,
        requiredStatus: ConversionStatus,
        updated: ConversionEnvelope,
    ): Boolean {
        savedReviews += SavedReview(expected, requiredStatus, updated, transaction.depth, transaction.epoch)
        val key = ownerId to updated.conversionId
        val row = lockedForReview[key]
        if (!saveReviewSucceeds || !updatesOneRow(key, row, expected, requiredStatus)) return false
        // 실물이 고치는 것은 **행 하나다.** 한쪽만 반영하면 같은 트랜잭션의 뒤이은 조회가 저장
        // 전 행을 보게 되고(「저장 응답이 방금 저장한 검수본을 싣는가」가 공허해진다), 이어지는
        // 저장은 [updatesOneRow] 의 열 대조에서 0행이 된다. `reviewed_at = now()` 도 같은
        // 문장이 찍으므로 여기서 함께 찍는다.
        reviewClock = reviewClock.plusSeconds(1)
        lockedForReview[key] = LockedConversion(row!!.status, updated)
        owned[key]?.let { owned[key] = it.copy(ciphertexts = updated.ciphertexts, reviewedAt = reviewClock) }
        return true
    }

    /**
     * 조건부 UPDATE 의 술어 — 1행인가 0행인가(`JdbcConversionRepository.SAVE_REVIEW_SQL`).
     *
     * 두 갈래를 함께 본다. ⑴ 조건으로 넘어온 것이 **잠그고 읽은 행 그 자체**이고 상태가
     * `done` 인가. ⑵ 그 봉투가 **행에 지금 들어 있는 암호문 세 열**과 같은가
     * (`IS NOT DISTINCT FROM` 셋). 대역이 ⑵ 를 지지 않으면 「잠금이 읽는 쪽과 조회가 읽는
     * 쪽이 같은 행」이라는 사실이 대역 안에서 아무것도 아니게 되고, 두 벌로 갈린 행이 조용히
     * 굴러간다.
     */
    private fun updatesOneRow(
        key: Pair<UUID, UUID>,
        row: LockedConversion?,
        expected: ConversionEnvelope,
        requiredStatus: ConversionStatus,
    ): Boolean {
        if (row == null) return false
        val lockedRow = row.envelope === expected && row.status == requiredStatus
        val columns = owned[key]?.let { sameColumns(it.ciphertexts, expected.ciphertexts) } ?: true
        return lockedRow && columns
    }

    /** 세 열이 행에 있는 그대로인가 — `IS NOT DISTINCT FROM` 셋과 같다(`null` 도 같으면 참). */
    private fun sameColumns(
        row: ConversionCiphertexts,
        expected: ConversionCiphertexts,
    ): Boolean =
        row.easyText == expected.easyText &&
            row.maskedItems == expected.maskedItems &&
            row.editedText == expected.editedText

    /** 한 번의 검수 저장 호출 — 조건과 쓴 값, 그리고 **어느 경계에서 돌았는지**를 그대로 든다. */
    internal class SavedReview(
        val expected: ConversionEnvelope,
        val requiredStatus: ConversionStatus,
        val updated: ConversionEnvelope,
        val depth: Int,
        /** [RecordingTransactionRunner.epoch] — 「저장과 같은 트랜잭션인가」의 기준값이다. */
        val epoch: Int,
    )

    private companion object {
        const val DEFAULT_EXPORT_TITLE: String = "안내문"
    }
}

/**
 * 피드백 저장 대역 — **실물 upsert 처럼 행 하나로 접힌다.** 호출은 [upserts] 에 전부
 * 쌓이고 [rows] 에는 변환당 마지막 값만 남으므로, 멱등성을 「호출 수」가 아니라
 * 「남은 행」으로 잰다.
 */
internal class FakeConversionFeedbackRepository(private val transaction: RecordingTransactionRunner) :
    ConversionFeedbackRepository {
    /** 변환 하나에 행 하나 — PK 가 `conversion_id` 인 것을 그대로 흉내 낸다. */
    val rows = mutableMapOf<UUID, StoredFeedback>()

    /** `user_id` 컬럼에 실제로 들어간 값. */
    val owners = mutableMapOf<UUID, UUID>()

    /** 호출 기록 전부 — 덮어쓴 이력을 본다. */
    val upserts = mutableListOf<StoredFeedback>()

    /** 저장이 불린 시점의 트랜잭션 깊이. 0 이면 경계 밖이다. */
    val depthWhenUpserted = mutableListOf<Int>()

    /** `submitted_at` 을 흉내 내는 시계 — 호출마다 앞으로 간다(재제출이 값을 민다). */
    private var clock: Instant = Instant.EPOCH

    override fun upsert(
        ownerId: UUID,
        feedback: StoredFeedback,
    ): Instant {
        upserts += feedback
        depthWhenUpserted += transaction.depth
        rows[feedback.conversionId] = feedback
        owners[feedback.conversionId] = ownerId
        clock = clock.plusSeconds(1)
        return clock
    }

    /**
     * 회전 팔은 저장 유스케이스가 부르지 않는다 — 부르면 이 대역이 그 사실로 끊긴다.
     * 회전 자체를 재는 것은 [EnvelopeRotationTest] 의 대역이다.
     */
    override fun lockComment(conversionId: UUID): LockedFeedbackComment = error(ROTATION_PORT_MESSAGE)

    override fun rewriteComment(
        conversionId: UUID,
        expected: EncryptedContent,
        comment: EncryptedContent,
    ): Boolean = error(ROTATION_PORT_MESSAGE)

    override fun conversionIdsOlderThan(
        keyVersion: Int,
        after: UUID,
        limit: Int,
    ): List<UUID> = error(ROTATION_PORT_MESSAGE)

    private companion object {
        const val ROTATION_PORT_MESSAGE = "피드백 저장 경로가 회전 포트를 부르면 안 된다"
    }
}

/** 대응표 읽기 대역. 형식은 한 줄에 자리표시자 하나다 — 실물 JSON 을 흉내 내지 않는다. */
internal class FakeMaskedItemReader : MaskedItemReader {
    val decoded = mutableListOf<PlainBody>()

    override fun decode(body: PlainBody): List<MaskedItemView> {
        decoded += body
        return body.value
            .lineSequence()
            .filter { it.isNotBlank() }
            .map { MaskedItemView(MaskCategory.RRN, it, Secret("가린값")) }
            .toList()
    }
}

/**
 * 원본 반영 대역 — 미리 정해 둔 판정과 파일을 돌려준다.
 *
 * 여기서 실제 파일을 열지 않는 것이 요점이다. 유스케이스가 지는 책임은 「어느 갈래에서
 * 원본을 열려 하는가」와 「그 결과를 응답·오류로 어떻게 옮기는가」이고, 원본 구조를 실제로
 * 고쳐 쓰는 일은 infrastructure 의 `PackagedOriginalReflector` 가 fixture 로 검증한다.
 */
internal class FakeOriginalStructureReflector(
    var outcome: ReflectionOutcome? = ReflectionOutcome(0, 0, 0, 0),
    var file: ExportFile? = null,
) : OriginalStructureReflector {
    /** 판정·반영에 넘어온 원본의 형식. 「무엇을 열려고 했는가」를 재는 재료다. */
    val outlined = mutableListOf<SourceFormat>()
    val reflected = mutableListOf<SourceFormat>()

    /** 판정에 넘어온 본문. 판정이 **어느 글**을 짝의 한쪽으로 삼았는지 재는 자리다. */
    val outlinedBodies = mutableListOf<String>()

    /** 반영에 넘어온 본문. 내보내기가 **복원된 본문**을 넘기는지 재는 자리다. */
    val bodies = mutableListOf<String>()

    /**
     * 원본 본문 단위 수. 설정하면 [outline] 이 [outcome] 대신 **넘어온 본문의 문단 수와
     * 짝지어** 판정을 계산한다.
     *
     * 「검수본이 바뀌면 판정도 바뀐다」를 재려면 대역이 본문에 반응해야 한다 — 고정 판정을
     * 돌려주는 대역으로는 갱신 여부를 잴 수 없다.
     *
     * **여기서 세는 수는 실물이 낼 수 있는 조합이다.** 아래 계산은 실물 `planOf` 를
     * **머리말·꼬리말이 없는 원본**에 적용한 결과와 같다: 단위가 전부 본문이면 짝지어지지
     * 못한 단위가 그대로 `emptiedUnits`, 자리를 못 얻은 문단이 그대로 `appendedLines` 이고,
     * 겹칠 머리말 자리가 없으니 `displacedLines` 는 0 이다(실물에서도 `displaced` 는
     * 머리말·꼬리말 단위와 겹친 줄이라 `headerFooterUnits = 0` 이면 함께 0 이다).
     * 문단을 나누는 일은 흉내 내지 않고 **판정과 반영이 실제로 쓰는 함수**를 그대로 부른다.
     *
     * 머리말·꼬리말이 **있는** 갈래와 자리 맞춤 규칙 자체는 실물
     * `PackagedOriginalReflector` 가 진짜 DOCX·HWPX fixture 로 검증한다
     * (`PackagedOriginalReflectorTest`).
     */
    var originalUnits: Int? = null

    override fun outline(
        original: OriginalDocument,
        body: String,
    ): ReflectionOutcome? {
        outlined += original.format
        outlinedBodies += body
        val units = originalUnits ?: return outcome
        val paragraphs = exportContentLines(body).size
        return ReflectionOutcome(
            headerFooterUnits = 0,
            emptiedUnits = maxOf(0, units - paragraphs),
            appendedLines = maxOf(0, paragraphs - units),
            displacedLines = 0,
        )
    }

    override fun reflect(
        original: OriginalDocument,
        title: String,
        body: String,
    ): ExportFile? {
        reflected += original.format
        bodies += body
        return file
    }
}

/**
 * 원문 저장소 대역 — `ConversionQueryService` 가 `segment_map` 을 유도하려고만 부른다.
 *
 * [seed] 로 심지 않은 문서는 `null` 을 돌려주고, 그러면 유스케이스가 `segment_map` 을
 * `null` 로 접는다(계획 §3 「원문을 읽을 수 없으면 null」의 대역 형태). `DocumentSourceServiceTest`
 * 의 로컬 대역과 같은 최소 구현 — 회전·삭제·목록 팔은 이 조회 경로가 부르지 않는다.
 */
internal class FakeQueryDocumentRepository(private val transaction: RecordingTransactionRunner) : DocumentRepository {
    private val rows = mutableMapOf<Pair<UUID, UUID>, StoredSourceText>()

    /** 조회에 넘어온 `(소유자, 문서)` 짝 전부. */
    val queries = mutableListOf<Pair<UUID, UUID>>()

    /** 조회가 불린 시점의 트랜잭션 깊이. `segment_map` 이 읽는 원문도 같은 경계 안에서 읽는다. */
    val depthWhenRead = mutableListOf<Int>()

    fun seed(
        ownerId: UUID,
        documentId: UUID,
        text: String,
        format: SourceFormat = SourceFormat.TEXT,
    ) {
        rows[ownerId to documentId] =
            StoredSourceText(
                documentId = documentId,
                sourceFormat = format,
                charCount = text.length,
                sourceText = EncryptedContent(text.toByteArray(Charsets.UTF_8), EncryptionScheme.AES_256_GCM_V1, 1),
            )
    }

    override fun findOwnedSource(
        ownerId: UUID,
        documentId: UUID,
    ): StoredSourceText? {
        queries += ownerId to documentId
        depthWhenRead += transaction.depth
        return rows[ownerId to documentId]
    }

    override fun insert(
        ownerId: UUID,
        draft: DocumentDraft,
        sourceText: EncryptedContent,
    ) = error("조회 경로가 문서를 만들지 않는다")

    override fun listOwned(
        ownerId: UUID,
        workspaceId: UUID?,
        limit: Int,
        offset: Int,
    ) = error("조회 경로가 목록을 읽지 않는다")

    override fun lockSourceText(documentId: UUID): EncryptedContent? = error("조회 경로가 행을 잠그지 않는다")

    override fun rewriteEnvelope(
        documentId: UUID,
        expected: EncryptedContent,
        sourceText: EncryptedContent,
    ): Boolean = error("조회 경로가 봉투를 다시 쓰지 않는다")

    override fun idsOlderThan(
        keyVersion: Int,
        after: UUID,
        limit: Int,
    ): List<UUID> = error("조회 경로가 회전 후보를 고르지 않는다")

    override fun deleteOwned(
        ownerId: UUID,
        documentId: UUID,
    ): Boolean = error("조회 경로가 문서를 지우지 않는다")
}
