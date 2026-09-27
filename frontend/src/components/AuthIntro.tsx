import { FileUp, PencilLine, Download, ShieldAlert } from 'lucide-react'
import type { LucideIcon } from 'lucide-react'
import type { ReactNode } from 'react'
import { Link } from 'react-router-dom'

import { DRAFT_NOTICE } from '../content/identity'
import { GUIDE_PATH } from '../routes/paths'

interface Step {
  icon: LucideIcon
  title: string
  detail: string
}

/**
 * 제품이 실제로 하는 일 세 단계.
 *
 * 현재 구현된 기능만 적는다(DESIGN.md §2) — 결제, 팀원 초대, 발행, 이메일 알림처럼
 * API에 없는 것을 여기에 적으면 가입 직후 화면이 곧바로 약속을 어긴다.
 */
const STEPS: readonly Step[] = [
  {
    icon: FileUp,
    title: '원문 올리기',
    detail: '가능하면 글을 붙여넣고, 필요하면 DOCX·PDF·HWPX 파일을 올립니다.',
  },
  {
    icon: PencilLine,
    title: '초안 확인·수정',
    detail: '쉬운 글 초안을 원문과 나란히 놓고 담당자가 직접 고칩니다.',
  },
  {
    icon: Download,
    title: '문서로 내려받기',
    detail: '검수한 글을 DOCX·TXT·HWPX로 내려받습니다.',
  },
]

/**
 * 설명 영역 제목.
 *
 * 정의 문장(두 문장)을 그대로 제목에 넣으면 낭독기가 문단 하나를 제목으로 읽고 눈으로도
 * 제목이 아니라 문단처럼 보인다. 제목은 랜딩과 같은 짧은 구로 두고 정의는 본문으로 내린다.
 */
const INTRO_TITLE = '주민 안내문을 쉬운 글 초안으로'

interface AuthIntroProps {
  /** 설명 영역 제목의 id. 화면마다 다른 값을 줘 중복되지 않게 한다. */
  headingId: string
  /**
   * 서비스 정의 정본 문장(두 문장, DESIGN.md §6.0). 제목이 아니라 제목 아래 본문으로 쓴다.
   * 모바일에서는 이 문장과 초안 고지만 남는다.
   */
  summary: string
  /** 화면별로 왼쪽 설명 영역에 덧붙일 안내. */
  children?: ReactNode
}

/**
 * 로그인·가입 화면 왼쪽의 설명 영역(DESIGN.md §6.1).
 *
 * 제목은 짧은 구 하나이고 서비스 정의 두 문장은 그 아래 본문으로 둔다 — 제목 자리에 정의를
 * 넣으면 낭독기가 두 문장짜리 제목을 알린다. 장식용 대시보드 목업 대신 실제 흐름을 적는다.
 * 모바일에서는 제목·정의 문장·초안 고지를 남기고 공통 제품 단계 목록을
 * 감춘다 — 폼이 먼저 보여야 하는 화면에서 설명이 스크롤을 잡아먹지 않게 한다. 다만
 * 초안 고지 한 줄은 모바일에도 남긴다 — 한 문장이라 스크롤 비용이 거의 없고, 결과가
 * 초안이라는 사실은 화면 크기와 무관하게 가입 전에 읽혀야 한다. 화면별 추가 안내는
 * 폼 다음에 필요한 내용만 이어 붙인다.
 *
 * 두 인증 화면이 같은 문구를 두 벌 갖지 않도록 컴포넌트로 뺐다. 흐름 설명이 어긋나면
 * 제품이 서로 다른 약속을 하는 셈이 된다.
 */
export function AuthIntro({ headingId, summary, children }: AuthIntroProps) {
  return (
    <aside className="lg:order-1" aria-labelledby={headingId}>
      <h2
        id={headingId}
        className="text-xl font-bold leading-7 text-foreground lg:text-[28px] lg:font-extrabold lg:leading-9 lg:tracking-tight"
      >
        {INTRO_TITLE}
      </h2>
      <p className="mt-3 text-[15px] leading-6 text-muted-foreground lg:text-base lg:leading-7">
        {summary}
      </p>
      <ol className="mt-8 hidden flex-col gap-5 md:flex">
        {STEPS.map((step, index) => (
          <li className="flex items-start gap-4" key={step.title}>
            <span
              className="flex size-10 shrink-0 items-center justify-center rounded-[10px] bg-accent text-accent-foreground"
              aria-hidden="true"
            >
              <step.icon className="size-[18px]" />
            </span>
            <div className="min-w-0">
              <p className="text-[15px] font-semibold text-foreground">
                {index + 1}. {step.title}
              </p>
              <p className="mt-1 text-sm leading-[22px] text-muted-foreground">{step.detail}</p>
            </div>
          </li>
        ))}
      </ol>
      {children}
      <p
        role="note"
        className="mt-6 flex items-start gap-2 rounded-[10px] border border-warning/25 bg-warning-surface px-3 py-2.5 text-sm font-semibold leading-[22px] text-warning"
      >
        <ShieldAlert className="mt-0.5 size-4 shrink-0" aria-hidden="true" />
        {DRAFT_NOTICE}
      </p>
      <p className="mt-3 text-sm text-muted-foreground">
        <Link to={GUIDE_PATH}>이용 가이드 보기</Link>
      </p>
    </aside>
  )
}
