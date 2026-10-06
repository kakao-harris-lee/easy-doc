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
    await selectTheme(page, 'dark')
    await expect(source).toHaveValue(text)
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
      await expectThemeSettled(page, theme, page.getByRole('link', { name: /가입하고 시작하기/ }))
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
