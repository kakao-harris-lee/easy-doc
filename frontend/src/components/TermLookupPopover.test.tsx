import { act, fireEvent, render, screen } from '@testing-library/react'
import { useRef, useState } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { ApiError } from '../api/client'
import { lookupTerm } from '../api/dictionary'
import type { DictionaryAttribution, DictionaryLookupCandidate } from '../api/types'
import { TermLookupPopover } from './TermLookupPopover'

vi.mock('../api/dictionary', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../api/dictionary')>()),
  lookupTerm: vi.fn(),
}))

const DICTIONARY: DictionaryAttribution = {
  name: '쉬운 말 사전',
  license: 'CC-BY',
  schema_version: '1.0.0',
}

const APPLICABLE_CANDIDATE: DictionaryLookupCandidate = {
  term: '구비서류',
  easy_term: '준비할 서류',
  strategy: 'substitute',
  risk: 'none',
  definition: '신청할 때 미리 갖춰야 하는 서류',
  caution: null,
  tags: [],
  examples: [],
  match_kind: 'exact',
  applicable: true,
}

const GLOSS_CANDIDATE: DictionaryLookupCandidate = {
  term: '과태료',
  easy_term: '과태료',
  strategy: 'gloss',
  risk: 'high',
  definition: '법을 어겼을 때 내는 돈',
  caution: '벌금과 다르다 — 함부로 바꾸면 안 된다',
  tags: [],
  examples: [],
  match_kind: 'exact',
  applicable: false,
}

/**
 * 결과 편집기를 흉내 낸 시험대.
 *
 * `unitIndexes`가 주어지면 단위별 textarea(`SegmentedResultEditor`처럼 `data-unit-index`를
 * 단다)로, 없으면 단일 textarea 폴백처럼 하나의 상자로 그린다. `TermLookupPopover`는
 * `containerRef` 하나에만 이벤트를 걸므로 이 얇은 래퍼로 실제 화면 배선을 충분히 흉내낼
 * 수 있다.
 */
function Harness({
  initialValue,
  segmented,
  disabled = false,
}: {
  initialValue: string
  segmented: boolean
  disabled?: boolean
}) {
  const [value, setValue] = useState(initialValue)
  const containerRef = useRef<HTMLDivElement>(null)
  const units = segmented ? value.split('\n') : [value]

  return (
    <div ref={containerRef}>
      {units.map((unit, index) => (
        <textarea
          key={index}
          aria-label={`단위 ${index}`}
          value={unit}
          data-unit-index={segmented ? index : undefined}
          onChange={() => undefined}
        />
      ))}
      <TermLookupPopover
        containerRef={containerRef}
        value={value}
        onApply={setValue}
        disabled={disabled}
      />
    </div>
  )
}

/** textarea 안에서 [start, end) 구간을 선택한 뒤 mouseup을 흉내낸다. */
function selectRange(textarea: HTMLTextAreaElement, start: number, end: number): void {
  textarea.focus()
  textarea.setSelectionRange(start, end)
  fireEvent.mouseUp(textarea)
}

async function advance(ms: number): Promise<void> {
  await act(async () => {
    await vi.advanceTimersByTimeAsync(ms)
  })
}

beforeEach(() => {
  vi.useFakeTimers()
  vi.mocked(lookupTerm).mockReset()
})

afterEach(() => {
  vi.useRealTimers()
})

describe('선택 트리거', () => {
  it('선택 후 250ms 뒤 정확히 한 번 조회하고 팝업을 띄운다', async () => {
    vi.mocked(lookupTerm).mockResolvedValue({
      query: '구비서류',
      candidates: [APPLICABLE_CANDIDATE],
      dictionary: DICTIONARY,
    })
    render(<Harness initialValue="구비서류가 필요합니다" segmented={false} />)
    const textarea = screen.getByLabelText('단위 0') as HTMLTextAreaElement

    selectRange(textarea, 0, 4) // "구비서류"
    expect(lookupTerm).not.toHaveBeenCalled()

    await advance(250)

    expect(lookupTerm).toHaveBeenCalledTimes(1)
    expect(lookupTerm).toHaveBeenCalledWith('구비서류', expect.anything())
    expect(screen.getByRole('dialog', { name: '쉬운 말 후보' })).toBeInTheDocument()
  })

  it('디바운스 도중 다시 선택하면 마지막 선택 하나만 조회한다', async () => {
    vi.mocked(lookupTerm).mockResolvedValue({
      query: '전체',
      candidates: [],
      dictionary: DICTIONARY,
    })
    render(<Harness initialValue="가나다라마바사" segmented={false} />)
    const textarea = screen.getByLabelText('단위 0') as HTMLTextAreaElement

    selectRange(textarea, 0, 2) // "가나"
    await advance(100)
    selectRange(textarea, 2, 4) // "다라" — 아직 250ms가 지나기 전에 재선택
    await advance(250)

    expect(lookupTerm).toHaveBeenCalledTimes(1)
    expect(lookupTerm).toHaveBeenCalledWith('다라', expect.anything())
  })

  it('빈 텍스트나 100자를 넘는 선택은 조회를 걸지 않는다', async () => {
    render(<Harness initialValue={'가'.repeat(101)} segmented={false} />)
    const textarea = screen.getByLabelText('단위 0') as HTMLTextAreaElement

    selectRange(textarea, 0, 101) // 101자
    await advance(250)
    expect(lookupTerm).not.toHaveBeenCalled()

    selectRange(textarea, 0, 0) // 빈 선택
    await advance(250)
    expect(lookupTerm).not.toHaveBeenCalled()
  })

  it('이미 도는 요청은 새 선택이 들어오면 취소한다', async () => {
    let firstSignal: AbortSignal | undefined
    vi.mocked(lookupTerm).mockImplementation((text, signal) => {
      if (text === '가나') {
        firstSignal = signal
        return new Promise(() => undefined) // 절대 끝나지 않는 진행 중 요청
      }
      return Promise.resolve({ query: text, candidates: [], dictionary: DICTIONARY })
    })
    render(<Harness initialValue="가나다라마바사아" segmented={false} />)
    const textarea = screen.getByLabelText('단위 0') as HTMLTextAreaElement

    selectRange(textarea, 0, 2) // "가나"
    await advance(250)
    expect(firstSignal?.aborted).toBe(false)

    selectRange(textarea, 2, 4) // "다라"
    await advance(250)

    expect(firstSignal?.aborted).toBe(true)
  })
})

describe('팝업 내용', () => {
  it('후보와 사전 출처를 보여준다', async () => {
    vi.mocked(lookupTerm).mockResolvedValue({
      query: '구비서류',
      candidates: [APPLICABLE_CANDIDATE],
      dictionary: DICTIONARY,
    })
    render(<Harness initialValue="구비서류가 필요합니다" segmented={false} />)
    selectRange(screen.getByLabelText('단위 0') as HTMLTextAreaElement, 0, 4)
    await advance(250)

    expect(screen.getByText('준비할 서류')).toBeInTheDocument()
    expect(screen.getByText('신청할 때 미리 갖춰야 하는 서류')).toBeInTheDocument()
    expect(screen.getByText(`${DICTIONARY.name} · ${DICTIONARY.license}`)).toBeInTheDocument()
  })

  it('applicable이 아니면 바꾸기 버튼이 없다', async () => {
    vi.mocked(lookupTerm).mockResolvedValue({
      query: '과태료',
      candidates: [GLOSS_CANDIDATE],
      dictionary: DICTIONARY,
    })
    render(<Harness initialValue="과태료를 내야 합니다" segmented={false} />)
    selectRange(screen.getByLabelText('단위 0') as HTMLTextAreaElement, 0, 3)
    await advance(250)

    expect(screen.getByText('과태료')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '바꾸기' })).not.toBeInTheDocument()
    // 위험도 높은 주의는 화면에 그대로 보인다.
    expect(screen.getByText(/벌금과 다르다/)).toBeInTheDocument()
  })

  it('후보가 0건이면 사전에 없는 말이라고 말한다', async () => {
    vi.mocked(lookupTerm).mockResolvedValue({
      query: '게시판',
      candidates: [],
      dictionary: DICTIONARY,
    })
    render(<Harness initialValue="게시판을 확인하세요" segmented={false} />)
    selectRange(screen.getByLabelText('단위 0') as HTMLTextAreaElement, 0, 3)
    await advance(250)

    expect(screen.getByText('사전에 없는 말입니다')).toBeInTheDocument()
  })
})

describe('바꾸기 적용', () => {
  it('단위 편집기에서 그 단위의 선택만 바꾸고 다른 단위는 그대로 둔다', async () => {
    vi.mocked(lookupTerm).mockResolvedValue({
      query: '구비서류',
      candidates: [APPLICABLE_CANDIDATE],
      dictionary: DICTIONARY,
    })
    render(<Harness initialValue={'구비서류를 내세요\n다음 문단은 그대로'} segmented={true} />)
    const unit0 = screen.getByLabelText('단위 0') as HTMLTextAreaElement

    selectRange(unit0, 0, 4) // "구비서류"
    await advance(250)
    screen.getByRole('dialog', { name: '쉬운 말 후보' })

    fireEvent.click(screen.getByRole('button', { name: '바꾸기' }))

    // 단위 수(줄 수)는 그대로이고, 첫 단위만 바뀌었다.
    expect(screen.getByLabelText('단위 0')).toHaveValue('준비할 서류를 내세요')
    expect(screen.getByLabelText('단위 1')).toHaveValue('다음 문단은 그대로')
  })

  it('단일 textarea 폴백에서도 선택만 바꾼다', async () => {
    vi.mocked(lookupTerm).mockResolvedValue({
      query: '구비서류',
      candidates: [APPLICABLE_CANDIDATE],
      dictionary: DICTIONARY,
    })
    render(<Harness initialValue="구비서류를 내세요" segmented={false} />)
    const textarea = screen.getByLabelText('단위 0') as HTMLTextAreaElement

    selectRange(textarea, 0, 4)
    await advance(250)
    screen.getByRole('dialog', { name: '쉬운 말 후보' })

    fireEvent.click(screen.getByRole('button', { name: '바꾸기' }))

    expect(textarea).toHaveValue('준비할 서류를 내세요')
  })
})

describe('오류·제한 처리', () => {
  it('422(조회 꺼짐)는 한 번만 보여주고 이후 선택에서는 다시 조회하지 않는다', async () => {
    vi.mocked(lookupTerm).mockRejectedValue(new ApiError(422, '사전 조회가 꺼져 있습니다'))
    render(<Harness initialValue="가나다라마바사" segmented={false} />)
    const textarea = screen.getByLabelText('단위 0') as HTMLTextAreaElement

    selectRange(textarea, 0, 2)
    await advance(250)
    expect(screen.getByText('사전 조회가 꺼져 있습니다')).toBeInTheDocument()
    expect(lookupTerm).toHaveBeenCalledTimes(1)

    selectRange(textarea, 2, 4)
    await advance(250)

    // 세션 동안 다시 서버에 묻지 않는다 — 그래도 문구는 그대로 보여준다.
    expect(lookupTerm).toHaveBeenCalledTimes(1)
    expect(screen.getByText('사전 조회가 꺼져 있습니다')).toBeInTheDocument()
  })

  it('429는 Retry-After 초를 보여주고 초마다 줄어든다', async () => {
    vi.mocked(lookupTerm).mockRejectedValue(new ApiError(429, '잠시 후 다시 시도해주세요', 5))
    render(<Harness initialValue="가나다라마바사" segmented={false} />)
    const textarea = screen.getByLabelText('단위 0') as HTMLTextAreaElement

    selectRange(textarea, 0, 2)
    await advance(250)

    expect(screen.getByText(/\(5초\)/)).toBeInTheDocument()

    await advance(1000)
    expect(screen.getByText(/\(4초\)/)).toBeInTheDocument()
  })

  it('네트워크 오류는 조용한 안내만 보여준다', async () => {
    vi.mocked(lookupTerm).mockRejectedValue(new ApiError(0, '서버에 연결하지 못했습니다.'))
    render(<Harness initialValue="가나다라마바사" segmented={false} />)
    const textarea = screen.getByLabelText('단위 0') as HTMLTextAreaElement

    selectRange(textarea, 0, 2)
    await advance(250)

    expect(screen.getByText('사전 조회에 실패했습니다.')).toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })
})

describe('닫기', () => {
  it('Esc로 닫으면 초점이 textarea로 돌아온다', async () => {
    vi.mocked(lookupTerm).mockResolvedValue({
      query: '구비서류',
      candidates: [APPLICABLE_CANDIDATE],
      dictionary: DICTIONARY,
    })
    render(<Harness initialValue="구비서류를 내세요" segmented={false} />)
    const textarea = screen.getByLabelText('단위 0') as HTMLTextAreaElement

    selectRange(textarea, 0, 4)
    await advance(250)
    screen.getByRole('dialog', { name: '쉬운 말 후보' })

    fireEvent.keyDown(document, { key: 'Escape' })

    expect(screen.queryByRole('dialog', { name: '쉬운 말 후보' })).not.toBeInTheDocument()
    expect(textarea).toHaveFocus()
  })
})
