/** 사전 조회 엔드포인트 (P0-5, 계약 2.11.0). */

import { requestJson } from './client'
import type { DictionaryLookupRequest, DictionaryLookupResponse } from './types'

/**
 * 조회 문자열 상한. 계약 `x-input-limits.max_term_query_chars`와 같은 값이다.
 *
 * 서버도 같은 상한을 검증하지만(초과 시 422), 여기서 먼저 걸러 두면 지목한 문자열이
 * 너무 길 때 조용히 요청을 보내지 않는다 — 서버 요청·응답 왕복 없이 화면이 바로 판단한다.
 */
export const MAX_TERM_QUERY_CHARS = 100

/**
 * POST /dictionary/lookup — 지목한 문자열로 쉬운 말 사전 후보를 찾는다.
 *
 * 위치 정보(문단·시작·끝 오프셋)는 요청에 담지 않는다 — 이 오퍼레이션이 받는 것은
 * 선택된 문자열뿐이다(계약 2.11.0 위치 계약). 후보가 없어도 200이며 빈 배열이 온다.
 */
export function lookupTerm(text: string, signal?: AbortSignal): Promise<DictionaryLookupResponse> {
  const body: DictionaryLookupRequest = { text }
  return requestJson<DictionaryLookupResponse>('/dictionary/lookup', {
    method: 'POST',
    body,
    signal,
  })
}
