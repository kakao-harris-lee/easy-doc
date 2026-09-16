import { useId } from 'react'

import { cn } from '../lib/utils'

const STEPS = [
  { title: '가입', detail: '계정을 만듭니다.' },
  { title: '로그인', detail: '가입한 계정으로 접속합니다.' },
  { title: '휴대폰 인증 요청', detail: '인증번호를 받아 확인합니다.' },
  { title: '체험 5크레딧 발급', detail: '샘플 변환을 시작합니다.' },
] as const

/** 가입 전에도 무료 체험을 받는 전체 순서를 한눈에 보여준다. */
export function TrialCreditSteps({ className }: { className?: string }) {
  const headingId = useId()

  return (
    <section
      className={cn('rounded-[12px] border border-border bg-accent/40 p-4', className)}
      aria-labelledby={headingId}
    >
      <h2 id={headingId} className="text-sm font-bold text-accent-foreground">
        무료 체험 시작 순서
      </h2>
      <p className="mt-1 text-xs leading-5 text-muted-foreground">
        가입·로그인 후 휴대폰 인증을 완료하면 샘플 변환용 5크레딧을 한 번 드립니다.
      </p>
      <ol className="mt-3 grid grid-cols-2 gap-2">
        {STEPS.map((step, index) => (
          <li className="flex items-start gap-2" key={step.title}>
            <span
              className="flex size-6 shrink-0 items-center justify-center rounded-full bg-primary text-xs font-bold text-primary-foreground"
              aria-hidden="true"
            >
              {index + 1}
            </span>
            <span className="min-w-0">
              <span className="block text-xs font-semibold text-foreground">{step.title}</span>
              <span className="block text-xs leading-5 text-muted-foreground">{step.detail}</span>
            </span>
          </li>
        ))}
      </ol>
      <p className="mt-3 text-xs leading-5 text-muted-foreground">
        이메일로 가입한 경우 휴대폰 인증 전에 이메일 인증을 먼저 완료해 주세요.
      </p>
    </section>
  )
}
