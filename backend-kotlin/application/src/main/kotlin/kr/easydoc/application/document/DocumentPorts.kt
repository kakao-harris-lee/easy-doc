package kr.easydoc.application.document

import kr.easydoc.core.crypto.EncryptedContent
import kr.easydoc.core.document.Conversion
import kr.easydoc.core.document.Document
import kr.easydoc.core.document.DocumentListing
import kr.easydoc.core.document.SourceFormat
import java.util.UUID

// 문서·변환 유스케이스가 바깥 세계에 요구하는 것들 — **포트 선언**.
//
// `application` 은 `infrastructure` 를 의존하지 않는다(계획 §3.2). 구현은 JDBC 를 아는
// `infrastructure/document` 가 제공한다. 원본에서 `app/services/documents.py` 가
// `DocumentStore`·`ConversionStore`·`WorkspaceLookup` 세 `Protocol` 을 나란히 선언하던
// 자리와 같다 — 그래서 [WorkspaceLookup] 도 여기 있다.
//
// **[DocumentTextExtractor] 는 이 파일에 없다.** 별도 파일(`DocumentTextExtractor.kt`)로
// 이미 있고, 합치면 그 파일을 읽던 자리들이 흔들린다.
//
// ## 평문이 이 경계를 넘지 않는다
//
// 저장소 포트는 [EncryptedContent] 만 주고받고 `PlainBody` 를 **타입으로 보지 못한다.**
// 암호화하는 층이 `application` 인 근거는 계획 §4.1 셋이다 — ⑴ AEAD 결속에 행 UUID 가
// 들어가므로 UUID 생성이 암호화보다 앞서야 하고 그 순서를 정하는 곳이 유스케이스다,
// ⑵ 평문의 노출면을 좁힌다, ⑶ 회전이 한 유스케이스다.

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

/**
 * `conversions` 저장소.
 *
 * ## 소유 조건 규약 — [DocumentRepository] 와 같은 규칙이고, 여기엔 **적혀 있지도 않았다**
 *
 * 게이트 27 M-3 이 지적한 자리다. 이쪽은 「거짓 선언」이 아니라 **무선언**이었고, 강제자가
 * 0인 것은 같았다(`privacy-gate` 판정 §1.5).
 *
 * `conversions` 에는 소유자 컬럼이 없다 — 소유는 `conversions.document_id` 를 거쳐
 * `documents.user_id` 로만 닿는다(`V1__python_schema_baseline.sql`). 그래서 사용자 경로가
 * 변환 한 건을 읽으려면 **조인을 품은 단일 질의**여야 한다. 읽고 나서 소유자를 비교하는 형태는
 * 만들지 않는다 — [ConversionEnvelope] 에는 애초에 비교할 재료(`documentId`)가 없어서 질의를
 * 한 번 더 던지게 되고, 그 순간 [DocumentRepository] KDoc 이 금지한 형태가 확정된다.
 *
 * ## 오늘 여기에 사용자 경로용 읽기 포트는 없다
 *
 * [lockEnvelope] 는 유지보수(키 회전)용이라 소유자를 받지 않는다. **`GET /conversions/{id}` 를
 * 만들 때 이 메서드를 쓰지 마라** — 소유자 인자를 받고 조인을 SQL `WHERE` 안에 넣는 새 포트가
 * 필요하다(`privacy-gate` 해제 조건 ⒜, C6 단위).
 *
 * 이 규약을 지키는 것도 시그니처가 아니라 탐지기다 — `OwnershipPredicateGuardTest` 가 제품
 * 소스에서 이 테이블에 닿는 SQL 을 뽑아 정확 열거 핀과 대조한다. 오늘 이 인터페이스가 내는
 * SQL 은 그 핀에 항목으로 올라 있다 — 목록의 정본은 그 파일이지 이 문장이 아니다.
 */
interface ConversionRepository {
    /** 대기 상태 변환을 만든다. **커밋하지 않는다.** */
    fun insertPending(
        id: UUID,
        documentId: UUID,
        scheme: String,
        keyVersion: Int,
    ): Conversion

    /**
     * 회전 대상 행의 암호문과 봉투를 읽고 **그 행을 잠근다**. 없으면 `null`.
     *
     * 잠그는 이유와 이름이 `lock` 인 이유는 [DocumentRepository.lockSourceText] 와 같다.
     * 소유자를 받지 않는 이유도 같다.
     */
    fun lockEnvelope(conversionId: UUID): ConversionEnvelope?

    /** 암호문 세 열과 봉투 두 값을 **한 UPDATE 로** 바꾼다. 갱신됐으면 `true`. */
    fun rewriteEnvelope(
        expected: ConversionEnvelope,
        scheme: String,
        keyVersion: Int,
        ciphertexts: ConversionCiphertexts,
    ): Boolean
}

/**
 * 변환 작업 큐.
 *
 * ## 등록이 저장과 **같은 트랜잭션**이다
 *
 * 계획 §4.4 가 정한 구조다 — *"문서·변환·작업 행을 같은 DB 트랜잭션에서 저장하면 'DB 커밋
 * 성공, 큐 등록 실패' 간극이 사라진다."* 큐가 같은 PostgreSQL 이므로 등록은 INSERT 한
 * 문장이고, 그 문장이 저장과 같은 트랜잭션에 있으면 **문서만 있고 작업이 없는 상태가
 * 구조적으로 생기지 않는다.**
 *
 * 그래서 이 포트는 실패를 별도 갈래로 만들지 않는다 — 등록이 실패하면 저장도 함께
 * 롤백된다. Redis/ARQ 전제였던 「커밋 이후 등록 → 실패 시 `EnqueueFailed` 표시 + 502」는
 * 재현 대상이 사라진 자리다.
 *
 * **계약 조항의 처분은 끝났다** — 502 는 2026-08-20 에 폐기됐고(계약 `x-retired-responses`,
 * 리더 판정 L-1) 그 자리를 대신하는 것은 **500 + 전량 롤백**이다(`POST /documents`
 * description). 폐기한 상태 코드가 어느 오퍼레이션에도 되살아나지 않는지는
 * `DocumentContractNodeTest` 의 P-39 케이스가 계약 파일을 읽어 전역으로 잰다.
 * (2026-08-21 정정 — 종전 문면은 저장소에 없는 이름을 지목했다. 그 종류는 이제
 * `NamedReferenceGuardTest` 축 A 가 잰다.)
 *
 * ## 멱등하다
 *
 * 작업 식별자를 변환 식별자로 고정한다. 같은 변환을 두 번 등록해도 작업은 하나다 —
 * 계약이 *"등록은 작업 id를 변환 id로 고정해 멱등하다"* 로 이미 그렇게 적었다.
 */
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
