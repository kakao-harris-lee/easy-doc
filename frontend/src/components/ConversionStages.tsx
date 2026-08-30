import { Check, Circle, Dot } from 'lucide-react'

import { cn } from '../lib/utils'

/**
 * 이 표시가 다룰 수 있는 상태. `done`·`failed`는 진행 표시가 아니라 다른 화면이 맡고,
 * `null`은 첫 조회 응답이 아직 오지 않은 순간이다.
 */
export type StageStatus = 'pending' | 'processing' | null

/**
 * 한 단계가 화면에서 가질 수 있는 상태.
 *
 * `done`은 **서버 응답이 근거를 줄 때만** 쓴다. `current`는 지금 진행 중, `next`는 바로
 * 다음에 올 단계, `waiting`은 아직 이르지 않은 단계다.
 */
type StageState = 'done' | 'current' | 'next' | 'waiting'

interface Stage {
  label: string
  hint: string
}

/** DESIGN.md §6.3이 요구하는 네 단계. 순서가 곧 사용자가 이해하는 작업 순서다. */
const STAGES: readonly Stage[] = [
  { label: '문서 접수', hint: '문서를 받아 변환 차례에 넣습니다.' },
  { label: '개인정보 확인', hint: '주민등록번호와 카드번호를 가립니다.' },
  { label: '쉬운 글 변환', hint: '쉬운 문장으로 다시 씁니다.' },
  { label: '검수 준비', hint: '고칠 수 있는 검수 화면을 엽니다.' },
]

/** 상태 이름. 색만으로 상태를 알리지 않기 위해 문구를 항상 함께 낸다(§8.1). */
const STATE_TEXT: Record<StageState, string> = {
  done: '완료',
  current: '진행 중',
  next: '다음 단계',
  waiting: '대기',
}

/**
 * 서버 상태 → 네 단계의 표시 상태.
 *
 * ## 왜 진행률처럼 채우지 않는가 (되돌리지 마라)
 *
 * 서버가 주는 상태는 `pending`·`processing`·`done`·`failed` 넷뿐이다(계약
 * `ConversionStatus`). 워커는 마스킹과 LLM 변환을 **하나의 작업**으로 처리하고 그 안의
 * 어느 지점인지는 응답에 싣지 않는다. 그래서 "개인정보 확인 완료 → 쉬운 글 변환 중"
 * 같은 표시는 서버가 알려준 적 없는 사실을 화면이 지어내는 것이다. 사용자가 그 표시를
 * 믿고 "개인정보는 이미 처리됐구나"라고 판단하면 제품이 거짓말을 한 셈이 된다.
 *
 * 그래서 이 함수가 `done`을 주는 근거는 딱 하나다.
 *
 * - `문서 접수`: 변환 조회가 응답했다는 것은 서버에 이 변환 레코드가 있다는 뜻이므로
 *   접수는 실제로 끝났다. 첫 응답 전(`null`)에는 그것조차 모르므로 `current`에 둔다.
 *
 * `processing`에서 `개인정보 확인`과 `쉬운 글 변환`을 **둘 다** `current`로 두는 것도
 * 같은 이유다. 서버는 "일이 돌고 있다"까지만 알려주고 둘 중 어디인지는 말해주지 않는다.
 * 앞의 것을 `done`으로 찍으면 근거 없는 완료 선언이고, 뒤의 것만 `current`로 찍으면
 * 근거 없는 진행 선언이다. 아는 만큼만 말하려면 둘을 한 덩어리로 두어야 한다.
 *
 * `검수 준비`는 이 화면에서 절대 `done`이 되지 않는다 — `done` 상태가 되는 순간
 * 페이지가 검수 에디터로 바뀌기 때문이다.
 *
 * 진행률 막대나 퍼센트를 다시 넣고 싶다면, 먼저 서버가 내부 단계를 응답에 실어야 한다
 * (계약 변경). 계약이 그대로인 채로 화면만 채우는 변경은 되돌려야 할 변경이다.
 */
function stageStates(status: StageStatus): StageState[] {
  if (status === 'processing') {
    return ['done', 'current', 'current', 'next']
  }
  if (status === 'pending') {
    return ['done', 'next', 'waiting', 'waiting']
  }
  // 첫 응답 전 — 접수됐는지조차 아직 확인하지 못했다.
  return ['current', 'next', 'waiting', 'waiting']
}

/** 상태별 표식. 반복 모션은 화면 전체에서 하나만 쓰므로(§12) 여기서는 돌리지 않는다. */
const MARKER_CLASS: Record<StageState, string> = {
  done: 'border-success bg-success text-success-foreground',
  current: 'border-primary bg-accent text-primary',
  next: 'border-input bg-card text-muted-foreground',
  waiting: 'border-border bg-muted text-muted-foreground',
}

const LABEL_CLASS: Record<StageState, string> = {
  done: 'text-foreground',
  current: 'text-foreground',
  next: 'text-foreground',
  waiting: 'text-muted-foreground',
}

const STATE_TEXT_CLASS: Record<StageState, string> = {
  done: 'text-success',
  current: 'text-primary',
  next: 'text-muted-foreground',
  waiting: 'text-muted-foreground',
}

/**
 * 변환 단계 표시(§6.3).
 *
 * 목록 자체는 live region이 아니다 — 상태 변화는 이 화면의 단 하나뿐인 `role="status"`
 * 문장이 알린다. 목록까지 live로 두면 같은 사실을 두 번 낭독한다(§11).
 */
export function ConversionStages({ status }: { status: StageStatus }) {
  const states = stageStates(status)
  return (
    <ol aria-label="변환 단계" className="m-0 flex list-none flex-col gap-3 p-0">
      {STAGES.map((stage, index) => {
        const state = states[index] ?? 'waiting'
        return (
          <li key={stage.label} className="flex items-start gap-3">
            <span
              aria-hidden="true"
              className={cn(
                'mt-0.5 flex size-6 shrink-0 items-center justify-center rounded-full border',
                MARKER_CLASS[state],
              )}
            >
              {state === 'done' ? (
                <Check className="size-3.5" />
              ) : state === 'current' ? (
                <Circle className="size-2.5 fill-current" />
              ) : (
                <Dot className="size-4" />
              )}
            </span>
            <span className="min-w-0">
              <span className="flex flex-wrap items-baseline gap-x-2">
                <span className={cn('text-[15px] font-semibold', LABEL_CLASS[state])}>
                  {stage.label}
                </span>
                <span className={cn('text-sm font-medium', STATE_TEXT_CLASS[state])}>
                  {STATE_TEXT[state]}
                </span>
              </span>
              <span className="mt-0.5 block text-sm leading-[22px] text-muted-foreground">
                {stage.hint}
              </span>
            </span>
          </li>
        )
      })}
    </ol>
  )
}
