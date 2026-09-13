import {
  ArrowRight,
  BookOpenCheck,
  Download,
  FileCheck2,
  FileUp,
  PencilLine,
  RefreshCcw,
  SearchCheck,
} from 'lucide-react'
import type { LucideIcon } from 'lucide-react'
import { Link } from 'react-router-dom'

import { GUIDE_PATH, LOGIN_PATH, SIGNUP_PATH } from '../routes/paths'

const STEPS: readonly {
  icon: LucideIcon
  title: string
  detail: string
}[] = [
  {
    icon: FileUp,
    title: '문서를 올려요',
    detail: '글을 붙여넣거나 DOCX·HWPX·PDF 파일을 올립니다.',
  },
  {
    icon: PencilLine,
    title: '쉬운 글로 바꿔요',
    detail: '어려운 말과 긴 문장을 이해하기 쉬운 표현으로 바꿉니다.',
  },
  {
    icon: Download,
    title: '확인하고 내려받아요',
    detail: '원문과 비교해 고친 뒤 DOCX·TXT·HWPX로 저장합니다.',
  },
]

const REVIEW_FEATURES: readonly {
  icon: LucideIcon
  title: string
  detail: string
}[] = [
  {
    icon: SearchCheck,
    title: '사실관계 확인',
    detail: '날짜·금액·대상·조건이 원문과 맞는지 살핍니다.',
  },
  {
    icon: RefreshCcw,
    title: '문단별 다시 쓰기',
    detail: '아쉬운 문단만 골라 다시 바꿀 수 있습니다.',
  },
  {
    icon: BookOpenCheck,
    title: '쉬운 낱말 찾기',
    detail: '어려운 낱말을 선택하면 쉬운 말 후보를 보여 줍니다.',
  },
]

const PRIMARY_LINK =
  'inline-flex min-h-12 items-center justify-center gap-2 rounded-[10px] bg-primary px-5 text-[15px] font-semibold text-primary-foreground transition-colors hover:bg-primary-hover'
const SECONDARY_LINK =
  'inline-flex min-h-12 items-center justify-center rounded-[10px] border border-input bg-card px-5 text-[15px] font-semibold text-foreground transition-colors hover:bg-secondary'

/** 로그인 전에 서비스의 실제 변환·검수 흐름을 짧게 보여 주는 첫 화면. */
export function LandingPage() {
  return (
    <article className="flex flex-col gap-20 pb-8 pt-3 md:gap-24 md:pt-8">
      <header className="grid items-center gap-10 lg:grid-cols-[minmax(0,0.92fr)_minmax(28rem,1.08fr)] lg:gap-12">
        <div>
          <p className="inline-flex items-center gap-2 rounded-full bg-accent px-3 py-1.5 text-sm font-semibold text-accent-foreground">
            <FileCheck2 className="size-4" aria-hidden="true" />
            공공 안내문 · 쉬운 우리말 변환
          </p>
          <h1
            aria-label="어려운 안내문을 읽히는 문서로 바꾸세요"
            className="mt-5 max-w-xl text-[36px] font-extrabold leading-[1.18] tracking-[-0.035em] text-foreground md:text-5xl md:leading-[1.16]"
          >
            어려운 안내문을
            <br />
            <span className="text-primary">읽히는 문서</span>로 바꾸세요
          </h1>
          <p className="mt-5 max-w-xl text-[17px] leading-7 text-muted-foreground md:text-lg md:leading-8">
            파일을 올리면 쉬운 글 초안을 만들고, 원문과 비교해 고친 뒤 문서로 내려받을 수 있어요.
          </p>
          <div className="mt-7 flex flex-col gap-3 sm:flex-row">
            <Link to={SIGNUP_PATH} className={PRIMARY_LINK}>
              무료로 변환 시작하기
              <ArrowRight className="size-[18px]" aria-hidden="true" />
            </Link>
            <a href="#how-it-works" className={SECONDARY_LINK}>
              이용 방식 보기
            </a>
          </div>
          <p className="mt-4 text-sm text-muted-foreground">
            지금은 파일럿 기간이라 결제 없이 이용할 수 있습니다.
          </p>
        </div>

        <div
          className="relative mx-auto w-full max-w-[640px]"
          role="img"
          aria-label="복잡한 문서가 짧고 읽기 쉬운 문장으로 바뀌는 모습"
        >
          <div className="absolute -left-3 top-10 size-24 rounded-full bg-warning-surface blur-2xl" />
          <div className="absolute -right-3 bottom-8 size-32 rounded-full bg-accent blur-2xl" />
          <img
            src="/landing-document-flow.svg"
            alt=""
            width={800}
            height={640}
            className="relative w-full"
            aria-hidden="true"
          />
        </div>
      </header>

      <section id="how-it-works" aria-labelledby="landing-steps-heading" className="scroll-mt-24">
        <div className="max-w-2xl">
          <p className="text-sm font-semibold text-primary">이용 방법</p>
          <h2
            id="landing-steps-heading"
            className="mt-2 text-[26px] font-extrabold leading-tight tracking-tight text-foreground md:text-[32px]"
          >
            파일 하나가 쉬운 안내문이 되기까지
          </h2>
        </div>
        <ol className="mt-8 grid gap-4 md:grid-cols-3">
          {STEPS.map((step, index) => (
            <li
              key={step.title}
              className="relative rounded-[16px] border border-border bg-card p-6 shadow-card"
            >
              <div className="flex items-center justify-between">
                <span className="flex size-11 items-center justify-center rounded-[12px] bg-accent text-accent-foreground">
                  <step.icon className="size-5" aria-hidden="true" />
                </span>
                <span className="text-sm font-extrabold text-primary">0{index + 1}</span>
              </div>
              <h3 className="mt-5 text-lg font-bold text-foreground">{step.title}</h3>
              <p className="mt-2 text-[15px] leading-6 text-muted-foreground">{step.detail}</p>
            </li>
          ))}
        </ol>
      </section>

      <section
        aria-labelledby="landing-example-heading"
        className="grid overflow-hidden rounded-[20px] border border-border bg-card shadow-card lg:grid-cols-[0.8fr_1.2fr]"
      >
        <div className="flex flex-col justify-center bg-primary px-6 py-9 text-primary-foreground md:px-9 lg:py-12">
          <p className="text-sm font-semibold text-primary-foreground">변환 예시</p>
          <h2 id="landing-example-heading" className="mt-2 text-2xl font-extrabold leading-tight">
            쉬운 글은 내용을 줄이는 요약이 아니에요
          </h2>
          <p className="mt-4 text-[15px] leading-6 text-primary-foreground/80">
            꼭 필요한 뜻은 남기고, 어려운 표현과 문장 구조를 쉽게 바꿉니다.
          </p>
        </div>
        <div className="grid gap-0 md:grid-cols-2">
          <figure className="border-b border-border p-6 md:border-b-0 md:border-r md:p-8">
            <figcaption className="flex items-center gap-2 text-sm font-semibold text-muted-foreground">
              <span className="size-2 rounded-full bg-warning" aria-hidden="true" />
              바꾸기 전
            </figcaption>
            <p className="mt-5 text-[16px] leading-7 text-foreground">
              신청 기한 내에 구비서류를 완비하여 관할 주민센터에 방문·접수하여야 합니다.
            </p>
          </figure>
          <figure className="bg-accent/60 p-6 md:p-8">
            <figcaption className="flex items-center gap-2 text-sm font-semibold text-accent-foreground">
              <span
                className="flex size-5 items-center justify-center rounded-full bg-primary text-primary-foreground"
                aria-hidden="true"
              >
                <FileCheck2 className="size-3" />
              </span>
              쉬운 글 초안
            </figcaption>
            <p className="mt-5 text-[16px] font-medium leading-7 text-foreground">
              기간 안에 서류를 모두 챙겨 주민센터에 가서 신청하세요.
            </p>
          </figure>
        </div>
      </section>

      <section aria-labelledby="landing-review-heading">
        <div className="max-w-2xl">
          <p className="text-sm font-semibold text-primary">초안에서 완성 문서까지</p>
          <h2
            id="landing-review-heading"
            className="mt-2 text-[26px] font-extrabold leading-tight tracking-tight text-foreground md:text-[32px]"
          >
            바꾸고 끝내지 않고, 확인하기 쉽게
          </h2>
        </div>
        <ul className="mt-8 grid gap-6 md:grid-cols-3">
          {REVIEW_FEATURES.map((feature) => (
            <li key={feature.title} className="flex gap-4">
              <span className="flex size-10 shrink-0 items-center justify-center rounded-[10px] bg-secondary text-primary">
                <feature.icon className="size-5" aria-hidden="true" />
              </span>
              <div>
                <h3 className="font-bold text-foreground">{feature.title}</h3>
                <p className="mt-1 text-sm leading-[22px] text-muted-foreground">
                  {feature.detail}
                </p>
              </div>
            </li>
          ))}
        </ul>
      </section>

      <section
        aria-labelledby="landing-cta-heading"
        className="relative overflow-hidden rounded-[20px] bg-foreground px-6 py-10 text-center md:px-10 md:py-12"
      >
        <div className="absolute -left-10 -top-16 size-44 rounded-full bg-primary/30 blur-3xl" />
        <div className="relative">
          <h2
            id="landing-cta-heading"
            className="text-2xl font-extrabold text-white md:text-[28px]"
          >
            첫 안내문을 쉬운 글로 바꿔 보세요
          </h2>
          <p className="mx-auto mt-3 max-w-xl text-[15px] leading-6 text-white/70">
            변환 결과는 AI 초안입니다. 사실관계와 신청 방법은 담당자가 확인한 뒤 사용해 주세요.
          </p>
          <div className="mt-6 flex flex-col justify-center gap-3 sm:flex-row">
            <Link to={SIGNUP_PATH} className={PRIMARY_LINK}>
              무료로 시작하기
              <ArrowRight className="size-[18px]" aria-hidden="true" />
            </Link>
            <Link
              to={LOGIN_PATH}
              className="inline-flex min-h-12 items-center justify-center rounded-[10px] border border-white/35 px-5 text-[15px] font-semibold text-white transition-colors hover:bg-white/10"
            >
              로그인
            </Link>
          </div>
          <Link
            to={GUIDE_PATH}
            className="mt-5 inline-flex min-h-11 items-center text-sm font-semibold text-white/75 underline decoration-white/35 underline-offset-4 hover:text-white"
          >
            자세한 이용 가이드 보기
          </Link>
        </div>
      </section>
    </article>
  )
}
