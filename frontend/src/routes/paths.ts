/** 라우트 경로 상수와 가드가 주고받는 상태 타입. */

export const HOME_PATH = '/'
export const LOGIN_PATH = '/login'
export const SIGNUP_PATH = '/signup'
export const HISTORY_PATH = '/history'

/** 변환 화면 라우트 패턴 (`useParams`의 키와 같은 이름을 쓴다). */
export const CONVERSION_PATH = '/conversions/:conversionId'

/** 변환 화면 주소를 만든다. */
export function conversionPath(conversionId: string): string {
  return `/conversions/${conversionId}`
}

/** 가드가 로그인 화면으로 넘길 때 싣는 원래 목적지. */
export interface FromLocationState {
  from?: string
}

/**
 * 업로드 화면이 변환 화면으로 넘기는 상태.
 *
 * 원문은 서버 응답에 없다 — 조회 API는 변환 결과만 준다. 붙여넣기로 올린 경우에만
 * 방금 화면에 있던 원문을 그대로 들고 넘어가 분할 화면 왼쪽에 보여줄 수 있다.
 * 새로고침하거나 변환 기록에서 다시 들어오면 이 상태가 없고, 그때는 에디터가
 * 원본을 볼 수 없다고 알린다.
 */
export interface SourceTextState {
  sourceText?: string
}
