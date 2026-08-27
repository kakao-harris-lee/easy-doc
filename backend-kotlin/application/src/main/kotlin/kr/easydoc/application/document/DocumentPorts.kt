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

/**
 * 사용자 경로가 읽어 가는 문서 원문 한 건 — **암호문 그대로**와 평문 메타데이터.
 *
 * [StoredConversion] 과 같은 형태다: 저장소 포트는 평문을 보지 못하고, 여는 일은 유스케이스가
 * 한다. 형식과 문자 수를 암호문 옆에 함께 드는 것은 계약 `DocumentSourceResponse` 가 그 셋을
 * 한 응답으로 요구하기 때문이고, 셋이 **같은 행**에서 와야 어긋난 조합이 생기지 않는다.
 */
class StoredSourceText(
    val documentId: UUID,
    val sourceFormat: SourceFormat,
    val charCount: Int,
    val sourceText: EncryptedContent,
) {
    /**
     * 식별자·형식과 길이만 남긴다.
     *
     * **암호문 자체를 찍지 않는다.** [EncryptedContent.toString] 이 이미 바이트를 가리지만
     * 방식 이름과 세대를 남기고, 이 필드의 이름이 민감 토큰(`text`)이라 `SensitiveToStringReachTest`
     * 의 R-10 축이 그 출력을 유출로 읽는다. 여기서 봉투를 찍어 얻을 것이 없다 — 세대를 알아야
     * 하는 것은 회전이고, 그쪽은 [EncryptedContent] 를 직접 든다.
     */
    override fun toString(): String = "StoredSourceText($documentId, ${sourceFormat.wireName}, ${charCount}자)"
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

    /**
     * **내** 문서의 원문을 읽는다. 없거나 내 것이 아니거나 **보존 기간이 지났으면** `null` —
     * **세 경우를 구분하지 않는다.**
     *
     * 만료가 여기 함께 있는 이유: 파기는 워커 배치가 실제로 지울 때 일어나므로 만료 시각과
     * 다음 배치 사이에 창이 열리고, 이 포트가 돌려주는 것은 **마스킹 전 문서 전문**이라 그
     * 창에서 노출되는 양이 다른 조회와 다르다. 판정을 질의 자신이 지는 사유는 소유 술어와
     * 같다 — 읽고 나서 비교하는 형태면 이미 평문이 이 경계를 넘은 뒤다.
     *
     * [lockSourceText] 와 갈린 이유는 [DocumentOriginalRepository.findOwned] 가
     * [DocumentOriginalRepository.lockOriginal] 과 갈린 것과 같다: 저쪽은 **회전 배치**라
     * 소유자가 없고 행을 잠그며, 이쪽은 **사용자 요청 경로**라 소유 술어가 질의 자신에
     * 걸리고 잠그지 않는다. 한 함수로 합치면 회전이 소유자를 지어내거나 조회가 행을 잠근다.
     *
     * 형식·문자 수를 함께 주는 것은 계약 `DocumentSourceResponse` 가 그 셋을 한 응답으로
     * 요구하기 때문이다 — 두 번 물으면 그 사이에 문서가 파기될 수 있다.
     */
    fun findOwnedSource(
        ownerId: UUID,
        documentId: UUID,
    ): StoredSourceText?

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

/**
 * 봉인된 업로드 원본 한 건 — 암호문과 **봉하기 전 바이트 수**.
 *
 * 크기가 암호문 옆에 함께 서는 이유: 원본은 최대 10MB 라 「있는가·얼마나 큰가」를 묻는 쪽이
 * 「무엇인가」를 묻는 쪽보다 훨씬 잦다. [EncryptedContent.bytes] 의 길이는 nonce·태그가 붙은
 * 값이라 원본 크기가 아니고, 그것을 빼서 되짚는 계산을 호출자마다 적게 하지 않는다.
 */
class StoredOriginal(
    val bytes: EncryptedContent,
    val byteSize: Int,
) {
    /** 크기 두 개만 남긴다 — 암호문 자체는 [EncryptedContent.toString] 이 이미 가린다. */
    override fun toString(): String = "StoredOriginal(원본 ${byteSize}바이트, $bytes)"
}

/**
 * `document_originals` 저장소 — 업로드된 **원본 파일 바이트**.
 *
 * `documents` 와 표가 갈린 사유는 `V3__document_originals.sql` 머리주석이다. 포트가 갈린 사유는
 * 그것과 같다: 원본은 **선택적으로 존재하고**(붙여넣기 경로에는 행이 없다) 텍스트 원문과
 * **따로 회전한다**. 두 성질을 [DocumentRepository] 안에 섞으면 「문서 한 건」의 뜻이 흐려진다.
 *
 * 다른 저장소와 같은 규약이다: 평문이 이 경계를 넘지 않고([EncryptedContent] 만 오간다),
 * 커밋하지 않으며, 회전 팔은 소유자를 받지 않는다(회전 배치에 「내 것」이 없다).
 */
interface DocumentOriginalRepository {
    /**
     * 원본 한 행을 만든다. **커밋하지 않는다** — 트랜잭션 경계는 유스케이스가 연다.
     *
     * `documents` 행이 **같은 트랜잭션 안에 이미 있어야 한다**(FK). 문서 등록과 원본 저장이
     * 한 경계 안에 있는 것이 「저장은 실패했는데 업로드는 성공」을 구조적으로 없앤다.
     *
     * 암호문과 크기를 [StoredOriginal] 하나로 받는 것은 **둘이 어긋난 조합을 호출자가 만들 수
     * 없게** 하려는 것이다(`ConversionEnvelope` 가 「쓸 행 버전 전체」인 것과 같은 판단).
     *
     * [ownerId] 는 잉여가 아니다 — **소유 술어가 쓰기 문장 자신에 걸린다**
     * ([ConversionRepository.saveReview] 와 같은 규칙). 이 표에는 `user_id` 열이 없고 소유자는
     * `documents` 가 안다. 호출자가 이미 그 문서를 방금 만들었더라도, 남의 문서에 원본을 붙일
     * 수 있는 문장을 저장소가 제공하지 않는 것이 이 인자의 값어치다.
     */
    fun insert(
        ownerId: UUID,
        documentId: UUID,
        original: StoredOriginal,
    )

    /**
     * **내** 문서의 원본을 읽는다. 없거나 내 것이 아니면 `null` — **두 경우를 구분하지 않는다.**
     *
     * 소유자를 인자로 받는 것이 이 포트에서 유일하게 사용자 경로인 자리다. 원본을 문서 식별자
     * 하나로 읽을 수 있게 두면 다음 조각(§6.5 원본 형식 내보내기)이 그 구멍으로 남의 파일을
     * 내보낸다 — 계약이 「남의 자원은 404」라고 정한 축이라, 부르는 곳이 생기기 전에 술어를
     * 포트에 박아 둔다.
     *
     * 붙여넣기 문서는 행이 없으므로 언제나 `null` 이다.
     */
    fun findOwned(
        ownerId: UUID,
        documentId: UUID,
    ): StoredOriginal?

    /** 회전 대상 행의 암호문과 봉투를 읽고 **그 행을 잠근다**. 없으면 `null`. */
    fun lockOriginal(documentId: UUID): EncryptedContent?

    /** 원본 암호문과 봉투 두 값을 **한 UPDATE 로** 바꾼다. 갱신됐으면 `true`. */
    fun rewriteEnvelope(
        documentId: UUID,
        expected: EncryptedContent,
        original: EncryptedContent,
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
    /** 문서 행에서 오는 원본 형식. 변환 행에는 없고 **같은 조인**이 함께 읽는다. */
    val sourceFormat: SourceFormat,
    /**
     * 그 문서의 **원본 파일 바이트가 저장돼 있는가**(`document_originals` 행의 유무).
     *
     * 바이트 자체를 들지 않는다 — 조회는 그것을 읽지 않고, 최대 10MB 를 열 이유가 없다.
     * 서식 유지 판정([kr.easydoc.core.document.formatPreservationOf])이 묻는 것은
     * 「되살릴 원본이 있는가」 하나뿐이다.
     */
    val hasStoredOriginal: Boolean,
    val ciphertexts: ConversionCiphertexts,
    val reviewedAt: Instant?,
    /**
     * `conversion_feedback.submitted_at` — 이 변환에 피드백을 마지막으로 낸 시각. 없으면 `null`.
     *
     * 조인해서 함께 읽는다. 피드백 표는 **왼쪽 조인**이다(행이 없는 것이 정상이고, 그때
     * 변환이 목록·조회에서 사라지면 안 된다). 시각 하나만 드는 사유는 계약
     * `ConversionResponse.feedback_submitted_at` — 봉인된 자유 의견은 여기로 나가지 않는다.
     */
    val feedbackSubmittedAt: Instant?,
    val missingPlaceholders: List<String>,
    val model: String?,
    val providerName: String?,
    val inputTokens: Int?,
    val outputTokens: Int?,
    val failureCode: String?,
) {
    /** 로그 허용목록 그대로 — 식별자·상태·형식·실패 코드와 **개수**뿐이다. */
    override fun toString(): String =
        "StoredConversion($id, doc=$documentId, ${status.wireName}, ${sourceFormat.wireName}, " +
            "failure=$failureCode, missing=${missingPlaceholders.size})"
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

/**
 * **봉인된 열이 사는 저장소 전부** — 키 회전이 받는 묶음.
 *
 * `core/crypto/StoredContent.kt` 의 `EncryptedField` 가 봉인된 **열**을 전수 열거하고, 이 묶음이
 * 그 열들이 사는 **표**를 전수로 든다. 둘을 나란히 두는 이유: 새 봉인 열이 생기면 회전 경로도
 * 함께 생겨야 하는데(없으면 옛 세대를 내리는 순간 그 열이 영원히 열리지 않는다), 저장소를
 * 인자로 흩어 놓으면 「어디까지가 전부인가」를 세는 자리가 사라진다.
 *
 * [DocumentStorage] 와 같은 형태이고 이유도 같다 — 함께 서야 하는 협력자를 묶는다.
 */
class SealedStores(
    val documents: DocumentRepository,
    val originals: DocumentOriginalRepository,
    val conversions: ConversionRepository,
    val feedback: ConversionFeedbackRepository,
)

/**
 * 업로드 한 번이 **같은 트랜잭션에서** 쓰는 네 저장소.
 *
 * [originals] 가 여기 있는 것이 「원본 저장 실패가 업로드를 조용히 성공시키지 않는다」의
 * 형태다 — 다른 경계에 두면 문서만 남고 원본이 사라지는 갈래가 생긴다.
 */
class DocumentStorage(
    val documents: DocumentRepository,
    val originals: DocumentOriginalRepository,
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
