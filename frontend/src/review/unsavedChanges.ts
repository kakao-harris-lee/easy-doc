/**
 * 저장하지 않은 검수 수정을 지키는 장치.
 *
 * 검수 화면에서 고친 글은 저장(PUT)하기 전까지 브라우저 안에만 있다 — 사용자가
 * 그대로 다른 화면으로 가거나 탭을 닫으면 조용히 사라진다. 그 두 경로를 막는다.
 *
 * 상태를 모듈 전역에 두는 이유는 알림을 걸 자리(머리말의 이동 링크·로그아웃 버튼)와
 * 변경이 생기는 자리(에디터)가 화면 트리에서 형제라, 컨텍스트로 잇자면 앱 전체를
 * 감싸는 배선이 필요하기 때문이다. API 클라이언트의 401 핸들러와 같은 방식이다.
 *
 * **한계**: 브라우저 뒤로 가기는 막지 못한다. react-router의 useBlocker는 데이터
 * 라우터(createBrowserRouter)에서만 동작하는데, 지금 앱은 BrowserRouter를 쓴다.
 * 라우터 구조 교체는 이 미션 범위 밖이라 남겨 둔다.
 */

/** 저장하지 않은 변경이 있는지. 에디터가 켜고 끈다. */
let unsaved = false

const CONFIRM_MESSAGE =
  '저장하지 않은 수정이 있습니다. 이 화면을 떠나면 수정한 내용이 사라집니다. 떠날까요?'

/** 저장하지 않은 변경 여부를 알린다. 에디터는 언마운트할 때 반드시 false로 되돌린다. */
export function setUnsavedChanges(value: boolean): void {
  unsaved = value
}

/** 저장하지 않은 변경이 있는지 읽는다. */
export function hasUnsavedChanges(): boolean {
  return unsaved
}

/**
 * 화면을 떠나도 되는지 확인한다.
 *
 * 저장하지 않은 변경이 없으면 묻지 않고 true. 있으면 사용자에게 물어 그 답을 준다.
 */
export function confirmDiscardUnsaved(): boolean {
  if (!unsaved) {
    return true
  }
  return window.confirm(CONFIRM_MESSAGE)
}
