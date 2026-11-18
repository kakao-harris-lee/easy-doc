#!/usr/bin/env node
/**
 * 토스페이먼츠 결제 계약 심사용 「결제경로」 초안 캡처 (운영 도구, 제품 코드 아님).
 *
 * 심사 서류는 운영자가 **자기 PC에서 전체 화면으로 직접 캡처한 그림**이어야 한다 —
 * 카드사는 주소창 도메인과 작업표시줄 시계를 보고 조작 여부를 확인한다. 이 스크립트는
 * 그 최종 캡처를 대신하지 않고, 「어느 화면을 어떤 상태로 찍어야 하는지」를 고정한
 * 초안(슬라이드 뼈대)을 만든다. 운영자는 `CHECKLIST.md`를 보고 같은 순서로 다시 찍어
 * `final/`에 같은 파일 이름으로 넣는다.
 *
 * 비밀번호 취급: `TOSS_REVIEW_PW`는 로그인 입력에만 쓰고 어디에도 출력하지 않는다.
 * 화면 기록(trace/video/HAR)도 켜지 않는다 — 입력 순간이 영상으로 남으면 비밀번호가
 * 파일로 새어 나간다. 예외 메시지도 `redact()`를 거쳐 manifest에 들어간다.
 *
 * 실행: README.md 참조 (`node ops/toss-billing-review/capture.mjs`).
 */
import { createRequire } from 'node:module'
import { mkdirSync, writeFileSync, rmSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const SCRIPT_DIR = dirname(fileURLToPath(import.meta.url))
const REPO_ROOT = resolve(SCRIPT_DIR, '..', '..')
const OUT_DIR = join(SCRIPT_DIR, 'out')
const MANIFEST_PATH = join(OUT_DIR, 'manifest.json')

const BASE_URL = (process.env.REVIEW_BASE_URL ?? 'https://easydoc.kr').replace(/\/+$/, '')
const REVIEW_ID = process.env.TOSS_REVIEW_ID ?? 'toss-review@easydoc.kr'
const REVIEW_PW = process.env.TOSS_REVIEW_PW ?? ''
const HEADED = process.env.REVIEW_HEADED === '1'

const VIEWPORT = { width: 1600, height: 900 }

/**
 * `playwright`는 이 디렉터리의 의존성이 아니라 `frontend/`의 devDependency다 —
 * 심사용 캡처 하나 때문에 운영 도구에 별도 node 패키지 트리를 만들지 않는다.
 * ESM은 `NODE_PATH`를 보지 않으므로 require 해석기로 후보 위치를 직접 찾는다.
 */
function loadPlaywright() {
  const candidates = [
    process.env.PLAYWRIGHT_NODE_MODULES !== undefined
      ? join(process.env.PLAYWRIGHT_NODE_MODULES, 'index.js')
      : null,
    join(REPO_ROOT, 'frontend', 'package.json'),
    join(REPO_ROOT, 'package.json'),
    join(SCRIPT_DIR, 'package.json'),
  ].filter((candidate) => candidate !== null)

  const tried = []
  for (const candidate of candidates) {
    try {
      // playwright는 CommonJS라 동적 import의 named export 추출이 어긋난다 — require로 받는다.
      return createRequire(candidate)('playwright')
    } catch {
      tried.push(candidate)
    }
  }
  throw new Error(
    `playwright 모듈을 찾지 못했습니다. frontend에서 \`npm install\`을 먼저 실행하거나 ` +
      `PLAYWRIGHT_NODE_MODULES=<node_modules 경로>를 지정하세요. 찾아본 곳: ${tried.join(', ')}`,
  )
}

/** 비밀번호가 로그·manifest에 섞여 나가지 않게 한다. */
function redact(text) {
  const value = String(text ?? '')
  if (REVIEW_PW === '') return value
  return value.split(REVIEW_PW).join('***')
}

function log(message) {
  process.stdout.write(`${redact(message)}\n`)
}

/** 뷰포트 밖으로 삐져나온 부분은 캡처에 없으므로 잘라낸다. */
function clampRect(box, label, viewport = VIEWPORT) {
  if (box === null || box === undefined) return null
  const left = Math.max(0, box.x)
  const top = Math.max(0, box.y)
  const right = Math.min(viewport.width, box.x + box.width)
  const bottom = Math.min(viewport.height, box.y + box.height)
  if (right <= left || bottom <= top) return null
  return {
    label,
    x: Math.round(left),
    y: Math.round(top),
    width: Math.round(right - left),
    height: Math.round(bottom - top),
  }
}

function unionBoxes(boxes) {
  const valid = boxes.filter((box) => box !== null && box !== undefined && box.width > 0)
  if (valid.length === 0) return null
  const left = Math.min(...valid.map((box) => box.x))
  const top = Math.min(...valid.map((box) => box.y))
  const right = Math.max(...valid.map((box) => box.x + box.width))
  const bottom = Math.max(...valid.map((box) => box.y + box.height))
  return { x: left, y: top, width: right - left, height: bottom - top }
}

/** 하이라이트는 여백을 조금 줘야 테두리가 글자를 덮지 않는다. */
function padBox(box, padding = 6) {
  if (box === null) return null
  return {
    x: box.x - padding,
    y: box.y - padding,
    width: box.width + padding * 2,
    height: box.height + padding * 2,
  }
}

/** 이 환경에서는 찍을 화면 자체가 없다는 뜻 — 실패가 아니라 건너뜀으로 기록된다. */
class SkipStep extends Error {}

/** 종료 경로가 어디든 닫을 수 있도록 실행 중인 브라우저를 들고 있는다. */
let activeBrowser = null

class Manifest {
  constructor() {
    this.data = {
      generated_at: new Date().toISOString(),
      base_url: BASE_URL,
      viewport: VIEWPORT,
      /** 비밀번호 미설정이면 공개 단계(02·03·04a)만 찍고 나머지는 skipped로 남긴다. */
      login_steps_enabled: REVIEW_PW !== '',
      steps: [],
    }
  }

  add(entry) {
    this.data.steps.push(entry)
    this.flush()
  }

  /** 한 단계가 실패해도 그때까지의 결과가 남도록 매번 기록한다. */
  flush() {
    writeFileSync(MANIFEST_PATH, `${JSON.stringify(this.data, null, 2)}\n`, 'utf8')
  }
}

const STEPS = [
  { id: '02-footer', slide: 2, slide_title: '하단 정보', login_required: false },
  { id: '03-refund', slide: 3, slide_title: '환불 규정', login_required: false },
  { id: '04a-login-redirect', slide: 4, slide_title: '로그인', login_required: false },
  { id: '04b-login-filled', slide: 4, slide_title: '로그인', login_required: true },
  { id: '05a-plan-select', slide: 5, slide_title: '상품 선택·구매', login_required: true },
  { id: '05b-plan-consent', slide: 5, slide_title: '상품 선택·구매', login_required: true },
  { id: '06-billing-window', slide: 6, slide_title: '정기결제 카드 입력창', login_required: true },
]

function stepMeta(id) {
  const meta = STEPS.find((step) => step.id === id)
  if (meta === undefined) throw new Error(`알 수 없는 단계: ${id}`)
  return meta
}

async function shoot(page, id) {
  const image = `${id}.png`
  await page.screenshot({ path: join(OUT_DIR, image), fullPage: false })
  return image
}

async function run() {
  // playwright의 디버그 로그는 요청 본문까지 stderr로 흘린다 — 비밀번호가 새지 않도록
  // 호출자가 켜 두었더라도 이 프로세스에서는 끈다.
  delete process.env.DEBUG
  delete process.env.PWDEBUG

  const { chromium } = loadPlaywright()

  rmSync(OUT_DIR, { recursive: true, force: true })
  mkdirSync(OUT_DIR, { recursive: true })

  const manifest = new Manifest()
  const browser = await chromium.launch({ headless: !HEADED })
  activeBrowser = browser
  // 기록 옵션(recordVideo / recordHar)과 tracing은 의도적으로 켜지 않는다 — 비밀번호 입력이
  // 파일로 남지 않게 하기 위해서다.
  const context = await browser.newContext({ viewport: VIEWPORT, locale: 'ko-KR' })
  const page = await context.newPage()

  /** 한 단계의 실패가 앞선 산출물을 지우지 않도록, 결과를 항상 manifest에 남긴다. */
  async function step(id, url, state, body) {
    const meta = stepMeta(id)
    if (meta.login_required && REVIEW_PW === '') {
      log(`skip ${id} (TOSS_REVIEW_PW 미설정)`)
      manifest.add({
        id,
        slide: meta.slide,
        slide_title: meta.slide_title,
        status: 'skipped',
        reason: 'TOSS_REVIEW_PW 미설정 — 로그인이 필요한 단계는 건너뜀',
        url,
        state,
        image: null,
        viewport: VIEWPORT,
        highlights: [],
      })
      return false
    }
    try {
      const result = await body()
      manifest.add({
        id,
        slide: meta.slide,
        slide_title: meta.slide_title,
        status: 'ok',
        reason: null,
        url: result.url ?? url,
        state: result.state ?? state,
        image: result.image,
        viewport: result.viewport ?? VIEWPORT,
        highlights: result.highlights.filter((rect) => rect !== null),
      })
      log(`ok   ${id} -> out/${result.image}`)
      return true
    } catch (error) {
      // 「이 환경에는 그 화면이 없다」와 「찍으려다 실패했다」는 다르다 — 전자는 운영자가
      // 고칠 것이 없으므로 실패가 아니라 skipped로 남긴다.
      const skipped = error instanceof SkipStep
      const reason = redact(error instanceof Error ? error.message : error)
      manifest.add({
        id,
        slide: meta.slide,
        slide_title: meta.slide_title,
        status: skipped ? 'skipped' : 'failed',
        reason,
        url,
        state,
        image: null,
        viewport: VIEWPORT,
        highlights: [],
      })
      log(`${skipped ? 'skip' : 'FAIL'} ${id}: ${reason}`)
      return false
    }
  }

  // ── 02 하단 정보 ────────────────────────────────────────────────────────────
  await step('02-footer', `${BASE_URL}/`, '홈 화면 맨 아래(푸터가 전부 보이게 스크롤)', async () => {
    await page.goto(`${BASE_URL}/`, { waitUntil: 'networkidle' })
    const footer = page.locator('footer').first()
    await footer.waitFor({ state: 'visible', timeout: 15000 })
    await page.evaluate(() => window.scrollTo(0, document.body.scrollHeight))
    await page.waitForTimeout(400)
    const rect = clampRect(padBox(await footer.boundingBox()), '사업자 정보 푸터')
    return { image: await shoot(page, '02-footer'), highlights: [rect] }
  })

  // ── 03 환불 규정 ───────────────────────────────────────────────────────────
  await step('03-refund', `${BASE_URL}/terms`, '이용약관 제9조(유료 이용) 위치로 스크롤', async () => {
    await page.goto(`${BASE_URL}/terms`, { waitUntil: 'networkidle' })
    const headingText = '제9조'
    // 제9조 제목부터 다음 조항 제목 직전까지를 한 덩어리로 본다 — 환불 항목(5호)이
    // 그 안에 있다. 마크다운 렌더 결과라 id·class가 없어 문서 순서로 찾는다.
    const found = await page.evaluate((needle) => {
      const headings = Array.from(document.querySelectorAll('h1, h2, h3'))
      const heading = headings.find((node) => (node.textContent ?? '').includes(needle))
      if (heading === undefined) return null
      const parts = [heading]
      let node = heading.nextElementSibling
      while (node !== null && !/^H[1-3]$/.test(node.tagName)) {
        parts.push(node)
        node = node.nextElementSibling
      }
      const rects = parts.map((part) => part.getBoundingClientRect())
      const top = Math.min(...rects.map((rect) => rect.top)) + window.scrollY
      const bottom = Math.max(...rects.map((rect) => rect.bottom)) + window.scrollY
      const left = Math.min(...rects.map((rect) => rect.left))
      const right = Math.max(...rects.map((rect) => rect.right))
      // 상단 고정 헤더가 조문 제목을 덮지 않도록 헤더 높이만큼 더 내려서 멈춘다.
      const header = document.querySelector('header')
      const offset = (header === null ? 0 : header.getBoundingClientRect().height) + 32
      window.scrollTo(0, Math.max(0, top - offset))
      return { top, bottom, left, right }
    }, headingText)
    if (found === null) throw new Error(`약관에서 "${headingText}" 제목을 찾지 못했습니다`)
    await page.waitForTimeout(300)
    const scrollY = await page.evaluate(() => window.scrollY)
    const rect = clampRect(
      padBox({
        x: found.left,
        y: found.top - scrollY,
        width: found.right - found.left,
        height: found.bottom - found.top,
      }),
      '제9조 (유료 이용) — 환불·청약철회',
    )
    return { image: await shoot(page, '03-refund'), highlights: [rect] }
  })

  // ── 04a 비로그인 /usage 접근 → 로그인 화면 ─────────────────────────────────
  const loginForm = page.locator('form').filter({ has: page.locator('input[type="password"]') })
  await step(
    '04a-login-redirect',
    `${BASE_URL}/usage`,
    '비로그인 상태로 /usage 접근 → /login 으로 이동된 화면',
    async () => {
      await context.clearCookies()
      await page.goto(`${BASE_URL}/usage`, { waitUntil: 'networkidle' })
      await loginForm.first().waitFor({ state: 'visible', timeout: 15000 })
      const rect = clampRect(padBox(await loginForm.first().boundingBox()), '로그인 입력 폼')
      return { url: page.url(), image: await shoot(page, '04a-login-redirect'), highlights: [rect] }
    },
  )

  // ── 04b 로그인 정보 입력 ───────────────────────────────────────────────────
  const filledLogin = await step(
    '04b-login-filled',
    `${BASE_URL}/login`,
    '아이디·비밀번호 입력 완료(비밀번호는 가려진 상태), 제출 직전',
    async () => {
      if (!page.url().includes('/login')) {
        await page.goto(`${BASE_URL}/login`, { waitUntil: 'networkidle' })
      }
      const form = loginForm.first()
      await form.waitFor({ state: 'visible', timeout: 15000 })
      await form.locator('input[type="email"]').first().fill(REVIEW_ID)
      // 비밀번호는 type=password 라 화면에도 ●로만 남는다. 값 자체는 출력하지 않는다.
      await form.locator('input[type="password"]').first().fill(REVIEW_PW)
      await page.waitForTimeout(200)
      const rect = clampRect(padBox(await form.boundingBox()), '로그인 입력 폼(입력 완료)')
      return { url: page.url(), image: await shoot(page, '04b-login-filled'), highlights: [rect] }
    },
  )

  // ── 05a 플랜 선택 화면 ─────────────────────────────────────────────────────
  const planSection = page.locator('section[aria-label="플랜 선택 및 결제"]').first()
  const startRadio = planSection.getByRole('radio', { name: 'Start' }).first()
  // 금액·플랜 이름은 바뀔 수 있다 — 버튼을 가려내는 데 필요한 만큼만 본다.
  const payButton = planSection.getByRole('button').filter({ hasText: /정기결제 시작/ }).first()
  const consentCheckbox = planSection.locator('input[type="checkbox"]').first()

  const loggedIn = await step(
    '05a-plan-select',
    `${BASE_URL}/usage`,
    '로그인 후 이용량 화면에서 플랜 선택 영역으로 스크롤',
    async () => {
      if (!filledLogin) throw new Error('앞 단계(04b)가 끝나지 않아 로그인할 수 없습니다')
      await loginForm.first().locator('button[type="submit"]').first().click()
      await page.waitForURL((url) => !url.pathname.includes('/login'), { timeout: 30000 })
      if (!page.url().includes('/usage')) {
        await page.goto(`${BASE_URL}/usage`, { waitUntil: 'networkidle' })
      }
      await planSection.waitFor({ state: 'visible', timeout: 20000 })
      await startRadio.waitFor({ state: 'visible', timeout: 15000 })
      if (!(await startRadio.isChecked())) await startRadio.check()
      await planSection.scrollIntoViewIfNeeded()
      await page.waitForTimeout(400)

      const cardBox = await startRadio.evaluate((input) => {
        const card = input.closest('li') ?? input.closest('label') ?? input
        const rect = card.getBoundingClientRect()
        return { x: rect.left, y: rect.top, width: rect.width, height: rect.height }
      })
      // 금액·제공량·자동결제 안내 문단 = 플랜 목록 뒤, 동의 체크박스 앞의 글 묶음.
      // 문구는 바뀔 수 있으므로 문장이 아니라 문서 순서로 범위를 잡는다.
      const disclosureBox = await planSection.evaluate((section) => {
        const list = section.querySelector('ul[aria-label]') ?? section.querySelector('ul')
        if (list === null) return null
        const consentLabel = section.querySelector('input[type="checkbox"]')?.closest('label')
        const nodes = Array.from(section.querySelectorAll('p, a')).filter((node) => {
          if (list.contains(node)) return false
          const afterList =
            (list.compareDocumentPosition(node) & Node.DOCUMENT_POSITION_FOLLOWING) !== 0
          if (!afterList) return false
          if (consentLabel === null || consentLabel === undefined) return true
          if (consentLabel.contains(node)) return false
          return (
            (consentLabel.compareDocumentPosition(node) & Node.DOCUMENT_POSITION_PRECEDING) !== 0
          )
        })
        if (nodes.length === 0) return null
        const rects = nodes.map((node) => node.getBoundingClientRect())
        const left = Math.min(...rects.map((rect) => rect.left))
        const top = Math.min(...rects.map((rect) => rect.top))
        const right = Math.max(...rects.map((rect) => rect.right))
        const bottom = Math.max(...rects.map((rect) => rect.bottom))
        return { x: left, y: top, width: right - left, height: bottom - top }
      })

      return {
        url: page.url(),
        image: await shoot(page, '05a-plan-select'),
        highlights: [
          clampRect(padBox(cardBox), 'Start 플랜 카드'),
          clampRect(padBox(disclosureBox), '금액·제공량·자동결제 안내'),
        ],
      }
    },
  )

  // ── 05b 동의 체크 ──────────────────────────────────────────────────────────
  await step(
    '05b-plan-consent',
    `${BASE_URL}/usage`,
    '금액·자동결제 동의 체크 완료, 결제 버튼 활성화 상태',
    async () => {
      if (!loggedIn) throw new Error('앞 단계(05a)가 끝나지 않아 진행할 수 없습니다')
      // 동의 체크박스는 결제가 열렸고 구독이 없는 계정에서만 그려진다
      // (`checkoutEnabled && !subscribed && (live || tossEnabled)`).
      // 없는 환경이라면 운영자가 고칠 것이 없으므로 실패가 아니라 건너뜀이다.
      if ((await consentCheckbox.count()) === 0) {
        throw new SkipStep(
          '이 환경에는 결제 동의 체크박스가 없습니다(결제 비활성 설정이거나 이미 구독 중인 계정)',
        )
      }
      await consentCheckbox.waitFor({ state: 'visible', timeout: 15000 })
      if (!(await consentCheckbox.isChecked())) await consentCheckbox.check()
      await page.waitForTimeout(300)
      const consentBox = await consentCheckbox.evaluate((input) => {
        const target = input.closest('label') ?? input
        const rect = target.getBoundingClientRect()
        return { x: rect.left, y: rect.top, width: rect.width, height: rect.height }
      })
      return {
        url: page.url(),
        image: await shoot(page, '05b-plan-consent'),
        highlights: [
          clampRect(padBox(consentBox), '결제 조건 동의'),
          clampRect(padBox(await payButton.boundingBox()), '정기결제 시작 버튼'),
        ],
      }
    },
  )

  // ── 06 토스 카드 등록창 ────────────────────────────────────────────────────
  await step(
    '06-billing-window',
    `${BASE_URL}/usage`,
    '정기결제 시작 클릭 → 토스페이먼츠 카드 등록창이 열린 상태 (카드 정보 미입력)',
    async () => {
      // 05b가 건너뛰어졌더라도 버튼이 눌리는 환경이면 찍는다 — 결제 버튼이 실제로
      // 활성화되어 있는지를 기준으로 삼는 편이 앞 단계 상태보다 정확하다.
      if (!loggedIn) throw new Error('앞 단계(05a)가 끝나지 않아 진행할 수 없습니다')
      if ((await payButton.count()) === 0) {
        throw new SkipStep(
          '이 환경에는 정기결제 시작 버튼이 없습니다(결제 비활성 설정이거나 이미 구독 중인 계정)',
        )
      }
      if (!(await payButton.isEnabled())) {
        throw new SkipStep('정기결제 시작 버튼이 비활성 상태입니다 — 동의 체크가 필요합니다')
      }

      // 토스 SDK v2는 PC에서 `windowTarget: 'iframe'`이 기본이고(@tosspayments/tosspayments-sdk
      // 타입 정의의 `BaseBillingAuthRequest.windowTarget`), 설정에 따라 별도 창으로도 열린다.
      // 팝업을 기다렸다가 타임아웃 후에 iframe을 보면 기본 경로에서 20초를 버리므로 둘을
      // 경주시킨다.
      const tossFrame = page
        .locator('iframe[src*="tosspayments.com"], iframe[src*="toss.im"]')
        .first()
      const popupPromise = context.waitForEvent('page', { timeout: 25000 }).catch(() => null)
      const framePromise = tossFrame
        .waitFor({ state: 'visible', timeout: 25000 })
        .then(() => 'iframe')
        .catch(() => null)
      await payButton.click()

      const opened = await Promise.race([
        popupPromise.then((popup) => (popup === null ? null : { popup })),
        framePromise.then((hit) => (hit === null ? null : { frame: true })),
        // 둘 다 끝까지 비면 아래에서 명시적으로 실패시킨다.
        Promise.all([popupPromise, framePromise]).then(() => null),
      ])

      if (opened !== null && opened.popup !== undefined) {
        const popup = opened.popup
        await popup.waitForLoadState('domcontentloaded', { timeout: 20000 })
        await popup.waitForTimeout(2500)
        const size = await popup.evaluate(() => ({
          width: window.innerWidth,
          height: window.innerHeight,
        }))
        const image = '06-billing-window.png'
        await popup.screenshot({ path: join(OUT_DIR, image), fullPage: false })
        // 카드 정보는 입력하지 않는다 — 화면이 열린 것까지가 심사 자료다.
        // 캡처 전체가 결제창이므로 그림 테두리와 겹치는 강조 사각형은 그리지 않는다.
        return {
          url: popup.url(),
          state: '토스페이먼츠 카드 등록 창(별도 창) — 카드 정보 미입력',
          image,
          viewport: size,
          highlights: [],
        }
      }

      if (opened === null) {
        throw new Error('토스페이먼츠 카드 등록창(팝업·iframe 어느 쪽도)이 열리지 않았습니다')
      }

      await page.waitForTimeout(2500)
      const frameBox = await tossFrame.boundingBox()
      const rect = clampRect(padBox(frameBox), '토스페이먼츠 카드 등록창')
      // 결제창이 화면을 거의 다 덮으면 강조 사각형이 그림 테두리와 겹쳐 의미가 없다.
      const coversViewport =
        rect !== null && rect.width >= VIEWPORT.width * 0.97 && rect.height >= VIEWPORT.height * 0.97
      return {
        url: page.url(),
        state: '토스페이먼츠 카드 등록 창(오버레이) — 카드 정보 미입력',
        image: await shoot(page, '06-billing-window'),
        highlights: coversViewport ? [] : [rect],
      }
    },
  )

  manifest.flush()
  log(`manifest: ${MANIFEST_PATH}`)
}

/**
 * 브라우저는 어떤 경로로 끝나든 반드시 닫는다 — 중간에 예외가 나면 헤드리스 크로뮴이
 * 남아 다음 실행과 호스트 메모리를 갉아먹는다. `browser.close()`가 컨텍스트까지 닫는다.
 */
run()
  .catch((error) => {
    log(`캡처 실패: ${error instanceof Error ? (error.stack ?? error.message) : error}`)
    process.exitCode = 1
  })
  .finally(async () => {
    if (activeBrowser !== null) {
      await activeBrowser.close().catch(() => {})
    }
  })
