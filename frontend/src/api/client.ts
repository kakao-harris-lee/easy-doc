/**
 * 백엔드 호출 단일 창구.
 *
 * 화면 코드는 fetch를 직접 부르지 않는다 — 토큰 부착, 401 처리, 오류 메시지 해석을
 * 한 자리에 모아 두어야 "어떤 화면은 세션 만료를 놓친다" 같은 구멍이 생기지 않는다.
 *
 * 멀티파트 업로드와 파일 내려받기(blob)는 여기 없다. JSON이 아닌 요청은 그 화면을
 * 만드는 시점에 필요한 모양이 정해지므로, 쓰이지 않는 추상을 미리 두지 않는다.
 */

import { clearToken, readToken } from './token'
import type { ConversionResponse, ConversionReviewRequest, DocumentListResponse } from './types'

/** 서버 주소. 배포에서는 빌드 시 VITE_API_BASE_URL로 주입한다(하드코딩 금지). */
const BASE_URL = (import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8000').replace(/\/+$/, '')

/** 응답에서 사유를 읽지 못했을 때 쓰는 문구. */
const FALLBACK_ERROR_MESSAGE = '요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.'

/** 서버에 닿지 못했을 때 쓰는 문구 — 사용자가 취할 조치가 다르므로 구분한다. */
const NETWORK_ERROR_MESSAGE = '서버에 연결하지 못했습니다. 네트워크 상태를 확인해 주세요.'

/** 연결 실패를 나타내는 status 값 (HTTP 응답이 없었다는 뜻). */
export const NETWORK_ERROR_STATUS = 0

/** API 오류. status로 화면이 분기하고, message는 그대로 사용자에게 보여줄 수 있다. */
export class ApiError extends Error {
  readonly status: number

  constructor(status: number, message: string) {
    super(message)
    this.name = 'ApiError'
    this.status = status
  }
}

/** 세션이 끊겼을 때 앱이 반응할 자리 (로그인 화면으로 보내기). */
type UnauthorizedHandler = () => void

let unauthorizedHandler: UnauthorizedHandler | null = null

/**
 * 401 응답을 받았을 때 부를 함수를 등록한다.
 *
 * 여기서 직접 화면을 바꾸지 않는 이유: 라우팅은 React 쪽 관심사고, 클라이언트가
 * window.location을 건드리면 테스트에서 실제 이동이 일어나 검증이 어려워진다.
 */
export function setUnauthorizedHandler(handler: UnauthorizedHandler | null): void {
  unauthorizedHandler = handler
}

interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'DELETE'
  /** JSON으로 직렬화해 보낼 본문. */
  body?: unknown
  /** 저장된 토큰을 붙일지. 가입·로그인처럼 인증 전 호출은 false. */
  auth?: boolean
  signal?: AbortSignal
}

/** 오류 응답에서 사용자에게 보여줄 문구를 뽑는다 (`{"detail": ...}` 규약). */
async function readErrorMessage(response: Response): Promise<string> {
  let payload: unknown
  try {
    payload = await response.json()
  } catch {
    // 본문이 비었거나 JSON이 아닌 경우(프록시 오류 페이지 등).
    return FALLBACK_ERROR_MESSAGE
  }
  const detail = (payload as { detail?: unknown } | null)?.detail
  // 도메인 예외는 문자열 하나를 준다.
  if (typeof detail === 'string' && detail.trim() !== '') {
    return detail
  }
  // Pydantic 검증 실패(422)는 항목 배열을 준다 — 값(input)은 서버가 이미 걷어냈다.
  if (Array.isArray(detail)) {
    const messages = detail
      .map((item) => (item as { msg?: unknown } | null)?.msg)
      .filter((msg): msg is string => typeof msg === 'string' && msg.trim() !== '')
    if (messages.length > 0) {
      return messages.join('\n')
    }
  }
  return FALLBACK_ERROR_MESSAGE
}

/** 요청을 보내고 성공 응답만 돌려준다. 실패는 모두 ApiError로 올린다. */
async function send(path: string, options: RequestOptions): Promise<Response> {
  const { method = 'GET', body, auth = true, signal } = options
  const headers = new Headers()
  const token = auth ? readToken() : null
  if (token !== null) {
    headers.set('Authorization', `Bearer ${token}`)
  }
  if (body !== undefined) {
    headers.set('Content-Type', 'application/json')
  }

  let response: Response
  try {
    response = await fetch(`${BASE_URL}${path}`, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
      signal,
    })
  } catch (error) {
    // 요청 취소는 호출한 쪽이 의도한 것이므로 오류로 바꾸지 않는다.
    if (error instanceof DOMException && error.name === 'AbortError') {
      throw error
    }
    throw new ApiError(NETWORK_ERROR_STATUS, NETWORK_ERROR_MESSAGE)
  }

  if (!response.ok) {
    // 토큰을 들고 갔는데 401이면 그 토큰은 더 이상 쓸모가 없다 — 즉시 버리고
    // 앱에 알린다. 인증 전 호출(로그인 실패)은 여기 해당하지 않는다.
    if (response.status === 401 && token !== null) {
      clearToken()
      unauthorizedHandler?.()
    }
    throw new ApiError(response.status, await readErrorMessage(response))
  }
  return response
}

/** JSON 응답을 기대하는 요청. */
export async function requestJson<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const response = await send(path, options)
  return (await response.json()) as T
}

/** GET /documents — 내 문서를 최신순으로 조회한다. */
export function listDocuments(
  params: { limit?: number; offset?: number } = {},
  signal?: AbortSignal,
): Promise<DocumentListResponse> {
  const query = new URLSearchParams()
  if (params.limit !== undefined) {
    query.set('limit', String(params.limit))
  }
  if (params.offset !== undefined) {
    query.set('offset', String(params.offset))
  }
  const suffix = query.size > 0 ? `?${query.toString()}` : ''
  return requestJson<DocumentListResponse>(`/documents${suffix}`, { signal })
}

/** GET /conversions/{id} — 변환 상태·결과를 조회한다. */
export function getConversion(
  conversionId: string,
  signal?: AbortSignal,
): Promise<ConversionResponse> {
  return requestJson<ConversionResponse>(`/conversions/${conversionId}`, { signal })
}

/** PUT /conversions/{id} — 검수 수정본을 저장한다 (AI 초안은 그대로 남는다). */
export function saveReview(conversionId: string, editedText: string): Promise<ConversionResponse> {
  const body: ConversionReviewRequest = { edited_text: editedText }
  return requestJson<ConversionResponse>(`/conversions/${conversionId}`, {
    method: 'PUT',
    body,
  })
}
