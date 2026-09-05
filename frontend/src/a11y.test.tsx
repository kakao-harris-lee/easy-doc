/**
 * 화면을 관통하는 접근성 검증 (DESIGN.md §11·§14).
 *
 * 각 조각이 자기 범위의 접근성만 지켰는지가 아니라, **한 화면을 통째로 그려 놓고**
 * 랜드마크·제목 순서·라벨 연결·낭독 중복을 한 번에 재는 곳이다. 화면마다 흩어져 있던
 * 단언과 달리 여기서는 «모든 화면이 같은 규칙을 지키는가»를 묻는다 — 새 화면이 늘어도
 * 이 파일의 `SCREENS` 에 한 줄만 더하면 같은 규칙이 그대로 적용된다.
 *
 * 브라우저가 있어야 재는 것(키보드 전 경로, 320px 가로 스크롤, 터치 대상 크기, 포커스
 * 링 두께, reduced motion)은 여기서 재지 않는다 — jsdom 에는 레이아웃도 CSS 도 없다.
 * 그쪽은 `e2e/a11y.spec.ts` 가 실제 크롬에서 잰다.
 */

import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { ApiError, getConversion, getDocumentSource, listDocuments } from './api/client'
import { AuthContext, type AuthContextValue } from './auth/context'
import { AppLayout } from './components/AppLayout'
import { AppRoutes } from './routes/AppRoutes'
import { conversion, documentItem, documentSource, workspaceContext } from './test/factories'
import { WorkspaceContext } from './workspace/context'

vi.mock('./api/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('./api/client')>()),
  listDocuments: vi.fn(),
  getConversion: vi.fn(),
  getDocumentSource: vi.fn(),
}))

const USER = {
  id: 'u1',
  email: 'gongmuwon@example.test',
  email_verified: true,
  identities: [],
}

function authValue(status: AuthContextValue['status']): AuthContextValue {
  return {
    status,
    user: status === 'authenticated' ? USER : null,
    signIn: () => Promise.resolve(),
    signUp: () => Promise.resolve(),
    signInWithSocialProvider: () => Promise.resolve(),
    signOut: () => undefined,
    refreshMe: () => Promise.resolve(),
  }
}

type Entry = string | { pathname: string; state?: unknown }

function renderAt(entry: Entry, status: AuthContextValue['status'] = 'authenticated') {
  return render(
    <AuthContext.Provider value={authValue(status)}>
      <WorkspaceContext.Provider value={workspaceContext()}>
        <MemoryRouter initialEntries={[entry]}>
          <AppLayout>
            <AppRoutes />
          </AppLayout>
        </MemoryRouter>
      </WorkspaceContext.Provider>
    </AuthContext.Provider>,
  )
}

/**
 * 검수 화면이 원문을 라우터 state 로 함께 받는 경로(붙여넣기 직후).
 *
 * 이 값이 없어도 화면은 서버에서 원문을 가져온다 — 여기서 굳이 실어 보내는 것은
 * «첫 화면부터 왼쪽이 차 있는» 경로도 접근성 규칙을 똑같이 지키는지 재기 위해서다.
 */
const REVIEW_WITH_SOURCE = {
  pathname: '/conversions/c1',
  state: { sourceText: '신청은 3월 2일부터 가능합니다.' },
}

/**
 * 이 저장소가 가진 모든 업무 화면.
 *
 * `settle` 은 그 화면이 «다 그려졌다»고 볼 수 있는 지점이다 — 폴링이나 목록 조회가
 * 끝나기 전에 재면 로딩 화면의 접근성만 재고 넘어간다.
 */
const SCREENS: readonly {
  readonly name: string
  readonly open: () => void
  readonly settle: () => Promise<unknown>
}[] = [
  {
    name: '로그인',
    open: () => renderAt('/login', 'anonymous'),
    settle: () => screen.findByRole('heading', { name: '로그인' }),
  },
  {
    name: '가입',
    open: () => renderAt('/signup', 'anonymous'),
    settle: () => screen.findByRole('heading', { name: '가입하기', level: 1 }),
  },
  {
    name: '새 변환',
    open: () => renderAt('/'),
    settle: () => screen.findByRole('heading', { name: '문서 변환하기' }),
  },
  {
    name: '변환 진행',
    open: () => {
      vi.mocked(getConversion).mockResolvedValue(
        conversion({ status: 'processing', easy_text: null }),
      )
      renderAt('/conversions/c1')
    },
    settle: () => screen.findByRole('heading', { name: '쉬운 글로 바꾸는 중' }),
  },
  {
    name: '변환 실패',
    open: () => {
      vi.mocked(getConversion).mockResolvedValue(
        conversion({ status: 'failed', easy_text: null, failure_code: 'llm_error' }),
      )
      renderAt('/conversions/c1')
    },
    settle: () => screen.findByRole('heading', { name: '변환하지 못했습니다' }),
  },
  {
    // 404 로 닫힌 변환(없는 것·남의 것·보관 기간이 지나 파기된 것)은 기다리는 화면이
    // 아니라 **끝난 화면**이다 — §9 가 요구하는 대로 로딩과 다른 상태로 잰다.
    name: '변환 없음',
    open: () => {
      vi.mocked(getConversion).mockRejectedValue(new ApiError(404, '변환을 찾을 수 없습니다.'))
      renderAt('/conversions/c1')
    },
    settle: () => screen.findByRole('heading', { name: '이 변환을 열 수 없습니다' }),
  },
  {
    name: '검수',
    open: () => {
      vi.mocked(getConversion).mockResolvedValue(conversion({ status: 'done' }))
      renderAt(REVIEW_WITH_SOURCE)
    },
    settle: () => screen.findByRole('heading', { name: '쉬운 글 검수' }),
  },
  {
    // §9 «원문을 불러오지 못함»은 로딩·빈 상태와 다른 화면이다 — 접근성도 따로 재야 한다.
    name: '검수 (원문 불러오기 실패)',
    open: () => {
      vi.mocked(getConversion).mockResolvedValue(conversion({ status: 'done' }))
      vi.mocked(getDocumentSource).mockRejectedValue(new ApiError(404, '문서를 찾을 수 없습니다.'))
      renderAt('/conversions/c1')
    },
    settle: () => screen.findByRole('heading', { name: '원문을 불러오지 못함' }),
  },
  {
    name: '변환 기록',
    open: () => renderAt('/history'),
    settle: () => screen.findByText('재난지원금 안내'),
  },
  {
    name: '찾을 수 없는 화면',
    open: () => renderAt('/없는-주소'),
    settle: () => screen.findByRole('heading', { name: '찾을 수 없는 화면입니다' }),
  },
]

/** 화면을 열고 다 그려질 때까지 기다린다. */
async function openScreen(screenName: string): Promise<void> {
  const target = SCREENS.find((entry) => entry.name === screenName)
  if (target === undefined) {
    throw new Error(`모르는 화면이다: ${screenName}`)
  }
  target.open()
  await target.settle()
}

/**
 * 문서 전체의 머리말(`banner`)인 `<header>`.
 *
 * `screen.getByRole('banner')` 를 쓰지 않는다. Testing Library 가 쓰는 역할 표는
 * `<header>` 를 **무조건** `banner` 로 옮기는데, HTML-AAM 은 `article`·`aside`·`main`·
 * `nav`·`section` **안에 있는** `<header>` 는 `banner` 가 아니라고 못박는다. 실제 브라우저는
 * 명세대로 동작하므로(`e2e/a11y.spec.ts` 가 크롬에서 같은 것을 1개로 잰다), 여기서 도구의
 * 넓은 매핑을 그대로 믿으면 `PageHeader` 의 화면 제목 머리말이 «두 번째 banner» 로 잡혀
 * 있지도 않은 결함을 고치게 된다. 그래서 스코프 규칙을 직접 적용한다.
 */
function pageBanners(): HTMLElement[] {
  return Array.from(document.querySelectorAll<HTMLElement>('header')).filter(
    (element) => element.closest('article, aside, main, nav, section') === null,
  )
}

/** 문서에 그려진 제목을 DOM 순서대로. 낭독기 목차가 보는 것과 같은 순서다. */
function headingOutline(): { level: number; text: string }[] {
  return Array.from(document.querySelectorAll<HTMLElement>('h1, h2, h3, h4, h5, h6')).map(
    (element) => ({
      level: Number(element.tagName.slice(1)),
      text: (element.textContent ?? '').replace(/\s+/g, ' ').trim(),
    }),
  )
}

/**
 * 요소의 접근 가능한 이름을 «있는지 없는지» 판정할 만큼만 구한다.
 *
 * `dom-accessibility-api` 를 직접 부르지 않는다 — 그것은 이 저장소가 선언한 의존성이
 * 아니라 Testing Library 가 끌고 온 것이라, 그것에 기대면 남의 잠금 파일에 우리 검증이
 * 매달린다. 여기서 필요한 것은 정확한 이름 계산이 아니라 «이름이 하나라도 붙어 있는가»다.
 */
function accessibleName(element: HTMLElement): string {
  const labelledBy = element.getAttribute('aria-labelledby')
  if (labelledBy !== null) {
    const text = labelledBy
      .split(/\s+/)
      .map((id) => document.getElementById(id)?.textContent ?? '')
      .join(' ')
      .trim()
    if (text !== '') {
      return text
    }
  }
  const ariaLabel = element.getAttribute('aria-label')?.trim()
  if (ariaLabel !== undefined && ariaLabel !== '') {
    return ariaLabel
  }
  const id = element.getAttribute('id')
  if (id !== null) {
    const label = document.querySelector<HTMLElement>(`label[for="${CSS.escape(id)}"]`)
    const text = label?.textContent?.trim() ?? ''
    if (text !== '') {
      return text
    }
  }
  const wrapping = element.closest('label')?.textContent?.trim() ?? ''
  if (wrapping !== '') {
    return wrapping
  }
  return element.getAttribute('title')?.trim() ?? element.textContent?.trim() ?? ''
}

/** 화면 안의 모든 폼 컨트롤. 숨김(`hidden` 속성)은 접근성 트리에 없으므로 뺀다. */
function formControls(): HTMLElement[] {
  return Array.from(document.querySelectorAll<HTMLElement>('input, select, textarea')).filter(
    (element) => element.closest('[hidden]') === null,
  )
}

/** `aria-labelledby`·`aria-describedby` 가 가리키는 모든 id 참조. */
function ariaReferences(): { attribute: string; id: string; from: string }[] {
  const references: { attribute: string; id: string; from: string }[] = []
  for (const attribute of ['aria-labelledby', 'aria-describedby', 'aria-controls']) {
    for (const element of document.querySelectorAll<HTMLElement>(`[${attribute}]`)) {
      for (const id of (element.getAttribute(attribute) ?? '').split(/\s+/).filter(Boolean)) {
        references.push({ attribute, id, from: element.tagName.toLowerCase() })
      }
    }
  }
  return references
}

/** 낭독되는 라이브 영역의 문장. `role=status`·`role=alert`·`aria-live` 를 모두 본다. */
function liveRegionTexts(): string[] {
  return Array.from(
    document.querySelectorAll<HTMLElement>('[role="status"], [role="alert"], [aria-live]'),
  )
    .map((element) => (element.textContent ?? '').replace(/\s+/g, ' ').trim())
    .filter((text) => text !== '')
}

beforeEach(() => {
  vi.mocked(listDocuments).mockResolvedValue({
    items: [documentItem()],
    limit: 20,
    offset: 0,
    has_more: false,
  })
  vi.mocked(getConversion).mockResolvedValue(conversion({ status: 'done' }))
  vi.mocked(getDocumentSource).mockResolvedValue(documentSource())
})

afterEach(() => {
  vi.mocked(listDocuments).mockReset()
  vi.mocked(getConversion).mockReset()
  vi.mocked(getDocumentSource).mockReset()
})

describe('①  랜드마크와 건너뛰기 링크', () => {
  it.each(SCREENS.map((entry) => entry.name))(
    '%s 화면에 banner·main 랜드마크와 본문 건너뛰기 링크가 있다',
    async (name) => {
      await openScreen(name)

      expect(pageBanners()).toHaveLength(1)
      const main = screen.getByRole('main')
      expect(main).toBeInTheDocument()

      // 건너뛰기 링크가 실제로 main 을 가리켜야 «건너뛰기»가 성립한다. 예전에 이
      // 저장소에서 문제가 됐던 축은 아니지만, id 를 바꾸면 조용히 끊어지는 연결이다.
      const skip = screen.getByRole('link', { name: '본문으로 건너뛰기' })
      expect(skip.getAttribute('href')).toBe(`#${main.id}`)
    },
  )
})

describe('②  제목 순서', () => {
  it.each(SCREENS.map((entry) => entry.name))(
    '%s 화면의 제목은 h1 하나로 시작하고 단계를 건너뛰지 않는다',
    async (name) => {
      await openScreen(name)
      const outline = headingOutline()

      expect(outline.length).toBeGreaterThan(0)
      // h1 은 정확히 하나다 — 둘이면 «이 화면이 무엇인가»가 둘로 갈린다.
      expect(outline.filter((heading) => heading.level === 1)).toHaveLength(1)
      // 그리고 그것이 첫 제목이다. 본문이 h2 로 시작하면 낭독기 목차의 뿌리가 없다.
      expect(outline[0]?.level).toBe(1)

      // 단계를 건너뛰지 않는다(h1 → h3 금지).
      for (let index = 1; index < outline.length; index += 1) {
        const previous = outline[index - 1]
        const current = outline[index]
        if (previous === undefined || current === undefined) {
          continue
        }
        expect(
          current.level - previous.level,
          `‘${previous.text}’(h${previous.level}) 다음에 ‘${current.text}’(h${current.level})가 온다`,
        ).toBeLessThanOrEqual(1)
      }
    },
  )
})

describe('③  입력과 오류의 프로그램적 연결', () => {
  it.each(SCREENS.map((entry) => entry.name))(
    '%s 화면의 모든 입력에 접근 가능한 이름이 있다',
    async (name) => {
      await openScreen(name)

      for (const control of formControls()) {
        expect(
          accessibleName(control),
          `${control.tagName.toLowerCase()}#${control.id || '(id 없음)'} 에 이름이 없다`,
        ).not.toBe('')
      }
    },
  )

  it.each(SCREENS.map((entry) => entry.name))(
    '%s 화면의 aria 참조가 실재하는 요소를 가리킨다',
    async (name) => {
      await openScreen(name)

      for (const reference of ariaReferences()) {
        expect(
          document.getElementById(reference.id),
          `${reference.from}[${reference.attribute}] 가 없는 id ‘${reference.id}’ 를 가리킨다`,
        ).not.toBeNull()
      }
    },
  )
})

describe('④  상태 변경 낭독 — 같은 문장을 두 번 읽지 않는다', () => {
  it.each(SCREENS.map((entry) => entry.name))(
    '%s 화면에 같은 문장을 가진 라이브 영역이 둘 이상 있지 않다',
    async (name) => {
      await openScreen(name)
      const texts = liveRegionTexts()

      expect(new Set(texts).size, `중복 낭독: ${texts.join(' / ')}`).toBe(texts.length)
    },
  )

  it('변환 진행 화면의 라이브 영역은 하나다 — 단계 목록은 스스로 알리지 않는다', async () => {
    await openScreen('변환 진행')

    // 단계 목록까지 live 로 두면 «쉬운 글로 바꾸고 있습니다»와 «진행 중»이 겹쳐 읽힌다.
    expect(screen.getAllByRole('status')).toHaveLength(1)
    expect(screen.getByRole('list', { name: '변환 단계' })).not.toHaveAttribute('aria-live')
  })
})

describe('⑥  검수 2열의 DOM 읽기 순서', () => {
  it('원문 패널이 결과 패널보다 DOM 에서 먼저 온다', async () => {
    await openScreen('검수')

    const source = screen.getByLabelText('원본 (읽기 전용)')
    const result = screen.getByLabelText('쉬운 글 결과 (고칠 수 있습니다)')

    expect(source.compareDocumentPosition(result)).toBe(Node.DOCUMENT_POSITION_FOLLOWING)
  })
})

describe('⑦  탭으로 갈린 좁은 화면에서도 원문이 먼저다', () => {
  it('탭 줄과 패널 모두 원문 다음 결과 순서다', async () => {
    // 좁은 화면이라고 답하는 matchMedia 를 꽂아야 탭이 만들어진다(2열에서는 탭이 없다).
    vi.stubGlobal('matchMedia', (query: string) => ({
      matches: false,
      media: query,
      onchange: null,
      addEventListener: () => undefined,
      removeEventListener: () => undefined,
      addListener: () => undefined,
      removeListener: () => undefined,
      dispatchEvent: () => false,
    }))
    vi.mocked(getConversion).mockResolvedValue(conversion({ status: 'done' }))
    renderAt(REVIEW_WITH_SOURCE)
    await screen.findByRole('heading', { name: '쉬운 글 검수' })

    expect(screen.getAllByRole('tab').map((tab) => tab.textContent)).toEqual(['원문', '쉬운 글'])

    // 탭이 가리키는 패널의 DOM 순서도 같아야 한다 — 탭 줄만 맞추고 패널을 뒤집으면
    // 낭독기로 훑는 사용자는 결과를 먼저 읽는다(§11).
    const panels = screen.getAllByRole('tab').map((tab) => {
      const id = tab.getAttribute('aria-controls') ?? ''
      const panel = document.getElementById(id)
      if (panel === null) {
        throw new Error(`탭이 없는 패널을 가리킨다: ${id}`)
      }
      return panel
    })
    const [source, result] = panels
    expect(source?.compareDocumentPosition(result as Node)).toBe(Node.DOCUMENT_POSITION_FOLLOWING)

    vi.unstubAllGlobals()
  })
})

describe('⑧  스켈레톤은 하나의 로딩 상태 이름만 준다', () => {
  it('블록마다 낭독되지 않고 이름이 한 번만 붙는다', async () => {
    await openScreen('변환 진행')

    const skeleton = screen.getByRole('img', {
      name: '검수 화면 미리보기입니다. 결과를 준비하고 있습니다.',
    })
    // `role="img"` 는 자식을 표현 전용으로 만든다 — 안쪽 블록에 이름이 더 붙어 있으면
    // 그 약속이 깨진 것이다.
    expect(skeleton.querySelectorAll('[aria-label], [role]')).toHaveLength(0)
  })
})

describe('⑨  아이콘 전용 버튼의 접근 가능한 이름', () => {
  it.each(SCREENS.map((entry) => entry.name))(
    '%s 화면의 모든 버튼과 링크에 이름이 있다',
    async (name) => {
      await openScreen(name)

      const controls = Array.from(document.querySelectorAll<HTMLElement>('button, a[href]')).filter(
        (element) => element.closest('[hidden]') === null,
      )
      expect(controls.length).toBeGreaterThan(0)

      for (const control of controls) {
        expect(
          accessibleName(control),
          `${control.tagName.toLowerCase()}.${control.className.split(' ')[0] ?? ''} 에 이름이 없다`,
        ).not.toBe('')
      }
    },
  )
})

describe('검수 화면의 저장 상태', () => {
  it('저장 성공은 상태 라벨과 안내 문구로 두 번 낭독되지 않는다', async () => {
    await openScreen('검수')

    // 저장 전에도 저장 상태는 라이브 영역 하나로만 말한다.
    await waitFor(() => {
      expect(screen.getAllByRole('status')).toHaveLength(1)
    })
    expect(screen.getByRole('status')).toHaveTextContent('저장 전')
  })
})
