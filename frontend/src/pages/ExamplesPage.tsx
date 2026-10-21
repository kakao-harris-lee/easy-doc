import { useParams } from 'react-router-dom'

import { DocumentExampleCards } from '../components/DocumentExampleCards'
import {
  EXAMPLE_NOTICE,
  EXAMPLES_DESCRIPTION,
  EXAMPLES_PATH,
  findDocumentExample,
  type DocumentExample,
} from '../content/documentExamples'
import { GUIDE_PATH, HOME_PATH, LOGIN_PATH, SIGNUP_PATH } from '../routes/paths'
import { NotFoundPage } from './NotFoundPage'

const TEXT_LINK = 'font-semibold text-primary underline underline-offset-4'
const PANEL = 'rounded-2xl border border-border bg-card p-5 sm:p-7'

export function ExamplesPage() {
  return (
    <article className="mx-auto max-w-5xl space-y-8 py-4">
      <header>
        <a href={HOME_PATH} className={TEXT_LINK}>
          홈
        </a>
        <p className="mt-6 text-sm font-semibold text-primary">가입 없이 살펴보기</p>
        <h1 className="mt-2 text-3xl font-extrabold text-foreground">쉬운 글 예시</h1>
        <p className="mt-4 leading-7 text-muted-foreground">{EXAMPLES_DESCRIPTION}</p>
      </header>
      <p className="rounded-xl bg-muted p-4 text-sm leading-6 text-muted-foreground">
        {EXAMPLE_NOTICE}
      </p>
      <DocumentExampleCards />
      <ExampleStart />
    </article>
  )
}

function ExampleStart() {
  return (
    <section className={`${PANEL} bg-brand-surface/50`} aria-label="예시로 변환 시작하기">
      <h2 className="text-xl font-bold text-foreground">내 문서가 없어도 시작할 수 있어요</h2>
      <p className="mt-3 leading-7 text-muted-foreground">
        가입·로그인 후 문서 변환 화면의 ‘예시 불러오기’에서 원문을 고르세요. 불러오기에는 크레딧이
        들지 않습니다. ‘쉬운 글로 바꾸기’를 누르면 실제 변환이 시작되며, 화면에 표시된 크레딧을
        사용합니다. 실제 결과는 이 설명용 예시와 다를 수 있습니다.
      </p>
      <div className="mt-5 flex flex-wrap gap-3">
        <a
          href={SIGNUP_PATH}
          className="inline-flex min-h-11 items-center rounded-lg bg-primary px-4 font-semibold text-primary-foreground"
        >
          가입하고 체험하기
        </a>
        <a
          href={LOGIN_PATH}
          className="inline-flex min-h-11 items-center rounded-lg border border-input bg-card px-4 font-semibold text-foreground"
        >
          로그인하고 시작하기
        </a>
      </div>
      <p className="mt-4 text-sm leading-6 text-muted-foreground">
        가입·휴대폰 인증 후 번호당 1회 체험 5크레딧을 받습니다. 지급일부터 한 달 동안 사용할 수
        있습니다.{' '}
        <a href={GUIDE_PATH} className={TEXT_LINK}>
          이용 조건과 파일 형식 확인
        </a>
      </p>
    </section>
  )
}

/** 프리렌더와 클라이언트가 같은 내용으로 상세 페이지를 만든다. */
export function DocumentExampleArticle({ example }: { readonly example: DocumentExample }) {
  return (
    <article className="mx-auto max-w-5xl space-y-8 py-4">
      <header>
        <a href={EXAMPLES_PATH} className={TEXT_LINK}>
          모든 예시 보기
        </a>
        <p className="mt-6 text-sm font-semibold text-primary">{example.category} · 가상 예시</p>
        <h1 className="mt-2 text-3xl font-extrabold leading-tight text-foreground">
          {example.title}
        </h1>
        <p className="mt-4 leading-7 text-muted-foreground">{example.summary}</p>
      </header>
      <p className="rounded-xl bg-muted p-4 text-sm leading-6 text-muted-foreground">
        {EXAMPLE_NOTICE}
      </p>
      <div className="grid items-start gap-5 lg:grid-cols-2">
        <section className={PANEL} aria-labelledby="example-original-heading">
          <h2 id="example-original-heading" className="text-xl font-bold text-foreground">
            원문
          </h2>
          <p className="mt-5 whitespace-pre-line text-[16px] leading-8 text-foreground">
            {example.original}
          </p>
          <a
            className={`mt-5 inline-flex min-h-11 items-center ${TEXT_LINK}`}
            href={`/example-files/${example.id}.txt`}
            download={`${example.category}-가상원문.txt`}
          >
            가상 원문 TXT 내려받기
          </a>
        </section>
        <section
          className={`${PANEL} bg-brand-surface/40`}
          aria-labelledby="example-simplified-heading"
        >
          <h2 id="example-simplified-heading" className="text-xl font-bold text-foreground">
            쉬운 표현 예시
          </h2>
          <p className="mt-2 text-sm text-muted-foreground">
            실제 변환 결과가 아닌 설명용 재작성입니다.
          </p>
          <p className="mt-5 whitespace-pre-line text-[16px] leading-8 text-foreground">
            {example.simplified}
          </p>
        </section>
      </div>
      <div className="grid gap-5 md:grid-cols-2">
        <section className={PANEL}>
          <h2 className="text-xl font-bold text-foreground">이렇게 풀어 썼어요</h2>
          <ul className="mt-4 list-disc space-y-3 pl-5 leading-7 text-muted-foreground">
            {example.changes.map((item) => (
              <li key={item}>{item}</li>
            ))}
          </ul>
        </section>
        <section className={PANEL}>
          <h2 className="text-xl font-bold text-foreground">공유하기 전에 확인하세요</h2>
          <ul className="mt-4 list-disc space-y-3 pl-5 leading-7 text-muted-foreground">
            {example.checkPoints.map((item) => (
              <li key={item}>{item}</li>
            ))}
          </ul>
        </section>
      </div>
      <section className={PANEL}>
        <h2 className="text-xl font-bold text-foreground">직접 다듬는 방법</h2>
        <p className="mt-3 text-sm leading-6 text-muted-foreground">
          실제 사용자의 수정 기록이 아닌 편집 제안입니다.
        </p>
        <dl className="mt-5 space-y-4 leading-7">
          <div>
            <dt className="font-semibold text-muted-foreground">다듬기 전</dt>
            <dd className="mt-1 text-foreground">{example.editing.before}</dd>
          </div>
          <div>
            <dt className="font-semibold text-primary">다듬은 표현</dt>
            <dd className="mt-1 text-foreground">{example.editing.after}</dd>
          </div>
        </dl>
        <p className="mt-4 text-sm leading-6 text-muted-foreground">{example.editing.reason}</p>
      </section>
      <ExampleStart />
    </article>
  )
}

export function DocumentExamplePage() {
  const { exampleId } = useParams()
  const example = findDocumentExample(exampleId)
  return example === undefined ? <NotFoundPage /> : <DocumentExampleArticle example={example} />
}
