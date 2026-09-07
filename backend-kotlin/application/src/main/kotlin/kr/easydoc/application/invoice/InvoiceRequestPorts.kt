package kr.easydoc.application.invoice

import kr.easydoc.core.invoice.InvoiceRequestStatus
import kr.easydoc.core.privacy.CONTENT_MASK
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

// 세금계산서 요청 유스케이스가 바깥 세계에 요구하는 것들 — **포트 선언**.
//
// `application` 은 `infrastructure` 를 의존하지 않는다(`CreditAccountPorts.kt` 와 같은
// 규약). `invoice_requests`(V16) 는 [kr.easydoc.core.crypto.EncryptedField] 가 아는 봉인
// 대상이 아니다 — 사업자등록번호·상호·대표자·주소·연락 이메일은 사업자 정보이지 문서
// 본문·개인정보가 아니다(`credit_transactions`·`llm_calls`와 같은 판단). 다만 `toString()`
// 에는 마스킹한다(인구조사 규약, `SensitiveToStringReachTest`).
//
// **`OwnershipPredicateGuardTest`(게이트 27 M-3)의 대상이 아니다** — 그 가드는
// `documents`·`conversions`에 닿는 SQL만 훑는다(`EncryptedField.entries`에서 감시 테이블을
// 파생한다). `invoice_requests`는 그 목록에 없으므로 이 저장소의 SQL은 애초에 그 인구조사가
// 보는 범위 밖이다 — 소유 술어는 아래 KDoc대로 여전히 지킨다.

/** 세금계산서 요청 한 건. */
data class InvoiceRequestRow(
    val id: UUID,
    val workspaceId: UUID?,
    val ownerUserId: UUID,
    val businessNumber: String,
    val companyName: String,
    val representativeName: String?,
    val contactEmail: String,
    val address: String?,
    val periodFrom: LocalDate,
    val periodTo: LocalDate,
    val status: InvoiceRequestStatus,
    val operatorNote: String?,
    val requestedAt: Instant,
    val handledAt: Instant?,
) {
    /** 사업자 정보를 찍지 않는다(인구조사 규약). */
    override fun toString(): String =
        "InvoiceRequestRow(id=$id, workspaceId=$workspaceId, ownerUserId=$ownerUserId, " +
            "businessNumber=$CONTENT_MASK, companyName=$CONTENT_MASK, representativeName=$CONTENT_MASK, " +
            "contactEmail=$CONTENT_MASK, address=$CONTENT_MASK, periodFrom=$periodFrom, periodTo=$periodTo, " +
            "status=$status, operatorNote=$CONTENT_MASK, requestedAt=$requestedAt, handledAt=$handledAt)"
}

/** [InvoiceRequestRepository.create] 의 결과. */
sealed interface InvoiceRequestCreation {
    /** 만들어졌다. */
    data class Created(val row: InvoiceRequestRow) : InvoiceRequestCreation

    /** 소유 워크스페이스가 아니다(없거나 남의 것) — 404. */
    object WorkspaceNotFound : InvoiceRequestCreation

    /** 같은 워크스페이스·같은 기간의 `requested` 요청이 이미 있다 — 409. */
    object DuplicateOpenPeriod : InvoiceRequestCreation
}

/** [InvoiceRequestRepository.handle] 의 결과. */
sealed interface InvoiceRequestHandling {
    /** 처리됐다. */
    data class Handled(val row: InvoiceRequestRow) : InvoiceRequestHandling

    /** 그 id의 요청이 없다. */
    object NotFound : InvoiceRequestHandling

    /** 요청이 이미 `requested` 가 아니다(중복 처리). */
    object AlreadyHandled : InvoiceRequestHandling
}

/**
 * `invoice_requests`(V16) 저장소 — 계획 §2 결정 2·4.
 *
 * **[create]·[listForOwner] 는 소유 술어를 문장 자신에 건다**(`CreditAccountRepository`
 * KDoc과 같은 규약) — 사용자 요청 경로(`createInvoiceRequest`·`listInvoiceRequests`)가
 * 호출자가 제출한 `workspace_id`를 그대로 받아 넘기기 때문이다.
 *
 * **[handle] 은 소유 술어가 없다** — 운영자 전용 CLI(`invoice-handle` 프로필)가 id 하나로
 * 처리한다. 인증된 요청자 컨텍스트가 없어(운영자가 CLI 인자로 id만 준다) 소유자를 받을
 * 자리가 원래 없다(`CreditAccountRepository.ownerOf`·`rotate-keys` 배치와 같은 사유).
 */
interface InvoiceRequestRepository {
    @Suppress("LongParameterList")
    fun create(
        id: UUID,
        ownerId: UUID,
        workspaceId: UUID,
        businessNumber: String,
        companyName: String,
        representativeName: String?,
        contactEmail: String,
        address: String?,
        periodFrom: LocalDate,
        periodTo: LocalDate,
        requestedAt: Instant,
    ): InvoiceRequestCreation

    /**
     * **내** 워크스페이스의 요청 최근 [limit]건, 최신순. 워크스페이스가 없거나 내 것이
     * 아니면 `null`(존재 은닉, `CreditAccountRepository.read`와 같은 규약) — 목록이
     * 비어 있는 것과 구분한다.
     */
    fun listForOwner(
        ownerId: UUID,
        workspaceId: UUID,
        limit: Int,
    ): List<InvoiceRequestRow>?

    fun handle(
        id: UUID,
        status: InvoiceRequestStatus,
        note: String?,
        handledAt: Instant,
    ): InvoiceRequestHandling
}
