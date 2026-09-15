import { ArrowRight, FileCheck2 } from 'lucide-react'
import { Link } from 'react-router-dom'

import { GUIDE_PATH, SIGNUP_PATH } from '../routes/paths'

const PRIMARY_LINK =
  'inline-flex min-h-12 items-center justify-center gap-2 rounded-[10px] bg-primary px-5 text-[15px] font-semibold text-primary-foreground transition-colors hover:bg-primary-hover'
const SECONDARY_LINK =
  'inline-flex min-h-12 items-center justify-center rounded-[10px] border border-input bg-card px-5 text-[15px] font-semibold text-foreground transition-colors hover:bg-secondary'

/** 로그인 전에 핵심 가치와 실제 변환 예시만 보여 주는 첫 화면. 자세한 사용법은 가이드로 보낸다. */
export function LandingPage() {
  return (
    <article className="flex flex-col gap-14 pb-8 pt-3 md:gap-16 md:pt-8">
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
            긴 문장과 어려운 표현을 읽기 쉬운 우리말 초안으로 바꿉니다.
          </p>
          <div className="mt-7 flex flex-col gap-3 sm:flex-row">
            <Link to={SIGNUP_PATH} className={PRIMARY_LINK}>
              가입하고 시작하기
              <ArrowRight className="size-[18px]" aria-hidden="true" />
            </Link>
            <Link to={GUIDE_PATH} className={SECONDARY_LINK}>
              이용 가이드 보기
            </Link>
          </div>
          <p className="mt-4 text-sm text-muted-foreground">
            AI가 만든 초안이므로 담당자가 원문과 비교해 확인해 주세요.
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

      <section
        aria-labelledby="landing-example-heading"
        className="overflow-hidden rounded-[20px] border border-border bg-card shadow-card"
      >
        <div className="border-b border-border px-6 py-6 md:px-8">
          <p className="text-sm font-semibold text-primary">변환 예시</p>
          <h2
            id="landing-example-heading"
            className="mt-2 text-2xl font-extrabold leading-tight text-foreground"
          >
            뜻은 그대로, 문장은 쉽게
          </h2>
        </div>
        <div className="grid md:grid-cols-2">
          <figure className="border-b border-border p-6 md:border-r md:border-b-0 md:p-8">
            <figcaption className="flex items-center gap-2 text-sm font-semibold text-muted-foreground">
              <span className="size-2 rounded-full bg-warning" aria-hidden="true" />
              바꾸기 전
            </figcaption>
            <p className="mt-4 text-[16px] leading-7 text-foreground">
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
            <p className="mt-4 text-[16px] font-medium leading-7 text-foreground">
              기간 안에 서류를 모두 챙겨 주민센터에 가서 신청하세요.
            </p>
          </figure>
        </div>
      </section>
    </article>
  )
}
