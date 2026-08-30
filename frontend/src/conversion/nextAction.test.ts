import { describe, expect, it } from 'vitest'

import type { DocumentListItem } from '../api/types'
import { documentItem } from '../test/factories'
import { chooseNextAction } from './nextAction'
import type { NextActionKind } from './nextAction'

/** 아직 변환을 시작하지 않은 줄(목록에는 문서만 있고 변환이 없다). */
const noConversion = documentItem({
  id: 'd0',
  conversion_id: null,
  status: null,
  reviewed_at: null,
})

const pending = documentItem({
  id: 'd1',
  conversion_id: 'c1',
  status: 'pending',
  reviewed_at: null,
})
const processing = documentItem({
  id: 'd2',
  conversion_id: 'c2',
  status: 'processing',
  reviewed_at: null,
})
const unreviewed = documentItem({
  id: 'd3',
  conversion_id: 'c3',
  status: 'done',
  reviewed_at: null,
})
const reviewed = documentItem({
  id: 'd4',
  conversion_id: 'c4',
  status: 'done',
  reviewed_at: '2026-08-20T01:00:00Z',
})
const failed = documentItem({ id: 'd5', conversion_id: 'c5', status: 'failed', reviewed_at: null })

interface Case {
  name: string
  documents: DocumentListItem[]
  kind: NextActionKind
  message: string
  conversionId: string | null
}

/** §7 표의 다섯 조건. 조합이 아니라 조건 하나씩을 그대로 고정한다. */
const SINGLE_CONDITIONS: Case[] = [
  {
    name: '문서 없음',
    documents: [],
    kind: 'newConversion',
    message: '첫 문서를 쉬운 글로 바꿔 보세요',
    conversionId: null,
  },
  {
    name: 'pending',
    documents: [pending],
    kind: 'inProgress',
    message: '변환 중인 문서를 확인하세요',
    conversionId: 'c1',
  },
  {
    name: 'processing',
    documents: [processing],
    kind: 'inProgress',
    message: '변환 중인 문서를 확인하세요',
    conversionId: 'c2',
  },
  {
    name: 'done이고 미검수',
    documents: [unreviewed],
    kind: 'review',
    message: '쉬운 글 초안을 검수해 주세요',
    conversionId: 'c3',
  },
  {
    name: '검수 저장됨',
    documents: [reviewed],
    kind: 'reviewed',
    // §7 표의 「원본 형식으로 내려받을 수 있습니다」를 그대로 쓰지 않는다 — 원본 형식
    // 내보내기는 아직 없고(계약의 ExportFormat은 클라이언트가 고른다), 그 문구는 §2가
    // 금지한 미구현 기능 노출이 된다. 4단계가 끝나면 이 기대값부터 바뀌어야 한다.
    message: '검수한 내용을 파일로 내려받을 수 있습니다',
    conversionId: 'c4',
  },
  {
    name: '최근 변환 실패',
    documents: [failed],
    kind: 'failed',
    message: '원문은 그대로 두고 다시 시도해 보세요',
    conversionId: 'c5',
  },
]

/**
 * 두 조건이 동시에 맞을 때 §7이 정한 순서.
 *
 * 순서는 `미검수 완료 문서` → `진행 중` → `실패` → `새 변환`이며, `검수 저장됨`은 남은
 * 할 일이 없는 상태라 앞의 셋 뒤에 온다. 목록 순서를 일부러 뒤집어 둔 사례를 섞는다 —
 * 규칙이 아니라 "목록의 첫 줄"을 고르는 구현이면 여기서 걸린다.
 */
const PRIORITY_CASES: Case[] = [
  {
    name: '미검수 완료 + 진행 중이면 검수가 먼저다',
    documents: [processing, unreviewed],
    kind: 'review',
    message: '쉬운 글 초안을 검수해 주세요',
    conversionId: 'c3',
  },
  {
    name: '진행 중 + 실패면 진행 중이 먼저다',
    documents: [failed, processing],
    kind: 'inProgress',
    message: '변환 중인 문서를 확인하세요',
    conversionId: 'c2',
  },
  {
    name: '미검수 완료 + 실패면 검수가 먼저다',
    documents: [failed, unreviewed],
    kind: 'review',
    message: '쉬운 글 초안을 검수해 주세요',
    conversionId: 'c3',
  },
  {
    name: '실패 + 검수 저장됨이면 실패가 먼저다',
    documents: [reviewed, failed],
    kind: 'failed',
    message: '원문은 그대로 두고 다시 시도해 보세요',
    conversionId: 'c5',
  },
  {
    name: '네 조건이 모두 있으면 미검수 완료 하나만 고른다',
    documents: [reviewed, failed, processing, pending, unreviewed],
    kind: 'review',
    message: '쉬운 글 초안을 검수해 주세요',
    conversionId: 'c3',
  },
]

describe('규칙 기반 다음 할 일', () => {
  it.each(SINGLE_CONDITIONS)(
    '$name 이면 $kind 를 제안한다',
    ({ documents, kind, message, conversionId }) => {
      const action = chooseNextAction(documents)

      expect(action).not.toBeNull()
      expect(action?.kind).toBe(kind)
      expect(action?.message).toBe(message)
      expect(action?.conversionId).toBe(conversionId)
    },
  )

  it.each(PRIORITY_CASES)('$name', ({ documents, kind, message, conversionId }) => {
    const action = chooseNextAction(documents)

    expect(action?.kind).toBe(kind)
    expect(action?.message).toBe(message)
    expect(action?.conversionId).toBe(conversionId)
  })

  it('같은 조건이 여러 줄이면 목록에서 가장 앞선(가장 최근) 줄을 고른다', () => {
    // GET /documents 는 최신순이다 — 앞줄이 더 최근 문서다.
    const newer = documentItem({
      id: 'd9',
      conversion_id: 'c9',
      status: 'failed',
      title: '최근 실패',
    })
    const older = documentItem({
      id: 'd8',
      conversion_id: 'c8',
      status: 'failed',
      title: '옛 실패',
    })

    const action = chooseNextAction([newer, older])

    expect(action?.conversionId).toBe('c9')
    expect(action?.documentTitle).toBe('최근 실패')
  })

  it('conversion_id 가 null 인 줄은 후보에서 빠진다', () => {
    // 열 변환이 없으면 행동을 만들 수 없다. 상태만 보고 고르면 갈 곳 없는 제안이 나온다.
    const action = chooseNextAction([noConversion])

    expect(action?.kind).toBe('newConversion')
    expect(action?.conversionId).toBeNull()
  })

  it('conversion_id 가 없는 줄을 건너뛰고 그 다음 후보를 고른다', () => {
    const action = chooseNextAction([noConversion, failed])

    expect(action?.kind).toBe('failed')
    expect(action?.conversionId).toBe('c5')
  })

  it('목록이 비면 새 변환을 제안하지만, 목록을 못 받았으면 아무것도 제안하지 않는다', () => {
    // 빈 목록은 "문서가 없다"는 서버의 답이고, null 은 "아직 모른다"이다. §6.2 는 완료
    // 상태와 검수 여부가 확인될 때만 제안하라고 했으므로 모르는 상태에서는 침묵한다.
    expect(chooseNextAction([])?.kind).toBe('newConversion')
    expect(chooseNextAction(null)).toBeNull()
  })
})
