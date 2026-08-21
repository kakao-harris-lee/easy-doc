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

/**
 * 저장 직전의 문서 한 건 — **DB 시계가 채우는 값을 뺀 전부**.
 *
 * `created_at`·`retention_expires_at` 이 여기 없는 것이 요점이다. 두 값은 DB `DEFAULT` 가
 * 채우고 `RETURNING` 으로 되읽는다 — 앱 시계와 DB 시계가 갈리는 자리를 만들지 않는다
 * (`JdbcWorkspaceRepository.create` 와 같은 규칙). 보존 만료는 사용자 데이터가 실제로
 * 파기되는 시각이라 특히 그렇다.
 *
 * [id] 를 호출자가 만든다. AEAD 결속(`associatedData`)에 행 식별자가 들어가므로
 * **UUID 생성이 암호화보다 앞서야** 하고, 그래서 저장소가 만들 수 없다
 * (`JdbcWorkspaceRepository.create` 는 저장소가 만드는데 문서는 그럴 수 없다 — 이 비대칭은
 * 계획 §4.1 이 명시적으로 기록한 것이다).
 *
 * `data class` 가 아닌 이유는 [Document] 와 같다 — 컴파일러가 만드는 `toString()` 에
 * [title] 이 실린다.
 */
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
 * `documents` 저장소.
 *
 * ## 소유 조건은 SQL `WHERE` 안에 둔다 — 다만 **이 인터페이스가 그것을 강제하지는 않는다**
 *
 * 사용자 요청 경로의 두 메서드 — 읽기 [listOwned] 와 **삭제 [deleteOwned]** — 는 `ownerId` 를
 * 받고 구현이 그것을 `WHERE` 절에 넣는다. "먼저 조회하고 나서 소유자를 비교한다"는 형태를
 * 만들지 않으려는 것이다 (`WorkspaceRepository` 와 같은 규칙) — 그 형태는 비교를 잊으면
 * 조용히 남의 자원을 내주고, 잊지 않아도 존재 여부가 응답 시간으로 샌다.
 *
 * **유지보수 경로의 [lockSourceText] 는 `ownerId` 를 받지 않는다.** 그래서 이 규약을
 * 「읽기 메서드가 전부 받는다」로 적으면 거짓이 된다 — 실제로 그렇게 적혀 있었고, 같은 파일
 * 아래쪽이 예외를 적고 있어 문면이 자기와 모순이었다(게이트 27 M-3 · `privacy-gate` 판정 §1.4).
 *
 * ## 무엇이 이 규약을 지키는가 — 시그니처가 아니라 **탐지기**다
 *
 * `OwnershipPredicateGuardTest` 가 제품 소스(`src/main`)를 훑어 `documents`·`conversions`
 * 테이블에 닿는 SQL 을 뽑고, 소유 매개변수가 걸리지 않은 문장을 **정확 열거 핀**과 대조한다.
 * 핀에 든 항목이 늘거나 줄면 그 diff 가 리뷰에 올라온다(수는 여기 적지 않는다 — 두 자리에
 * 적으면 갈린다. 목록의 정본은 그 파일이다). 그 장치가 재지 못하는 것(문자열 조립 SQL,
 * 매개변수의 결속 대상 등)도 그 파일 KDoc 에 적혀 있다.
 *
 * 사용자 경로가 변환·문서 암호문을 읽을 때 쓸 **소유자 인자 필수 포트는 아직 없다** —
 * `privacy-gate` 해제 조건 ⒜ 이고 C6 단위의 몫이다.
 */
interface DocumentRepository {
    /**
     * 문서 행을 만든다. **커밋하지 않는다** — 트랜잭션 경계는 유스케이스가 연다.
     *
     * `encryption_scheme`·`key_version` 은 [sourceText] 가 들고 온 값을 그대로 적는다.
     * 봉투 세 값이 한 타입에 묶여 있으므로 "암호문은 v2 인데 컬럼은 v1" 이 되지 않는다.
     */
    fun insert(
        ownerId: UUID,
        draft: DocumentDraft,
        sourceText: EncryptedContent,
    ): Document

    /**
     * 내 문서를 **최신순**으로 읽는다. [workspaceId] 를 주면 그 작업 공간 안만 본다.
     *
     * 정렬은 `created_at DESC, id DESC` 다 — `created_at` 은 트랜잭션 시각이라 동률이 나고,
     * 동률의 순서가 정해지지 않으면 페이지 경계에서 **같은 문서가 두 번 보이거나 아예 빠진다.**
     *
     * 총 개수를 세지 않는다. 다음 쪽 유무는 호출자가 `limit + 1` 로 판정한다
     * (계약 `DocumentListResponse` 가 총 개수를 싣지 않는 이유가 그것이다).
     */
    fun listOwned(
        ownerId: UUID,
        workspaceId: UUID?,
        limit: Int,
        offset: Int,
    ): List<DocumentListing>

    /**
     * 원문 암호문과 그 봉투를 읽고 **그 행을 잠근다**. 없으면 `null`.
     *
     * **`load` 가 아니라 `lock` 인 이유**: 이 읽기는 부수 효과가 있고 **트랜잭션 안에서만**
     * 뜻이 있다. 잠금이 없으면 읽기와 [rewriteEnvelope] 사이에 다른 트랜잭션의 내용 쓰기가
     * 끼어들고, 회전이 그것을 낡은 값으로 덮는다(게이트 27 ① · 계획 §9.2-ter).
     *
     * **소유자를 받지 않는다** — 키 회전은 사용자 요청 경로가 아니라 운영 배치의 일이고,
     * 그 배치에는 「내 것」이 없다. 그래서 호출자를 회전 유스케이스로 제한하는데, **그 제한을
     * 강제하는 장치는 없다.** 오늘 그것을 지키는 것은 이 문장과 「제품 호출자가 `EnvelopeRotation`
     * 둘뿐」이라는 사실뿐이며, 그 사실은 커밋 하나로 바뀐다(`privacy-gate` 판정 §4.1 — 강제자 0).
     *
     * 그 대신 이 메서드가 소유 술어 없이 사는 것은 `OwnershipPredicateGuardTest` 의 정확 열거
     * 핀에 **항목으로 올라 있다.** 사용자 경로가 원문 암호문을 읽어야 하면 이 메서드를 쓰지 말고
     * 소유자 인자를 받는 포트를 만들어라 — `privacy-gate` 해제 조건 ⒜, C6 단위다.
     */
    fun lockSourceText(documentId: UUID): EncryptedContent?

    /**
     * 원문 암호문과 봉투 두 값을 **한 UPDATE 로** 바꾼다. 갱신됐으면 `true`.
     *
     * [expected] 는 **[lockSourceText] 가 잠근 채 돌려준 그 값**이고, 그대로 낙관적 조건이
     * 된다 — 봉투 두 값뿐 아니라 **암호문 바이트까지** 비교한다. 정수 하나(`key_version`)만
     * 조건으로 두면 `key_version` 을 건드리지 않는 내용 쓰기가 조건을 통과해 **조용히
     * 사라진다**(계획 §9.2-ter 실측).
     *
     * 잠금이 서 있으면 이 조건은 깨질 수 없다. 그래서 `false` 는 「누가 먼저 회전했다」가
     * 아니라 **「잠금 전제가 성립하지 않았다」**는 신호다 — 자동 커밋으로 도는 배선처럼
     * 잠금이 문장 끝에서 풀리는 상태를 조용한 덮어쓰기가 아니라 실패로 드러낸다.
     */
    fun rewriteEnvelope(
        documentId: UUID,
        expected: EncryptedContent,
        sourceText: EncryptedContent,
    ): Boolean

    /**
     * 내 문서 한 건을 **지운다**. 지웠으면 `true`, 없거나 내 것이 아니면 `false`.
     *
     * ## 소유 조건이 `WHERE` 안에 있다 — 이 메서드가 그 규약의 첫 쓰기 적용이다
     *
     * 지금까지 소유 술어를 SQL 에 넣는 규약은 **읽기**([listOwned])에서만 실제로 도는
     * 자리를 가졌다. 여기서는 잘못 쓰면 잃는 것이 다르다 — 읽기의 실패는 남의 문서를
     * 보여 주는 것이고, 이 메서드의 실패는 **남의 문서를 지우는 것**이며 복구 수단이 없다.
     *
     * 그래서 「먼저 읽어 소유자를 비교하고 나서 지운다」를 만들지 않는다. 두 문장 사이에서
     * 소유자가 바뀔 여지가 있을 뿐 아니라(작업 공간 이전 같은 미래 기능), 비교를 잊은
     * 코드가 조용히 통과한다. **한 문장 안에 조건이 있으면 잊을 자리가 없다.**
     *
     * ## 반환이 `Unit` 이 아니라 `Boolean` 인 이유
     *
     * 「지울 것이 없었다」와 「지웠다」는 **응답이 갈리는** 사건이다(404 대 204). 저장소가
     * 그것을 삼키면 유스케이스가 그 둘을 구분할 방법이 없고, 계약이 요구한 **비멱등**
     * 동작(삭제 직후 재요청은 204 가 아니라 404)이 성립하지 않는다.
     *
     * ## 변환·작업 행을 **여기서 지우지 않는다**
     *
     * `conversions.document_id → documents.id ON DELETE CASCADE`(`V1`)와
     * `conversion_jobs.conversion_id → conversions.id ON DELETE CASCADE`(`V5`)가 이어져
     * 있으므로 이 한 문장이 세 테이블을 비운다. **애플리케이션이 같은 일을 한 번 더 하면
     * 그 자체가 결함이다** — ⑴ 두 경로가 생기면 어느 쪽이 실제로 지웠는지 알 수 없고,
     * ⑵ 앱 삭제문에는 소유 조건을 다시 적어야 하는데 그 사본이 원본과 갈리는 날이 오고,
     * ⑶ `conversions` 에는 소유자 컬럼이 없어(그 사실은 [ConversionRepository] KDoc)
     * 조건을 조인으로 조립하게 된다. CASCADE 는 그 세 위험을 전부 스키마로 옮긴다.
     *
     * 연쇄가 실제로 도는지는 `JdbcDocumentStoreTest` 가 실 PostgreSQL 에서 잰다 —
     * 문장 수까지 세므로 앱이 몰래 한 문장 더 넣으면 그쪽이 빨개진다.
     */
    fun deleteOwned(
        ownerId: UUID,
        documentId: UUID,
    ): Boolean
}

/**
 * 한 변환 행의 암호문 세 열. **셋을 함께 다루는 것이 요점이다.**
 *
 * 열 하나짜리 갱신 메서드를 만들지 않는 이유: 행당 키 세대가 하나라 두 문장으로 나누면
 * "세대는 v2 인데 한 열은 v1 암호문" 인 중간 상태가 생기고, 그 행은 **영원히 열리지 않는다**
 * (AAD 에 세대가 실리므로 태그 검증부터 실패한다).
 *
 * **이 규약을 지키는 것은 「지금 그런 메서드가 없다」가 아니다** — `EnvelopeColumnWriteGuardTest`
 * 가 소스 전수에서 `documents`·`conversions` 를 UPDATE 하는 SQL 을 뽑아, 암호문 열을 SET 하는
 * 문장이 `encryption_scheme`·`key_version` 도 **같은 문장에서** SET 하는지 단언한다. 산문으로
 * 두었더니 탐지기가 0개였다(게이트 27 ② · 계획 §9.2-ter D-r).
 *
 * `null` 은 **"그 열이 비어 있다"** 이지 "바꾸지 않는다"가 아니다. 대기 중 변환은 세 열이
 * 전부 NULL 이고, 그것을 빈 문자열로 암호화하면 **없던 내용을 지어낸 것**이라 되돌릴 수 없다.
 *
 * `toString()` 을 재정의하지 않는다 — 재정의가 없으면 `Any.toString()` 이 클래스명과 해시만
 * 내므로 구조적으로 샐 수 없다. 담고 있는 [EncryptedContent] 자신도 바이트를 찍지 않는다.
 */
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
    /**
     * 대기 상태 변환을 만든다. **커밋하지 않는다.**
     *
     * 암호문 세 열이 전부 NULL 인 채로 태어나지만 **봉투 두 값은 적는다.** `V3` 가 두 컬럼의
     * DEFAULT 를 없앴으므로 빠뜨리면 NOT NULL 위반으로 즉시 실패한다 — 그것이 `V3` 의 설계
     * 의도다(거짓 이름이 조용히 채워지는 경로를 막는다).
     */
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

    /**
     * 암호문 세 열과 봉투 두 값을 **한 UPDATE 로** 바꾼다. 갱신됐으면 `true`.
     *
     * [expected] 는 **[lockEnvelope] 가 잠근 채 돌려준 그 행**이고 그대로 낙관적 조건이
     * 된다 — 봉투 두 값과 **암호문 세 열 전부**를 비교한다. 세 열의 `null` 도 조건에
     * 포함된다(`IS NOT DISTINCT FROM`): 「비어 있었다」가 「누가 채웠다」로 바뀐 것이
     * 정확히 이 조건이 잡아야 할 사건이다. 사유는 [DocumentRepository.rewriteEnvelope].
     *
     * `updated_at` 을 건드리지 않는다 — 재암호화는 **내용의 변경이 아니다.** 그 컬럼은
     * 워커와 화면이 "이 변환에 무슨 일이 있었나"를 읽는 값이라, 회전 배치가 전 행의
     * `updated_at` 을 오늘로 밀어 버리면 그 뜻이 사라진다. 경합 판정에도 쓰지 않는다 —
     * 대리 지표라 쓰기가 깜빡하면 조용히 무력해지고, 지켜야 하는 값(암호문) 자체를 조건에
     * 넣을 수 있다. 게다가 `documents` 에는 그 컬럼이 아예 없다(계획 §9.2-ter).
     */
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

/**
 * 업로드 한 번이 **같은 트랜잭션에서** 쓰는 세 저장소.
 *
 * 셋을 한 타입으로 묶는 이유는 그것들이 함께 성공하거나 함께 실패해야 하기 때문이다
 * (계획 §4.4 — 문서·변환·작업 행을 같은 DB 트랜잭션에서 저장하면 "DB 커밋 성공, 큐 등록
 * 실패" 간극이 사라진다). 유스케이스가 셋을 따로 받으면 **셋 중 하나만 다른 경계에 두는
 * 배선**이 타입으로는 막히지 않는다.
 *
 * 묶음을 만든 직접 계기는 detekt `LongParameterList` 였다(유스케이스 생성자가 상한을
 * 넘었다). 그 신호가 가리킨 것이 실제로 **함께 다뤄야 할 것들이 흩어져 있다**는 사실이라
 * 임계값을 늘리는 대신 구조를 바꿨다 — 사실 관계를 그대로 적어 둔다.
 *
 * `toString()` 을 재정의하지 않는다 — 담고 있는 것이 포트 참조뿐이라 값이 샐 수 없다.
 */
class DocumentStorage(
    val documents: DocumentRepository,
    val conversions: ConversionRepository,
    val queue: ConversionQueue,
)

/**
 * 작업 공간 **읽기 전용** 포트.
 *
 * 문서 경로가 작업 공간을 만들거나 지울 이유가 없으므로, 가질 수 있는 권한을 필요한
 * 만큼으로 좁힌다(원본 `app/services/documents.py::WorkspaceLookup` 과 같은 판단).
 *
 * `WorkspaceRepository` 를 상속하지 않고 **별도 인터페이스**로 둔다 — 상속시키면 그 포트를
 * 구현하는 기존 대역들이 전부 이 두 메서드를 지게 되고, 문서 단위의 필요가 인증 단위의
 * 테스트를 흔든다.
 *
 * 구현도 **별도 클래스**다(`JdbcWorkspaceLookup`). 한 클래스가 두 포트를 겸하면 Spring 이
 * 같은 구상 타입의 빈을 둘 보고 주입이 모호해진다 — 실측으로 밟았고 그 경위는 그 클래스의
 * KDoc 에 있다.
 */
interface WorkspaceLookup {
    /** 내 작업 공간이면 그 식별자, 아니면 `null`. **없는 것과 남의 것을 구분하지 않는다.** */
    fun findOwnedId(
        ownerId: UUID,
        workspaceId: UUID,
    ): UUID?

    /** 기본(가장 먼저 만든) 작업 공간. 하나도 없으면 `null`. */
    fun findDefaultId(ownerId: UUID): UUID?
}
