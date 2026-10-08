import { ArrowRight, Check, FileCheck2, FileText, ShieldAlert, Sparkles } from 'lucide-react'
import { Link } from 'react-router-dom'

import {
  Card,
  CardContent,
  CardDescription,
  CardFooter,
  CardHeader,
  CardTitle,
} from '../components/shadcn/card'
import { buttonVariants } from '../components/shadcn/button'
import { Badge } from '../components/ui/Badge'
import { DRAFT_NOTICE, SERVICE_DEFINITION } from '../content/identity'
import { GUIDE_PATH, SIGNUP_PATH } from '../routes/paths'

const PRIMARY_LINK = `${buttonVariants({ variant: 'default', size: 'lg' })} min-h-12 w-full gap-2 px-5 text-[15px] font-semibold no-underline sm:w-auto`
const SECONDARY_LINK = `${buttonVariants({ variant: 'outline', size: 'lg' })} min-h-12 w-full px-5 text-[15px] font-semibold no-underline sm:w-auto`

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

const IMAGE_LABEL = '복잡한 문서가 짧고 읽기 쉬운 문장으로 바뀌는 모습'

/**
 * 로그인 전 첫 화면.
 *
 * 기능 목록보다 정체성이 먼저다 — 독자(누구를 위한 글인가) · 입력(어떤 문서를 넣는가) ·
 * 초안(무엇이 나오는가) 세 가지 사실을 먼저 읽게 하고, 재작성 예시 한 쌍으로 끝낸다.
 * 자세한 사용법은 가이드로 보낸다.
 */
export function LandingPage() {
  return (
    <article className="flex flex-col gap-10 pb-8 pt-2 md:gap-12 md:pt-6">
      <header className="grid items-center gap-8 lg:grid-cols-[minmax(0,0.95fr)_minmax(28rem,1.05fr)] lg:gap-10">
        <div className="min-w-0">
          <p className="inline-flex items-center gap-2 rounded-full border border-primary/20 bg-brand-surface px-3 py-1.5 text-sm font-semibold text-brand-foreground">
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
            className="mt-5 flex max-w-xl items-start gap-2 rounded-xl border border-warning/25 bg-warning-surface px-4 py-3 text-[15px] font-semibold leading-6 text-warning"
          >
            <ShieldAlert className="mt-0.5 size-5 shrink-0" aria-hidden="true" />
            {DRAFT_NOTICE}
          </p>
        </div>

        <Card size="sm" className="w-full border-border/80 shadow-card">
          <CardHeader className="border-b border-border/80 bg-background/40">
            <div className="flex items-center justify-between gap-3">
              <div className="flex min-w-0 items-center gap-2 text-xs font-semibold text-muted-foreground">
                <span className="flex size-7 shrink-0 items-center justify-center rounded-lg bg-accent text-accent-foreground">
                  <Sparkles className="size-3.5" aria-hidden="true" />
                </span>
                <span className="truncate">문서 미리보기</span>
              </div>
              <Badge tone="warning" withIcon={false} className="shrink-0 px-2 text-xs leading-5">
                초안
              </Badge>
            </div>
            <CardTitle className="mt-2">
              <h2 className="text-xl font-extrabold leading-tight tracking-tight text-foreground">
                문서가 쉬운 글이 되는 과정
              </h2>
            </CardTitle>
            <CardDescription>원문의 뜻을 살피고, 읽기 편한 문장으로 다시 씁니다.</CardDescription>
          </CardHeader>
          <CardContent className="space-y-4">
            <div className="overflow-hidden rounded-xl border border-border bg-background shadow-sm">
              <div className="flex min-h-8 items-center gap-2 border-b border-border px-3">
                <span className="flex gap-1.5" aria-hidden="true">
                  <span className="size-2 rounded-full bg-warning/70" />
                  <span className="size-2 rounded-full bg-brand/70" />
                  <span className="size-2 rounded-full bg-success/70" />
                </span>
                <span className="truncate text-xs font-medium text-muted-foreground">
                  easy-doc / 읽기 쉬운 문서
                </span>
                <FileText
                  className="ml-auto size-4 shrink-0 text-muted-foreground"
                  aria-hidden="true"
                />
              </div>
              <div
                className="bg-muted/30 px-2 py-2 sm:px-4 sm:py-3"
                role="img"
                aria-label={IMAGE_LABEL}
              >
                <img
                  src="/landing-document-flow.svg"
                  alt=""
                  width={800}
                  height={640}
                  className="landing-flow-image landing-flow-image-light mx-auto max-h-56 w-auto max-w-full rounded-lg object-contain"
                  aria-hidden="true"
                />
                <img
                  src="/landing-document-flow-dark.svg"
                  alt=""
                  width={800}
                  height={640}
                  className="landing-flow-image landing-flow-image-dark mx-auto max-h-56 w-auto max-w-full rounded-lg object-contain"
                  aria-hidden="true"
                />
              </div>
            </div>
            <div className="grid gap-3 sm:grid-cols-2">
              <div className="rounded-xl border border-border bg-card px-3.5 py-3">
                <p className="text-xs font-semibold text-muted-foreground">원문</p>
                <p className="mt-1 text-sm font-medium leading-6 text-foreground">
                  복잡한 표현이 담긴 문서
                </p>
              </div>
              <div className="rounded-xl border border-primary/25 bg-brand-surface/70 px-3.5 py-3">
                <p className="text-xs font-semibold text-brand-foreground">쉬운 글 초안</p>
                <p className="mt-1 text-sm font-medium leading-6 text-foreground">
                  짧고 분명한 문장으로 정리
                </p>
              </div>
            </div>
          </CardContent>
          <CardFooter className="justify-between gap-3 border-t border-border/80 text-xs text-muted-foreground">
            <span>결과를 직접 확인하고 고칠 수 있어요.</span>
            <Check className="size-4 shrink-0 text-success" aria-hidden="true" />
          </CardFooter>
        </Card>
      </header>

      <section aria-labelledby="landing-fit-heading" className="grid gap-4 lg:grid-cols-3">
        <Card className="lg:col-span-1">
          <CardHeader>
            <p className="text-sm font-semibold text-primary">이 서비스가 하는 일</p>
            <CardTitle>
              <h2
                id="landing-fit-heading"
                className="text-2xl font-extrabold leading-tight tracking-tight text-foreground"
              >
                일상부터 학습과 업무까지
              </h2>
            </CardTitle>
            <CardDescription>원문에 있는 내용을 읽기 쉬운 초안으로 바꿉니다.</CardDescription>
          </CardHeader>
          <CardContent className="space-y-5">
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
          </CardContent>
          <CardFooter className="border-t border-border/80 text-sm text-muted-foreground">
            원문에 있는 내용만 쉬운 글로 바꾸는 것이 목표입니다.
          </CardFooter>
        </Card>

        <Card className="lg:col-span-2">
          <CardHeader>
            <p className="text-sm font-semibold text-primary">재작성 예시</p>
            <CardTitle>
              <h2
                id="landing-example-heading"
                className="text-2xl font-extrabold leading-tight tracking-tight text-foreground"
              >
                뜻은 그대로, 문장은 쉽게
              </h2>
            </CardTitle>
            <CardDescription>
              어려운 표현을 쉽게 풀어 쓴 예시입니다. 결과는 직접 확인하고 고칠 수 있습니다.
            </CardDescription>
          </CardHeader>
          <CardContent className="grid gap-3 sm:grid-cols-2">
            <figure className="rounded-xl border border-border bg-background px-4 py-4 sm:px-5 sm:py-5">
              <figcaption className="flex items-center gap-2 text-sm font-semibold text-muted-foreground">
                <span className="size-2 rounded-full bg-warning" aria-hidden="true" />
                바꾸기 전
              </figcaption>
              <p className="mt-4 text-[16px] leading-7 text-foreground">
                행사 참여를 희망하는 경우 신청 기한 내에 온라인 신청서를 제출하여야 합니다.
              </p>
            </figure>
            <figure className="rounded-xl border border-primary/25 bg-brand-surface/70 px-4 py-4 sm:px-5 sm:py-5">
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
          </CardContent>
        </Card>
      </section>
    </article>
  )
}
