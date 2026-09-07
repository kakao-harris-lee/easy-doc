package kr.easydoc.api.admin

import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity

/**
 * 관리자 컨트롤러 다섯 곳(`AdminWorkspaceController`·`AdminInvoiceRequestController`·
 * `AdminErrorController`·`AdminUsageController`·`AdminAnnouncementController`)이 공유하는
 * 사적 응답 헤더 부착 — `WorkspaceController.private`와 같은 모양(독립 리뷰 지적 —
 * 이전에는 컨트롤러마다 따로 쥐고 있었고 그중 셋은 `X-Content-Type-Options`를 빠뜨렸다).
 */
internal fun adminResponse(status: HttpStatus): ResponseEntity.BodyBuilder =
    ResponseEntity
        .status(status)
        .contentType(MediaType.APPLICATION_JSON)
        .header(CACHE_CONTROL, NO_STORE)
        .header(X_CONTENT_TYPE_OPTIONS, NOSNIFF)

private const val CACHE_CONTROL = "Cache-Control"
private const val NO_STORE = "no-store"
private const val X_CONTENT_TYPE_OPTIONS = "X-Content-Type-Options"
private const val NOSNIFF = "nosniff"
