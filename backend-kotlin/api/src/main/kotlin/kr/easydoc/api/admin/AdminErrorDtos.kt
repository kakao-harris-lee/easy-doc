package kr.easydoc.api.admin

import com.fasterxml.jackson.annotation.JsonProperty
import kr.easydoc.application.admin.AdminErrorRow
import kr.easydoc.application.admin.AdminErrorsView
import kr.easydoc.application.admin.AdminFailureCount
import java.time.Instant

/** `GET /admin/errors` 응답 — 코드별 건수 + 최근 목록. **본문·프롬프트 없음.** */
data class AdminErrorsResponse(
    @get:JsonProperty("counts") val counts: List<AdminFailureCountResponse>,
    @get:JsonProperty("recent") val recent: List<AdminErrorItemResponse>,
) {
    companion object {
        fun of(view: AdminErrorsView): AdminErrorsResponse =
            AdminErrorsResponse(
                counts = view.counts.map(AdminFailureCountResponse::of),
                recent = view.recent.map(AdminErrorItemResponse::of),
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
