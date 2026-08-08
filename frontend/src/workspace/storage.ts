/**
 * 지금 보고 있는 작업 공간 기억하기.
 *
 * 새로고침·탭 이동으로 선택이 초기화되면 업로드가 엉뚱한 공간으로 간다. 토큰과 같은
 * localStorage를 쓰지만 성격이 다르다 — 이 값은 자격증명이 아니라 화면 상태이고,
 * 없어져도(사생활 보호 모드·직접 삭제) 기본 작업 공간으로 되돌아가면 그만이다.
 * 그래서 실패는 모두 삼키고 null로 취급한다.
 *
 * **저장된 값을 그대로 믿지 않는다.** 다른 계정으로 로그인했거나 그 사이 작업 공간이
 * 사라졌을 수 있으므로, 읽은 값이 현재 목록에 있는지 확인한 뒤에만 쓴다
 * (WorkspaceProvider).
 */

const SELECTED_KEY = 'easydoc.workspace_id'

/** 저장된 선택을 읽는다. 없거나 저장소를 쓸 수 없으면 null. */
export function readSelectedWorkspaceId(): string | null {
  try {
    return window.localStorage.getItem(SELECTED_KEY)
  } catch {
    return null
  }
}

/** 선택을 저장한다. 실패해도 현재 화면은 그대로 동작해야 하므로 오류로 올리지 않는다. */
export function writeSelectedWorkspaceId(workspaceId: string): void {
  try {
    window.localStorage.setItem(SELECTED_KEY, workspaceId)
  } catch {
    // 위와 같은 이유.
  }
}
