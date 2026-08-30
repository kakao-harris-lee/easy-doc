/**
 * 백엔드 호출 단일 창구.
 *
 * 화면 코드는 fetch를 직접 부르지 않는다 — 토큰 부착, 401 처리, 오류 메시지 해석을
 * 한 자리에 모아 두어야 "어떤 화면은 세션 만료를 놓친다" 같은 구멍이 생기지 않는다.
 */

import { clearToken, readToken } from './token'
import type {
  ConversionFeedbackRequest,
  ConversionFeedbackResponse,
  ConversionResponse,
  ConversionReviewRequest,
  DocumentCreatedResponse,
  DocumentListResponse,
  DocumentSourceResponse,
  DocumentTextRequest,
  ExportFormat,
  WorkspaceListResponse,
  WorkspaceNameRequest,
  WorkspaceResponse,
} from './types'

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
  method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE'
  /** 보낼 본문. FormData면 그대로, 그 밖에는 JSON으로 직렬화한다. */
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
  // FormData의 Content-Type은 브라우저가 정해야 한다 — 여기서 손으로 붙이면 파서가
  // 파트를 가르는 데 쓰는 boundary가 빠져 서버가 본문을 읽지 못한다.
  const isFormData = body instanceof FormData
  if (body !== undefined && !isFormData) {
    headers.set('Content-Type', 'application/json')
  }

  let response: Response
  try {
    response = await fetch(`${BASE_URL}${path}`, {
      method,
      headers,
      body: body === undefined ? undefined : isFormData ? body : JSON.stringify(body),
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

/**
 * POST /documents — 붙여넣은 본문으로 문서를 등록한다 (202, 변환은 그 뒤에 돌아간다).
 *
 * workspaceId가 null이면 싣지 않는다 — 서버가 기본 작업 공간에 담는다. 작업 공간
 * 목록을 아직 못 불러온 상태에서도 업로드가 막히지 않게 하려는 선택이다.
 */
export function createDocumentFromText(
  text: string,
  workspaceId: string | null,
  title?: string,
): Promise<DocumentCreatedResponse> {
  const body: DocumentTextRequest = { text, title: title ?? null }
  if (workspaceId !== null) {
    body.workspace_id = workspaceId
  }
  return requestJson<DocumentCreatedResponse>('/documents', { method: 'POST', body })
}

/** POST /documents — 업로드 파일로 문서를 등록한다 (multipart). */
export function createDocumentFromFile(
  file: File,
  workspaceId: string | null,
  title?: string,
): Promise<DocumentCreatedResponse> {
  const form = new FormData()
  form.append('file', file)
  if (title !== undefined && title !== '') {
    form.append('title', title)
  }
  if (workspaceId !== null) {
    form.append('workspace_id', workspaceId)
  }
  return requestJson<DocumentCreatedResponse>('/documents', { method: 'POST', body: form })
}

/** GET /documents — 내 문서를 최신순으로 조회한다 (작업 공간을 주면 그 안만). */
export function listDocuments(
  params: { limit?: number; offset?: number; workspaceId?: string } = {},
  signal?: AbortSignal,
): Promise<DocumentListResponse> {
  const query = new URLSearchParams()
  if (params.limit !== undefined) {
    query.set('limit', String(params.limit))
  }
  if (params.offset !== undefined) {
    query.set('offset', String(params.offset))
  }
  if (params.workspaceId !== undefined) {
    query.set('workspace_id', params.workspaceId)
  }
  const suffix = query.size > 0 ? `?${query.toString()}` : ''
  return requestJson<DocumentListResponse>(`/documents${suffix}`, { signal })
}

/**
 * GET /documents/{id}/source — 추출된 원문을 가져온다.
 *
 * **한 번만 부른다.** 원문은 문서 등록 시점에 확정돼 변하지 않으므로 변환 상태처럼
 * 주기적으로 물어볼 값이 아니다(`src/review/sourceText.ts`).
 *
 * 소유자가 아니거나 보관 기간이 지나 파기된 문서는 404다 — 존재를 숨기기 위해 서버가
 * 403 대신 404로 답한다.
 */
export function getDocumentSource(
  documentId: string,
  signal?: AbortSignal,
): Promise<DocumentSourceResponse> {
  return requestJson<DocumentSourceResponse>(`/documents/${documentId}/source`, { signal })
}

/** GET /workspaces — 내 작업 공간을 만든 순서대로 조회한다 (문서 수 포함). */
export function listWorkspaces(signal?: AbortSignal): Promise<WorkspaceListResponse> {
  return requestJson<WorkspaceListResponse>('/workspaces', { signal })
}

/** POST /workspaces — 작업 공간을 만든다. 같은 이름이 있으면 409. */
export function createWorkspace(name: string): Promise<WorkspaceResponse> {
  const body: WorkspaceNameRequest = { name }
  return requestJson<WorkspaceResponse>('/workspaces', { method: 'POST', body })
}

/**
 * PATCH /workspaces/{id} — 이름을 바꾼다.
 *
 * 삭제(DELETE /workspaces/{id})는 여기에 두지 않는다. 화면이 이번 범위에 없어
 * (app/api/workspaces.py 참고) 부를 곳이 없고, 되돌릴 수 없는 조작의 통로를 미리
 * 열어 두면 확인 절차 없이 연결될 위험만 남는다.
 */
export function renameWorkspace(workspaceId: string, name: string): Promise<WorkspaceResponse> {
  const body: WorkspaceNameRequest = { name }
  return requestJson<WorkspaceResponse>(`/workspaces/${workspaceId}`, { method: 'PATCH', body })
}

/**
 * DELETE /documents/{id} — 문서와 변환 결과를 즉시 파기한다.
 *
 * 204라 본문이 없다 — requestJson을 쓰면 빈 본문을 JSON으로 읽다가 실패한다.
 */
export async function deleteDocument(documentId: string): Promise<void> {
  await send(`/documents/${documentId}`, { method: 'DELETE' })
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

/**
 * PUT /conversions/{id}/feedback — 파일럿 검수 피드백을 저장한다.
 *
 * 멱등 upsert다. 한 변환의 피드백은 1건이고 다시 보내면 덮어쓴다 — 실무자가 검수
 * 도중 값을 고쳐 다시 보내도 표에 줄이 늘지 않는다(docs/pilot-runbook.md 게이트 ①).
 */
export function saveFeedback(
  conversionId: string,
  feedback: ConversionFeedbackRequest,
): Promise<ConversionFeedbackResponse> {
  return requestJson<ConversionFeedbackResponse>(`/conversions/${conversionId}/feedback`, {
    method: 'PUT',
    body: feedback,
  })
}

/** 내려받은 파일 한 건. 저장 이름은 서버가 정한 것을 따른다. */
export interface DownloadedFile {
  blob: Blob
  /** 서버가 Content-Disposition으로 알려준 이름. 읽지 못하면 null. */
  filename: string | null
}

/** `filename*=UTF-8''...`에서 실제 파일명을 뽑는다. 없으면 null. */
function parseFilename(disposition: string | null): string | null {
  if (disposition === null) {
    return null
  }
  // 한글 이름은 filename*(RFC 5987)에만 실린다 — filename=은 ASCII 대체 이름이다.
  const encoded = /filename\*=UTF-8''([^;]+)/i.exec(disposition)?.[1]
  if (encoded === undefined) {
    return null
  }
  try {
    return decodeURIComponent(encoded)
  } catch {
    // 서버가 만든 값이라 여기까지 올 일은 없지만, 이름 하나 때문에 내려받기를
    // 통째로 실패시킬 이유는 없다.
    return null
  }
}

/**
 * GET /conversions/{id}/export — 검수 완료 문서를 파일로 받는다.
 *
 * JSON이 아니라 바이트를 받으므로 requestJson을 쓰지 않는다. 파일명은 응답 헤더에서
 * 읽는다 — 개발 환경은 교차 출처라 백엔드가 Content-Disposition을 노출 목록에 넣어
 * 두었지만, 프록시가 걷어낼 수도 있어 호출한 쪽이 대체 이름을 갖는다.
 *
 * **`format`은 선택이 아니라 주장이다.** 서버가 원본에서 형식을 정하고(계약
 * `x-export-format-derivation.enforcement`), 다른 값을 주면 409로 거절한다. 계약은
 * 생략도 허용하지만 여기서는 늘 보낸다 — 화면이 `conversion.export_format`을 그대로
 * 넘기므로 값이 언제나 일치하고, 보내 두면 서버와 화면이 갈린 날 조용히 다른 형식을
 * 받는 대신 409로 드러난다.
 */
export async function downloadExport(
  conversionId: string,
  format: ExportFormat,
): Promise<DownloadedFile> {
  const response = await send(`/conversions/${conversionId}/export?format=${format}`, {})
  return {
    blob: await response.blob(),
    filename: parseFilename(response.headers.get('Content-Disposition')),
  }
}
