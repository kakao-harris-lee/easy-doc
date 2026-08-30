/**
 * 화면 조작 도우미 — 선택자와 픽스처를 한 곳에 모은다.
 *
 * **제품 코드를 고치지 않는다**(계획 §3-1). `data-testid` 를 심지 않고 접근 가능한 이름
 * (label·role)으로만 찾는다 — 그 이름들은 KWCAG 때문에 이미 화면에 있고, 선택자가
 * 접근성 배선을 함께 재는 부수 효과가 있다.
 */

import { expect, type Locator, type Page } from '@playwright/test'

/** 브라우저가 보는 출처. `playwright.config.ts` 와 같은 기본값을 쓴다. */
export const FRONTEND_ORIGIN = process.env.E2E_FRONTEND_ORIGIN ?? 'http://localhost:5173'

/** Kotlin API 출처. */
export const API_BASE_URL = process.env.E2E_API_BASE_URL ?? 'http://localhost:8100'

/** 토큰 보관소 키 — `frontend/src/api/token.ts` 의 `TOKEN_KEY`. */
export const TOKEN_KEY = 'easydoc.access_token'

/**
 * 세션 만료를 흉내 내는 토큰.
 *
 * **운영 비밀키로 만료 토큰을 서명하지 않는다**(계획 §3-5). 계약
 * `components/responses/Unauthorized` 가 헤더 누락·위조·만료·용도 불일치·계정 삭제를
 * 모두 **같은 401** 로 못박았으므로, 서명 없는 문자열이면 충분하고 그 편이 테스트에
 * 비밀키를 들이지 않는다.
 */
export const INVALID_TOKEN = 'e2e.invalid.token'

export interface Account {
  readonly email: string
  readonly password: string
}

/**
 * 케이스마다 자기 계정을 만든다(계획 §4-4). 공유 계정을 쓰면 E4(소유자 범위)와
 * E6(이름 중복)이 실행 순서에 의존한다.
 *
 * 값은 전부 합성이다 — 실재하는 사람·기관의 정보가 로그·추적에 들어가지 않는다.
 * 도메인은 RFC 6761 이 예약한 `.test` 를 쓴다(누구에게도 배달되지 않는다).
 */
export function newAccount(): Account {
  return {
    email: `e2e-${crypto.randomUUID()}@example.test`,
    password: 'e2e-synthetic-password',
  }
}

/** 이 실행에서만 쓰는 작업 공간 이름. 같은 DB 를 여러 번 써도 충돌하지 않는다. */
export function uniqueWorkspaceName(prefix = 'E2E'): string {
  return `${prefix}-${crypto.randomUUID().slice(0, 8)}`
}

/** 자격증명 폼을 채우고 제출한다. `submitLabel` 은 버튼 문구(`로그인` / `가입하기`). */
export async function submitCredentials(
  page: Page,
  account: Account,
  submitLabel: string,
): Promise<void> {
  await page.getByLabel('이메일').fill(account.email)
  await page.getByLabel('비밀번호').fill(account.password)
  await page.getByRole('button', { name: submitLabel, exact: true }).click()
}

/**
 * 머리말의 작업 공간 메뉴. `AppLayout` 이 로그인 뒤에만 그린다.
 *
 * **`WorkspaceMenu` 는 DOM 에 두 벌 있다.** `AppLayout` 이 데스크톱 자리
 * (`hidden lg:block`)와 모바일 바(`lg:hidden`)에 각각 그리고, Tailwind `hidden` 은
 * `display: none` 이므로 뷰포트마다 **정확히 하나만** 보인다. 숨은 쪽은 접근성 트리에서도
 * 빠지므로 낭독기에는 하나만 들린다 — 즉 이것은 반응형 레이아웃이 정상 동작하는 모습이고,
 * 제품 버그가 아니다.
 *
 * 반면 Playwright strict mode 는 **가시성을 따지기 전에** 로케이터 해석 단계에서 2개를
 * 발견하고 끊는다. 그래서 맞춰야 하는 쪽은 제품 코드가 아니라 로케이터다 —
 * `filter({ visible: true })` 로 지금 화면에 실제로 있는 한 벌만 남긴다. 제품을 조건부
 * 렌더로 바꾸면 미디어 쿼리 훅과 리사이즈 처리가 딸려 오고, 얻는 것은 테스트 편의뿐이다.
 *
 * (선택자가 `WorkspaceMenu` 의 컨테이너 클래스에 붙지만, 그 클래스는 제품 코드에 이미
 * 있고 이 스위트는 제품 코드를 고치지 않는다.)
 */
export function workspaceMenu(page: Page): Locator {
  return page.locator('.workspace-menu').filter({ visible: true })
}

/** 머리말의 작업 공간 선택 상자 — 지금 보이는 메뉴 안의 것. */
export function workspaceSelect(page: Page): Locator {
  return workspaceMenu(page).getByLabel('작업 공간')
}

/** 선택 상자의 옵션 문구를 보이는 순서대로. */
export async function workspaceNames(page: Page): Promise<string[]> {
  return workspaceSelect(page).locator('option').allTextContents()
}

/**
 * 작업 공간 만들기·이름 바꾸기의 오류 문단.
 *
 * 대화상자 안을 본다. 오류는 대화상자를 닫고 바깥에 알리는 것이 아니라 **고칠 입력
 * 바로 아래에** 남는다(DESIGN.md §9) — 닫아 버리면 고쳐야 할 입력이 함께 사라진다.
 * 대화상자는 `document.body` 바로 아래로 포털되므로 `.workspace-menu` 안을 보던 예전
 * 범위로는 찾지 못한다.
 *
 * 홈 화면에는 업로드 폼의 오류 문단도 있어 `role="alert"` 만으로는 갈리지 않는데,
 * 대화상자로 좁히면 그 문제도 함께 닫힌다.
 */
export function workspaceAlert(page: Page): Locator {
  return page.getByRole('dialog').getByRole('alert')
}

/** 지금 선택된 작업 공간 이름. */
export async function selectedWorkspaceName(page: Page): Promise<string> {
  const value = await workspaceSelect(page).inputValue()
  return workspaceSelect(page).locator(`option[value="${value}"]`).innerText()
}

/** 작업 공간 대화상자의 확인 버튼 이름 — 여는 버튼과 확인 버튼의 문구가 다르다. */
const WORKSPACE_CONFIRM: Record<string, string> = {
  '새로 만들기': '만들기',
  '이름 바꾸기': '바꾸기',
}

/**
 * 작업 공간 대화상자를 열어 이름을 넣고 확인한다.
 *
 * 예전에는 브라우저 기본 `prompt` 를 `page.once('dialog')` 로 받아 냈다. 지금은 화면 안
 * 대화상자다(DESIGN.md §6.7 — `prompt` 는 초기 초점·포커스 가두기·Esc·초점 복귀를
 * 제어할 수 없어 §11 을 만족시키지 못한다). 그래서 **네이티브 dialog 이벤트는 더 이상
 * 오지 않는다** — 이 헬퍼가 직접 입력하고 확인을 누른다.
 *
 * 이름을 그대로 두지 않고 `fill` 로 덮어쓴다. 이름 바꾸기 대화상자는 현재 이름을 채운
 * 채로 열리므로, 이어 붙이면 원하지 않은 값이 만들어진다.
 *
 * 여는 버튼은 보이는 것 하나로 좁힌다 — `새로 만들기`·`이름 바꾸기` 는 `WorkspaceMenu`
 * 안에 있고 그 메뉴는 반응형으로 두 벌 그려진다([workspaceMenu] 참고). 대화상자는
 * body 바로 아래로 포털되므로 한 벌만 뜨고, 확인 버튼은 좁히지 않아도 된다.
 */
export async function answerPrompt(page: Page, buttonName: string, answer: string): Promise<void> {
  const confirm = WORKSPACE_CONFIRM[buttonName]
  if (confirm === undefined) {
    throw new Error(`대화상자 확인 버튼을 모르는 여는 버튼이다: ${buttonName}`)
  }

  const dialog = page.getByRole('dialog')
  // 이미 열려 있으면 다시 열지 않는다. 오류(409·422)가 나면 대화상자는 **닫히지 않고**
  // 고칠 입력 옆에 문구를 남긴다(DESIGN.md §9). 그 상태에서 사용자가 하는 일은 메뉴로
  // 돌아가 다시 여는 것이 아니라 값을 고쳐 다시 확인하는 것이고, 애초에 배경이 `inert`
  // 라 여는 버튼에 닿지도 않는다. 반복 시도를 재는 테스트(E6·E7)가 그 흐름을 탄다.
  if (!(await dialog.isVisible())) {
    await page
      .getByRole('button', { name: buttonName, exact: true })
      .filter({ visible: true })
      .click()
  }

  // 이름을 그대로 두지 않고 덮어쓴다 — 이름 바꾸기는 현재 이름이 채워진 채 열리고,
  // 재시도할 때는 직전에 거절당한 값이 남아 있다.
  await dialog.getByLabel('작업 공간 이름').fill(answer)
  await dialog.getByRole('button', { name: confirm, exact: true }).click()
}

/**
 * 로그아웃한다.
 *
 * 로그아웃 버튼은 머리말에 상시 노출되지 않는다 — 계정 이메일과 함께 계정 메뉴 안에
 * 들어갔다(DESIGN.md §5.1: 이메일을 늘 펼쳐 두지 않는다). 그래서 먼저 메뉴를 펼친다.
 * 데스크톱 뷰포트(`devices['Desktop Chrome']` 1280px)에서는 이 트리거가 보이는 유일한
 * 계정 통로이고, 좁은 화면에서는 햄버거 메뉴가 같은 역할을 겸한다.
 */
export async function signOut(page: Page): Promise<void> {
  await page.getByRole('button', { name: '계정 메뉴' }).click()
  await page.getByRole('button', { name: '로그아웃', exact: true }).click()
}

/** 가입 → 자동 로그인 → 홈. 작업 공간 메뉴가 뜰 때까지 기다린다. */
export async function signUpAndLand(page: Page, account: Account): Promise<void> {
  await page.goto('/signup')
  await submitCredentials(page, account, '가입하기')
  await expect(workspaceSelect(page)).toBeVisible()
}

/** 저장된 액세스 토큰(없으면 null). */
export async function storedToken(page: Page): Promise<string | null> {
  return page.evaluate((key) => window.localStorage.getItem(key), TOKEN_KEY)
}

/** 저장된 토큰을 바꿔 심는다 — 세션 만료 상태를 만드는 통로다. */
export async function plantToken(page: Page, token: string): Promise<void> {
  await page.evaluate(
    ([key, value]) => {
      window.localStorage.setItem(key as string, value as string)
    },
    [TOKEN_KEY, token],
  )
}

/** API 절대 주소. */
export function api(path: string): string {
  return `${API_BASE_URL}${path}`
}
