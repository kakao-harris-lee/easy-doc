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
      className={cn('rounded-xl border border-border bg-accent/40 p-5', className)}
      aria-labelledby={headingId}
    >
      <h2 id={headingId} className="text-base font-bold text-accent-foreground">
        무료 체험 시작 순서
      </h2>
      <p className="mt-1 text-sm leading-6 text-muted-foreground">
        가입·로그인 후 휴대폰 인증을 완료하면 샘플 변환용 5크레딧을 한 번 드립니다.
      </p>
      <ol className="mt-4 grid grid-cols-1 gap-x-4 gap-y-3 sm:grid-cols-2">
        {STEPS.map((step, index) => (
          <li className="flex min-w-0 items-start gap-3" key={step.title}>
            <span
              className="flex size-7 shrink-0 items-center justify-center rounded-full bg-primary text-sm font-bold text-primary-foreground"
              aria-hidden="true"
            >
              {index + 1}
            </span>
            <span className="min-w-0">
              <span className="block break-keep text-sm font-semibold text-foreground">
                {step.title}
              </span>
              <span className="block break-keep text-sm leading-6 text-muted-foreground">
                {step.detail}
              </span>
            </span>
          </li>
        ))}
      </ol>
      <p className="mt-4 text-sm leading-6 text-muted-foreground">
        이메일로 가입했거나 소셜 제공자가 이메일을 확인해 주지 않은 경우, 휴대폰 인증 전에 메일로
        받은 인증 코드를 먼저 입력해 주세요.
      </p>
    </section>
  )
}
