import { useCallback, useEffect, useMemo, useState } from 'react'
import type { ReactNode } from 'react'

import { ApiError, createWorkspace, listWorkspaces, renameWorkspace } from '../api/client'
import type { WorkspaceListItem } from '../api/types'
import { useAuth } from '../auth/context'
import { WorkspaceContext } from './context'
import type { WorkspaceContextValue } from './context'
import { readSelectedWorkspaceId, writeSelectedWorkspaceId } from './storage'

const LOAD_ERROR_MESSAGE = '작업 공간을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.'

/** 취소된 요청인지 — 화면 전환·언마운트로 끊긴 요청은 오류로 알릴 일이 아니다. */
function isAbort(error: unknown): boolean {
  return error instanceof DOMException && error.name === 'AbortError'
}

/**
 * 기억해 둔 선택을 지금 목록에 비춰 고른다.
 *
 * 저장된 값을 그대로 믿지 않는 이유: 다른 계정으로 로그인했거나, 그 사이 작업 공간이
 * 사라졌거나, 사용자가 키를 지웠을 수 있다. 목록에 없으면 첫 번째(=기본 작업 공간)로
 * 돌아간다 — 아무것도 고르지 못한 상태를 만들지 않는 것이 이 함수의 목적이다.
 */
function pickCurrent(items: WorkspaceListItem[], preferred: string | null): string | null {
  if (preferred !== null && items.some((item) => item.id === preferred)) {
    return preferred
  }
  return items[0]?.id ?? null
}

/**
 * 작업 공간 상태를 앱 전체에 제공한다.
 *
 * 업로드가 갈 곳과 변환 기록이 보여줄 범위를 한 자리에서 정한다 — 화면마다 따로
 * 기억하면 "머리말은 A인데 목록은 B"인 상태가 만들어진다.
 *
 * 로그인한 뒤에만 불러온다. 비로그인 상태에서 부르면 401이 나고, 그 401이 세션 만료로
 * 오인되어 로그인 화면이 깜빡인다(API 클라이언트의 401 처리).
 */
export function WorkspaceProvider({ children }: { children: ReactNode }) {
  const { status } = useAuth()
  const [workspaces, setWorkspaces] = useState<WorkspaceListItem[]>([])
  const [currentId, setCurrentId] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const authenticated = status === 'authenticated'

  /** 방금 읽은 목록을 반영하고 선택을 맞춘다. `preferred`를 주면 그쪽을 우선한다. */
  const apply = useCallback((items: WorkspaceListItem[], preferred?: string) => {
    setWorkspaces(items)
    setCurrentId((previous) =>
      pickCurrent(items, preferred ?? previous ?? readSelectedWorkspaceId()),
    )
  }, [])

  /** 목록을 다시 읽는다 (만들기·이름 바꾸기 뒤). */
  const refresh = useCallback(
    async (preferred?: string) => {
      apply((await listWorkspaces()).items, preferred)
    },
    [apply],
  )

  useEffect(() => {
    if (!authenticated) {
      return
    }
    // 요청을 여기서 직접 부른다(refresh를 쓰지 않는다) — 상태 갱신이 응답 콜백 안에서만
    // 일어나야 effect가 렌더를 연쇄로 밀지 않는다 (AuthProvider의 fetchMe와 같은 모양).
    const controller = new AbortController()
    listWorkspaces(controller.signal)
      .then((page) => {
        apply(page.items)
        setError(null)
      })
      .catch((caught: unknown) => {
        if (!isAbort(caught)) {
          setError(caught instanceof ApiError ? caught.message : LOAD_ERROR_MESSAGE)
        }
      })
    return () => controller.abort()
  }, [authenticated, apply])

  const select = useCallback((workspaceId: string) => {
    setCurrentId(workspaceId)
    writeSelectedWorkspaceId(workspaceId)
  }, [])

  const create = useCallback(
    async (name: string) => {
      const created = await createWorkspace(name)
      // 만든 뒤 목록을 다시 읽는다 — 응답에는 문서 수가 없고, 순서(만든 순)도 서버가
      // 정한다. 새 공간으로 바로 옮겨 가 방금 만든 곳에 올릴 수 있게 한다.
      await refresh(created.id)
      writeSelectedWorkspaceId(created.id)
      setError(null)
    },
    [refresh],
  )

  const rename = useCallback(
    async (workspaceId: string, name: string) => {
      await renameWorkspace(workspaceId, name)
      await refresh()
      setError(null)
    },
    [refresh],
  )

  // 로그아웃 상태에서는 들고 있던 목록을 내보내지 않는다. 상태를 지우는 대신 내보낼
  // 때 거르는 이유: 지우는 일은 effect 안의 즉시 setState가 되어 불필요한 렌더가 겹치고,
  // 다시 로그인하면 어차피 새로 읽어 덮어쓴다. 저장된 선택(localStorage)은 남겨 둔다 —
  // 같은 사람이 돌아오면 보던 곳으로 가고, 다른 계정이면 목록에 없어 무시된다.
  const value = useMemo<WorkspaceContextValue>(
    () => ({
      workspaces: authenticated ? workspaces : [],
      currentId: authenticated ? currentId : null,
      error: authenticated ? error : null,
      select,
      create,
      rename,
    }),
    [authenticated, workspaces, currentId, error, select, create, rename],
  )

  return <WorkspaceContext.Provider value={value}>{children}</WorkspaceContext.Provider>
}
