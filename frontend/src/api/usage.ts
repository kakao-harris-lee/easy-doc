/** 워크스페이스 사용량 집계 엔드포인트 (U2, 계약 2.20.0). */

import { requestJson } from './client'
import type { WorkspaceUsageResponse } from './types'

/**
 * GET /workspaces/{workspace_id}/usage — 기간별 사용량을 집계한다.
 *
 * `from`·`to`를 생략하면 서버가 `easydoc.usage.zone`(기본 `Asia/Seoul`) 기준
 * 이번 달 1일~오늘로 채운다. 둘 다 `YYYY-MM-DD`이고 `to`는 포함 상한이다.
 */
export function getWorkspaceUsage(
  workspaceId: string,
  params: { from?: string; to?: string } = {},
  signal?: AbortSignal,
): Promise<WorkspaceUsageResponse> {
  const query = new URLSearchParams()
  if (params.from !== undefined) {
    query.set('from', params.from)
  }
  if (params.to !== undefined) {
    query.set('to', params.to)
  }
  const suffix = query.size > 0 ? `?${query.toString()}` : ''
  return requestJson<WorkspaceUsageResponse>(`/workspaces/${workspaceId}/usage${suffix}`, {
    signal,
  })
}
