package kr.easydoc.api.admin

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import kr.easydoc.api.auth.AuthenticatedUser
import kr.easydoc.api.invoice.InvoiceRequestResponse
import kr.easydoc.application.invoice.InvoiceRequestRepository
import kr.easydoc.application.invoice.InvoiceRequestService
import kr.easydoc.core.exceptions.InvalidInputException
import kr.easydoc.core.invoice.InvoiceRequestStatus
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * `GET /admin/invoice-requests`·`POST /admin/invoice-requests/{id}/handle` — 어드민 최소
 * 계획 §2 결정 4 A1. `x-admin-only: true`(계약 2.25.0). [InvoiceRequestService.handle]을
 * `invoice-handle` CLI와 함께 재사용한다 — `handled_by`(V17)에 관리자 id를 남긴다.
 */
@RestController
@RequestMapping("/admin/invoice-requests")
class AdminInvoiceRequestController(
    private val repository: InvoiceRequestRepository,
    private val service: InvoiceRequestService,
) {
    /** `page` 1~100000, `size` 1~100(기본 20). */
    @GetMapping
    fun list(
        @RequestParam(name = "status", required = false) status: InvoiceRequestStatus?,
        @RequestParam(name = "page", defaultValue = "1")
        @Min(1)
        @Max(MAX_PAGE)
        page: Int,
        @RequestParam(name = "size", defaultValue = "20")
        @Min(1)
        @Max(100)
        size: Int,
    ): ResponseEntity<AdminInvoiceRequestListResponse> {
        val result = repository.listAll(status, page, size)
        return adminResponse(HttpStatus.OK).body(AdminInvoiceRequestListResponse.of(result, page, size))
    }

    @PostMapping("/{id}/handle", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun handle(
        user: AuthenticatedUser,
        @PathVariable("id") id: UUID,
        @RequestBody request: AdminInvoiceRequestHandleRequest,
    ): ResponseEntity<InvoiceRequestResponse> {
        val status = InvoiceRequestStatus.ofWireName(request.status)
        // `InvoiceRequestService.handle`은 REQUESTED를 `require()`(IllegalArgumentException)로
        // 막는다 — HTTP 경계에서는 422로 드러나야 하므로 여기서 먼저 거절한다.
        if (status == InvoiceRequestStatus.REQUESTED) {
            throw InvalidInputException(STATUS_MUST_BE_ISSUED_OR_REJECTED_MESSAGE)
        }
        val view = service.handle(id, status, request.note, handledBy = user.id)
        AdminActionLog.record(ACTION_INVOICE_HANDLE, user.id, id)
        return adminResponse(HttpStatus.OK).body(InvoiceRequestResponse.of(view))
    }

    private companion object {
        /** 계약 `listAdminInvoiceRequests.parameters[page].schema.maximum`과 같은 값. */
        const val MAX_PAGE = 100000L
        const val STATUS_MUST_BE_ISSUED_OR_REJECTED_MESSAGE = "status는 issued 또는 rejected여야 합니다"
        const val ACTION_INVOICE_HANDLE = "invoice_handle"
    }
}
