import { Link } from 'react-router-dom'
import type { LucideIcon } from 'lucide-react'

import { Button } from './ui/Button'

/** 대표 행동의 공통 부분. 아이콘은 장식이므로 낭독기에서 숨긴다. */
interface PageHeaderActionBase {
  /** 버튼·링크에 보이는 문구. 접근 가능한 이름이기도 하다. */
  label: string
  icon?: LucideIcon
}

/**
 * 페이지의 대표 행동.
 *
 * 이동(`to`)이거나 실행(`onClick`)이며 둘을 겸하지 않는다 — 링크와 버튼은 키보드 조작과
 * 낭독기 안내가 다르므로 어느 쪽인지를 화면이 아니라 타입에서 정한다.
 */
export type PageHeaderAction =
  | (PageHeaderActionBase & { to: string; onClick?: never })
  | (PageHeaderActionBase & { onClick: () => void; to?: never })

export interface PageHeaderProps {
  /** 짧은 맥락 라벨. 예: `복지정책팀 · 변환 기록` */
  context: string
  /** 사용자가 여기서 할 일. 이 화면의 `h1`이 된다. */
  title: string
  /** 한 줄 설명. */
  description: string
  /** 제목의 id. 바깥 `section`이 `aria-labelledby`로 가리킬 때만 넘긴다. */
  titleId?: string
  /**
   * 대표 행동. 없거나 하나다.
   *
   * 배열을 받지 않는 이유: 헤더에 행동을 여러 개 늘어놓으면 "지금 할 일 하나"라는
   * 위계가 무너진다(DESIGN.md §5.3). 두 번째 행동이 필요하면 그것은 헤더가 아니라
   * 본문 카드에 속한다.
   */
  action?: PageHeaderAction
}

/** 대표 행동이 링크일 때 쓰는 모양. `Button`의 primary와 같은 시각 언어를 맞춘다. */
const ACTION_LINK_CLASS =
  'inline-flex h-11 w-full items-center justify-center gap-2 rounded-md bg-primary px-4 text-[15px] font-semibold text-primary-foreground no-underline transition-colors hover:bg-primary-hover focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-ring sm:w-auto'

/**
 * 업무 화면의 첫머리. 맥락 라벨 → 제목 → 한 줄 설명 → 대표 행동 순서를 강제한다
 * (DESIGN.md §5.3).
 *
 * 순서를 컴포넌트로 굳혀 두는 이유: 화면마다 제목 크기와 배치를 다시 정하면 같은 앱이
 * 화면마다 다른 곳에서 시작하는 것처럼 보인다. 제목을 `h1`으로 고정하는 것도 같은
 * 이유다 — 본문의 첫 제목이 화면마다 다른 단계에서 시작하면 낭독기 사용자가 목차를
 * 잃는다(머리말의 로고는 제목이 아니다).
 *
 * 아래 여백 24px은 "화면 제목과 첫 카드 사이 24px"(§5.2)을 컴포넌트가 직접 책임진 것이다.
 */
export function PageHeader({ context, title, description, titleId, action }: PageHeaderProps) {
  const Icon = action?.icon
  return (
    <header className="mb-6 flex flex-col gap-4 sm:flex-row sm:items-end sm:justify-between">
      <div className="min-w-0">
        <p className="text-sm font-semibold leading-[22px] text-accent-foreground">{context}</p>
        <h1
          id={titleId}
          className="mt-1 text-[28px] font-extrabold leading-9 tracking-tight text-foreground"
        >
          {title}
        </h1>
        <p className="mt-2 max-w-2xl text-sm leading-[22px] text-muted-foreground">{description}</p>
      </div>
      {action !== undefined && (
        <div className="shrink-0 sm:pb-1">
          {action.to !== undefined ? (
            <Link className={ACTION_LINK_CLASS} to={action.to}>
              {Icon && <Icon className="size-[18px]" aria-hidden="true" />}
              {action.label}
            </Link>
          ) : (
            <Button
              type="button"
              onClick={action.onClick}
              className="h-11 w-full px-4 text-[15px] sm:w-auto"
            >
              {Icon && <Icon className="size-[18px]" aria-hidden="true" />}
              {action.label}
            </Button>
          )}
        </div>
      )}
    </header>
  )
}
