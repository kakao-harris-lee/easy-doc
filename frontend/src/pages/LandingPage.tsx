import { ArrowRight, FileCheck2, ShieldAlert } from 'lucide-react'
import { Link } from 'react-router-dom'

import { Badge } from '../components/ui/Badge'
import { DRAFT_NOTICE, SERVICE_DEFINITION } from '../content/identity'
import { GUIDE_PATH, SIGNUP_PATH } from '../routes/paths'

const PRIMARY_LINK =
  'inline-flex min-h-12 items-center justify-center gap-2 rounded-[10px] bg-primary px-5 text-[15px] font-semibold text-primary-foreground transition-colors hover:bg-primary-hover'
const SECONDARY_LINK =
  'inline-flex min-h-12 items-center justify-center rounded-[10px] border border-input bg-card px-5 text-[15px] font-semibold text-foreground transition-colors hover:bg-secondary'

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

/**
 * 로그인 전 첫 화면.
 *
 * 기능 목록보다 정체성이 먼저다 — 독자(누구를 위한 글인가) · 입력(어떤 문서를 넣는가) ·
 * 초안(무엇이 나오는가) 세 가지 사실을 먼저 읽게 하고, 재작성 예시 한 쌍으로 끝낸다.
 * 자세한 사용법은 가이드로 보낸다.
 */
export function LandingPage() {
  return (
    <article className="flex flex-col gap-14 pb-8 pt-3 md:gap-16 md:pt-8">
      <header className="grid items-center gap-10 lg:grid-cols-[minmax(0,0.92fr)_minmax(28rem,1.08fr)] lg:gap-12">
        <div>
          <p className="inline-flex items-center gap-2 rounded-full bg-brand-surface px-3 py-1.5 text-sm font-semibold text-brand-foreground">
            <FileCheck2 className="size-4" aria-hidden="true" />
            쉬운 글로 다시 쓰는 도구
          </p>
          <h1
            aria-label="어려운 글을 누구나 읽기 쉬운 글로"
            className="mt-5 max-w-xl text-[36px] font-extrabold leading-[1.18] tracking-[-0.035em] text-foreground md:text-5xl md:leading-[1.16]"
          >
            어려운 글을
            <br />
            <span className="text-primary">누구나 읽기 쉬운 글</span>로
          </h1>
          <p className="mt-5 max-w-xl text-[17px] leading-7 text-muted-foreground md:text-lg md:leading-8">
            {SERVICE_DEFINITION}
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
          {/* 고지는 회색 보조 문장이 아니라 검수 화면과 같은 경고 블록이다 — 확인은 자연스러운
              행동이 아니라 눈에 띄는 컴포넌트가 있어야 일어난다(GOV.UK AI 패턴). */}
          <p
            role="note"
            className="mt-5 flex max-w-xl items-start gap-2 rounded-[10px] border border-warning/25 bg-warning-surface px-4 py-3 text-[15px] font-semibold leading-6 text-warning"
          >
            <ShieldAlert className="mt-0.5 size-5 shrink-0" aria-hidden="true" />
            {DRAFT_NOTICE}
          </p>
        </div>

        <div
          className="relative mx-auto w-full max-w-[640px]"
          role="img"
          aria-label="복잡한 문서가 짧고 읽기 쉬운 문장으로 바뀌는 모습"
        >
          <div className="absolute -left-3 top-10 size-24 rounded-full bg-brand-surface blur-2xl" />
          <div className="absolute -right-3 bottom-8 size-32 rounded-full bg-accent blur-2xl" />
          <img
            src="/landing-document-flow.svg"
            alt=""
            width={800}
            height={640}
            className="landing-flow-image landing-flow-image-light relative w-full"
            aria-hidden="true"
          />
          <img
            src="/landing-document-flow-dark.svg"
            alt=""
            width={800}
            height={640}
            className="landing-flow-image landing-flow-image-dark relative w-full"
            aria-hidden="true"
          />
        </div>
      </header>

      <section
        aria-labelledby="landing-fit-heading"
        className="rounded-[20px] border border-border bg-card p-6 shadow-card md:p-8"
      >
        <p className="text-sm font-semibold text-primary">이 서비스가 하는 일</p>
        <h2
          id="landing-fit-heading"
          className="mt-2 text-2xl font-extrabold leading-tight text-foreground"
        >
          일상부터 학습과 업무까지
        </h2>
        <div className="mt-6 grid gap-6 md:grid-cols-2">
          {FIT_GROUPS.map((group) => (
            <div key={group.title}>
              <h3 className="text-[15px] font-semibold text-foreground">{group.title}</h3>
              <ul className="mt-3 flex flex-wrap gap-2">
                {group.items.map((item) => (
                  <li key={item}>
                    <Badge tone={group.tone} withIcon={false}>
                      {item}
                    </Badge>
                  </li>
                ))}
              </ul>
            </div>
          ))}
        </div>
        <p className="mt-6 border-t border-border pt-4 text-sm text-muted-foreground">
          원문에 있는 내용만 쉬운 글로 바꾸는 것이 목표입니다.
        </p>
      </section>

      <section
        aria-labelledby="landing-example-heading"
        className="overflow-hidden rounded-[20px] border border-border bg-card shadow-card"
      >
        <div className="border-b border-border px-6 py-6 md:px-8">
          <p className="text-sm font-semibold text-primary">재작성 예시</p>
          <h2
            id="landing-example-heading"
            className="mt-2 text-2xl font-extrabold leading-tight text-foreground"
          >
            뜻은 그대로, 문장은 쉽게
          </h2>
          <p className="mt-2 text-sm text-muted-foreground">
            어려운 표현을 쉽게 풀어 쓴 예시입니다. 결과는 직접 확인하고 고칠 수 있습니다.
          </p>
        </div>
        <div className="grid md:grid-cols-2">
          <figure className="border-b border-border p-6 md:border-r md:border-b-0 md:p-8">
            <figcaption className="flex items-center gap-2 text-sm font-semibold text-muted-foreground">
              <span className="size-2 rounded-full bg-warning" aria-hidden="true" />
              바꾸기 전
            </figcaption>
            <p className="mt-4 text-[16px] leading-7 text-foreground">
              행사 참여를 희망하는 경우 신청 기한 내에 온라인 신청서를 제출하여야 합니다.
            </p>
          </figure>
          <figure className="bg-brand-surface/70 p-6 md:p-8">
            <figcaption className="flex items-center gap-2 text-sm font-semibold text-brand-foreground">
              <span
                className="flex size-5 items-center justify-center rounded-full bg-primary text-primary-foreground"
                aria-hidden="true"
              >
                <FileCheck2 className="size-3" />
              </span>
              쉬운 글 초안
            </figcaption>
            <p className="mt-4 text-[16px] font-medium leading-7 text-foreground">
              행사에 참여하려면 신청 기간 안에 온라인 신청서를 보내세요.
            </p>
          </figure>
        </div>
      </section>
    </article>
  )
}
