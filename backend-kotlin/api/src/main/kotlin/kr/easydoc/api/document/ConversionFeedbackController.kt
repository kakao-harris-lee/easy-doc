package kr.easydoc.api.document

import kr.easydoc.api.MIGRATE_PROFILE
import kr.easydoc.api.auth.AuthenticatedUser
import kr.easydoc.application.document.ConversionFeedbackService
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

// 계약·실경로 검증: `ConversionFeedbackContractTest`, `ConversionFeedbackReachTest`.

/**
 * `PUT /conversions/{conversion_id}/feedback` — 파일럿 게이트 ①(master-plan §9)의 **유일한
 * 수기 입력**을 받는다.
 *
 * **`ConversionController` 에 얹지 않는다.** 저 컨트롤러는 변환 결과 자원을 다루고 이쪽은
 * 판정 근거를 다룬다 — 자원의 수명부터 다르다(피드백 표는 문서 파기와 분리돼 있다.
 * `V2__conversion_feedback.sql` 의 FK 주석). 변경 이유가 다른 것을 한 클래스에 모으지 않는다.
 */
@Profile("!$MIGRATE_PROFILE")
@RestController
class ConversionFeedbackController(private val feedback: ConversionFeedbackService) {
    /**
     * 피드백을 저장하고 **저장된 값 그대로**를 돌려준다. 처음 낸 것이든 덮어쓴 것이든 같은
     * 200 이다 — 「이미 냈는가」가 화면 분기로 새어 들어오지 않게 한다(계약 200 설명).
     *
     * 사적 헤더 2종을 **개별로** 붙인다. 전역 부착 장치가 있더라도 이 자리는 계약 하한선
     * (`x-private-response-headers.applies_to`)이라 전역이 빠진 컨텍스트에서도 서야 한다
     * (`PrivateHeaderFloorCensusTest`). 응답이 봉인해 둔 자유 의견을 풀어 싣는 자리이므로
     * 캐시 금지가 이 오퍼레이션의 성질이다.
     */
    @PutMapping(CONVERSION_FEEDBACK_PATH, consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun saveConversionFeedback(
        user: AuthenticatedUser,
        @PathVariable(CONVERSION_ID_VARIABLE) conversionId: UUID,
        @RequestBody request: ConversionFeedbackRequest,
    ): ResponseEntity<ConversionFeedbackResponse> {
        val view =
            feedback.save(
                ownerId = user.id,
                conversionId = conversionId,
                submitted = request.toSubmission(),
            )
        return ResponseEntity
            .ok()
            .contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.CACHE_CONTROL, NO_STORE)
            .header(X_CONTENT_TYPE_OPTIONS, NOSNIFF)
            .body(ConversionFeedbackResponse.of(view))
    }

    private companion object {
        /** 계약 `paths./conversions/{conversion_id}/feedback` — 경로 문자열과 **변수 이름**. */
        const val CONVERSION_FEEDBACK_PATH = "/conversions/{conversion_id}/feedback"
        const val CONVERSION_ID_VARIABLE = "conversion_id"

        /** 값의 정본은 계약 `components/headers` 의 각 컴포넌트다([ConversionController] 와 같다). */
        const val X_CONTENT_TYPE_OPTIONS = "X-Content-Type-Options"
        const val NO_STORE = "no-store"
        const val NOSNIFF = "nosniff"
    }
}
