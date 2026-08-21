package kr.easydoc.api.workspace

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import kr.easydoc.core.workspace.Workspace
import kr.easydoc.core.workspace.WorkspaceListing

/** `/workspaces` 네 오퍼레이션의 요청·응답 본문. */
data class WorkspaceNameRequest
    @JsonCreator
    constructor(
        @param:JsonProperty("name") val name: String,
    ) {
        /** **이름을 찍지 않는다** — [Workspace.toString] 과 같은 이유(A-3). */
        override fun toString(): String = "WorkspaceNameRequest(name=${Workspace.NAME_MASK})"
    }

/** 작업 공간 한 건. 계약 `components/schemas/WorkspaceResponse` — `POST`·`PATCH` 응답이다. */
data class WorkspaceResponse(
    @get:JsonProperty("id") val id: String,
    @get:JsonProperty("name") val name: String,
    @get:JsonProperty("created_at") val createdAt: String,
) {
    /** 이름을 찍지 않는다 — [Workspace.toString] 과 같은 이유(A-3). */
    override fun toString(): String = "WorkspaceResponse(id=$id, name=${Workspace.NAME_MASK}, createdAt=$createdAt)"

    companion object {
        fun of(workspace: Workspace): WorkspaceResponse =
            WorkspaceResponse(
                id = workspace.id.toString(),
                name = workspace.name,
                createdAt = workspace.createdAt.toString(),
            )
    }
}

/** 목록 한 줄. 계약 `components/schemas/WorkspaceListItem` = `WorkspaceResponse` + `document_count`. */
data class WorkspaceListItemResponse(
    @get:JsonProperty("id") val id: String,
    @get:JsonProperty("name") val name: String,
    @get:JsonProperty("created_at") val createdAt: String,
    @get:JsonProperty("document_count") val documentCount: Int,
) {
    /** 이름을 찍지 않는다 — [Workspace.toString] 과 같은 이유(A-3). */
    override fun toString(): String =
        "WorkspaceListItemResponse(id=$id, name=${Workspace.NAME_MASK}, " +
            "createdAt=$createdAt, documentCount=$documentCount)"

    companion object {
        fun of(listing: WorkspaceListing): WorkspaceListItemResponse {
            val workspace = WorkspaceResponse.of(listing.workspace)
            return WorkspaceListItemResponse(
                id = workspace.id,
                name = workspace.name,
                createdAt = workspace.createdAt,
                documentCount = listing.documentCount,
            )
        }
    }
}

/** `GET /workspaces` 응답. 계약 `components/schemas/WorkspaceListResponse`. */
data class WorkspaceListResponse(
    @get:JsonProperty("items") val items: List<WorkspaceListItemResponse>,
)
