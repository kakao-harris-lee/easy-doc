/** 사용자용 활성 공지 엔드포인트 (어드민 최소, 계약 2.25.0). 관리자 전용이 아니다. */

import { requestJson } from './client'
import type { ActiveAnnouncementListResponse } from './types'

/**
 * GET /announcements/active — 활성 공지를 최신순 최대 5건 조회한다.
 *
 * 인증 사용자면 누구나 부를 수 있다(전역 공지, 소유 자원이 없다). `AppLayout`이 상단
 * 배너로 보여준다 — 닫기는 브라우저 로컬 저장(공지 id별)이라 계약에 없다.
 */
export function listActiveAnnouncements(
  signal?: AbortSignal,
): Promise<ActiveAnnouncementListResponse> {
  return requestJson<ActiveAnnouncementListResponse>('/announcements/active', { signal })
}
