package kr.easydoc.api.admin

import com.fasterxml.jackson.annotation.JsonProperty
import kr.easydoc.application.admin.AdminErrorRow
import kr.easydoc.application.admin.AdminErrorsView
import kr.easydoc.application.admin.AdminFailureCount
import kr.easydoc.application.admin.AdminProviderFailureCount
import java.time.Instant

/**
 * `GET /admin/errors` 응답 — 코드별 건수 + 최근 목록 + provider 실패 건수(V18, 계약
 * 2.26.0 신설, 백로그 「실패 호출 원장 추적」). **본문·프롬프트 없음.**
 */
data class AdminErrorsResponse(
    @get:JsonProperty("counts") val counts: List<AdminFailureCountResponse>,
    @get:JsonProperty("recent") val recent: List<AdminErrorItemResponse>,
    @get:JsonProperty("provider_failures") val providerFailures: List<AdminProviderFailureCountResponse>,
) {
    companion object {
        fun of(view: AdminErrorsView): AdminErrorsResponse =
            AdminErrorsResponse(
                counts = view.counts.map(AdminFailureCountResponse::of),
                recent = view.recent.map(AdminErrorItemResponse::of),
                providerFailures = view.providerFailures.map(AdminProviderFailureCountResponse::of),
            )
    }
}

data class AdminFailureCountResponse(
    @get:JsonProperty("failure_code") val failureCode: String,
    @get:JsonProperty("count") val count: Long,
) {
    companion object {
        fun of(row: AdminFailureCount): AdminFailureCountResponse =
            AdminFailureCountResponse(failureCode = row.failureCode, count = row.count)
    }
}

data class AdminErrorItemResponse(
    @get:JsonProperty("id") val id: String,
    @get:JsonProperty("workspace_id") val workspaceId: String,
    @get:JsonProperty("created_at") val createdAt: Instant,
    @get:JsonProperty("failure_code") val failureCode: String,
) {
    companion object {
        fun of(row: AdminErrorRow): AdminErrorItemResponse =
            AdminErrorItemResponse(
                id = row.id.toString(),
                workspaceId = row.workspaceId.toString(),
                createdAt = row.createdAt,
                failureCode = row.failureCode,
            )
    }
}

/**
 * `GET /admin/errors`의 `failure_class`별 건수 항목(V18, `llm_calls.outcome =
 * 'provider_error'`) — 개별 LLM 호출이 완성 자체를 못 받은 사유별 건수다. 위
 * [AdminFailureCountResponse]([counts])가 세는 `conversions.failure_code`(변환이
 * 최종적으로 실패로 보고된 사유)와는 다른 축이다.
 */
data class AdminProviderFailureCountResponse(
    @get:JsonProperty("failure_class") val failureClass: String,
    @get:JsonProperty("count") val count: Long,
) {
    companion object {
        fun of(row: AdminProviderFailureCount): AdminProviderFailureCountResponse =
            AdminProviderFailureCountResponse(failureClass = row.failureClass, count = row.count)
    }
}
