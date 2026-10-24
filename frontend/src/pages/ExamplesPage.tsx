import { useState } from 'react'
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
const PANEL = 'rounded-xl border border-border bg-card p-4 sm:p-6'

export function ExamplesPage() {
  return (
    <article className="mx-auto max-w-5xl space-y-5 py-3 sm:space-y-6">
      <header>
        <a href={HOME_PATH} className={`inline-flex min-h-11 items-center ${TEXT_LINK}`}>
          홈
        </a>
        <h1 className="page-title mt-2">쉬운 글 예시</h1>
        <p className="mt-2 text-sm leading-6 text-muted-foreground">{EXAMPLES_DESCRIPTION}</p>
      </header>
      <DocumentExampleCards heading="h2" />
      <p className="text-sm leading-6 text-muted-foreground">{EXAMPLE_NOTICE}</p>
      <ExampleStart />
    </article>
  )
}

function ExampleStart() {
  return (
    <section className={`${PANEL} bg-brand-surface/40`} aria-label="예시로 변환 시작하기">
      <h2 className="section-title">예시로 시작하기</h2>
      <p className="mt-2 text-sm leading-6 text-muted-foreground">
        로그인 후 ‘예시 불러오기’를 선택하세요.
      </p>
      <p className="text-sm leading-6 text-muted-foreground">
        불러오기는 무료 · 변환 시 크레딧 사용
      </p>
      <div className="mt-4 grid grid-cols-2 gap-2 sm:flex sm:gap-3">
        <a
          href={SIGNUP_PATH}
          className="inline-flex min-h-11 items-center justify-center rounded-lg bg-primary px-3 font-semibold text-primary-foreground"
        >
          가입하기
        </a>
        <a
          href={LOGIN_PATH}
          className="inline-flex min-h-11 items-center justify-center rounded-lg border border-input bg-card px-3 font-semibold text-foreground"
        >
          로그인
        </a>
      </div>
      <p className="mt-3 text-sm leading-6 text-muted-foreground">
        가입·휴대폰 인증 후 5크레딧
        <br />
        번호당 1회 · 지급일부터 한 달{' '}
        <a href={GUIDE_PATH} className={TEXT_LINK}>
          체험 안내
        </a>
      </p>
    </section>
  )
}

/** 두 글은 SSR에 모두 남긴다. 모바일만 선택한 글을 보여주며 데스크톱은 나란히 비교한다. */
export function DocumentExampleArticle({ example }: { readonly example: DocumentExample }) {
  const [view, setView] = useState<'original' | 'simplified'>('simplified')
  return (
    <article className="mx-auto max-w-5xl space-y-5 py-3 sm:space-y-6">
      <header>
        <a href={EXAMPLES_PATH} className={`inline-flex min-h-11 items-center ${TEXT_LINK}`}>
          모든 예시
        </a>
        <p className="mt-2 text-sm font-semibold text-primary">{example.category}</p>
        <h1 className="page-title mt-2">{example.title}</h1>
        <p className="mt-3 text-sm leading-6 text-muted-foreground">{EXAMPLE_NOTICE}</p>
      </header>
      <div className="space-y-3">
        <div
          role="group"
          aria-label="비교할 글"
          className="grid grid-cols-2 gap-1 rounded-xl bg-muted p-1 lg:hidden"
        >
          {(
            [
              ['original', '원문'],
              ['simplified', '쉬운 글'],
            ] as const
          ).map(([value, label]) => (
            <button
              key={value}
              type="button"
              aria-pressed={view === value}
              aria-controls={`example-${value}`}
              onClick={() => setView(value)}
              className={`min-h-11 rounded-lg px-3 text-sm font-semibold ${view === value ? 'bg-card text-primary shadow-sm' : 'text-muted-foreground'}`}
            >
              {label}
            </button>
          ))}
        </div>
        <div className="grid items-start gap-4 lg:grid-cols-2">
          <section
            id="example-original"
            className={`${PANEL} ${view === 'original' ? '' : 'hidden lg:block'}`}
            aria-labelledby="example-original-heading"
          >
            <h2 id="example-original-heading" className="section-title">
              원문
            </h2>
            <p className="body-copy mt-4 whitespace-pre-line text-foreground">{example.original}</p>
          </section>
          <section
            id="example-simplified"
            className={`${PANEL} bg-brand-surface/40 ${view === 'simplified' ? '' : 'hidden lg:block'}`}
            aria-labelledby="example-simplified-heading"
          >
            <h2 id="example-simplified-heading" className="section-title">
              쉬운 글
            </h2>
            <p className="body-copy mt-4 whitespace-pre-line text-foreground">
              {example.simplified}
            </p>
          </section>
        </div>
        <a
          className={`inline-flex min-h-11 items-center text-sm ${TEXT_LINK}`}
          href={`/example-files/${example.id}.txt`}
          download={`${example.category}-가상원문.txt`}
        >
          원문 TXT 다운로드
        </a>
      </div>
      <div className="space-y-2">
        <details className="help-disclosure">
          <summary>
            <h2>바뀐 표현과 편집 제안</h2>
          </summary>
          <div className="space-y-4 px-4 pb-4 text-sm leading-6">
            <ul className="list-disc space-y-1 pl-5 text-muted-foreground">
              {example.changes.map((item) => (
                <li key={item}>{item}</li>
              ))}
            </ul>
            <dl className="space-y-3 border-t border-border pt-3">
              <div>
                <dt className="font-semibold text-muted-foreground">다듬기 전</dt>
                <dd className="mt-1 text-foreground">{example.editing.before}</dd>
              </div>
              <div>
                <dt className="font-semibold text-primary">편집 제안</dt>
                <dd className="mt-1 text-foreground">{example.editing.after}</dd>
              </div>
            </dl>
            <p className="text-muted-foreground">{example.editing.reason}</p>
          </div>
        </details>
        <details className="help-disclosure">
          <summary>
            <h2>공유 전 확인할 내용</h2>
          </summary>
          <ul className="list-disc space-y-1 pr-4 pb-4 pl-9 text-sm leading-6 text-muted-foreground">
            {example.checkPoints.map((item) => (
              <li key={item}>{item}</li>
            ))}
          </ul>
        </details>
      </div>
      <ExampleStart />
    </article>
  )
}

export function DocumentExamplePage() {
  const { exampleId } = useParams()
  const example = findDocumentExample(exampleId)
  return example === undefined ? (
    <NotFoundPage />
  ) : (
    <DocumentExampleArticle key={example.id} example={example} />
  )
}
