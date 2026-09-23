package kr.easydoc.api.illustration

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import kr.easydoc.api.MIGRATE_PROFILE
import kr.easydoc.api.auth.AuthenticatedUser
import kr.easydoc.application.illustration.IllustrationPlacementService
import kr.easydoc.application.illustration.IllustrationPlacementsView
import kr.easydoc.core.exceptions.InvalidInputException
import kr.easydoc.core.illustration.IllustrationAssetId
import kr.easydoc.core.illustration.IllustrationPlacement
import kr.easydoc.core.illustration.IllustrationPlacements
import kr.easydoc.core.illustration.PLACEMENT_ASSET_NOT_SELECTABLE_MESSAGE
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * ER-16 「그림 배치」 조회·저장. 토글은 ER-15 와 같은 `easydoc.illustrations.enabled`.
 *
 * **파일 내보내기(DOCX/HWPX/TXT)에 이 배치가 반영되지 않는다** — 웹 미리보기 전용이다
 * (계약 `IllustrationPlacementsResponse` 설명, v1 범위).
 */
@Profile("!$MIGRATE_PROFILE")
@RestController
class IllustrationPlacementController(private val service: IllustrationPlacementService) {
    @GetMapping(PLACEMENTS_PATH)
    fun read(
        user: AuthenticatedUser,
        @PathVariable(CONVERSION_ID) conversionId: UUID,
    ): ResponseEntity<IllustrationPlacementsResponse> = response(service.read(user.id, conversionId))

    @PutMapping(PLACEMENTS_PATH, consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun replace(
        user: AuthenticatedUser,
        @PathVariable(CONVERSION_ID) conversionId: UUID,
        @Valid @RequestBody request: IllustrationPlacementsRequest,
    ): ResponseEntity<IllustrationPlacementsResponse> {
        val entries = request.placements.map(::toDomainEntry)
        val saved =
            service.replace(user.id, conversionId, request.expectedContentRevision, IllustrationPlacements(entries))
        return response(saved)
    }

    /**
     * 형식 위반과 카탈로그 미존재·미검수를 **구분하지 않는 같은 메시지**로 거절한다
     * (`IllustrationPlacements.validateAgainst` 가 카탈로그 부재·미검수에 쓰는 것과
     * 같은 문구 — 존재 은닉).
     */
    private fun toDomainEntry(payload: IllustrationPlacementPayload): IllustrationPlacement {
        val assetId =
            IllustrationAssetId.parse(payload.assetId)
                ?: throw InvalidInputException(PLACEMENT_ASSET_NOT_SELECTABLE_MESSAGE)
        return IllustrationPlacement(payload.easyUnitIndex, assetId)
    }

    private fun response(view: IllustrationPlacementsView): ResponseEntity<IllustrationPlacementsResponse> =
        ResponseEntity
            .ok()
            .contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.CACHE_CONTROL, NO_STORE)
            .header(X_CONTENT_TYPE_OPTIONS, NOSNIFF)
            .body(IllustrationPlacementsResponse.of(view))

    private companion object {
        const val CONVERSION_ID = "conversion_id"
        const val PLACEMENTS_PATH = "/conversions/{conversion_id}/illustration-placements"
        const val X_CONTENT_TYPE_OPTIONS = "X-Content-Type-Options"
        const val NO_STORE = "no-store"
        const val NOSNIFF = "nosniff"
    }
}

data class IllustrationPlacementsRequest
    @JsonCreator
    constructor(
        @param:JsonProperty("expected_content_revision", required = true)
        @field:Min(1)
        @field:Max(9_007_199_254_740_991)
        val expectedContentRevision: Long,
        @param:JsonProperty("placements", required = true)
        @field:Valid
        val placements: List<IllustrationPlacementPayload>,
    ) {
        /** 개수만 남긴다 — 좌표·asset은 본문 내용을 유추할 수 있는 데이터다. */
        override fun toString(): String =
            "IllustrationPlacementsRequest(contentRevision=$expectedContentRevision, count=${placements.size})"
    }

data class IllustrationPlacementPayload
    @JsonCreator
    constructor(
        @param:JsonProperty("easy_unit_index", required = true)
        @field:Min(0)
        val easyUnitIndex: Int,
        @param:JsonProperty("asset_id", required = true) val assetId: String,
    )

/** `IllustrationPlacementsResponse` — 계약 스키마. [toString] 은 개수만 남긴다. */
data class IllustrationPlacementsResponse(
    @get:JsonProperty("conversion_id") val conversionId: UUID,
    @get:JsonProperty("current_content_revision") val currentContentRevision: Long,
    @get:JsonProperty("placements_content_revision")
    @get:JsonInclude(JsonInclude.Include.ALWAYS)
    val placementsContentRevision: Long?,
    @get:JsonProperty("stale") val stale: Boolean,
    @get:JsonProperty("placements") val placements: List<IllustrationPlacementPayload>,
) {
    override fun toString(): String =
        "IllustrationPlacementsResponse($conversionId, current=$currentContentRevision, " +
            "placements=$placementsContentRevision, stale=$stale, count=${placements.size})"

    companion object {
        fun of(view: IllustrationPlacementsView): IllustrationPlacementsResponse =
            IllustrationPlacementsResponse(
                conversionId = view.conversionId,
                currentContentRevision = view.currentContentRevision,
                placementsContentRevision = view.placementsContentRevision,
                stale = view.stale,
                placements = view.placements.map { IllustrationPlacementPayload(it.easyUnitIndex, it.assetId.value) },
            )
    }
}
