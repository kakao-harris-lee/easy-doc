package kr.easydoc.application.document

import kr.easydoc.application.auth.TransactionRunner
import kr.easydoc.application.crypto.ContentCipher
import kr.easydoc.core.crypto.EncryptedContent
import kr.easydoc.core.crypto.EncryptedField
import kr.easydoc.core.crypto.EncryptionScheme
import kr.easydoc.core.crypto.PlainBody
import kr.easydoc.core.document.Conversion
import kr.easydoc.core.document.ConversionStatus
import kr.easydoc.core.document.MaskedItemView
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

    override fun <T> inTransaction(block: () -> T): T {
        started++
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

    override fun encrypt(
        plain: PlainBody,
        record: UUID,
        field: EncryptedField,
    ): EncryptedContent {
        sealed += Triple(plain.value, record, field)
        return EncryptedContent(plain.value.toByteArray(Charsets.UTF_8), writeScheme, writeKeyVersion)
    }

    override fun decrypt(
        content: EncryptedContent,
        record: UUID,
        field: EncryptedField,
    ): PlainBody {
        decryptions += record to field
        depthWhenDecrypted += transaction?.depth ?: 0
        return PlainBody(String(content.bytes, Charsets.UTF_8))
    }

    /** **배경을 심을 때만 쓴다** — 옛 세대로 봉인된 행을 만든다. 제품 포트에는 이 갈래가 없다. */
    fun encryptAs(
        plain: PlainBody,
        keyVersion: Int,
    ): EncryptedContent = EncryptedContent(plain.value.toByteArray(Charsets.UTF_8), writeScheme, keyVersion)
}

internal class FakeConversionRepository(private val transaction: RecordingTransactionRunner) : ConversionRepository {
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
        return owned[ownerId to conversionId]
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
        return StoredExport(stored, titles[conversionId] ?: DEFAULT_EXPORT_TITLE)
    }

    override fun lockEnvelope(conversionId: UUID): ConversionEnvelope? = null

    override fun rewriteEnvelope(
        expected: ConversionEnvelope,
        scheme: String,
        keyVersion: Int,
        ciphertexts: ConversionCiphertexts,
    ): Boolean = false

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

    override fun saveReview(
        ownerId: UUID,
        expected: ConversionEnvelope,
        requiredStatus: ConversionStatus,
        updated: ConversionEnvelope,
    ): Boolean {
        savedReviews += SavedReview(expected, requiredStatus, updated, transaction.depth)
        return saveReviewSucceeds
    }

    /** 한 번의 검수 저장 호출 — 조건과 쓴 값을 그대로 든다. */
    internal class SavedReview(
        val expected: ConversionEnvelope,
        val requiredStatus: ConversionStatus,
        val updated: ConversionEnvelope,
        val depth: Int,
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
