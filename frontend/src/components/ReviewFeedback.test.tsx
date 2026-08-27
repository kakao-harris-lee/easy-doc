import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { ApiError, saveFeedback } from '../api/client'
import type { ConversionFeedbackResponse } from '../api/types'
import { ReviewFeedback } from './ReviewFeedback'

vi.mock('../api/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../api/client')>()),
  saveFeedback: vi.fn(),
}))

beforeEach(() => {
  vi.mocked(saveFeedback).mockReset()
})

/** 저장 응답. 서버는 저장된 값을 그대로 돌려준다. */
function saved(overrides: Partial<ConversionFeedbackResponse> = {}): ConversionFeedbackResponse {
  return {
    conversion_id: 'c1',
    publish_intent: 'with_edits',
    quality_score: 4,
    minutes_spent: 25,
    comment: null,
    submitted_at: '2026-08-26T02:00:00Z',
    ...overrides,
  }
}

/** 필수 세 값을 채운다. 각 테스트가 채우는 값이 곧 보내질 payload다. */
async function fillRequired(user: ReturnType<typeof userEvent.setup>): Promise<void> {
  await user.click(screen.getByLabelText('조금 고쳐서 쓰겠다'))
  await user.click(screen.getByLabelText('4점'))
  await user.type(screen.getByLabelText('이번 건 소요 시간(분)'), '25')
}

describe('검수 피드백 폼', () => {
  it('라디오 묶음에 접근 가능한 이름이 있다', () => {
    render(<ReviewFeedback conversionId="c1" />)

    expect(
      screen.getByRole('group', { name: '이 결과를 실제로 배포할 수 있나요?' }),
    ).toBeInTheDocument()
    expect(screen.getByRole('group', { name: '품질 만족도' })).toBeInTheDocument()
  })

  /*
    라디오 묶음의 오류는 «보인다»로 끝나지 않는다. `fieldset` 의 역할은 `group` 이고
    ARIA 1.2 의 `group` 은 `aria-invalid` 를 지원 속성으로 두지 않아, 그것만 붙어 있던
    동안 낭독기 사용자는 어느 묶음이 왜 거절됐는지 들을 수 없었다. `aria-describedby`
    로 이어 둔 관계를 여기서 고정한다 — 되돌리면 이 테스트가 먼저 깨진다.
  */
  it('고르지 않은 라디오 묶음의 오류가 묶음에 프로그램적으로 연결된다', async () => {
    const user = userEvent.setup()
    render(<ReviewFeedback conversionId="c1" />)

    await user.click(screen.getByRole('button', { name: '의견 보내기' }))

    for (const [name, message] of [
      ['이 결과를 실제로 배포할 수 있나요?', '배포 의향을 골라 주세요.'],
      ['품질 만족도', '품질 만족도를 골라 주세요.'],
    ]) {
      const group = screen.getByRole('group', { name })
      const described = (group.getAttribute('aria-describedby') ?? '').split(/\s+/).filter(Boolean)
      expect(described.length).toBeGreaterThan(0)
      const texts = described.map((id) => document.getElementById(id)?.textContent ?? '')
      expect(texts.join(' ')).toContain(message)
    }
  })

  it('품질 만족도 눈금 설명은 오류 전에도 묶음에 연결돼 있다', () => {
    render(<ReviewFeedback conversionId="c1" />)

    const group = screen.getByRole('group', { name: '품질 만족도' })
    const described = (group.getAttribute('aria-describedby') ?? '').split(/\s+/).filter(Boolean)
    const texts = described.map((id) => document.getElementById(id)?.textContent ?? '')
    expect(texts.join(' ')).toContain('1점은 전혀 만족스럽지 않음')
  })

  it('문서 내용을 적지 말라는 안내를 조건 없이 보여준다', () => {
    render(<ReviewFeedback conversionId="c1" />)

    expect(screen.getByText(/문서 내용은 적지 마세요/)).toBeInTheDocument()
  })

  it('필수값을 비운 채 보내면 서버를 부르지 않고 화면에서 막는다', async () => {
    const user = userEvent.setup()
    render(<ReviewFeedback conversionId="c1" />)

    await user.click(screen.getByRole('button', { name: '의견 보내기' }))

    expect(await screen.findByRole('alert')).toHaveTextContent(
      '배포 의향, 품질 만족도, 이번 건 소요 시간(0~600분의 정수)을 모두 채운 뒤 보내 주세요.',
    )
    expect(vi.mocked(saveFeedback)).not.toHaveBeenCalled()
    // 무엇이 비었는지 입력 칸에도 드러난다.
    expect(screen.getByRole('group', { name: '품질 만족도' })).toHaveAttribute(
      'aria-invalid',
      'true',
    )
  })

  it('소요 시간이 상한을 넘으면 서버를 부르지 않는다', async () => {
    const user = userEvent.setup()
    render(<ReviewFeedback conversionId="c1" />)

    await user.click(screen.getByLabelText('그대로 쓸 수 있다'))
    await user.click(screen.getByLabelText('5점'))
    await user.type(screen.getByLabelText('이번 건 소요 시간(분)'), '601')
    await user.click(screen.getByRole('button', { name: '의견 보내기' }))

    expect(vi.mocked(saveFeedback)).not.toHaveBeenCalled()
    expect(screen.getByLabelText('이번 건 소요 시간(분)')).toHaveAttribute('aria-invalid', 'true')
  })

  it('세 값을 채우면 계약 그대로의 payload로 보낸다', async () => {
    const user = userEvent.setup()
    vi.mocked(saveFeedback).mockResolvedValue(saved())
    render(<ReviewFeedback conversionId="c1" />)

    await fillRequired(user)
    await user.click(screen.getByRole('button', { name: '의견 보내기' }))

    expect(vi.mocked(saveFeedback)).toHaveBeenCalledWith('c1', {
      publish_intent: 'with_edits',
      quality_score: 4,
      minutes_spent: 25,
      // 적지 않은 의견은 빈 문자열이 아니라 null이다.
      comment: null,
    })
  })

  it('적은 의견을 함께 보낸다', async () => {
    const user = userEvent.setup()
    vi.mocked(saveFeedback).mockResolvedValue(saved({ comment: '문장이 짧아 읽기 좋았습니다.' }))
    render(<ReviewFeedback conversionId="c1" />)

    await fillRequired(user)
    await user.type(screen.getByLabelText('의견 (선택)'), '문장이 짧아 읽기 좋았습니다.')
    await user.click(screen.getByRole('button', { name: '의견 보내기' }))

    expect(vi.mocked(saveFeedback)).toHaveBeenCalledWith('c1', {
      publish_intent: 'with_edits',
      quality_score: 4,
      minutes_spent: 25,
      comment: '문장이 짧아 읽기 좋았습니다.',
    })
  })

  it('보내고 나면 성공을 알린다', async () => {
    const user = userEvent.setup()
    vi.mocked(saveFeedback).mockResolvedValue(saved())
    render(<ReviewFeedback conversionId="c1" />)

    await fillRequired(user)
    await user.click(screen.getByRole('button', { name: '의견 보내기' }))

    expect(await screen.findByRole('status')).toHaveTextContent('의견을 보냈습니다. 감사합니다.')
  })

  it('서버가 거절하면 그 사유를 보여준다', async () => {
    const user = userEvent.setup()
    vi.mocked(saveFeedback).mockRejectedValue(new ApiError(409, '아직 완료되지 않은 변환입니다'))
    render(<ReviewFeedback conversionId="c1" />)

    await fillRequired(user)
    await user.click(screen.getByRole('button', { name: '의견 보내기' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('아직 완료되지 않은 변환입니다')
    // 다시 보낼 수 있도록 적은 값은 그대로 남는다.
    expect(screen.getByLabelText('이번 건 소요 시간(분)')).toHaveValue(25)
  })
})
