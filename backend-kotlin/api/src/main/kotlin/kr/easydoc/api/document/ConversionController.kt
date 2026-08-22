package kr.easydoc.api.document

import kr.easydoc.api.MIGRATE_PROFILE
import kr.easydoc.api.auth.AuthenticatedUser
import kr.easydoc.application.document.ConversionQueryService
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

// 계약·실경로 검증: `ConversionReadContractTest`, `ConversionReadReachTest`.

/** `GET /conversions/{conversion_id}` — 변환 상태와 결과 조회. */
@Profile("!$MIGRATE_PROFILE")
@RestController
class ConversionController(private val conversions: ConversionQueryService) {
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

    private companion object {
        /** 계약 `paths./conversions/{conversion_id}` — 경로 문자열과 **변수 이름**. */
        const val CONVERSION_ITEM_PATH = "/conversions/{conversion_id}"
        const val CONVERSION_ID_VARIABLE = "conversion_id"

        /**
         * 하한선 10곳에 붙는 사적 응답 헤더. 값의 정본은 계약 `components/headers` 의 각
         * 컴포넌트이고, 실제로 나가는 값이 그 `const` 와 같은지는 계약 케이스가 잰다.
         */
        const val X_CONTENT_TYPE_OPTIONS = "X-Content-Type-Options"
        const val NO_STORE = "no-store"
        const val NOSNIFF = "nosniff"
    }
}
