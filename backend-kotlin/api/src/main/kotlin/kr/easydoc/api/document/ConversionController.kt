package kr.easydoc.api.document

import kr.easydoc.api.MIGRATE_PROFILE
import kr.easydoc.api.auth.AuthenticatedUser
import kr.easydoc.application.document.ConversionExportService
import kr.easydoc.application.document.ConversionQueryService
import kr.easydoc.application.document.ConversionReviewService
import kr.easydoc.core.easyread.ExportFormat
import kr.easydoc.core.easyread.contentDisposition
import kr.easydoc.core.privacy.ReviewedBody
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

// 계약·실경로 검증: `ConversionReadContractTest`, `ConversionReadReachTest`,
// `ConversionReviewContractTest`, `ConversionReviewReachTest`,
// `ConversionExportContractTest`, `ConversionExportReachTest`.

/** `GET`·`PUT /conversions/{conversion_id}` 와 `GET .../export`. */
@Profile("!$MIGRATE_PROFILE")
@RestController
class ConversionController(
    private val conversions: ConversionQueryService,
    private val review: ConversionReviewService,
    private val exports: ConversionExportService,
) {
    /** 변환 한 건의 상태와 결과. 완료 전에는 결과 필드가 비어 있다(계약 `get.description`). */
    @GetMapping(CONVERSION_ITEM_PATH)
    fun readConversion(
        user: AuthenticatedUser,
        @PathVariable(CONVERSION_ID_VARIABLE) conversionId: UUID,
    ): ResponseEntity<ConversionResponse> {
        val view = conversions.read(ownerId = user.id, conversionId = conversionId)
        return ResponseEntity
            .ok()
            .contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.CACHE_CONTROL, NO_STORE)
            .header(X_CONTENT_TYPE_OPTIONS, NOSNIFF)
            .body(ConversionResponse.of(view))
    }

    /**
     * 검수본 저장. **AI 초안은 남는다.** 응답이 `GET` 과 같은 스키마라 헤더도 같다.
     * 요청 본문에서 [ReviewedBody] 를 만드는 **프로덕션 자리**다. 저장된 검수본을 다시
     * 감싸는 자리는 내보내기다.
     */
    @PutMapping(CONVERSION_ITEM_PATH, consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun updateConversion(
        user: AuthenticatedUser,
        @PathVariable(CONVERSION_ID_VARIABLE) conversionId: UUID,
        @RequestBody request: ConversionReviewRequest,
    ): ResponseEntity<ConversionResponse> {
        val view =
            review.save(
                ownerId = user.id,
                conversionId = conversionId,
                submitted = ReviewedBody(request.editedText),
            )
        return ResponseEntity
            .ok()
            .contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.CACHE_CONTROL, NO_STORE)
            .header(X_CONTENT_TYPE_OPTIONS, NOSNIFF)
            .body(ConversionResponse.of(view))
    }

    /**
     * 검수 완료 문서를 파일로 내려받는다. **이 응답에만** 자리표시자가 원문으로 복원될 수 있다.
     * 본문은 JSON 이 아니라 파일 바이트다.
     *
     * **`format` 은 선택이다**(계약 `x-export-format-derivation.enforcement`). 없으면 서버가
     * 원본에서 정하고, 있으면 그 값과 같아야 한다 — 그 판정은 변환 행을 읽어야 서므로
     * 여기가 아니라 [ConversionExportService] 가 한다. 이 층이 거르는 것은 **값 집합**
     * 하나뿐이고(`ExportFormatConverter` → 422), 그 갈래는 변환을 읽기 전에 갈린다.
     */
    @GetMapping(CONVERSION_EXPORT_PATH)
    fun exportConversion(
        user: AuthenticatedUser,
        @PathVariable(CONVERSION_ID_VARIABLE) conversionId: UUID,
        @RequestParam(name = FORMAT_PARAM, required = false) format: ExportFormat?,
    ): ResponseEntity<ByteArray> {
        val file = exports.export(ownerId = user.id, conversionId = conversionId, requested = format)
        return ResponseEntity
            .ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition(file.filename))
            .header(HttpHeaders.CACHE_CONTROL, NO_STORE)
            .header(X_CONTENT_TYPE_OPTIONS, NOSNIFF)
            .contentType(MediaType.parseMediaType(file.mediaType))
            .body(file.content)
    }

    private companion object {
        /** 계약 `paths./conversions/{conversion_id}` — 경로 문자열과 **변수 이름**. */
        const val CONVERSION_ITEM_PATH = "/conversions/{conversion_id}"
        const val CONVERSION_EXPORT_PATH = "/conversions/{conversion_id}/export"
        const val CONVERSION_ID_VARIABLE = "conversion_id"
        const val FORMAT_PARAM = "format"

        /**
         * 하한선 10곳에 붙는 사적 응답 헤더. 값의 정본은 계약 `components/headers` 의 각
         * 컴포넌트이고, 실제로 나가는 값이 그 `const` 와 같은지는 계약 케이스가 잰다.
         */
        const val X_CONTENT_TYPE_OPTIONS = "X-Content-Type-Options"
        const val NO_STORE = "no-store"
        const val NOSNIFF = "nosniff"
    }
}
