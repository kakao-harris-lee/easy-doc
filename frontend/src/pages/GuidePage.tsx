// 원본은 `src/content/guide/user-guide.md` 하나다 — 이 화면은 그 파일을 Vite `?raw`로
// 읽어 그대로 그릴 뿐 문구를 옮겨 두지 않는다.
import guideMarkdown from '../content/guide/user-guide.md?raw'
import { LegalDocument } from '../components/LegalDocument'
import { EXAMPLES_PATH } from '../content/documentExamples'
import { HOME_PATH } from '../routes/paths'

// 이 문서의 ## 절만 접는다. 본문은 기존 Markdown 렌더러가 처리하고 SSR에도 남긴다.
const [introduction = '', ...sections] = guideMarkdown.split(/^## /m)
const topics = sections.map((section) => {
  const newline = section.indexOf('\n')
  return { title: section.slice(0, newline), content: section.slice(newline + 1) }
})

/**
 * 이용 가이드 화면. 로그인 없이 열린다 — 가입 전에도 사용법을 읽을 수 있어야
 * 한다. 푸터와 로그인 뒤 상단 메뉴가 이리로 보낸다.
 */
export function GuidePage() {
  return (
    <article className="guide-page">
      <LegalDocument content={introduction} />
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
      <div className="divide-y divide-border border-y border-border bg-card">
        {topics.map((topic) => (
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
