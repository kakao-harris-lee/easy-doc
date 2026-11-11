// 원본은 `src/content/guide/user-guide.md` 하나다 — 이 화면은 `userGuide.ts`가 자른
// 조각을 그대로 그릴 뿐 문구를 옮겨 두지 않는다. 본문은 기존 Markdown 렌더러가
// 처리하고 SSR에도 남긴다.
import { LegalDocument } from '../components/LegalDocument'
import { TEXT_LINK } from '../components/layoutStyles'
import { EXAMPLES_PATH } from '../content/documentExamples'
import { GUIDE_INTRODUCTION, GUIDE_TOPICS } from '../content/guide/userGuide'
import { HOME_PATH } from '../routes/paths'

/**
 * 이용 가이드 화면. 로그인 없이 열린다 — 가입 전에도 사용법을 읽을 수 있어야
 * 한다. 푸터와 로그인 뒤 상단 메뉴가 이리로 보낸다.
 */
export function GuidePage() {
  return (
    <article className="guide-page">
      <LegalDocument content={GUIDE_INTRODUCTION} />
      <div className="my-5 grid grid-cols-2 gap-3">
        <a
          href={HOME_PATH}
          className="inline-flex min-h-11 items-center justify-center rounded-lg bg-primary px-3 font-semibold text-primary-foreground transition-colors hover:bg-primary-hover active:bg-primary-active"
        >
          시작하기
        </a>
        <a
          href={EXAMPLES_PATH}
          className="inline-flex min-h-11 items-center justify-center rounded-lg border border-input bg-card px-3 font-semibold text-foreground transition-colors hover:bg-secondary active:bg-muted"
        >
          예시 보기
        </a>
      </div>
      {/* 버튼 라벨(시작하기)만으로는 링크가 어디로 가는지 읽히지 않아 설명이 있는 본문 링크를 함께 둔다. */}
      <p className="my-5 text-sm leading-6">
        <a href={HOME_PATH} className={TEXT_LINK}>
          내 문서를 쉬운 글로 바꿔 보기
        </a>
      </p>
      <div className="divide-y divide-border border-y border-border bg-card">
        {GUIDE_TOPICS.map((topic) => (
          <details key={topic.title} className="help-disclosure">
            <summary>
              <h2>{topic.title}</h2>
            </summary>
            <div className="px-4 pb-4">
              <LegalDocument content={topic.content} />
            </div>
          </details>
        ))}
      </div>
    </article>
  )
}
