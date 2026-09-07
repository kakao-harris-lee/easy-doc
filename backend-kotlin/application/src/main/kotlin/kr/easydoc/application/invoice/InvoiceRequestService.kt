package kr.easydoc.application.invoice

import kr.easydoc.application.mail.EmailAddress
import kr.easydoc.application.mail.MailSender
import kr.easydoc.application.mail.OutboundMail
import kr.easydoc.core.exceptions.ConflictException
import kr.easydoc.core.exceptions.NotFoundException
import kr.easydoc.core.invoice.BusinessNumber
import kr.easydoc.core.invoice.InvoiceRequestStatus
import kr.easydoc.core.privacy.CONTENT_MASK
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** `POST /workspaces/{workspace_id}/invoice-requests` 요청 입력 — 정규화 전 원시 값. */
data class InvoiceRequestInput(
    val businessNumber: String,
    val companyName: String,
    val representativeName: String?,
    val contactEmail: String,
    val address: String?,
    val periodFrom: String,
    val periodTo: String,
) {
    /** 사업자 정보를 찍지 않는다(인구조사 규약). */
    override fun toString(): String =
        "InvoiceRequestInput(businessNumber=$CONTENT_MASK, companyName=$CONTENT_MASK, " +
            "representativeName=$CONTENT_MASK, contactEmail=$CONTENT_MASK, address=$CONTENT_MASK, " +
            "periodFrom=$periodFrom, periodTo=$periodTo)"
}

/** `GET`·`POST /workspaces/{workspace_id}/invoice-requests` 응답 — 요청 한 건. */
data class InvoiceRequestView(
    val id: UUID,
    val workspaceId: UUID?,
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
        "InvoiceRequestView(id=$id, workspaceId=$workspaceId, businessNumber=$CONTENT_MASK, " +
            "companyName=$CONTENT_MASK, representativeName=$CONTENT_MASK, contactEmail=$CONTENT_MASK, " +
            "address=$CONTENT_MASK, periodFrom=$periodFrom, periodTo=$periodTo, status=$status, " +
            "operatorNote=$CONTENT_MASK, requestedAt=$requestedAt, handledAt=$handledAt)"

    companion object {
        fun of(row: InvoiceRequestRow): InvoiceRequestView =
            InvoiceRequestView(
                id = row.id,
                workspaceId = row.workspaceId,
                businessNumber = row.businessNumber,
                companyName = row.companyName,
                representativeName = row.representativeName,
                contactEmail = row.contactEmail,
                address = row.address,
                periodFrom = row.periodFrom,
                periodTo = row.periodTo,
                status = row.status,
                operatorNote = row.operatorNote,
                requestedAt = row.requestedAt,
                handledAt = row.handledAt,
            )
    }
}

/**
 * 세금계산서 요청 유스케이스 — 요청 · 목록 · 운영자 처리(계획
 * `docs/plans/2026-09-07-invoice-requests.md` §2).
 *
 * [create]·[list] 는 워크스페이스 소유자(로그인한 사용자)가 부른다. [handle] 은
 * `invoice-handle` 운영 프로필 전용이다 — id 하나로 처리하고 소유자 검증이 없다
 * ([InvoiceRequestRepository] KDoc).
 *
 * 메일 두 종류는 **최선 노력**이다 — 발송 실패가 요청 자체를 실패시키지 않는다
 * (`EmailVerificationService.issueFor` 와 같은 규약). [operatorEmail] 이 비어 있으면
 * 운영자 알림을 보내지 않고 경고 로그 한 줄만 남긴다(계획 §2 결정 3).
 */
class InvoiceRequestService(
    private val repository: InvoiceRequestRepository,
    private val mail: MailSender,
    private val operatorEmail: String,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(InvoiceRequestService::class.java)

    fun create(
        ownerId: UUID,
        workspaceId: UUID,
        input: InvoiceRequestInput,
    ): InvoiceRequestView {
        val businessNumber = BusinessNumber.of(input.businessNumber)
        val companyName = requireValidCompanyName(input.companyName)
        val representativeName = requireValidRepresentativeName(input.representativeName)
        val contactEmail = EmailAddress.of(input.contactEmail)
        val address = requireValidAddress(input.address)
        val periodFrom = parseInvoicePeriodDate(input.periodFrom)
        val periodTo = parseInvoicePeriodDate(input.periodTo)
        requireValidInvoicePeriod(periodFrom, periodTo)

        val result =
            repository.create(
                id = UUID.randomUUID(),
                ownerId = ownerId,
                workspaceId = workspaceId,
                businessNumber = businessNumber.digits,
                companyName = companyName,
                representativeName = representativeName,
                contactEmail = contactEmail.value,
                address = address,
                periodFrom = periodFrom,
                periodTo = periodTo,
                requestedAt = clock.instant(),
            )
        val row =
            when (result) {
                is InvoiceRequestCreation.Created -> result.row
                InvoiceRequestCreation.WorkspaceNotFound -> throw NotFoundException(WORKSPACE_NOT_FOUND_MESSAGE)
                InvoiceRequestCreation.DuplicateOpenPeriod -> throw ConflictException(DUPLICATE_OPEN_PERIOD_MESSAGE)
            }

        sendOperatorMail(row)
        sendRequesterAckMail(row)
        return InvoiceRequestView.of(row)
    }

    /** **내** 워크스페이스의 요청 최근 50건, 최신순. 없거나 내 것이 아니면 404. */
    fun list(
        ownerId: UUID,
        workspaceId: UUID,
    ): List<InvoiceRequestView> {
        val rows =
            repository.listForOwner(ownerId, workspaceId, LIST_LIMIT)
                ?: throw NotFoundException(WORKSPACE_NOT_FOUND_MESSAGE)
        return rows.map(InvoiceRequestView::of)
    }

    /**
     * `invoice-handle` 프로필과 관리자 화면(`POST /admin/invoice-requests/{id}/handle`)
     * 공용. [status] 는 `ISSUED`·`REJECTED` 만 허용한다. [handledBy] 는 관리자 화면
     * 경유일 때만 관리자 id — CLI 는 `null`(어드민 최소 계획 §2 결정 3).
     */
    fun handle(
        id: UUID,
        status: InvoiceRequestStatus,
        note: String?,
        handledBy: UUID? = null,
    ): InvoiceRequestView {
        require(status != InvoiceRequestStatus.REQUESTED) {
            "handle 은 issued·rejected 로만 상태를 바꿀 수 있다: $status"
        }
        val validatedNote = requireValidOperatorNote(note)

        val result = repository.handle(id, status, validatedNote, clock.instant(), handledBy)
        val row =
            when (result) {
                is InvoiceRequestHandling.Handled -> result.row
                InvoiceRequestHandling.NotFound -> throw NotFoundException(REQUEST_NOT_FOUND_MESSAGE)
                InvoiceRequestHandling.AlreadyHandled -> throw ConflictException(ALREADY_HANDLED_MESSAGE)
            }

        sendStatusMail(row)
        return InvoiceRequestView.of(row)
    }

    private fun sendOperatorMail(row: InvoiceRequestRow) {
        if (operatorEmail.isBlank()) {
            log.warn("easydoc.billing.operator-email 설정이 비어 있어 세금계산서 요청 운영자 알림을 보내지 않는다")
            return
        }
        val subject = "[쉬운 글] 세금계산서 요청 — ${row.companyName} ${periodLabel(row)}"
        val body =
            "요청 id: ${row.id}\n워크스페이스 id: ${row.workspaceId}\n" +
                "사업자등록번호: ${row.businessNumber}\n상호: ${row.companyName}\n" +
                "기간: ${periodLabel(row)}\n연락 이메일: ${row.contactEmail}"
        sendBestEffort(operatorEmail, subject, body, "운영자 알림")
    }

    private fun sendRequesterAckMail(row: InvoiceRequestRow) {
        val subject = "[쉬운 글] 세금계산서 요청을 받았습니다"
        val body = "요청 id: ${row.id}\n기간: ${periodLabel(row)}"
        sendBestEffort(row.contactEmail, subject, body, "접수 안내")
    }

    private fun sendStatusMail(row: InvoiceRequestRow) {
        val issued = row.status == InvoiceRequestStatus.ISSUED
        val subject = if (issued) "[쉬운 글] 세금계산서가 발급되었습니다" else "[쉬운 글] 세금계산서 요청을 처리할 수 없습니다"
        val body =
            if (issued) {
                "요청 id: ${row.id}\n기간: ${periodLabel(row)}"
            } else {
                "요청 id: ${row.id}\n기간: ${periodLabel(row)}\n사유: ${row.operatorNote ?: ""}"
            }
        sendBestEffort(row.contactEmail, subject, body, "처리 상태 안내")
    }

    /** 메일 주소 형식 오류·발송 실패 모두 삼킨다 — 요청 자체를 실패시키지 않는다. */
    private fun sendBestEffort(
        rawTo: String,
        subject: String,
        body: String,
        label: String,
    ) {
        try {
            mail.send(OutboundMail(EmailAddress.of(rawTo), subject, body))
        } catch (
            @Suppress("TooGenericExceptionCaught") failure: RuntimeException,
        ) {
            log.warn("세금계산서 $label 메일 발송에 실패했다: 예외={}", failure::class.java.simpleName)
        }
    }

    private fun periodLabel(row: InvoiceRequestRow): String = "${row.periodFrom}~${row.periodTo}"

    companion object {
        private const val LIST_LIMIT = 50

        /** `WorkspaceService`·`CreditAccountService`와 같은 존재 은닉 문구. */
        const val WORKSPACE_NOT_FOUND_MESSAGE = "작업 공간을 찾을 수 없습니다"
        const val DUPLICATE_OPEN_PERIOD_MESSAGE = "같은 기간의 요청이 처리 대기 중입니다"
        const val REQUEST_NOT_FOUND_MESSAGE = "요청을 찾을 수 없습니다"
        const val ALREADY_HANDLED_MESSAGE = "이미 처리된 요청입니다"
    }
}
