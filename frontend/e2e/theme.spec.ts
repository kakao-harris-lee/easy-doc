import { expect, test, type Locator, type Page, type Route } from '@playwright/test'

import { API_BASE_URL, TOKEN_KEY } from './support/app'

const THEME_STORAGE_KEY = 'easy-doc-theme'
const WORKSPACE_ID = 'theme-workspace'
const DOCUMENT_ID = 'theme-document'
const CONVERSION_ID = 'theme-conversion'

type Theme = 'light' | 'dark' | 'system'

const USER = {
  id: 'theme-user',
  email: 'theme-e2e@example.test',
  email_verified: true,
  phone_verified: true,
  identities: [],
  has_password: true,
  is_admin: true,
}

const WORKSPACE = {
  id: WORKSPACE_ID,
  name: '테마 확인용 작업 공간',
  created_at: '2026-09-28T00:00:00Z',
  document_count: 1,
}

const CREDITS = {
  workspace_id: WORKSPACE_ID,
  balance: 100,
  reserved: 0,
  available: 100,
  enforced: true,
  transactions: [],
  signup_grant_skipped: false,
  allowance: 100,
  cycle_started_at: '2026-09-01T00:00:00Z',
  cycle_ends_at: '2026-10-01T00:00:00Z',
}

const DOCUMENT = {
  id: DOCUMENT_ID,
  title: '테마 확인 문서',
  source_format: 'text',
  char_count: 24,
  created_at: '2026-09-28T00:00:00Z',
  retention_expires_at: '2026-10-28T00:00:00Z',
  conversion_id: CONVERSION_ID,
  status: 'done',
  reviewed_at: null,
  feedback_submitted_at: null,
}

const CONVERSION = {
  id: CONVERSION_ID,
  document_id: DOCUMENT_ID,
  status: 'done',
  reading_level: 'grade_5_6',
  source_format: 'text',
  export_format: 'txt',
  export_format_choices: [],
  format_preservation: { status: 'not_applicable', details: [] },
  easy_text: '쉽게 읽을 수 있는 테스트 문장입니다.',
  edited_text: null,
  reviewed_at: null,
  feedback_submitted_at: null,
  model: null,
  provider_name: null,
  input_tokens: null,
  output_tokens: null,
  failure_code: null,
  segment_map: null,
  content_revision: 1,
  review_capabilities: {
    explanations: false,
    illustrations: false,
    review_history: false,
    table_relations: false,
    focused_review: false,
    illustration_suggestions: false,
    action_guide: false,
  },
}

const SUBSCRIPTION = {
  mock_enabled: false,
  toss_enabled: false,
  plans: [],
  subscription: null,
  payments: [],
}

const USAGE = {
  documents: 1,
  characters: 24,
  credits: 0.1,
  llm_calls: 1,
  input_tokens: 10,
  output_tokens: 10,
  estimated_cost_usd: null,
  cost_unknown_calls: 1,
  failed_calls: 0,
  by_purpose: [],
}

function json(route: Route, body: unknown, status = 200): Promise<void> {
  return route.fulfill({
    status,
    contentType: 'application/json',
    body: JSON.stringify(body),
  })
}

/**
 * Keep this suite independent of the Kotlin stack. Every request to the configured API origin
 * is fulfilled here, including unknown paths, so a missing fixture cannot silently reach a real
 * service, payment provider, or LLM.
 */
async function mockApi(page: Page): Promise<void> {
  await page.route('**/*', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    if (url.origin !== new URL(API_BASE_URL).origin) {
      await route.continue()
      return
    }

    const path = url.pathname
    if (path === '/auth/me') {
      await json(route, USER)
      return
    }
    if (path === '/workspaces' && request.method() === 'GET') {
      await json(route, { items: [WORKSPACE] })
      return
    }
    if (path === '/announcements/active') {
      await json(route, { items: [] })
      return
    }
    if (path === '/documents' && request.method() === 'GET') {
      await json(route, { items: [DOCUMENT], limit: 20, offset: 0, has_more: false })
      return
    }
    if (path === `/documents/${DOCUMENT_ID}/source`) {
      await json(route, {
        document_id: DOCUMENT_ID,
        source_format: 'text',
        char_count: 24,
        source_text: '원문을 확인하기 위한 테스트 문장입니다.',
        tables: null,
      })
      return
    }
    if (path === `/conversions/${CONVERSION_ID}`) {
      await json(route, CONVERSION)
      return
    }
    if (path.endsWith('/credits')) {
      await json(route, CREDITS)
      return
    }
    if (path.endsWith('/usage')) {
      await json(route, USAGE)
      return
    }
    if (path.endsWith('/invoice-requests')) {
      await json(route, { items: [] })
      return
    }
    if (path.endsWith('/subscription')) {
      await json(route, SUBSCRIPTION)
      return
    }
    if (['/admin/operations', '/admin/notifications', '/admin/errors/events'].includes(path)) {
      await json(route, { items: [], page: 1, size: 20, total: 0, counts: {} })
      return
    }
    if (path === '/admin/workspaces') {
      await json(route, {
        items: [],
        page: Number(url.searchParams.get('page') ?? 1),
        size: Number(url.searchParams.get('size') ?? 20),
        total: 0,
      })
      return
    }
    if (path === '/admin/invoice-requests') {
      await json(route, { items: [], page: 1, size: 20, total: 0 })
      return
    }
    if (path === '/admin/errors') {
      await json(route, { counts: [], recent: [], provider_failures: [] })
      return
    }
    if (path === '/admin/announcements') {
      await json(route, { items: [] })
      return
    }
    if (path === '/admin/feedback') {
      await json(route, { items: [], page: 1, size: 20, total: 0 })
      return
    }

    // Unknown API calls remain synthetic and explicit. This catches fixture drift in the test
    // output without allowing a network fallback.
    await json(
      route,
      { detail: `theme fixture has no response for ${request.method()} ${path}` },
      404,
    )
  })
}

async function seedAuthenticatedTheme(page: Page, theme: Theme = 'dark'): Promise<void> {
  await page.addInitScript(
    ({ tokenKey, themeKey, token, selectedTheme }) => {
      window.localStorage.setItem(tokenKey, token)
      window.localStorage.setItem(themeKey, selectedTheme)
    },
    {
      tokenKey: TOKEN_KEY,
      themeKey: THEME_STORAGE_KEY,
      token: 'theme-e2e-token',
      selectedTheme: theme,
    },
  )
}

const THEME_SWITCH_NAME = '다크 모드'

async function selectTheme(page: Page, theme: Exclude<Theme, 'system'>): Promise<void> {
  const visibleSwitch = page
    .getByRole('switch', { name: THEME_SWITCH_NAME })
    .filter({ visible: true })
  let openedSheet = false
  if ((await visibleSwitch.count()) === 0) {
    await page.getByRole('button', { name: '메뉴 열기', exact: true }).click()
    openedSheet = true
  }
  const control = visibleSwitch.first()
  const isDark = (await control.getAttribute('aria-checked')) === 'true'
  if (isDark !== (theme === 'dark')) {
    await control.click()
  }
  if (openedSheet) {
    await page.getByRole('button', { name: '메뉴 닫기', exact: true }).click()
  }
}

async function currentTheme(page: Page): Promise<string | null> {
  return page.locator('html').getAttribute('data-theme')
}

async function expectThemeSettled(
  page: Page,
  theme: Exclude<Theme, 'system'>,
  primaryAction: Locator,
): Promise<void> {
  const colors =
    theme === 'dark'
      ? {
          background: 'rgb(17, 24, 39)',
          primary: 'rgb(117, 191, 255)',
          primaryForeground: 'rgb(17, 24, 39)',
        }
      : {
          background: 'rgb(247, 249, 252)',
          primary: 'rgb(23, 100, 181)',
          primaryForeground: 'rgb(255, 255, 255)',
        }
  await expect.poll(() => currentTheme(page)).toBe(theme)
  await expect(
    page.locator(`button[role="switch"][aria-label="${THEME_SWITCH_NAME}"]`).first(),
  ).toHaveAttribute('aria-checked', String(theme === 'dark'))
  await expect
    .poll(() => page.evaluate(() => getComputedStyle(document.body).backgroundColor))
    .toBe(colors.background)
  await expect
    .poll(() =>
      primaryAction.evaluate((element) => {
        const style = getComputedStyle(element)
        return { background: style.backgroundColor, color: style.color }
      }),
    )
    .toEqual({ background: colors.primary, color: colors.primaryForeground })
}

async function expectNoHorizontalOverflow(page: Page): Promise<void> {
  const dimensions = await page.evaluate(() => ({
    clientWidth: document.documentElement.clientWidth,
    scrollWidth: document.documentElement.scrollWidth,
  }))
  expect(
    dimensions.scrollWidth,
    `horizontal overflow: ${JSON.stringify(dimensions)}`,
  ).toBeLessThanOrEqual(dimensions.clientWidth + 1)
}

function channels(color: string): [number, number, number] {
  const values = color
    .match(/[\d.]+/g)
    ?.map(Number)
    .slice(0, 3)
  if (values?.length !== 3 || values.some((value) => value === undefined)) {
    throw new Error(`RGB 색을 해석할 수 없습니다: ${color}`)
  }
  return values as [number, number, number]
}

function luminance(color: string): number {
  const linear = channels(color).map((value) => {
    const channel = value / 255
    return channel <= 0.04045 ? channel / 12.92 : ((channel + 0.055) / 1.055) ** 2.4
  })
  return 0.2126 * linear[0]! + 0.7152 * linear[1]! + 0.0722 * linear[2]!
}

function contrastRatio(foreground: string, background: string): number {
  const first = luminance(foreground)
  const second = luminance(background)
  return (Math.max(first, second) + 0.05) / (Math.min(first, second) + 0.05)
}

interface MeasuredColors {
  foreground: string
  background: string
}

function readTextColors(locator: Locator): Promise<MeasuredColors> {
  return locator.evaluate((element) => {
    let current: HTMLElement | SVGElement | null = element
    let background = ''
    while (current !== null) {
      const candidate = getComputedStyle(current).backgroundColor
      if (candidate !== 'transparent' && candidate !== 'rgba(0, 0, 0, 0)') {
        background = candidate
        break
      }
      current = current.parentElement
    }
    return { foreground: getComputedStyle(element).color, background }
  })
}

function readBorderColors(locator: Locator): Promise<MeasuredColors> {
  return locator.evaluate((element) => {
    const style = getComputedStyle(element)
    return { foreground: style.borderTopColor, background: style.backgroundColor }
  })
}

/**
 * 테마를 바꾸면 색에 전환이 걸린 요소(`Button.tsx`, `LandingPage.tsx`, `ReviewEditor.tsx`,
 * `index.css`의 `.help-disclosure`)의 computed color가 두 테마 사이의 중간값을 지나간다.
 * `data-theme`만 확인하고 바로 읽으면 그 중간값을 재게 되어, 실제로는 대비가 충분한 색이
 * 1.5 같은 값으로 측정된다. 요소와 그 조상에 걸린 색 전환이 끝난 뒤에 읽는다. 무한 반복
 * 애니메이션(로딩 스피너)에 걸려 멈추지 않도록 끝이 정해진 `CSSTransition`만 기다리고,
 * 요소가 사라져 전환이 취소된 경우는 무시한다.
 */
async function settleColorTransitions(locator: Locator): Promise<void> {
  await locator.evaluate(async (element) => {
    const transitions: Promise<unknown>[] = []
    for (let node: Element | null = element; node !== null; node = node.parentElement) {
      for (const animation of node.getAnimations()) {
        if (animation instanceof CSSTransition) {
          transitions.push(animation.finished.catch(() => undefined))
        }
      }
    }
    await Promise.all(transitions)
  })
}

/**
 * 전환이 끝난 뒤에 읽어도 측정 시점이 한 번뿐이면 느린 CI에서 다시 어긋날 수 있어,
 * 기준을 넘길 때까지 다시 읽는다. 색 자체가 기준에 못 미치면 폴링이 만료되면서 실패한다.
 */
async function expectContrast(
  locator: Locator,
  minimum: number,
  read: (target: Locator) => Promise<MeasuredColors>,
): Promise<void> {
  await expect
    .poll(async () => {
      await settleColorTransitions(locator)
      const colors = await read(locator)
      if (colors.background === '') {
        throw new Error('배경색을 가진 조상을 찾지 못했습니다.')
      }
      return contrastRatio(colors.foreground, colors.background)
    })
    .toBeGreaterThanOrEqual(minimum)
}

async function expectTextContrast(locator: Locator, minimum = 4.5): Promise<void> {
  await expectContrast(locator, minimum, readTextColors)
}

async function expectBorderContrast(locator: Locator): Promise<void> {
  await expectContrast(locator, 3, readBorderColors)
}

async function expectFocusRing(locator: Locator, expectedRing: string): Promise<void> {
  // Establish keyboard modality before focusing; doing it afterwards can leave the browser's
  // default focus color in place even though the element already has DOM focus.
  await locator.page().keyboard.press('Shift')
  await locator.focus()
  await expect(locator).toBeFocused()
  await expect
    .poll(() => locator.evaluate((element) => getComputedStyle(element).outlineColor))
    .toBe(expectedRing)
  // 초점 링과 대비를 재는 조상 배경도 테마 전환 중에는 중간값이다.
  await settleColorTransitions(locator)
  const outline = await locator.evaluate((element) => {
    const style = getComputedStyle(element)
    let adjacent = element.parentElement
    let adjacentBackground = ''
    while (adjacent !== null) {
      const candidate = getComputedStyle(adjacent).backgroundColor
      if (candidate !== 'transparent' && candidate !== 'rgba(0, 0, 0, 0)') {
        adjacentBackground = candidate
        break
      }
      adjacent = adjacent.parentElement
    }
    return {
      focusVisible: element.matches(':focus-visible'),
      ringToken: getComputedStyle(document.documentElement).getPropertyValue('--ring').trim(),
      style: style.outlineStyle,
      width: style.outlineWidth,
      color: style.outlineColor,
      adjacentBackground,
    }
  })
  expect(outline.focusVisible).toBe(true)
  expect(outline.ringToken).not.toBe('')
  expect(outline.style).toBe('solid')
  expect(outline.width).toBe('3px')
  expect(outline.color).toBe(expectedRing)
  expect(outline.adjacentBackground).not.toBe('')
  expect(contrastRatio(outline.color, outline.adjacentBackground)).toBeGreaterThanOrEqual(3)
}

async function tabTo(page: Page, locator: Locator): Promise<void> {
  for (let attempt = 0; attempt < 80; attempt += 1) {
    if (await locator.evaluate((element) => element === document.activeElement)) {
      return
    }
    await page.keyboard.press('Tab')
  }
  await expect(locator).toBeFocused()
}

test.describe('테마 디자인 브라우저 검증', () => {
  test('저장된 다크 테마를 첫 페인트 전에 복원한다', async ({ page }) => {
    await mockApi(page)
    await page.route('**/theme-init.js', async (route) => {
      const response = await route.fetch()
      const script = await response.text()
      await route.fulfill({
        response,
        body: `window.__themeScriptBeforeBody = document.body === null;\n${script}`,
      })
    })
    await page.addInitScript(
      ({ themeKey }) => {
        window.localStorage.setItem(themeKey, 'dark')
        const state = window as Window & {
          __themePrepaint?: string | null
          __themeScriptBeforeBody?: boolean | null
        }
        state.__themePrepaint = null
        state.__themeScriptBeforeBody = null
        const rememberBeforeReactPaint = (parent: Node): void => {
          if ((parent as HTMLElement).id === 'root' && state.__themePrepaint === null) {
            state.__themePrepaint = document.documentElement.getAttribute('data-theme')
          }
        }
        const appendChild = Node.prototype.appendChild
        Node.prototype.appendChild = function appendChildWithThemeCheck<T extends Node>(
          child: T,
        ): T {
          rememberBeforeReactPaint(this)
          return appendChild.call(this, child) as T
        }
        const insertBefore = Node.prototype.insertBefore
        Node.prototype.insertBefore = function insertBeforeWithThemeCheck<T extends Node>(
          child: T,
          reference: Node | null,
        ): T {
          rememberBeforeReactPaint(this)
          return insertBefore.call(this, child, reference) as T
        }
      },
      { themeKey: THEME_STORAGE_KEY },
    )

    await page.goto('/')
    await expect(page.locator('head > script[src="/theme-init.js"]')).toHaveCount(1)
    await expect(page.locator('body > script[src="/theme-init.js"]')).toHaveCount(0)
    await expect
      .poll(() =>
        page.evaluate(
          () =>
            (window as Window & { __themeScriptBeforeBody?: boolean | null })
              .__themeScriptBeforeBody,
        ),
      )
      .toBe(true)
    await expect.poll(() => currentTheme(page)).toBe('dark')
    await expect
      .poll(() =>
        page.evaluate(
          () => (window as Window & { __themePrepaint?: string | null }).__themePrepaint,
        ),
      )
      .toBe('dark')
    await expect(
      page.evaluate((key) => window.localStorage.getItem(key), THEME_STORAGE_KEY),
    ).resolves.toBe('dark')
  })

  test('선택한 테마를 저장하고 새로고침 뒤에도 유지한다', async ({ page }) => {
    await mockApi(page)
    await page.goto('/')
    await expect(page.locator('main')).toBeVisible()

    await selectTheme(page, 'dark')
    await expect.poll(() => currentTheme(page)).toBe('dark')
    await expect(
      page.evaluate((key) => window.localStorage.getItem(key), THEME_STORAGE_KEY),
    ).resolves.toBe('dark')

    await page.reload()
    await expect.poll(() => currentTheme(page)).toBe('dark')
  })

  test('시스템 테마와 다른 탭의 변경을 화면에 반영한다', async ({ context, page }) => {
    await mockApi(page)
    await page.goto('/')
    await expect(
      page.evaluate((key) => window.localStorage.getItem(key), THEME_STORAGE_KEY),
    ).resolves.toBeNull()

    await page.emulateMedia({ colorScheme: 'dark' })
    await expect.poll(() => currentTheme(page)).toBe('dark')
    await page.emulateMedia({ colorScheme: 'light' })
    await expect.poll(() => currentTheme(page)).toBe('light')

    const secondPage = await context.newPage()
    await mockApi(secondPage)
    await secondPage.goto('/')
    await selectTheme(secondPage, 'dark')
    await expect.poll(() => currentTheme(page)).toBe('dark')
    await expect(
      page.evaluate((key) => window.localStorage.getItem(key), THEME_STORAGE_KEY),
    ).resolves.toBe('dark')
    await secondPage.close()
  })

  test('공개 주요 페이지가 두 테마에서 320px 가로로 넘치지 않는다', async ({ page }) => {
    await mockApi(page)
    await page.setViewportSize({ width: 320, height: 900 })

    const publicPages = [
      { path: '/', heading: '어려운 글을 누구나 읽기 쉬운 글로' },
      { path: '/login', heading: '로그인' },
      { path: '/guide' },
      { path: '/terms' },
      { path: '/privacy' },
    ]

    for (const theme of ['light', 'dark'] as const) {
      for (const entry of publicPages) {
        await page.goto(entry.path)
        await selectTheme(page, theme)
        if (entry.heading === undefined) {
          await expect(page.locator('main h1').first()).toBeVisible()
        } else {
          await expect(
            page.getByRole('heading', { name: entry.heading, exact: true }),
          ).toBeVisible()
        }
        await expect.poll(() => currentTheme(page)).toBe(theme)
        await expectNoHorizontalOverflow(page)
      }
    }
  })

  test('인증 주요 페이지가 두 테마에서 320px 가로로 넘치지 않는다', async ({ page }) => {
    await mockApi(page)
    await seedAuthenticatedTheme(page, 'dark')
    await page.setViewportSize({ width: 320, height: 900 })

    const pages = [
      { path: '/', heading: '문서 변환하기' },
      { path: '/history', heading: '변환한 문서를 확인합니다' },
      { path: '/usage', heading: '플랜과 사용량' },
      { path: '/account', heading: '계정 설정' },
      { path: '/admin', heading: '처리할 일을 확인하고 고객을 관리합니다' },
      { path: `/conversions/${CONVERSION_ID}`, heading: '쉬운 글 확인' },
    ]

    for (const theme of ['light', 'dark'] as const) {
      for (const entry of pages) {
        await page.goto(entry.path)
        await selectTheme(page, theme)
        await expect(page.getByRole('heading', { name: entry.heading, exact: true })).toBeVisible()
        await expect.poll(() => currentTheme(page)).toBe(theme)
        await expectNoHorizontalOverflow(page)
      }
    }
  })

  test('업로드 입력은 테마를 바꿔도 유지되고 문서 등록 요청을 보내지 않는다', async ({ page }) => {
    await mockApi(page)
    await seedAuthenticatedTheme(page, 'light')
    const documentPosts: string[] = []
    page.on('request', (request) => {
      const url = new URL(request.url())
      if (
        url.origin === new URL(API_BASE_URL).origin &&
        url.pathname === '/documents' &&
        request.method() === 'POST'
      ) {
        documentPosts.push(request.url())
      }
    })

    await page.setViewportSize({ width: 320, height: 900 })
    await page.goto('/')
    const source = page.getByLabel('바꿀 글')
    const text = '테마를 바꿔도 작성 중인 문장은 그대로 남아야 합니다.'
    await source.fill(text)
    const level = page.getByRole('combobox', { name: '변환 레벨' })
    await expect(
      page.getByRole('region', { name: '변환 입력' }).getByRole('combobox', { name: '변환 레벨' }),
    ).toBeVisible()
    await level.selectOption('middle_school')
    await expect(level).toHaveAccessibleDescription('익숙한 표현으로 핵심 내용을 쉽게 풀어 써요.')
    await expectNoHorizontalOverflow(page)
    await selectTheme(page, 'dark')
    await expect(source).toHaveValue(text)
    await expect(level).toHaveValue('middle_school')
    await expectNoHorizontalOverflow(page)
    expect(documentPosts).toEqual([])
  })

  test('모바일 업로드 제출 버튼은 긴 폼을 스크롤해도 하단에 고정되고 포커스 링이 잘리지 않는다', async ({
    page,
  }, testInfo) => {
    await mockApi(page)
    await seedAuthenticatedTheme(page, 'dark')

    const longText = Array.from(
      { length: 24 },
      (_, index) => `${index + 1}번째 문장은 모바일 폼의 스크롤 위치를 확인하기 위한 내용입니다.`,
    ).join('\n')

    for (const width of [390, 320]) {
      await page.setViewportSize({ width, height: 900 })
      await page.goto('/')
      await expect(page.getByRole('heading', { name: '문서 변환하기', exact: true })).toBeVisible()

      const source = page.getByLabel('바꿀 글')
      const submit = page.getByRole('button', { name: '쉬운 글로 바꾸기', exact: true })
      await source.fill(longText)
      await source.evaluate((element) => {
        element.scrollIntoView({ block: 'center', inline: 'nearest' })
      })
      await expect.poll(() => page.evaluate(() => window.scrollY)).toBeGreaterThan(0)

      const scrollYBeforeFocus = await page.evaluate(() => window.scrollY)
      const boundsBeforeFocus = await submit.boundingBox()
      expect(boundsBeforeFocus).not.toBeNull()
      expect(boundsBeforeFocus?.x ?? 0).toBeGreaterThan(0)
      expect((boundsBeforeFocus?.x ?? 0) + (boundsBeforeFocus?.width ?? 0)).toBeLessThanOrEqual(
        width,
      )
      expect(boundsBeforeFocus?.y ?? 0).toBeGreaterThanOrEqual(900 - 180)
      expect((boundsBeforeFocus?.y ?? 0) + (boundsBeforeFocus?.height ?? 0)).toBeLessThanOrEqual(
        900,
      )

      await submit.evaluate((element) => element.focus({ preventScroll: true }))
      await expect(submit).toBeFocused()
      await expect.poll(() => page.evaluate(() => window.scrollY)).toBe(scrollYBeforeFocus)

      const boundsAfterFocus = await submit.boundingBox()
      expect(boundsAfterFocus).not.toBeNull()
      expect(boundsAfterFocus?.x ?? 0).toBeGreaterThan(0)
      expect((boundsAfterFocus?.x ?? 0) + (boundsAfterFocus?.width ?? 0)).toBeLessThanOrEqual(width)
      expect(boundsAfterFocus?.y ?? 0).toBeGreaterThanOrEqual(900 - 180)
      expect((boundsAfterFocus?.y ?? 0) + (boundsAfterFocus?.height ?? 0)).toBeLessThanOrEqual(900)

      const focusAudit = await submit.evaluate((element) => {
        const style = getComputedStyle(element)
        const clippingAncestors: Array<{ tag: string; className: string; overflow: string }> = []
        let ancestor = element.parentElement
        while (ancestor !== null && ancestor !== document.body) {
          const ancestorStyle = getComputedStyle(ancestor)
          if (
            ancestorStyle.overflowX === 'hidden' ||
            ancestorStyle.overflowX === 'clip' ||
            ancestorStyle.overflowY === 'hidden' ||
            ancestorStyle.overflowY === 'clip'
          ) {
            clippingAncestors.push({
              tag: ancestor.tagName,
              className: ancestor.className,
              overflow: `${ancestorStyle.overflowX}/${ancestorStyle.overflowY}`,
            })
          }
          ancestor = ancestor.parentElement
        }
        return {
          outlineStyle: style.outlineStyle,
          outlineWidth: style.outlineWidth,
          clippingAncestors,
        }
      })
      expect(focusAudit.outlineStyle).toBe('solid')
      expect(focusAudit.outlineWidth).toBe('3px')
      expect(focusAudit.clippingAncestors).toEqual([])

      await page.screenshot({
        path: testInfo.outputPath(`upload-sticky-${width}.png`),
      })
    }
  })

  test('모바일 메뉴와 작업 공간 대화상자가 화면 안에 남는다', async ({ page }) => {
    await mockApi(page)
    await seedAuthenticatedTheme(page, 'dark')
    await page.setViewportSize({ width: 320, height: 900 })
    await page.goto('/')
    await expect(page.getByRole('heading', { name: '문서 변환하기', exact: true })).toBeVisible()

    await page.getByRole('button', { name: '메뉴 열기', exact: true }).click()
    await expect(
      page.getByRole('navigation', { name: '주요 메뉴 (모바일)', exact: true }),
    ).toBeVisible()
    await expectNoHorizontalOverflow(page)

    const workspace = page.getByRole('button', { name: /^작업 공간:/ }).filter({ visible: true })
    await workspace.click()
    await page.getByRole('button', { name: '새로 만들기', exact: true }).click()
    const dialog = page.getByRole('dialog')
    await expect(dialog).toBeVisible()
    const dialogBounds = await dialog.boundingBox()
    expect(dialogBounds).not.toBeNull()
    expect(dialogBounds?.width ?? 0).toBeLessThanOrEqual(320)
    await expectNoHorizontalOverflow(page)
  })

  test('라이트·다크 대표 화면을 데스크톱과 320px 스크린샷으로 남긴다', async ({
    page,
  }, testInfo) => {
    await mockApi(page)

    for (const theme of ['light', 'dark'] as const) {
      await page.setViewportSize({ width: 1440, height: 1000 })
      await page.goto('/')
      await selectTheme(page, theme)
      await expectThemeSettled(page, theme, page.getByRole('link', { name: /무료로 체험하기/ }))
      await page.screenshot({
        path: testInfo.outputPath(`landing-${theme}-desktop.png`),
        fullPage: true,
      })
    }

    // Use a separate page with a token bootstrap so the public landing captures above cannot
    // accidentally turn into the authenticated upload screen on a later iteration.
    const authenticatedPage = await page.context().newPage()
    await mockApi(authenticatedPage)
    await authenticatedPage.addInitScript(
      ({ tokenKey }) => window.localStorage.setItem(tokenKey, 'theme-e2e-token'),
      { tokenKey: TOKEN_KEY },
    )
    for (const theme of ['light', 'dark'] as const) {
      await authenticatedPage.setViewportSize({ width: 320, height: 900 })
      await authenticatedPage.goto('/')
      await selectTheme(authenticatedPage, theme)
      await expect(
        authenticatedPage.getByRole('heading', { name: '문서 변환하기', exact: true }),
      ).toBeVisible()
      await expectThemeSettled(
        authenticatedPage,
        theme,
        authenticatedPage.getByRole('button', { name: '쉬운 글로 바꾸기', exact: true }),
      )
      await authenticatedPage.screenshot({
        path: testInfo.outputPath(`upload-${theme}-320.png`),
        fullPage: true,
      })
    }
    await authenticatedPage.close()
  })

  for (const width of [320, 375, 768, 1024, 1440]) {
    test(`시각 개선 — 공개 화면 ${width}px 두 테마`, async ({ page }) => {
      await mockApi(page)
      await page.setViewportSize({ width, height: 900 })

      for (const theme of ['light', 'dark'] as const) {
        for (const path of ['/', '/guide', '/examples', '/examples/school-notice']) {
          await page.goto(path)
          await selectTheme(page, theme)
          await expect.poll(() => currentTheme(page)).toBe(theme)
          await expect(page.getByRole('heading', { level: 1 })).toBeVisible()
          await expectNoHorizontalOverflow(page)

          if (path === '/') {
            const warning = page.getByRole('note')
            await expectTextContrast(warning)
            await expectTextContrast(warning.locator('svg'), 3)
            await expectTextContrast(page.getByText(/가정통신문부터/))
            await expectTextContrast(page.getByText('쉬운 글 변환 AI', { exact: true }))
            await expectTextContrast(page.getByText(/카드 등록 없이 체험 5크레딧/))
            await expectBorderContrast(page.getByRole('link', { name: '이용 가이드 보기' }))
            const glowOpacity = await page
              .locator('.landing-glow')
              .first()
              .evaluate((element) => getComputedStyle(element).opacity)
            expect(glowOpacity).toBe(theme === 'dark' ? '0.18' : '0.4')
          }
          if (path === '/guide') {
            await expectTextContrast(page.locator('.guide-page blockquote'))
            const disclosure = page.locator('details.help-disclosure').first()
            const summary = disclosure.locator('summary')
            await summary.click()
            await expect(disclosure).toHaveAttribute('open', '')
            await expect(disclosure).toHaveCSS(
              'background-color',
              theme === 'dark' ? 'rgb(24, 57, 84)' : 'rgb(231, 242, 252)',
            )
            await expectTextContrast(summary)
          }
          if (path === '/examples/school-notice') {
            await expectTextContrast(page.getByText(/AI가 만든 가상 예시입니다/))
            await expectTextContrast(page.locator('#example-simplified .body-copy'))
            await expectBorderContrast(
              page
                .getByRole('region', { name: '예시로 변환 시작하기' })
                .getByRole('link', { name: '로그인', exact: true }),
            )
            const selected = page.getByRole('button', { name: '쉬운 글', exact: true })
            if (await selected.isVisible()) {
              await expect(selected).toHaveAttribute('aria-pressed', 'true')
              await expectTextContrast(selected)
            }
          }
        }
      }
    })

    test(`시각 개선 — 검수 화면 ${width}px 두 테마`, async ({ page }) => {
      await mockApi(page)
      await seedAuthenticatedTheme(page, 'light')
      await page.setViewportSize({ width, height: 900 })

      for (const theme of ['light', 'dark'] as const) {
        await page.goto(`/conversions/${CONVERSION_ID}`)
        await expect(page.getByRole('heading', { name: '쉬운 글 확인' })).toBeVisible()
        await selectTheme(page, theme)
        await expect.poll(() => currentTheme(page)).toBe(theme)
        await expectNoHorizontalOverflow(page)
        await expectTextContrast(page.getByRole('note'))
        await expectTextContrast(page.getByText('변환 완료', { exact: true }))
        const easyTextTab = page.getByRole('tab', { name: '쉬운 글', exact: true })
        if (await easyTextTab.isVisible()) {
          await easyTextTab.click()
        }
        const editor = page.getByLabel('쉬운 글 결과 (고칠 수 있습니다)')
        await expect(editor).toBeVisible()
        await expectTextContrast(editor)
        await expectBorderContrast(editor)
        await expectBorderContrast(page.getByRole('button', { name: /TXT로/ }))
        await expectFocusRing(
          page.getByRole('button', { name: '검수 내용 저장' }),
          theme === 'dark' ? 'rgb(117, 191, 255)' : 'rgb(23, 100, 181)',
        )
      }
    })
  }

  test('시각 개선 — 상호작용 상태와 키보드 초점이 유지된다', async ({ page }) => {
    await mockApi(page)
    await page.setViewportSize({ width: 1024, height: 900 })
    await page.goto('/')

    const primary = page.getByRole('link', { name: /체험하기/ })
    await primary.hover()
    await page.mouse.down()
    await expect(primary).toHaveCSS('background-color', 'rgb(15, 68, 122)')
    await page.mouse.up()

    await page.goto('/examples')
    const card = page.locator('main a[href^="/examples/"]').first()
    const before = await card.boundingBox()
    await card.hover()
    const after = await card.boundingBox()
    expect(after).toEqual(before)
    await expect(card).toHaveCSS('background-color', 'rgb(231, 242, 252)')
    await tabTo(page, card)
    await expectFocusRing(card, 'rgb(23, 100, 181)')
    await page.keyboard.press('Enter')
    await expect(page).toHaveURL('/examples/school-notice')
    await expect(
      page.getByRole('heading', { name: '학교 독서 행사 신청 안내', exact: true }),
    ).toBeVisible()

    await page.goto('/guide')
    const summary = page.locator('details.help-disclosure summary').first()
    await tabTo(page, summary)
    await expectFocusRing(summary, 'rgb(23, 100, 181)')
    await page.keyboard.press('Enter')
    await expect(summary.locator('..')).toHaveAttribute('open', '')

    await seedAuthenticatedTheme(page, 'light')
    await page.goto('/history')
    const current = page
      .getByRole('navigation', { name: '주요 메뉴', exact: true })
      .getByRole('link', { name: '변환 기록' })
    await expect(current).toHaveAttribute('aria-current', 'page')
    await expect(current).toHaveCSS('background-color', 'rgb(231, 242, 252)')

    const account = page.getByRole('button', { name: '계정 메뉴' })
    await account.focus()
    await page.keyboard.press('Enter')
    await expect(page.getByRole('button', { name: '로그아웃' })).toBeFocused()
    await page.keyboard.press('Escape')
    await expect(account).toBeFocused()

    await page.setViewportSize({ width: 375, height: 900 })
    await page.goto('/history')
    const menu = page.getByRole('button', { name: '메뉴 열기' })
    await menu.click()
    const mobileCurrent = page
      .getByRole('navigation', { name: '주요 메뉴 (모바일)' })
      .getByRole('link', { name: '변환 기록' })
    await expect(mobileCurrent).toHaveCSS('background-color', 'rgb(231, 242, 252)')
    await page.keyboard.press('Escape')
    await expect(menu).toBeFocused()

    await page.goto(`/conversions/${CONVERSION_ID}`)
    await expect(page.getByRole('heading', { name: '쉬운 글 확인' })).toBeVisible()
    const sourceTab = page.getByRole('tab', { name: '원문', exact: true })
    const easyTextTab = page.getByRole('tab', { name: '쉬운 글', exact: true })
    await tabTo(page, sourceTab)
    await page.keyboard.press('ArrowRight')
    await expect(easyTextTab).toBeFocused()
    await expect(easyTextTab).toHaveAttribute('aria-selected', 'true')
    const editor = page.getByLabel('쉬운 글 결과 (고칠 수 있습니다)')
    await editor.fill('저장 중 disabled 상태를 확인하는 수정본입니다.')
    let releaseSave: (() => void) | undefined
    const saveGate = new Promise<void>((resolve) => {
      releaseSave = resolve
    })
    await page.route(`**/conversions/${CONVERSION_ID}`, async (route) => {
      if (route.request().method() !== 'PUT') {
        await route.continue()
        return
      }
      await saveGate
      await json(route, {
        ...CONVERSION,
        edited_text: '저장 중 disabled 상태를 확인하는 수정본입니다.',
        reviewed_at: '2026-10-24T00:00:00Z',
      })
    })
    const save = page.getByRole('button', { name: /검수 내용 저장|저장 중…/ })
    await tabTo(page, save)
    await page.keyboard.press('Enter')
    await expect(save).toBeDisabled()
    await expect(save).toHaveCSS('opacity', '0.5')
    await expect(save).toHaveCSS('cursor', 'not-allowed')
    await page.mouse.move(0, 0)
    await expect(save).toHaveCSS('background-color', 'rgb(23, 100, 181)')
    const disabledBackground = await save.evaluate(
      (element) => getComputedStyle(element).backgroundColor,
    )
    await save.hover()
    await expect(save).toHaveCSS('background-color', disabledBackground)
    releaseSave?.()
    await expect(save).toBeEnabled()
    await expectTextContrast(page.getByText('검수 내용을 저장했습니다.', { exact: true }))
    const download = page.getByRole('button', { name: /TXT로/, exact: true })
    await expectBorderContrast(download)
    await save.focus()
    await page.keyboard.press('Tab')
    await expect(download).toBeFocused()
  })

  test('시각 개선 — reduced motion에서 색 전환만 즉시 끝난다', async ({ page }) => {
    await mockApi(page)
    await page.emulateMedia({ reducedMotion: 'reduce' })
    await page.goto('/examples')
    const card = page.locator('main a[href^="/examples/"]').first()
    await card.hover()

    const durations = await card.evaluate((element) =>
      getComputedStyle(element)
        .transitionDuration.split(',')
        .map((value) => Number.parseFloat(value) * (value.includes('ms') ? 1 : 1000)),
    )
    expect(durations.every((duration) => duration <= 0.001)).toBe(true)
    expect(await card.evaluate((element) => getComputedStyle(element).animationName)).toBe('none')
  })
})

// Uses only synthetic customer data and no mail/provider connections.
test('관리자 처리할 일에서 상세로 이동하고 URL과 뒤로 가기를 복원한다', async ({ page }) => {
  await mockApi(page)
  await seedAuthenticatedTheme(page)
  await page.route(`${API_BASE_URL}/admin/operations*`, async (route) => {
    await json(route, {
      items: [
        {
          id: 'invoice-1',
          kind: 'invoice',
          state: 'requested',
          environment: null,
          workspace_id: WORKSPACE_ID,
          workspace_name: '합성 고객',
          created_at: '2026-10-01T00:00:00Z',
          severity: 2,
          next_action: '세금계산서 확인',
        },
      ],
      total: 1,
      page: 1,
      size: 20,
      counts: { invoice: 1 },
    })
  })
  await page.goto('/admin')
  await expect(page.getByRole('tab', { name: '처리할 일', exact: true })).toHaveAttribute(
    'aria-selected',
    'true',
  )
  await expect(page.getByText('총 1 작업 건')).toBeVisible()
  await page.getByRole('link', { name: '상세 확인', exact: true }).click()
  await expect(page).toHaveURL(/tab=invoices.*invoice=invoice-1/)
  await expect(page.getByRole('tab', { name: '세금계산서', exact: true })).toHaveAttribute(
    'aria-selected',
    'true',
  )
  await page.goBack()
  await expect(page.getByRole('tab', { name: '처리할 일', exact: true })).toHaveAttribute(
    'aria-selected',
    'true',
  )
  await page.getByRole('tab', { name: '처리할 일', exact: true }).focus()
  await page.keyboard.press('End')
  await expect(page.getByRole('tab', { name: '공지', exact: true })).toBeFocused()
  await page.getByLabel('공지 내용', { exact: true }).fill('확인 전 게시되지 않는 공지')
  await page.getByRole('button', { name: '공지 만들기', exact: true }).click()
  await expect(page.getByRole('alert')).toContainText('게시 확인')
  await expect(page.getByRole('region', { name: '공지 노출 미리보기' })).toBeVisible()
})
