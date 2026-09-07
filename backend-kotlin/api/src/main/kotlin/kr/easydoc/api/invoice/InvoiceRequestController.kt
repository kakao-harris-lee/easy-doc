package kr.easydoc.api.invoice

import kr.easydoc.api.auth.AuthenticatedUser
import kr.easydoc.application.invoice.InvoiceRequestInput
import kr.easydoc.application.invoice.InvoiceRequestService
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * `POST`·`GET /workspaces/{workspace_id}/invoice-requests` — 세금계산서 요청 기록(계획
 * `docs/plans/2026-09-07-invoice-requests.md` §2, 계약 2.23.0).
 */
@RestController
@RequestMapping("/workspaces/{workspace_id}/invoice-requests")
class InvoiceRequestController(private val service: InvoiceRequestService) {
    /** **201** — 자원(요청 기록)이 실제로 생겼다. */
    @PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun create(
        user: AuthenticatedUser,
        @PathVariable("workspace_id") workspaceId: UUID,
        @RequestBody request: InvoiceRequestCreate,
    ): ResponseEntity<InvoiceRequestResponse> =
        ResponseEntity
            .status(HttpStatus.CREATED)
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                InvoiceRequestResponse.of(
                    service.create(
                        ownerId = user.id,
                        workspaceId = workspaceId,
                        input =
                            InvoiceRequestInput(
                                businessNumber = request.businessNumber,
                                companyName = request.companyName,
                                representativeName = request.representativeName,
                                contactEmail = request.contactEmail,
                                address = request.address,
                                periodFrom = request.periodFrom,
                                periodTo = request.periodTo,
                            ),
                    ),
                ),
            )

    /** 최근 50건, 최신순. 소유자만 조회할 수 있고 아니면 404(존재 은닉). */
    @GetMapping
    fun list(
        user: AuthenticatedUser,
        @PathVariable("workspace_id") workspaceId: UUID,
    ): ResponseEntity<InvoiceRequestListResponse> =
        ResponseEntity
            .status(HttpStatus.OK)
            .contentType(MediaType.APPLICATION_JSON)
            .body(InvoiceRequestListResponse(service.list(user.id, workspaceId).map(InvoiceRequestResponse::of)))
}
