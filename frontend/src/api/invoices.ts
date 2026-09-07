/** 세금계산서 요청 기록 엔드포인트 (계약 2.24.0). */

import { requestJson } from './client'
import type {
  InvoiceRequestCreate,
  InvoiceRequestListResponse,
  InvoiceRequestResponse,
} from './types'

/**
 * POST /workspaces/{workspace_id}/invoice-requests — 세금계산서 발급을 요청한다.
 *
 * 소유자만 요청할 수 있고, 남의 것·없는 워크스페이스는 404다(존재 은닉). 체크섬이 틀린
 * 사업자번호·필드 길이 위반·기간 오류는 422, 같은 기간의 요청이 이미 처리 대기 중이면 409다.
 */
export function createInvoiceRequest(
  workspaceId: string,
  request: InvoiceRequestCreate,
): Promise<InvoiceRequestResponse> {
  return requestJson<InvoiceRequestResponse>(`/workspaces/${workspaceId}/invoice-requests`, {
    method: 'POST',
    body: request,
  })
}

/**
 * GET /workspaces/{workspace_id}/invoice-requests — 최근 요청 50건을 최신순으로 조회한다.
 *
 * 소유자만 조회할 수 있고, 남의 것·없는 워크스페이스는 404다(존재 은닉).
 */
export function listInvoiceRequests(
  workspaceId: string,
  signal?: AbortSignal,
): Promise<InvoiceRequestListResponse> {
  return requestJson<InvoiceRequestListResponse>(`/workspaces/${workspaceId}/invoice-requests`, {
    signal,
  })
}
