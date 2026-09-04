package kr.easydoc.application.dictionary

import kr.easydoc.core.dictionary.TermCandidate
import kr.easydoc.core.dictionary.TermQuery

/**
 * 검수 화면 조회 유스케이스 (P0-5 조각 3).
 *
 * 원문 문자열을 받아 [TermQuery] 로 정제한 뒤 [TermCandidateSource] 에 넘긴다. 정제가
 * 거절하면(빈 문자열, 제어문자만, 상한 초과) [kr.easydoc.core.exceptions.InvalidInputException]
 * 이 그대로 올라간다 - 이 계층은 트랜잭션 경계일 뿐 HTTP 상태 코드를 모른다. 그 예외를 422 로
 * 옮기는 것은 조각 4(API 컨트롤러/`GlobalExceptionHandler`)의 몫이다.
 */
class TermLookupService(private val source: TermCandidateSource) {
    fun lookup(rawText: String): List<TermCandidate> = source.candidatesFor(TermQuery.of(rawText))
}
