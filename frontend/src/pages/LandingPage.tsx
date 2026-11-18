import { ArrowRight, FileCheck2, ShieldAlert } from 'lucide-react'
import { Link } from 'react-router-dom'

import { DocumentExampleCards } from '../components/DocumentExampleCards'
import { EXAMPLE_NOTICE, EXAMPLES_PATH } from '../content/documentExamples'
import { COMPANY_INFO } from '../config/company'
import { DRAFT_NOTICE, SERVICE_DEFINITION } from '../content/identity'
import { TARGET_PLANS } from '../content/plans/targetPlans'
import { GUIDE_PATH, SIGNUP_PATH, TERMS_PATH, USAGE_PATH } from '../routes/paths'

const PRIMARY_LINK =
  'inline-flex min-h-12 items-center justify-center gap-2 rounded-md bg-primary px-5 text-base font-semibold text-primary-foreground transition-colors hover:bg-primary-hover active:bg-primary-active'
const SECONDARY_LINK =
  'inline-flex min-h-12 items-center justify-center rounded-md border border-input bg-card px-5 text-base font-semibold text-foreground transition-colors hover:bg-secondary active:bg-muted'

interface FitGroup {
  title: string
  /** 칩의 색. 맞는 문서는 강조색, 넣지 않는 문서는 중립색이다 — 색만으로 구분하지 않도록 제목이 함께 있다. */
  tone: 'primary' | 'neutral'
  items: readonly string[]
}

/**
 * 어떤 문서를 넣는 도구인지 보여 주는 입력 예시.
 *
 * 문단을 늘리지 않고 칩으로만 적는다 — 사용자는 랜딩을 훑어 읽는다. 구현된 범위만
 * 적는다(DESIGN.md §2): 원문을 쉬운 글 초안으로 바꾸는 것이 전부라, 원문이 없는 글
 * 쓰기나 개인정보가 든 서류는 넣지 않는 쪽에 둔다.
 */
const FIT_GROUPS: readonly FitGroup[] = [
  {
    title: '이런 문서에 맞습니다',
    tone: 'primary',
    items: ['학교 가정통신문', '서비스 이용 안내', '제품 설명', '행사 공지'],
  },
  {
    title: '이런 문서는 넣지 마세요',
    tone: 'neutral',
    items: ['개인정보가 담긴 글', '외부에 공개하지 않는 내부 문서'],
  },
]

const FULL_BLEED = 'bg-muted [box-shadow:0_0_0_100vmax_var(--muted)] [clip-path:inset(0_-100vmax)]'

const WORKFLOW_STEPS = [
  {
    title: '글을 붙여넣거나 문서 올리기',
    detail: '텍스트, DOCX, HWPX, PDF를 넣고 읽기 수준을 고릅니다.',
  },
  {
    title: '원문과 비교하며 고치기',
    detail:
      '결과를 직접 편집하고, 아쉬운 문단만 다시 변환합니다. 어려운 낱말의 쉬운 표현도 찾아볼 수 있습니다.',
  },
  {
    title: '확인한 글 내려받기',
    detail: 'DOCX·HWPX는 올린 형식으로, 붙여넣은 글·TXT·PDF는 TXT로 내려받습니다.',
  },
] as const

const START_PLAN = TARGET_PLANS.find((plan) => plan.id === 'start')

/**
 * 로그인 전 첫 화면.
 *
 * 기능 목록보다 정체성이 먼저다 — 독자(누구를 위한 글인가) · 입력(어떤 문서를 넣는가) ·
 * 초안(무엇이 나오는가)을 먼저 밝히고 체험 조건과 문서 작업 흐름을 안내한다.
 * 자세한 사용법과 형식별 제약은 가이드로 보낸다.
 */
export function LandingPage() {
  return (
    <article className="pb-8">
      <header className="grid items-center gap-10 pb-16 pt-6 md:pb-24 md:pt-12 lg:grid-cols-[minmax(0,0.92fr)_minmax(28rem,1.08fr)] lg:gap-12">
        <div>
          <p className="eyebrow inline-flex items-center gap-2">
            <FileCheck2 className="size-4" aria-hidden="true" />
            쉬운 글 변환 AI
          </p>
          <h1 aria-label="어려운 글을 누구나 읽기 쉬운 글로" className="hero-title mt-4 max-w-xl">
            어려운 글을 <span className="marker">누구나 읽기 쉬운 글</span>로
          </h1>
          <p className="lead-copy mt-6 max-w-xl text-foreground">{SERVICE_DEFINITION}</p>
          <p className="body-copy mt-3 max-w-xl text-muted-foreground">
            가정통신문부터 고객 안내문까지, 원문과 비교하며 직접 고치고 문서로 내려받으세요.
          </p>
          <div className="mt-8 flex flex-col gap-3 sm:flex-row">
            <Link to={SIGNUP_PATH} className={PRIMARY_LINK}>
              무료로 체험하기
              <ArrowRight className="size-[18px]" aria-hidden="true" />
            </Link>
            <Link to={GUIDE_PATH} className={SECONDARY_LINK}>
              이용 가이드 보기
            </Link>
          </div>
          <p className="mt-4 text-sm font-medium text-brand-foreground">
            카드 등록 없이 체험 5크레딧 · 기본 수준 기준 약 5,000자
          </p>
          <p className="mt-1 text-sm leading-6 text-muted-foreground">
            가입·휴대폰 인증 후 번호당 1회 지급하며, 지급일부터 한 달 동안 사용할 수 있습니다.
            재변환에도 크레딧이 사용됩니다.
          </p>
          <p
            role="note"
            className="notice-warning mt-6 flex max-w-xl items-start gap-2 px-4 py-3 text-[15px] font-semibold leading-6"
          >
            <ShieldAlert className="mt-0.5 size-5 shrink-0" aria-hidden="true" />
            {DRAFT_NOTICE}
          </p>
        </div>

        <div
          className="mx-auto w-full max-w-[640px]"
          role="img"
          aria-label="복잡한 문서가 짧고 읽기 쉬운 문장으로 바뀌는 모습"
        >
          <img
            src="/landing-document-flow.svg"
            alt="복잡한 문서가 짧고 읽기 쉬운 문장으로 바뀌는 모습"
            width={800}
            height={640}
            className="landing-flow-image landing-flow-image-light w-full"
            aria-hidden="true"
          />
          <img
            src="/landing-document-flow-dark.svg"
            alt="복잡한 문서가 짧고 읽기 쉬운 문장으로 바뀌는 모습"
            width={800}
            height={640}
            className="landing-flow-image landing-flow-image-dark w-full"
            aria-hidden="true"
          />
        </div>
      </header>

      <section aria-labelledby="landing-fit-heading" className="py-16 md:py-24">
        <p className="eyebrow">이 서비스가 하는 일</p>
        <h2 id="landing-fit-heading" className="section-title mt-2">
          일상부터 학습과 업무까지
        </h2>
        <div className="mt-8 grid gap-8 md:grid-cols-2 md:gap-12">
          {FIT_GROUPS.map((group) => (
            <div key={group.title}>
              <h3 className="text-lg font-bold text-foreground">{group.title}</h3>
              <ul className="mt-4 flex flex-wrap gap-2">
                {group.items.map((item) => (
                  <li
                    key={item}
                    className={`rounded-full bg-secondary px-3.5 py-1.5 text-sm font-medium ${
                      group.tone === 'primary' ? 'text-selection-foreground' : 'text-foreground'
                    }`}
                  >
                    {item}
                  </li>
                ))}
              </ul>
            </div>
          ))}
        </div>
        <p className="body-copy mt-8 text-foreground">
          원문에 있는 내용만 쉬운 글로 바꾸는 것이 목표입니다.
        </p>
      </section>

      <section aria-labelledby="landing-example-heading" className={`${FULL_BLEED} py-16 md:py-24`}>
        <p className="eyebrow">재작성 예시</p>
        <h2 id="landing-example-heading" className="section-title mt-2">
          뜻은 그대로, 문장은 쉽게
        </h2>
        <p className="body-copy mt-3 max-w-[68ch] text-muted-foreground">
          어려운 표현을 쉽게 풀어 쓴 예시입니다. 결과는 직접 확인하고 고칠 수 있습니다.
        </p>
        <div className="mt-10 grid gap-6 md:grid-cols-2 md:gap-10">
          <figure className="py-2 md:p-8">
            <figcaption className="flex items-center gap-2 text-sm font-semibold text-muted-foreground">
              <span className="size-2 rounded-full bg-warning" aria-hidden="true" />
              바꾸기 전
            </figcaption>
            <p className="mt-4 text-[17px] leading-[1.75] text-foreground">
              행사 참여를 희망하는 경우 신청 기한 내에 온라인 신청서를 제출하여야 합니다.
            </p>
          </figure>
          <figure className="rounded-xl border-l-4 border-l-primary bg-card p-6 md:p-8">
            <figcaption className="flex items-center gap-2 text-sm font-semibold text-selection-foreground">
              <span
                className="flex size-5 items-center justify-center rounded-full bg-primary text-primary-foreground"
                aria-hidden="true"
              >
                <FileCheck2 className="size-3" />
              </span>
              쉬운 글 초안
            </figcaption>
            <p className="mt-4 text-[17px] font-medium leading-[1.75] text-foreground">
              행사에 참여하려면 신청 기간 안에 온라인 신청서를 보내세요.
            </p>
          </figure>
        </div>
      </section>

      <section aria-labelledby="landing-document-examples-heading" className="py-16 md:py-24">
        <div className="mb-8">
          <h2 id="landing-document-examples-heading" className="section-title">
            문서 예시 살펴보기
          </h2>
          <p className="body-copy mt-3 max-w-[68ch] text-muted-foreground">{EXAMPLE_NOTICE}</p>
        </div>
        <DocumentExampleCards />
        <a
          href={EXAMPLES_PATH}
          className="mt-6 inline-flex min-h-11 items-center font-semibold text-primary underline underline-offset-4"
        >
          예시 모두 보기
        </a>
      </section>

      <section
        aria-labelledby="landing-workflow-heading"
        className={`${FULL_BLEED} py-16 md:py-24`}
      >
        <h2 id="landing-workflow-heading" className="section-title">
          쉬운 글 만들기부터 문서로 저장하기까지
        </h2>
        <ol className="mt-10 grid gap-10 md:grid-cols-3 md:gap-8">
          {WORKFLOW_STEPS.map((step, index) => (
            <li key={step.title}>
              <div className="flex items-center gap-3">
                <span
                  className="flex size-8 shrink-0 items-center justify-center rounded-full bg-selection font-bold text-primary"
                  aria-hidden="true"
                >
                  {index + 1}
                </span>
                <h3 className="text-lg font-bold text-foreground">{step.title}</h3>
              </div>
              <p className="body-copy mt-2 pl-11 text-muted-foreground">{step.detail}</p>
            </li>
          ))}
        </ol>
        <p className="supporting-copy mt-10 max-w-[68ch] text-muted-foreground">
          DOCX·HWPX도 줄바꿈·표·쪽 배치가 달라질 수 있습니다. 구형 HWP·DOC는 HWPX·DOCX로 저장해
          주세요. PDF는 원본 서식을 유지하지 않습니다.
        </p>
      </section>
      {START_PLAN !== undefined && (
        <section aria-labelledby="landing-start-plan-heading" className="py-16 md:py-24">
          <div className="flex flex-col gap-6 md:flex-row md:items-start md:justify-between">
            <div>
              <p className="eyebrow">월 정기결제 상품 안내</p>
              <h2 id="landing-start-plan-heading" className="section-title mt-2">
                {START_PLAN.name} 월 정기결제
              </h2>
              <p className="body-copy mt-2 text-muted-foreground">{START_PLAN.audience}</p>
            </div>
            <div className="rounded-xl bg-brand-surface px-5 py-4 md:text-right">
              <p className="text-2xl font-extrabold tabular-nums text-foreground">
                {START_PLAN.monthlyPriceLabel}
                <span className="ml-1 text-sm font-semibold text-muted-foreground">/월</span>
              </p>
              <p className="mt-1 text-sm font-semibold text-brand-foreground">
                월 {START_PLAN.monthlyCredits.toLocaleString('ko-KR')}크레딧 ·{' '}
                {START_PLAN.pageEquivalent}
              </p>
            </div>
          </div>

          <div className="mt-8 grid gap-8 md:grid-cols-2 md:gap-12">
            <div>
              <h3 className="text-lg font-bold text-foreground">제공 기능</h3>
              <ul className="mt-3 space-y-2 text-[15px] text-muted-foreground">
                {START_PLAN.features.map((feature) => (
                  <li key={feature.label} className="flex items-start gap-2">
                    <span
                      className="mt-2 size-1.5 shrink-0 rounded-full bg-primary"
                      aria-hidden="true"
                    />
                    <span>{feature.label}</span>
                  </li>
                ))}
              </ul>
            </div>
            <div>
              <h3 className="text-lg font-bold text-foreground">이용 조건</h3>
              <ul className="mt-3 space-y-2 text-[15px] leading-6 text-muted-foreground">
                <li>결제 승인일부터 한 달 동안 이용합니다.</li>
                <li>등록한 카드로 매월 자동 결제합니다.</li>
                <li>사용량 화면에서 구독 갱신 중단을 선택할 수 있습니다.</li>
              </ul>
            </div>
          </div>

          <div className="mt-8 border-t border-border pt-4 text-sm leading-6 text-muted-foreground">
            <p>
              환불 문의:{' '}
              <a
                href={`mailto:${COMPANY_INFO.supportEmail}`}
                className="font-medium text-foreground underline underline-offset-2"
              >
                {COMPANY_INFO.supportEmail}
              </a>
              <span className="mx-2" aria-hidden="true">
                ·
              </span>
              <Link
                to={TERMS_PATH}
                className="font-medium text-foreground underline underline-offset-2"
              >
                이용약관
              </Link>
              에서 환불 조건을 확인하세요.
            </p>
            <p className="mt-1 text-sm">결제 가능 여부와 이용 시작은 사용량 화면에서 확인하세요.</p>
          </div>

          <div className="mt-6 flex flex-col items-start gap-3 sm:flex-row sm:items-center">
            <Link to={USAGE_PATH} className={PRIMARY_LINK}>
              Start 구독 안내
              <ArrowRight className="size-[18px]" aria-hidden="true" />
            </Link>
            <p className="text-sm text-muted-foreground">로그인 후 확인할 수 있습니다.</p>
          </div>
        </section>
      )}
    </article>
  )
}
