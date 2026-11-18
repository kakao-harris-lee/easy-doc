import { useState } from 'react'
import { useParams } from 'react-router-dom'

import { DocumentExampleCards } from '../components/DocumentExampleCards'
import { TEXT_LINK } from '../components/layoutStyles'
import {
  DOCUMENT_EXAMPLES,
  EXAMPLE_NOTICE,
  EXAMPLES_DESCRIPTION,
  EXAMPLES_INTRO,
  EXAMPLES_PATH,
  exampleDescription,
  examplePath,
  findDocumentExample,
  type DocumentExample,
} from '../content/documentExamples'
import { GUIDE_PATH, HOME_PATH, LOGIN_PATH, SIGNUP_PATH } from '../routes/paths'
import { NotFoundPage } from './NotFoundPage'

export function ExamplesPage() {
  return (
    <article className="mx-auto max-w-5xl space-y-12 pb-8 pt-6 md:space-y-16 md:pb-10 md:pt-12">
      <header>
        <a href={HOME_PATH} className={`inline-flex min-h-11 items-center ${TEXT_LINK}`}>
          홈
        </a>
        <h1 className="page-title mt-2">쉬운 글 예시</h1>
        <p className="lead-copy mt-4 max-w-[68ch] text-foreground">{EXAMPLES_DESCRIPTION}</p>
        <p className="body-copy mt-3 max-w-[68ch] text-muted-foreground">{EXAMPLES_INTRO}</p>
      </header>
      <DocumentExampleCards heading="h2" />
      <div className="space-y-6">
        <ExampleStart />
        <p className="supporting-copy max-w-[68ch] border-t border-border pt-4 text-muted-foreground">
          {EXAMPLE_NOTICE}
        </p>
      </div>
    </article>
  )
}

function ExampleStart() {
  return (
    <section className="rounded-xl bg-muted p-6 md:p-10" aria-label="예시로 변환 시작하기">
      <div className="md:grid md:grid-cols-[minmax(0,1fr)_minmax(15rem,18rem)] md:items-center md:gap-12">
        <div>
          <h2 className="section-title">예시로 시작하기</h2>
          <div className="body-copy mt-3 text-foreground">
            <p>로그인 후 ‘예시 불러오기’를 선택하세요.</p>
            <p>불러오기는 무료 · 변환 시 크레딧 사용</p>
          </div>
          <p className="mt-6 border-t border-border pt-4 text-sm leading-6 text-muted-foreground">
            가입·휴대폰 인증 후 5크레딧
            <br />
            번호당 1회 · 지급일부터 한 달{' '}
            <a href={GUIDE_PATH} className={TEXT_LINK}>
              체험 안내
            </a>
          </p>
        </div>
        <div className="mt-8 md:mt-0">
          <div className="grid grid-cols-2 gap-3 md:grid-cols-1">
            <a
              href={SIGNUP_PATH}
              className="inline-flex min-h-12 w-full items-center justify-center rounded-md bg-primary px-3 text-base font-semibold text-primary-foreground transition-colors hover:bg-primary-hover active:bg-primary-active"
            >
              가입하기
            </a>
            <a
              href={LOGIN_PATH}
              className="inline-flex min-h-12 w-full items-center justify-center rounded-md border border-input bg-card px-3 text-base font-semibold text-foreground transition-colors hover:bg-secondary active:bg-muted"
            >
              로그인
            </a>
          </div>
          {/* 버튼 라벨(가입하기·로그인)만으로는 링크가 어디로 가는지 읽히지 않아 설명이 있는 본문 링크를 함께 둔다. */}
          <p className="mt-4 text-center text-base leading-6">
            <a href={HOME_PATH} className={TEXT_LINK}>
              내 문서를 쉬운 글로 바꿔 보기
            </a>
          </p>
        </div>
      </div>
    </section>
  )
}

/** 두 글은 SSR에 모두 남긴다. 모바일만 선택한 글을 보여주며 데스크톱은 나란히 비교한다. */
export function DocumentExampleArticle({ example }: { readonly example: DocumentExample }) {
  const [view, setView] = useState<'original' | 'simplified'>('simplified')
  return (
    <article className="mx-auto max-w-5xl space-y-12 py-6 md:space-y-16 md:py-12">
      <header>
        <a href={EXAMPLES_PATH} className={`inline-flex min-h-11 items-center ${TEXT_LINK}`}>
          모든 예시
        </a>
        <p className="eyebrow mt-2">{example.category}</p>
        <h1 className="page-title mt-2">{example.title}</h1>
        {/* 검색 결과 설명문과 같은 문장이다 — 설명을 메타에만 두지 않는다. */}
        <p className="lead-copy mt-4 max-w-[68ch] text-foreground">{exampleDescription(example)}</p>
        <p className="supporting-copy mt-3 max-w-[68ch] text-muted-foreground">{EXAMPLE_NOTICE}</p>
      </header>
      <div className="space-y-4">
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
              className={`min-h-11 rounded-lg px-3 text-sm font-semibold ${view === value ? 'bg-selection text-selection-foreground' : 'text-muted-foreground hover:bg-secondary hover:text-foreground'}`}
            >
              {label}
            </button>
          ))}
        </div>
        <div className="grid items-start gap-6 lg:grid-cols-2 lg:gap-10">
          <section
            id="example-original"
            className={`py-2 md:p-8 ${view === 'original' ? '' : 'hidden lg:block'}`}
            aria-labelledby="example-original-heading"
          >
            <h2 id="example-original-heading" className="section-title">
              원문
            </h2>
            <p className="body-copy mt-4 whitespace-pre-line text-foreground">{example.original}</p>
          </section>
          <section
            id="example-simplified"
            className={`rounded-xl border-l-4 border-l-primary bg-card p-6 md:p-8 ${view === 'simplified' ? '' : 'hidden lg:block'}`}
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
          className={`inline-flex min-h-11 items-center ${TEXT_LINK}`}
          href={`/example-files/${example.id}.txt`}
          download={`${example.category}-가상원문.txt`}
        >
          원문 TXT 다운로드
        </a>
      </div>
      <ExampleRationale example={example} />
      <div className="divide-y divide-border border-y border-border">
        <details className="help-disclosure">
          <summary>
            <h2>바뀐 표현과 편집 제안</h2>
          </summary>
          <div className="space-y-4 pb-6 text-base leading-7">
            <ul className="list-disc space-y-1 pl-5 text-foreground">
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
          <ul className="list-disc space-y-1 pb-6 pl-5 text-base leading-7 text-foreground">
            {example.checkPoints.map((item) => (
              <li key={item}>{item}</li>
            ))}
          </ul>
        </details>
      </div>
      <OtherExamples currentId={example.id} />
      <ExampleStart />
    </article>
  )
}

/**
 * 「이 예시에서 바꾼 점」. 접는 상자에 넣지 않는다 — 이 글이 예시 페이지에서
 * 원문·쉬운 글 말고 유일하게 사람이 쓴 설명이라, 접으면 읽을 사람도 검색 엔진도
 * 본문으로 보지 않는다.
 */
function ExampleRationale({ example }: { readonly example: DocumentExample }) {
  return (
    <section aria-labelledby="example-rationale-heading">
      <h2 id="example-rationale-heading" className="section-title">
        이 예시에서 바꾼 점
      </h2>
      <h3 className="mt-4 text-base font-bold text-foreground">누구를 위한 문서인지</h3>
      <p className="body-copy mt-1 text-muted-foreground">{example.audience}</p>
      <h3 className="mt-6 text-base font-bold text-foreground">바꾼 점 3가지</h3>
      <ol className="body-copy mt-1 list-decimal space-y-2 pl-5 text-muted-foreground">
        {example.rationale.map((point) => (
          <li key={point}>{point}</li>
        ))}
      </ol>
    </section>
  )
}

/** 지금 보는 예시를 뺀 나머지로 가는 길. 목록 페이지를 거치지 않아도 옮겨 갈 수 있다. */
function OtherExamples({ currentId }: { readonly currentId: string }) {
  return (
    <section aria-labelledby="other-examples-heading">
      <h2 id="other-examples-heading" className="section-title">
        다른 예시
      </h2>
      <ul className="mt-2 space-y-1 text-base leading-6">
        {DOCUMENT_EXAMPLES.filter((item) => item.id !== currentId).map((item) => (
          <li key={item.id}>
            <a
              href={examplePath(item)}
              className={`inline-flex min-h-11 items-center ${TEXT_LINK}`}
            >
              {`${item.category} 쉬운 글 예시: ${item.title}`}
            </a>
          </li>
        ))}
      </ul>
    </section>
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
