/**
 * 화면 조작 도우미 — 선택자와 픽스처를 한 곳에 모은다.
 *
 * **제품 코드를 고치지 않는다**(계획 §3-1). `data-testid` 를 심지 않고 접근 가능한 이름
 * (label·role)으로만 찾는다 — 그 이름들은 KWCAG 때문에 이미 화면에 있고, 선택자가
 * 접근성 배선을 함께 재는 부수 효과가 있다.
 */

import { expect, type Page } from '@playwright/test'

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

/** 머리말의 작업 공간 선택 상자. `AppLayout` 이 로그인 뒤에만 그린다. */
export function workspaceSelect(page: Page) {
  return page.getByLabel('작업 공간')
}

/** 선택 상자의 옵션 문구를 보이는 순서대로. */
export async function workspaceNames(page: Page): Promise<string[]> {
  return workspaceSelect(page).locator('option').allTextContents()
}

/**
 * 작업 공간 메뉴의 오류 문단.
 *
 * 홈 화면에는 업로드 폼의 오류 문단도 있어 `role="alert"` 만으로는 갈리지 않는다.
 * 머리말 메뉴 안으로 범위를 좁힌다 — 선택자가 `WorkspaceMenu` 의 컨테이너 클래스에
 * 붙지만, 그 클래스는 제품 코드에 이미 있고 이 스위트는 제품 코드를 고치지 않는다.
 */
export function workspaceAlert(page: Page) {
  return page.locator('.workspace-menu').getByRole('alert')
}

/** 지금 선택된 작업 공간 이름. */
export async function selectedWorkspaceName(page: Page): Promise<string> {
  const value = await workspaceSelect(page).inputValue()
  return workspaceSelect(page).locator(`option[value="${value}"]`).innerText()
}

/**
 * `window.prompt` 에 답하면서 버튼을 누른다.
 *
 * `WorkspaceMenu` 는 자체 대화상자 대신 브라우저 기본 `prompt` 를 쓴다(포커스 가두기·
 * Esc 닫기를 다시 구현하지 않으려는 선택). Playwright 는 그것을 dialog 로 다룬다 —
 * 실 브라우저이기 때문에 가능한 조작이고, 계획 §3-1 이 Playwright 를 고른 근거 중 하나다.
 */
export async function answerPrompt(page: Page, buttonName: string, answer: string): Promise<void> {
  page.once('dialog', (dialog) => {
    void dialog.accept(answer)
  })
  await page.getByRole('button', { name: buttonName, exact: true }).click()
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
