/**
 * 관리자 전용 엔드포인트 (어드민 최소, 계약 2.25.0).
 *
 * 전부 `/admin/…` 아래이고 `x-admin-only: true`다 — 관리자가 아니거나 이메일이
 * 미검증이면 403 「관리자 권한이 필요합니다」(`AdminGuard`). 화면은 `ApiError.status`가
 * 403이면 서버 문구를 그대로 보여준다(정본 `docs/plans/2026-09-07-admin-minimum.md` §2).
 */

import { requestJson } from './client'
import type {
  AdminCreditAdjustmentRequest,
  AdminErrorsResponse,
  AdminInvoiceRequestHandleRequest,
  AdminInvoiceRequestListResponse,
  AdminWorkspaceDetailResponse,
  AdminWorkspaceListResponse,
  AnnouncementCreateRequest,
  AnnouncementListResponse,
  AnnouncementResponse,
  AnnouncementUpdateRequest,
  InvoiceRequestResponse,
  InvoiceRequestStatus,
  WorkspaceCreditsResponse,
} from './types'

/**
 * GET /admin/workspaces — 워크스페이스를 검색한다.
 *
 * `q`는 이름·소유자 이메일 부분 일치(대소문자 무시), 생략하면 전체다. 페이지 기본 1,
 * 크기 기본 20·최대 100.
 */
export function listAdminWorkspaces(
  params: { q?: string; page?: number; size?: number } = {},
  signal?: AbortSignal,
): Promise<AdminWorkspaceListResponse> {
  const query = new URLSearchParams()
  if (params.q !== undefined && params.q !== '') {
    query.set('q', params.q)
  }
  if (params.page !== undefined) {
    query.set('page', String(params.page))
  }
  if (params.size !== undefined) {
    query.set('size', String(params.size))
  }
  const suffix = query.size > 0 ? `?${query.toString()}` : ''
  return requestJson<AdminWorkspaceListResponse>(`/admin/workspaces${suffix}`, { signal })
}

/**
 * GET /admin/workspaces/{workspace_id} — 워크스페이스 상세를 읽는다.
 *
 * 요약 + 최근 거래 50건 + 세금계산서 요청 + 최근 변환 20건(본문·프롬프트 없음). 없으면
 * 404다(관리자 전용이라 존재 은닉이 아니라 진짜 404).
 */
export function readAdminWorkspace(
  workspaceId: string,
  signal?: AbortSignal,
): Promise<AdminWorkspaceDetailResponse> {
  return requestJson<AdminWorkspaceDetailResponse>(`/admin/workspaces/${workspaceId}`, { signal })
}

/**
 * POST /admin/workspaces/{workspace_id}/credits — 워크스페이스 크레딧을 수동으로
 * 조정한다. `credit-grant` 운영 프로필(C2)과 같은 경로를 재사용한다 — `credits`가
 * 0이면 안 되고(0 이상이면 부여, 음수면 조정), `reason`은 `plan_monthly`·`manual`·
 * `refund` 셋만 허용한다. 반영된 거래에 호출한 관리자의 `actor_user_id`가 남는다.
 */
export function adjustAdminWorkspaceCredits(
  workspaceId: string,
  request: AdminCreditAdjustmentRequest,
): Promise<WorkspaceCreditsResponse> {
  return requestJson<WorkspaceCreditsResponse>(`/admin/workspaces/${workspaceId}/credits`, {
    method: 'POST',
    body: request,
  })
}

/**
 * GET /admin/invoice-requests — 워크스페이스를 가로질러 전체 세금계산서 요청을
 * 조회한다. `status`(선택)로 좁힐 수 있다. 최신순.
 */
export function listAdminInvoiceRequests(
  params: { status?: InvoiceRequestStatus; page?: number; size?: number } = {},
  signal?: AbortSignal,
): Promise<AdminInvoiceRequestListResponse> {
  const query = new URLSearchParams()
  if (params.status !== undefined) {
    query.set('status', params.status)
  }
  if (params.page !== undefined) {
    query.set('page', String(params.page))
  }
  if (params.size !== undefined) {
    query.set('size', String(params.size))
  }
  const suffix = query.size > 0 ? `?${query.toString()}` : ''
  return requestJson<AdminInvoiceRequestListResponse>(`/admin/invoice-requests${suffix}`, {
    signal,
  })
}

/**
 * POST /admin/invoice-requests/{id}/handle — 세금계산서 요청을 발급·거절 처리한다.
 *
 * `InvoiceRequestService.handle`(운영 프로필과 같은 유스케이스)을 재사용한다 —
 * `status`는 `issued`·`rejected`만 허용한다. 처리된 요청에 호출한 관리자의
 * `handled_by`가 남고, 요청자에게 상태 안내 메일이 최선 노력으로 간다.
 */
export function handleAdminInvoiceRequest(
  id: string,
  request: AdminInvoiceRequestHandleRequest,
): Promise<InvoiceRequestResponse> {
  return requestJson<InvoiceRequestResponse>(`/admin/invoice-requests/${id}/handle`, {
    method: 'POST',
    body: request,
  })
}

/**
 * GET /admin/errors — 기간 내 실패한 변환을 코드별로 집계한다.
 *
 * `from`·`to`(생략 시 이번 달 1일~오늘, `readWorkspaceUsage`와 같은 기본값·범위 규칙)
 * 안의 `failed` 변환. **본문·프롬프트는 어디에도 없다.**
 */
export function readAdminErrors(
  params: { from?: string; to?: string } = {},
  signal?: AbortSignal,
): Promise<AdminErrorsResponse> {
  const query = new URLSearchParams()
  if (params.from !== undefined) {
    query.set('from', params.from)
  }
  if (params.to !== undefined) {
    query.set('to', params.to)
  }
  const suffix = query.size > 0 ? `?${query.toString()}` : ''
  return requestJson<AdminErrorsResponse>(`/admin/errors${suffix}`, { signal })
}

// GET /admin/usage(JSON 사용량 리포트)는 A2 화면 범위 밖이다 — 이번 어드민 최소
// 화면은 「워크스페이스」 탭 요약(이번 달 문서·크레딧·비용)으로 사용량을 보여준다
// (계획 §3 A2). 별도 사용량 탭이 생기면 그때 이 오퍼레이션을 다시 감싼다. CSV 산출은
// 여전히 `usage-report` 운영 프로필의 몫이다(러너북 「월간 청구」).

/** GET /admin/announcements — 공지 전체(활성·비활성)를 최신순으로 조회한다. */
export function listAdminAnnouncements(signal?: AbortSignal): Promise<AnnouncementListResponse> {
  return requestJson<AnnouncementListResponse>('/admin/announcements', { signal })
}

/**
 * POST /admin/announcements — 공지를 만든다. `body`는 필수, 정규화 후(제어문자 제거 +
 * 앞뒤 공백 제거) 500자 이하다. 새로 만든 공지는 항상 `active: true`다.
 */
export function createAdminAnnouncement(
  request: AnnouncementCreateRequest,
): Promise<AnnouncementResponse> {
  return requestJson<AnnouncementResponse>('/admin/announcements', {
    method: 'POST',
    body: request,
  })
}

/**
 * PATCH /admin/announcements/{id} — 공지를 수정한다. `body`·`active` 둘 다 선택 —
 * 준 필드만 바꾼다(명시적 `null`은 생략과 같다).
 */
export function updateAdminAnnouncement(
  id: string,
  request: AnnouncementUpdateRequest,
): Promise<AnnouncementResponse> {
  return requestJson<AnnouncementResponse>(`/admin/announcements/${id}`, {
    method: 'PATCH',
    body: request,
  })
}
