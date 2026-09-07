/** 워크스페이스 크레딧 계정 엔드포인트 (C1/C2, 계약 2.22.0). */

import { requestJson } from './client'
import type { WorkspaceCreditsResponse } from './types'

/**
 * GET /workspaces/{workspace_id}/credits — 크레딧 잔액·예약·최근 거래 50건을 조회한다.
 *
 * 소유자만 조회할 수 있고, 남의 것·없는 것은 404다(존재 은닉).
 */
export function getWorkspaceCredits(
  workspaceId: string,
  signal?: AbortSignal,
): Promise<WorkspaceCreditsResponse> {
  return requestJson<WorkspaceCreditsResponse>(`/workspaces/${workspaceId}/credits`, { signal })
}
