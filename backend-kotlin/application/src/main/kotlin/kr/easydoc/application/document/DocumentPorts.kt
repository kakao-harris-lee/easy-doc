package kr.easydoc.application.document

import kr.easydoc.core.crypto.EncryptedContent
import kr.easydoc.core.crypto.PlainBody
import kr.easydoc.core.document.Conversion
import kr.easydoc.core.document.ConversionStatus
import kr.easydoc.core.document.Document
import kr.easydoc.core.document.DocumentListing
import kr.easydoc.core.document.MaskedItemView
import kr.easydoc.core.document.SourceFormat
import kr.easydoc.core.pilot.MinutesSpent
import kr.easydoc.core.pilot.PublishIntent
import kr.easydoc.core.pilot.QualityScore
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

/** 내보내기가 읽는 행 — 변환 결과와 **파일명에 쓸 문서 제목**. */
class StoredExport(
    val result: StoredConversion,
    val documentTitle: String,
) {
    /** 제목은 길이만 남긴다. 본문은 이 객체에 없다. */
    override fun toString(): String = "StoredExport(${result.id}, 제목 ${documentTitle.length}자)"
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

    /**
     * 내보내기가 읽는 **내** 변환. 조회와 같은 소유 술어이고, 파일명에 쓸 제목을 함께 준다.
     * 없거나 내 것이 아니면 `null` — **두 경우를 구분하지 않는다.**
     */
    fun findOwnedExport(
        ownerId: UUID,
        conversionId: UUID,
    ): StoredExport?

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

/**
 * 저장할 피드백 한 행 — **DB 시계가 채우는 값을 뺀 전부**. [DocumentDraft] 와 같은 형태다.
 *
 * 라벨과 열 내용이 어긋난 조합을 호출자가 만들 수 없게 **행 하나를 통째로** 든다
 * ([ConversionEnvelope] 가 「쓸 행 버전 전체」인 것과 같은 판단이다). 특히 수정률 지표
 * 셋은 서로 모순될 수 있는 값들이라(검수본 없이 `edit_distance` 만 있는 행) 한 자리에서
 * 함께 만들어져야 한다.
 *
 * `user_id` 는 여기 없다 — 소유자는 [ConversionFeedbackRepository.upsert] 의 인자로 따로
 * 간다. 소유 술어는 쓰기 문장 자신이 지는 것이라 값 타입의 일부가 아니다.
 */
data class StoredFeedback(
    val conversionId: UUID,
    val publishIntent: PublishIntent,
    val qualityScore: QualityScore,
    val minutesSpent: MinutesSpent,
    /** 봉인된 자유 의견. 없으면 `null` — 스키마의 「셋이 함께 있거나 함께 없다」와 맞는다. */
    val comment: EncryptedContent?,
    val easyCharCount: Int?,
    val editedCharCount: Int?,
    val editDistance: Int?,
) {
    /**
     * 로그 허용목록 그대로 — 식별자·의향과 **의견의 유무**뿐이다.
     * ([StoredConversion.toString] 과 같은 규칙.)
     *
     * 자유 의견은 이미 암호문이라 [EncryptedContent.toString] 이 가리지만, 점수·분·지표까지
     * 찍을 이유가 없다. 셋을 함께 보면 「누가 몇 점을 주고 얼마나 고쳤는가」가 로그에 남고,
     * 그것은 파일럿 참여자 한 사람의 평가다.
     */
    override fun toString(): String =
        "StoredFeedback($conversionId, ${publishIntent.wireName}, 의견 ${if (comment == null) "없음" else "있음"})"
}

/**
 * 회전이 잠근 피드백 행 한 건 — **봉인된 자유 의견과 그 봉투**.
 *
 * `null` 인 [comment] 와 「행이 없다」를 구분하려고 감싼다. 자유 의견은 선택 항목이라
 * **행은 있는데 봉인된 것이 없는** 상태가 정상이고, 그 둘은 회전에서 다른 결과다
 * (`EnvelopeRotation.rotateFeedback`).
 *
 * 봉투 두 값이 [ConversionEnvelope] 처럼 따로 서지 않는 것은 이 표의 봉투 열이 암호문과
 * **함께 NULL 이 되기** 때문이다(V2 의 `ck_conversion_feedback_comment_*_paired`) — 행 단위
 * 봉투가 아니라 그 한 열의 봉투다. `documents.lockSourceText` 가 [EncryptedContent] 하나를
 * 돌려주는 것과 같은 형태다.
 */
class LockedFeedbackComment(val comment: EncryptedContent?) {
    /** 봉인 유무만 남긴다 — 암호문도 세대도 로그에 넣을 이유가 없다. */
    override fun toString(): String = "LockedFeedbackComment(의견 ${if (comment == null) "없음" else "있음"})"
}

/**
 * `conversion_feedback` 저장소.
 *
 * 사용자 경로는 **upsert 하나뿐이다** — 한 변환의 피드백은 1건이고 다시 보내면 덮어쓴다
 * (계약 #15 가 `POST` 가 아니라 `PUT` 인 사유). 만드는 자리와 고치는 자리가 같으므로 갈래를
 * 나누지 않는다.
 *
 * 나머지 둘은 **키 회전 전용**이다. `core/crypto/StoredContent.kt` 가 저장 암호화의 요구
 * 성질로 키 회전을 적어 두었고, 봉인된 열은 그 경로를 하나씩 가져야 한다 — 없으면 옛 세대를
 * 설정에서 내리는 순간 그 열의 행들이 영원히 열리지 않는다(AAD 에 `key_version` 이 실린다).
 * 회전 팔은 [ConversionRepository.lockEnvelope]/[ConversionRepository.rewriteEnvelope] 와 같은
 * 규약이다: **암호문만 오가고 평문을 보지 못하며**, 소유자를 인자로 받지 않는다(회전 배치에
 * 「내 것」이 없다).
 */
interface ConversionFeedbackRepository {
    /**
     * 피드백 한 행을 쓰거나 덮어쓴다. **커밋하지 않는다** — 트랜잭션 경계는 유스케이스가 연다.
     *
     * [ownerId] 는 `user_id` 컬럼에 그대로 들어간다. 집계가 참여자 수를 세는 축이고
     * (`docs/pilot-runbook.md` 「대상과 규모」), 소유 판정은 이 호출 **앞에서** 끝나 있다.
     *
     * @return 저장된 `submitted_at`. 덮어쓴 경우에도 그 시점의 값이다.
     */
    fun upsert(
        ownerId: UUID,
        feedback: StoredFeedback,
    ): Instant

    /**
     * 회전 대상 행의 봉인된 의견과 봉투를 읽고 **그 행을 잠근다**(`FOR NO KEY UPDATE`).
     * 행이 없으면 `null` — 행은 있는데 의견이 없는 상태는 [LockedFeedbackComment] 안의
     * `null` 이고, 둘은 다른 결과다.
     */
    fun lockComment(conversionId: UUID): LockedFeedbackComment?

    /**
     * 봉인된 의견과 봉투 두 값을 **한 UPDATE 로** 바꾼다. [expected] 는 잠근 채 읽은 암호문
     * 그 자체이고 그것이 쓰기 조건이다 — 정수 하나를 넘기면 조건을 좁게 쓰는 갈래가 생긴다.
     *
     * `false` 는 「할 일이 없었다」가 아니라 **잠금 전제가 깨졌다는 신호다.**
     */
    fun rewriteComment(
        conversionId: UUID,
        expected: EncryptedContent,
        comment: EncryptedContent,
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
