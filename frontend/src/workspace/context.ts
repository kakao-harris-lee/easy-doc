import { createContext, useContext } from 'react'

import type { WorkspaceListItem } from '../api/types'

export interface WorkspaceContextValue {
  /** 내 작업 공간 (만든 순서 — 첫 번째가 기본 작업 공간이다). */
  workspaces: WorkspaceListItem[]
  /** 지금 보고 있는 작업 공간. 아직 목록을 불러오지 못했으면 null. */
  currentId: string | null
  /** 목록을 불러오지 못한 사유. 화면이 그대로 보여줄 수 있는 한국어 문구다. */
  error: string | null
  /** 보고 있는 작업 공간을 바꾼다 (선택은 localStorage에 남는다). */
  select: (workspaceId: string) => void
  /** 작업 공간을 만들고 그쪽으로 옮겨 간다. 실패하면 ApiError를 그대로 올린다. */
  create: (name: string) => Promise<void>
  /** 작업 공간 이름을 바꾼다. 실패하면 ApiError를 그대로 올린다. */
  rename: (workspaceId: string, name: string) => Promise<void>
}

export const WorkspaceContext = createContext<WorkspaceContextValue | null>(null)

/** 작업 공간 상태를 읽는다. WorkspaceProvider 밖에서 부르면 즉시 오류로 알린다. */
export function useWorkspace(): WorkspaceContextValue {
  const value = useContext(WorkspaceContext)
  if (value === null) {
    throw new Error('useWorkspace는 WorkspaceProvider 안에서만 사용할 수 있습니다')
  }
  return value
}
