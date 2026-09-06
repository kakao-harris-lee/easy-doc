/**
 * E19 — 사전 팝업(`TermLookupPopover`) 조회·적용 수직 흐름(P0-5 조각 6).
 *
 * E13(`conversion-flow.spec.ts`)이 문서 등록 → worker 변환 → 검수 화면 도달까지의 흐름을
 * 이미 재는 것을 그대로 되풀이해 도달한 뒤, 여기서는 그 검수 화면에 뜬 「쉬운 말 후보」
 * 팝업만 잰다: ① 원문(읽기 전용) 패널에서 선택하면 조회만 되고 「바꾸기」는 없어야
 * 한다(계획 §3.5) ② 결과 패널에서 선택하면 조회 + 「바꾸기」로 실제 치환까지 된다.
 *
 * `POST /dictionary/lookup`은 `easydoc.dictionary.lookup.enabled=true`일 때만 열린다
 * (`DictionaryLookupProperties`, 기본 꺼짐) — `compose.e2e.yml`이 `backend-api`에
 * `EASYDOC_DICTIONARY_LOOKUP_ENABLED=true`를 얹어 이 스위트의 스택에서만 켠다.
 */

import { expect, test } from '@playwright/test'

import { ROUTES } from './contract'
import { api, newAccount, signUpAndLand, verifyEmail } from './support/app'

/** fake LLM 변환이 끝나야 도달하는 검수 화면에 실릴 원문. 사전 표제어 「납부」를 담는다. */
const SOURCE_TEXT = '국민건강보험료를 납부하려면 가까운 지사를 방문하세요.'

/** 결과 패널에서 「바꾸기」를 시험할 문장 — 같은 표제어 「납부」를 담는다. */
const RESULT_TEXT = '보험료를 납부하세요.'

/** 사전 표제어(`easy_dict.index.json` id 1818, strategy `substitute`)와 그 쉬운 말. */
const HEADWORD = '납부'
const EASY_TERM = '내기'

/** textarea 안에서 [start, end)를 선택하고 팝업이 듣는 이벤트(mouseup)를 쏜다. */
async function selectAndTriggerLookup(
  locator: import('@playwright/test').Locator,
  start: number,
  end: number,
): Promise<void> {
  await locator.evaluate(
    (node, range: [number, number]) => {
      const textarea = node as HTMLTextAreaElement
      textarea.focus()
      textarea.setSelectionRange(range[0], range[1])
      textarea.dispatchEvent(new MouseEvent('mouseup', { bubbles: true }))
    },
    [start, end] as [number, number],
  )
}

/** 값 안에서 표제어의 [start, end) 오프셋을 찾는다 — 좌표를 문자열에 손으로 세어 적지 않는다. */
function offsetsOf(value: string, term: string): [number, number] {
  const start = value.indexOf(term)
  if (start < 0) {
    throw new Error(`"${term}" 을 "${value}" 안에서 찾지 못했다.`)
  }
  return [start, start + term.length]
}

test.describe('사전 팝업 조회 흐름', () => {
  test('E19 원문에서는 조회만 되고, 결과에서는 바꾸기까지 된다', async ({ page }) => {
    test.setTimeout(120_000)

    const account = newAccount()
    await signUpAndLand(page, account)
    // 이메일/비밀번호 계정은 인증을 마쳐야 문서 등록(`POST /documents`)이 열린다(계약 2.9.0).
    await verifyEmail(page, account)

    await page.getByLabel('문서 제목').fill('E2E 사전 팝업 조회')
    await page.getByLabel('바꿀 글').fill(SOURCE_TEXT)

    await Promise.all([
      page.waitForResponse(
        (response) =>
          response.url() === api(ROUTES.documentCreate.path) &&
          response.request().method() === ROUTES.documentCreate.method,
      ),
      page.getByRole('button', { name: '쉬운 글 초안 만들기', exact: true }).click(),
    ])

    // worker 가 fake LLM 으로 끝낼 때까지 기다린다 — E13 과 같은 대기다.
    await expect(page.getByRole('heading', { name: '쉬운 글 검수' })).toBeVisible({
      timeout: 90_000,
    })

    // --- ① 원문(읽기 전용) 패널: 조회는 되지만 적용 버튼은 없다(계획 §3.5) -------------
    const sourceTextarea = page.getByLabel('원본 1번째 문단', { exact: true })
    await expect(sourceTextarea).toHaveValue(SOURCE_TEXT)
    const [sourceStart, sourceEnd] = offsetsOf(SOURCE_TEXT, HEADWORD)

    const [sourceLookupResponse] = await Promise.all([
      page.waitForResponse(
        (response) =>
          response.url() === api(ROUTES.dictionaryLookup.path) &&
          response.request().method() === ROUTES.dictionaryLookup.method,
      ),
      selectAndTriggerLookup(sourceTextarea, sourceStart, sourceEnd),
    ])
    expect(sourceLookupResponse.status()).toBe(ROUTES.dictionaryLookup.ok)

    const dialog = page.getByRole('dialog', { name: '쉬운 말 후보' })
    await expect(dialog).toBeVisible()
    await expect(dialog.getByText(EASY_TERM)).toBeVisible()
    // 원문 패널 인스턴스는 `applyDisabled`로 배선돼 「바꾸기」 버튼을 아예 그리지 않는다.
    await expect(dialog.getByRole('button', { name: '바꾸기' })).toHaveCount(0)

    // 다음 조회(결과 패널)와 헷갈리지 않도록 명시적으로 닫는다.
    await page.keyboard.press('Escape')
    await expect(dialog).not.toBeVisible()

    // --- ② 결과 패널: 조회 + 바꾸기로 실제 치환까지 된다 -------------------------------
    // 문단 하나뿐이라 단위도 하나 — 대응 확인 앵커가 없어 라벨은 "대응 확인 불가"다
    // (`SegmentedResultEditor.unitLabel`, E13 과 같은 근거).
    const resultEditor = page.getByLabel('쉬운 글 단위 1, 대응 확인 불가')
    await resultEditor.fill(RESULT_TEXT)
    const [resultStart, resultEnd] = offsetsOf(RESULT_TEXT, HEADWORD)

    const [resultLookupResponse] = await Promise.all([
      page.waitForResponse(
        (response) =>
          response.url() === api(ROUTES.dictionaryLookup.path) &&
          response.request().method() === ROUTES.dictionaryLookup.method,
      ),
      selectAndTriggerLookup(resultEditor, resultStart, resultEnd),
    ])
    expect(resultLookupResponse.status()).toBe(ROUTES.dictionaryLookup.ok)

    await expect(dialog).toBeVisible()
    // 후보가 실제로 그려진 뒤에 누른다 — 후보 0건이면 click 이 테스트 타임아웃까지 기다리며
    // 원인을 감춘다. 같은 표제어 항목이 둘이 되어도 「내기」 행 안의 버튼만 고른다.
    const candidate = dialog.getByRole('listitem').filter({ hasText: EASY_TERM })
    await expect(candidate).toBeVisible()
    await candidate.getByRole('button', { name: '바꾸기', exact: true }).click()

    await expect(resultEditor).toHaveValue(RESULT_TEXT.replace(HEADWORD, EASY_TERM))
  })
})
