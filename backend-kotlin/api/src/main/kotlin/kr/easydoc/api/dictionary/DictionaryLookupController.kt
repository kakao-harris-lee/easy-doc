package kr.easydoc.api.dictionary

import kr.easydoc.api.auth.AuthenticatedUser
import kr.easydoc.application.dictionary.DictionaryAttributionProvider
import kr.easydoc.application.dictionary.LookupRateLimiter
import kr.easydoc.application.dictionary.TermLookupService
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

// 계약·실경로 검증: `DictionaryLookupContractTest`, `DictionaryLookupReachTest`.

/**
 * `POST /dictionary/lookup` — 검수 화면 담당자가 지목한 문자열의 사전 지침 조회
 * (P0-5 조각 4, 계획 §3.4).
 *
 * **남용 한도를 조회보다 먼저 잰다.** [rateLimiter] 가 사용자별 분당 한도를 넘으면
 * [kr.easydoc.core.exceptions.RateLimitedException] 을 던지고, 그 다음에야 [termLookupService]
 * 가 [kr.easydoc.core.dictionary.TermQuery] 정제·조회를 한다 — 계획이 남용 한도의 목적을
 * "사전 전량 긁기를 늦추는 것"이라고 밝혔으므로, 잘못된 입력이어도 호출 자체는 한도를
 * 소비해야 한다.
 */
@RestController
class DictionaryLookupController(
    private val termLookupService: TermLookupService,
    private val rateLimiter: LookupRateLimiter,
    private val attribution: DictionaryAttributionProvider,
) {
    @PostMapping(DICTIONARY_LOOKUP_PATH, consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun lookup(
        user: AuthenticatedUser,
        @RequestBody request: DictionaryLookupRequest,
    ): ResponseEntity<DictionaryLookupResponse> {
        rateLimiter.checkAndRecord(user.id)
        val candidates = termLookupService.lookup(request.text)
        return ResponseEntity
            .ok()
            .contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.CACHE_CONTROL, NO_STORE)
            .header(X_CONTENT_TYPE_OPTIONS, NOSNIFF)
            .body(DictionaryLookupResponse.of(request.text, candidates, attribution.current()))
    }

    private companion object {
        /** 계약 `paths./dictionary/lookup`. */
        const val DICTIONARY_LOOKUP_PATH = "/dictionary/lookup"

        /** 값의 정본은 계약 `components/headers` 의 각 컴포넌트다([ConversionFeedbackController] 와 같다). */
        const val X_CONTENT_TYPE_OPTIONS = "X-Content-Type-Options"
        const val NO_STORE = "no-store"
        const val NOSNIFF = "nosniff"
    }
}
