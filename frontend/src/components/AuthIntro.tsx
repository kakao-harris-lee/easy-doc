import { FileUp, PencilLine, Download } from 'lucide-react'
import type { LucideIcon } from 'lucide-react'

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
    detail: '붙여넣거나 DOCX·PDF·HWPX 파일을 올립니다.',
  },
  {
    icon: PencilLine,
    title: 'AI 초안 검수',
    detail: '쉬운 글 초안을 원문과 나란히 놓고 직접 고칩니다.',
  },
  {
    icon: Download,
    title: '문서로 내려받기',
    detail: '검수한 글을 DOCX·TXT·HWPX로 내려받습니다.',
  },
]

interface AuthIntroProps {
  /** 설명 영역 제목의 id. 화면마다 다른 값을 줘 중복되지 않게 한다. */
  headingId: string
  /** 제품이 하는 일 한 문장. 모바일에서는 이 문장만 남는다. */
  summary: string
}

/**
 * 로그인·가입 화면 왼쪽의 설명 영역(DESIGN.md §6.1).
 *
 * 장식용 대시보드 목업 대신 실제 흐름을 적는다. 모바일에서는 한 문장만 남기고 단계
 * 목록을 감춘다 — 폼이 먼저 보여야 하는 화면에서 설명이 스크롤을 잡아먹지 않게 한다.
 *
 * 두 인증 화면이 같은 문구를 두 벌 갖지 않도록 컴포넌트로 뺐다. 흐름 설명이 어긋나면
 * 제품이 서로 다른 약속을 하는 셈이 된다.
 */
export function AuthIntro({ headingId, summary }: AuthIntroProps) {
  return (
    <aside className="lg:order-1" aria-labelledby={headingId}>
      <h2
        id={headingId}
        className="text-xl font-bold leading-7 text-foreground lg:text-[28px] lg:font-extrabold lg:leading-9 lg:tracking-tight"
      >
        {summary}
      </h2>
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
      <p className="mt-6 hidden text-sm leading-[22px] text-muted-foreground md:block">
        변환 결과는 언제나 AI 초안입니다. 사실관계와 신청 방법은 담당자가 확인한 뒤 사용해 주세요.
      </p>
    </aside>
  )
}
