/**
 * E14–E18 — 화면을 관통하는 접근성 검증 (DESIGN.md §10·§11·§12·§14).
 *
 * 여기서 재는 것은 **브라우저가 있어야만 잴 수 있는 것들**이다: 실제 탭 순서, 실제
 * 레이아웃 폭, 실제 요소 크기, 실제 계산된 CSS. 랜드마크·라벨 연결·중복 낭독처럼
 * DOM 만으로 판정되는 것은 `src/a11y.test.tsx` 가 훨씬 싸게 재므로 여기서 되풀이하지
 * 않는다.
 *
 * 자동 검사 도구(axe 등)를 들이지 않는다 — 새 의존성이고, §11 이 요구하는 항목은
 * 대부분 그런 도구가 «판정 불가»로 흘려보내는 것들(탭 경로가 실제로 끝까지 닿는가,
 * 같은 문장이 두 번 낭독되지 않는가)이다.
 *
 * 이 스위트의 다른 파일과 같은 규약을 지킨다: 스텁·목·MSW 를 쓰지 않고 실 브라우저 ↔
 * 실 Kotlin API ↔ 실 PostgreSQL ↔ 실 worker 를 상대한다.
 */

import { expect, test, type Page } from '@playwright/test'

import { newAccount, signUpAndLand, workspaceSelect } from './support/app'

/** 모바일 최소 폭(DESIGN.md §14). `body { min-width: 320px }` 와 같은 값이다. */
const MOBILE = { width: 320, height: 800 } as const

/** §10 이 정한 터치 대상 최소 크기. */
const TOUCH_TARGET_PX = 44

/** §11 이 요구하는 포커스 링 두께. */
const FOCUS_RING_PX = 3

const SOURCE_TEXT = '국민건강보험료를 납부하려면 가까운 지사를 방문하세요.'
const REVIEWED_TEXT = '키보드로만 고친 검수본입니다.'

/**
 * 지금 초점을 가진 요소를 사람이 읽을 수 있게 적은 것.
 *
 * 이름 계산을 브라우저 안에서 직접 한다 — Playwright 의 로케이터를 쓰면 «찾아서 누르는»
 * 것이 되어 키보드 경로를 재는 의미가 사라진다. 여기서 필요한 것은 «Tab 을 눌렀더니
 * 무엇에 닿았는가»이지 «무엇을 찾을 수 있는가»가 아니다.
 */
async function focusedStop(page: Page): Promise<string> {
  return page.evaluate(() => {
    const element = document.activeElement as HTMLElement | null
    if (element === null || element === document.body) {
      return 'body'
    }
    const labelledBy = element.getAttribute('aria-labelledby')
    const fromLabelledBy =
      labelledBy === null
        ? ''
        : labelledBy
            .split(/\s+/)
            .map((id) => document.getElementById(id)?.textContent ?? '')
            .join(' ')
            .trim()
    const label =
      element.id === ''
        ? null
        : document.querySelector<HTMLElement>(`label[for="${CSS.escape(element.id)}"]`)
    const name = (
      fromLabelledBy ||
      element.getAttribute('aria-label') ||
      label?.textContent ||
      element.closest('label')?.textContent ||
      element.textContent ||
      ''
    )
      .replace(/\s+/g, ' ')
      .trim()
    return `${element.tagName.toLowerCase()}:${name}`
  })
}

/**
 * 이름이 `name` 을 포함하는 곳에 닿을 때까지 Tab 만 누른다.
 *
 * 못 찾으면 지나온 정거장을 전부 적어 던진다 — «키보드로 닿지 않는다»는 실패는 어디까지
 * 갔다가 어디서 끊겼는지를 알려주지 않으면 고칠 수가 없다.
 */
async function tabTo(page: Page, name: string, limit = 60): Promise<void> {
  const visited: string[] = []
  for (let step = 0; step < limit; step += 1) {
    await page.keyboard.press('Tab')
    const stop = await focusedStop(page)
    visited.push(stop)
    if (stop.includes(name)) {
      return
    }
  }
  throw new Error(
    `Tab ${limit}번 안에 ‘${name}’ 에 닿지 못했다.\n지나온 정거장:\n  ${visited.join('\n  ')}`,
  )
}

/**
 * 탭 순서를 문서 맨 앞에서 다시 밟는다.
 *
 * `blur()` 만으로는 부족하다 — 크롬은 «다음 Tab 이 출발할 지점»을 초점과 별개로 들고
 * 있어서, 초점만 떼면 Tab 은 방금 있던 자리에서 이어진다. `<body>` 에 초점을 줘야 그
 * 출발점까지 문서 맨 앞으로 돌아온다. 준 tabindex 는 곧바로 걷어낸다 — 남겨 두면
 * 이 도우미가 재려던 탭 순서에 body 자신이 끼어든다.
 */
async function restartTabbing(page: Page): Promise<void> {
  await page.evaluate(() => {
    document.body.setAttribute('tabindex', '-1')
    document.body.focus()
    document.body.removeAttribute('tabindex')
    window.scrollTo(0, 0)
  })
}

/** 문서가 가로로 넘치는지. 넘친다면 어느 요소가 범인인지까지 같이 가져온다. */
async function horizontalOverflow(page: Page) {
  return page.evaluate(() => {
    const root = document.documentElement
    const culprits: string[] = []
    if (root.scrollWidth > root.clientWidth) {
      for (const element of document.querySelectorAll<HTMLElement>('body *')) {
        const rect = element.getBoundingClientRect()
        if (rect.width === 0 || rect.height === 0) {
          continue
        }
        if (rect.right > root.clientWidth + 1) {
          culprits.push(
            `${element.tagName.toLowerCase()}.${element.className.toString().split(' ')[0] ?? ''} ` +
              `right=${Math.round(rect.right)} width=${Math.round(rect.width)}`,
          )
        }
      }
    }
    return { scrollWidth: root.scrollWidth, clientWidth: root.clientWidth, culprits }
  })
}

/**
 * 화면 안 조작 대상의 실제 크기.
 *
 * 두 가지를 따로 다룬다.
 *
 * - **라디오·체크박스**는 네이티브 상자 자체가 13px 남짓이다. 실제로 누를 수 있는 넓이는
 *   «상자 ∪ 그 라벨»이므로 두 사각형의 합집합을 잰다. 상자만 재면 실제보다 좁게 나오고,
 *   상자를 감싼 카드를 재면 (그 카드가 클릭을 받지 않는 경우) 실제보다 넓게 나온다.
 * - **문장 안에 섞인 링크**(`display: inline`)는 뺀다. §10 의 «터치 대상»은 버튼·입력처럼
 *   따로 서 있는 조작 대상을 말하고, 본문 문장 속 링크에 44px 을 요구하면 줄 간격이
 *   무너진다(WCAG 2.2 SC 2.5.8 도 인라인 링크를 같은 이유로 제외한다).
 */
async function touchTargets(page: Page) {
  return page.evaluate(() => {
    const selector = 'button, a[href], select, textarea, input, [role="tab"]'
    const results: { name: string; width: number; height: number }[] = []
    for (const element of document.querySelectorAll<HTMLElement>(selector)) {
      const style = window.getComputedStyle(element)
      if (style.display === 'none' || style.visibility === 'hidden') {
        continue
      }
      if (element.hasAttribute('disabled') || element.closest('[hidden]') !== null) {
        continue
      }
      if (element.tagName === 'A' && style.display === 'inline') {
        continue
      }
      let rect = element.getBoundingClientRect()
      if (rect.width === 0 || rect.height === 0) {
        continue
      }
      const input = element as HTMLInputElement
      if (element.tagName === 'INPUT' && (input.type === 'radio' || input.type === 'checkbox')) {
        const label =
          element.closest('label') ??
          (element.id === ''
            ? null
            : document.querySelector<HTMLElement>(`label[for="${CSS.escape(element.id)}"]`))
        if (label !== null) {
          const labelRect = label.getBoundingClientRect()
          rect = new DOMRect(
            Math.min(rect.left, labelRect.left),
            Math.min(rect.top, labelRect.top),
            Math.max(rect.right, labelRect.right) - Math.min(rect.left, labelRect.left),
            Math.max(rect.bottom, labelRect.bottom) - Math.min(rect.top, labelRect.top),
          )
        }
      }
      const name = (
        element.getAttribute('aria-label') ||
        element.textContent ||
        (element.id === ''
          ? ''
          : (document.querySelector<HTMLElement>(`label[for="${CSS.escape(element.id)}"]`)
              ?.textContent ?? '')) ||
        element.tagName.toLowerCase()
      )
        .replace(/\s+/g, ' ')
        .trim()
      results.push({
        name: `${element.tagName.toLowerCase()}:${name.slice(0, 40)}`,
        width: Math.round(rect.width),
        height: Math.round(rect.height),
      })
    }
    return results
  })
}

/** 지금 초점을 가진 요소의 포커스 링 두께. */
async function focusedOutlineWidth(page: Page): Promise<string> {
  return page.evaluate(
    () => window.getComputedStyle(document.activeElement as HTMLElement).outlineWidth,
  )
}

/** 44px 에 못 미치는 대상만. */
function undersized(targets: { name: string; width: number; height: number }[]) {
  return targets.filter(
    (target) => target.width < TOUCH_TARGET_PX || target.height < TOUCH_TARGET_PX,
  )
}

test.describe('접근성 — 키보드', () => {
  test('E14 마우스 없이 가입 → 로그아웃 → 로그인 → 새 변환 → 검수 저장 → 내려받기', async ({
    page,
  }) => {
    test.setTimeout(120_000)
    const account = newAccount()

    // --- 가입 (자격증명 폼은 로그인과 같은 컴포넌트다) ---------------------------
    await page.goto('/signup')
    await tabTo(page, '이메일')
    await page.keyboard.type(account.email)
    await tabTo(page, '비밀번호')
    await page.keyboard.type(account.password)
    await tabTo(page, '가입하기')
    await page.keyboard.press('Enter')
    await expect(workspaceSelect(page)).toBeVisible()

    // --- 계정 메뉴를 키보드로 펼쳐 로그아웃 --------------------------------------
    // 아이콘 하나뿐인 트리거라 이름이 없으면 여기서 길이 끊긴다.
    await restartTabbing(page)
    await tabTo(page, '계정 메뉴')
    await page.keyboard.press('Enter')
    // 펼치면 초점이 첫 행동으로 옮겨 간다 — Tab 을 더 누르지 않아도 닿아야 한다.
    expect(await focusedStop(page)).toContain('로그아웃')
    await page.keyboard.press('Enter')
    await expect(page.getByRole('heading', { name: '로그인', level: 1 })).toBeVisible()

    // --- 로그인 ------------------------------------------------------------------
    await restartTabbing(page)
    await tabTo(page, '이메일')
    await page.keyboard.type(account.email)
    await tabTo(page, '비밀번호')
    await page.keyboard.type(account.password)
    await tabTo(page, '로그인')
    await page.keyboard.press('Enter')
    await expect(page.getByRole('heading', { name: '문서 변환하기' })).toBeVisible()

    // --- 새 변환 -----------------------------------------------------------------
    await restartTabbing(page)
    await tabTo(page, '문서 제목')
    await page.keyboard.type('키보드 전용 경로 확인')
    await tabTo(page, '바꿀 글')
    await page.keyboard.type(SOURCE_TEXT)
    await tabTo(page, '쉬운 글 초안 만들기')

    // 포커스 링은 제거되지 않고 §11 이 요구한 3px 로 보인다.
    expect(await focusedOutlineWidth(page)).toBe(`${FOCUS_RING_PX}px`)

    await page.keyboard.press('Enter')
    await expect(page.getByRole('heading', { name: '쉬운 글 검수' })).toBeVisible({
      timeout: 90_000,
    })

    // --- 검수 --------------------------------------------------------------------
    // 에디터가 나타나면 초점은 새 화면의 제목으로 옮겨 온다 — 거기서 이어서 Tab 한다.
    expect(await focusedStop(page)).toContain('쉬운 글 검수')
    await tabTo(page, '쉬운 글 결과 (고칠 수 있습니다)')
    await page.keyboard.press('ControlOrMeta+a')
    await page.keyboard.type(REVIEWED_TEXT)

    await tabTo(page, '검수 내용 저장')
    // 버튼·링크·탭이 각자 포커스 링을 다시 정하던 시절에는 이 값이 컨트롤마다 달랐다.
    expect(await focusedOutlineWidth(page)).toBe(`${FOCUS_RING_PX}px`)
    await page.keyboard.press('Enter')
    await expect(page.getByText('검수 내용을 저장했습니다.')).toBeVisible()

    // 저장하는 동안 버튼이 잠기지만, 끝나면 초점은 방금 누른 버튼으로 돌아와야 한다.
    // 초점이 <body> 로 떨어지면 키보드 사용자는 여기까지 온 길을 처음부터 다시 밟는다.
    expect(await focusedStop(page)).toContain('검수 내용 저장')

    // --- 내려받기 ----------------------------------------------------------------
    await tabTo(page, '내려받기')
    const download = page.waitForEvent('download')
    await page.keyboard.press('Enter')
    expect(await (await download).path()).not.toBeNull()
    await expect(page.getByText('TXT 파일을 내려받았습니다.')).toBeVisible()
  })

  test('E15 건너뛰기 링크가 첫 Tab 에서 나오고 실제로 본문으로 건너뛴다', async ({ page }) => {
    await signUpAndLand(page, newAccount())
    // 갓 불러온 문서에서 잰다 — 조작을 거친 뒤에는 크롬이 «다음 Tab 출발점»을 그 자리에
    // 들고 있어서, 첫 Tab 이 무엇에 닿는지가 화면이 아니라 직전 조작에 좌우된다.
    await page.goto('/')
    await expect(page.getByRole('heading', { name: '문서 변환하기' })).toBeVisible()

    await page.keyboard.press('Tab')
    expect(await focusedStop(page)).toContain('본문으로 건너뛰기')

    await page.keyboard.press('Enter')
    // 크롬은 조각 링크를 따라가면 «다음 Tab 의 출발점»을 대상으로 옮긴다. 그래서
    // activeElement 가 아니라 «다음에 닿는 곳»으로 건너뛰기 성립 여부를 판정한다.
    await page.keyboard.press('Tab')
    const insideMain = await page.evaluate(
      () => document.querySelector('main')?.contains(document.activeElement) ?? false,
    )
    expect(insideMain).toBe(true)
  })

  test('E16 실제 접근성 트리에서 랜드마크와 h1 은 화면마다 하나씩이다', async ({ page }) => {
    // 익명 화면 하나와 로그인 뒤 화면들을 함께 본다 — 머리말이 갈리는 지점이다.
    await page.goto('/login')
    await expect(page.getByRole('banner')).toHaveCount(1)
    await expect(page.getByRole('main')).toHaveCount(1)
    await expect(page.locator('h1')).toHaveCount(1)

    await signUpAndLand(page, newAccount())

    for (const path of ['/', '/history']) {
      await page.goto(path)
      await expect(page.getByRole('banner')).toHaveCount(1)
      await expect(page.getByRole('main')).toHaveCount(1)
      await expect(page.locator('h1')).toHaveCount(1)
    }
  })
})

/**
 * 변환 조회 응답을 늦추는 시간. 진행 화면을 재는 동안만 건다.
 *
 * 화면의 폴링 주기(2초)보다 짧게 둔다 — 폴링을 밀리게 하는 것이 아니라 한 응답이 오는
 * 시각만 뒤로 미는 것이다.
 */
const POLL_DELAY_MS = 1_000

test.describe('접근성 — 320px', () => {
  test('E17 320px 에서 어느 화면도 가로로 넘치지 않고 터치 대상이 44px 이상이다', async ({
    page,
  }) => {
    test.setTimeout(120_000)
    await page.setViewportSize(MOBILE)

    const account = newAccount()

    // 익명 화면 두 개.
    for (const path of ['/login', '/signup']) {
      await page.goto(path)
      await expect(page.getByRole('heading', { level: 1 })).toBeVisible()
      const overflow = await horizontalOverflow(page)
      expect(overflow.culprits.join('\n'), `${path} 가 가로로 넘친다`).toBe('')
      expect(overflow.scrollWidth).toBeLessThanOrEqual(overflow.clientWidth)
      expect(undersized(await touchTargets(page)), `${path} 의 작은 터치 대상`).toEqual([])
    }

    await signUpAndLand(page, account)

    // 새 변환.
    await expect(page.getByRole('heading', { name: '문서 변환하기' })).toBeVisible()
    let overflow = await horizontalOverflow(page)
    expect(overflow.culprits.join('\n'), '새 변환이 가로로 넘친다').toBe('')
    expect(undersized(await touchTargets(page)), '새 변환의 작은 터치 대상').toEqual([])

    // 변환 진행 — fake LLM 으로 도는 worker 가 첫 폴링 전에 끝내 버리면 이 화면은 몇 ms 만
    // 떠 있다 사라지고, 그러면 넘침을 재지 못한 채 지나간다(부하가 있는 전체 실행에서 실제로
    // 그렇게 됐다). 그래서 조회 응답만 1초 늦춘다 — **응답 내용은 그대로 통과시킨다.**
    // 진행 화면을 위조하는 것이 아니라 실제 진행 상태를 잴 시간을 만드는 것이다.
    // 늦추는 것은 **첫 응답 하나뿐**이고 그 뒤 폴링은 손대지 않는다. 중간에 `unroute` 로
    // 걷어내면 아직 자고 있던 처리기가 깨어나 이미 처리된 요청을 이어 보내려다 죽는다 —
    // 그래서 등록은 그대로 두고 처리기 안에서 한 번만 늦춘다.
    let delayedFirstRead = false
    await page.route(/\/conversions\/[^/?]+$/, async (route) => {
      if (!delayedFirstRead) {
        delayedFirstRead = true
        await new Promise((resolve) => setTimeout(resolve, POLL_DELAY_MS))
      }
      await route.continue()
    })
    await page.getByLabel('문서 제목').fill('320px 확인')
    await page.getByLabel('바꿀 글').fill(SOURCE_TEXT)
    await page.getByRole('button', { name: '쉬운 글 초안 만들기', exact: true }).click()
    await expect(page.getByRole('heading', { name: '쉬운 글로 바꾸는 중' })).toBeVisible()
    overflow = await horizontalOverflow(page)
    expect(overflow.culprits.join('\n'), '변환 진행이 가로로 넘친다').toBe('')

    // 검수 — 좁은 화면에서는 원문/쉬운 글이 탭으로 갈린다.
    await expect(page.getByRole('heading', { name: '쉬운 글 검수' })).toBeVisible({
      timeout: 90_000,
    })
    overflow = await horizontalOverflow(page)
    expect(overflow.culprits.join('\n'), '검수가 가로로 넘친다').toBe('')
    expect(undersized(await touchTargets(page)), '검수의 작은 터치 대상').toEqual([])

    // 검수 2열의 읽기 순서는 탭으로 갈린 화면에서도 원문 다음 결과다(§11).
    const tabs = await page.getByRole('tab').allInnerTexts()
    expect(tabs).toEqual(['원문', '쉬운 글'])

    // 변환 기록 — 767px 이하에서는 표가 아니라 카드 목록이다.
    await page.goto('/history')
    await expect(page.getByRole('heading', { name: '변환한 문서를 확인합니다' })).toBeVisible()
    await expect(page.getByText('320px 확인')).toBeVisible()
    expect(await page.getByRole('table').count(), '320px 에서 표를 가로로 밀지 않는다').toBe(0)
    overflow = await horizontalOverflow(page)
    expect(overflow.culprits.join('\n'), '변환 기록이 가로로 넘친다').toBe('')
    expect(undersized(await touchTargets(page)), '변환 기록의 작은 터치 대상').toEqual([])
  })
})

test.describe('접근성 — 모션', () => {
  test('E18 prefers-reduced-motion 에서는 어떤 요소도 애니메이션을 돌리지 않는다', async ({
    page,
  }) => {
    test.setTimeout(120_000)
    // 프로젝트 설정(`test.use`)이 아니라 이 테스트 안에서만 켠다 — 나머지 케이스는
    // 기본 설정 그대로 돌아야 «평소에도 통과하는가»를 함께 재게 된다.
    await page.emulateMedia({ reducedMotion: 'reduce' })
    await signUpAndLand(page, newAccount())

    // 이 앱에서 반복 모션이 도는 유일한 화면은 변환 진행이다(§12).
    await page.getByLabel('문서 제목').fill('모션 확인')
    await page.getByLabel('바꿀 글').fill(SOURCE_TEXT)
    await page.getByRole('button', { name: '쉬운 글 초안 만들기', exact: true }).click()
    await expect(page.getByRole('heading', { name: '쉬운 글로 바꾸는 중' })).toBeVisible()

    const moving = await page.evaluate(() => {
      const running: string[] = []
      for (const element of document.querySelectorAll<HTMLElement>('body *')) {
        const style = window.getComputedStyle(element)
        const durations = style.animationDuration
          .split(',')
          .map((value) => Number.parseFloat(value) * (value.includes('ms') ? 1 : 1000))
        if (style.animationName !== 'none' && durations.some((duration) => duration > 1)) {
          running.push(
            `${element.tagName.toLowerCase()}.${element.className.toString().split(' ')[0] ?? ''} ` +
              `${style.animationName} ${style.animationDuration}`,
          )
        }
      }
      return running
    })

    expect(moving.join('\n'), 'reduced motion 에서 도는 애니메이션이 남아 있다').toBe('')
  })
})
