package kr.easydoc.application.document

import kr.easydoc.core.crypto.EncryptedContent
import kr.easydoc.core.crypto.PlainBody
import kr.easydoc.core.document.Conversion
import kr.easydoc.core.document.ConversionStatus
import kr.easydoc.core.document.Document
import kr.easydoc.core.document.DocumentListing
import kr.easydoc.core.document.MaskedItemView
import kr.easydoc.core.document.SourceFormat
import kr.easydoc.core.privacy.MaskedItem
import java.time.Instant
import java.util.UUID

// 문서·변환 유스케이스가 바깥 세계에 요구하는 것들 — **포트 선언**.
//
// `application` 은 `infrastructure` 를 의존하지 않는다(계획 §3.2).
//
// **평문이 이 경계를 넘지 않는다** — 저장소 포트는 [EncryptedContent] 만 주고받고 `PlainBody` 를
// **타입으로 보지 못한다.** 암호화하는 층이 `application` 인 근거는 계획 §4.1.

/** 저장 직전의 문서 한 건 — **DB 시계가 채우는 값을 뺀 전부**. */
class DocumentDraft(
    val id: UUID,
    val workspaceId: UUID,
    val title: String,
    val sourceFormat: SourceFormat,
    val charCount: Int,
) {
    /** 제목은 길이만 남긴다. [Document.toString] 과 같은 형태다. */
    override fun toString(): String = "DocumentDraft($id, ${sourceFormat.wireName}, 제목 ${title.length}자, ${charCount}자)"
}

/** `documents` 저장소. */
interface DocumentRepository {
    /** 문서 행을 만든다. **커밋하지 않는다** — 트랜잭션 경계는 유스케이스가 연다. */
    fun insert(
        ownerId: UUID,
        draft: DocumentDraft,
        sourceText: EncryptedContent,
    ): Document

    /** 내 문서를 **최신순**으로 읽는다. [workspaceId] 를 주면 그 작업 공간 안만 본다. */
    fun listOwned(
        ownerId: UUID,
        workspaceId: UUID?,
        limit: Int,
        offset: Int,
    ): List<DocumentListing>

    /** 원문 암호문과 그 봉투를 읽고 **그 행을 잠근다**. 없으면 `null`. */
    fun lockSourceText(documentId: UUID): EncryptedContent?

    /** 원문 암호문과 봉투 두 값을 **한 UPDATE 로** 바꾼다. 갱신됐으면 `true`. */
    fun rewriteEnvelope(
        documentId: UUID,
        expected: EncryptedContent,
        sourceText: EncryptedContent,
    ): Boolean

    /** 내 문서 한 건을 **지운다**. 지웠으면 `true`, 없거나 내 것이 아니면 `false`. */
    fun deleteOwned(
        ownerId: UUID,
        documentId: UUID,
    ): Boolean
}

/** 한 변환 행의 암호문 세 열. **셋을 함께 다루는 것이 요점이다.** */
class ConversionCiphertexts(
    val easyText: EncryptedContent?,
    val maskedItems: EncryptedContent?,
    val editedText: EncryptedContent?,
)

/** 회전이 읽어 가는 변환 행 한 건 — 암호문 세 열과 **행의 봉투 두 값**. */
class ConversionEnvelope(
    val conversionId: UUID,
    val scheme: String,
    val keyVersion: Int,
    val ciphertexts: ConversionCiphertexts,
)

/** 사용자 경로가 읽어 가는 변환 행 한 건 — **암호문 그대로**와 평문 메타데이터. */
data class StoredConversion(
    val id: UUID,
    val documentId: UUID,
    val status: ConversionStatus,
    val ciphertexts: ConversionCiphertexts,
    val reviewedAt: Instant?,
    val missingPlaceholders: List<String>,
    val model: String?,
    val providerName: String?,
    val inputTokens: Int?,
    val outputTokens: Int?,
    val failureCode: String?,
) {
    /** 로그 허용목록 그대로 — 식별자·상태·실패 코드와 **개수**뿐이다. */
    override fun toString(): String =
        "StoredConversion($id, doc=$documentId, ${status.wireName}, failure=$failureCode, " +
            "missing=${missingPlaceholders.size})"
}

/** 잠근 행 — 상태와 봉투. 봉투가 행 단위라 열이 전부 NULL 이어도 세대를 묻는다. */
class LockedConversion(
    val status: ConversionStatus,
    val envelope: ConversionEnvelope,
) {
    /** 식별자·상태·세대만 남긴다. */
    override fun toString(): String =
        "LockedConversion(${envelope.conversionId}, ${status.wireName}, ${envelope.scheme} v${envelope.keyVersion})"
}

/** `conversions` 저장소. */
interface ConversionRepository {
    /** 대기 상태 변환을 만든다. **커밋하지 않는다.** */
    fun insertPending(
        id: UUID,
        documentId: UUID,
        scheme: String,
        keyVersion: Int,
    ): Conversion

    /** **내** 변환 한 건을 읽는다. 없거나 내 것이 아니면 `null` — **두 경우를 구분하지 않는다.** */
    fun findOwnedResult(
        ownerId: UUID,
        conversionId: UUID,
    ): StoredConversion?

    /** 회전 대상 행의 암호문과 봉투를 읽고 **그 행을 잠근다**. 없으면 `null`. */
    fun lockEnvelope(conversionId: UUID): ConversionEnvelope?

    /** 암호문 세 열과 봉투 두 값을 **한 UPDATE 로** 바꾼다. 갱신됐으면 `true`. */
    fun rewriteEnvelope(
        expected: ConversionEnvelope,
        scheme: String,
        keyVersion: Int,
        ciphertexts: ConversionCiphertexts,
    ): Boolean

    /**
     * 검수 저장 대상인 **내** 변환을 읽고 **잠근다**(`FOR NO KEY UPDATE`) — 회전과 직렬화한다.
     * 없거나 내 것이 아니면 `null` — **두 경우를 구분하지 않는다.**
     */
    fun lockOwnedForReview(
        ownerId: UUID,
        conversionId: UUID,
    ): LockedConversion?

    /**
     * 검수본과 검수 시각을 **한 UPDATE 로** 저장한다. [updated] 가 **쓸 행 버전 전체**인 것은
     * 라벨과 열 내용이 어긋난 조합을 호출자가 만들 수 없게 한다. [ownerId] 는 잉여가 아니다 —
     * 소유 술어가 **쓰기 문장 자신에도** 걸린다. `false` 는 **잠금 전제가 깨졌다는 신호다.**
     */
    fun saveReview(
        ownerId: UUID,
        expected: ConversionEnvelope,
        requiredStatus: ConversionStatus,
        updated: ConversionEnvelope,
    ): Boolean
}

/** 마스킹 대응표를 **읽는** 포트. */
fun interface MaskedItemReader {
    /** 복호화된 대응표 JSON 을 항목 목록으로 되살린다. */
    fun decode(body: PlainBody): List<MaskedItemView>
}

/** 마스킹 대응표를 **저장용 JSON 으로 만드는** 포트. 결과는 반드시 암호화해서 저장한다. */
fun interface MaskedItemWriter {
    fun encode(items: List<MaskedItem>): PlainBody
}

/** 변환 작업 큐. */
fun interface ConversionQueue {
    /** 변환 작업을 등록한다. 호출자의 트랜잭션 안에서 돈다. */
    fun enqueue(conversionId: UUID)
}

/** 업로드 한 번이 **같은 트랜잭션에서** 쓰는 세 저장소. */
class DocumentStorage(
    val documents: DocumentRepository,
    val conversions: ConversionRepository,
    val queue: ConversionQueue,
)

/** 작업 공간 **읽기 전용** 포트. */
interface WorkspaceLookup {
    /** 내 작업 공간이면 그 식별자, 아니면 `null`. **없는 것과 남의 것을 구분하지 않는다.** */
    fun findOwnedId(
        ownerId: UUID,
        workspaceId: UUID,
    ): UUID?

    /** 기본(가장 먼저 만든) 작업 공간. 하나도 없으면 `null`. */
    fun findDefaultId(ownerId: UUID): UUID?
}
