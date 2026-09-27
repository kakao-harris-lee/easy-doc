import type { ComponentPropsWithoutRef } from 'react'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'

/**
 * 이용약관·개인정보처리방침 렌더링(P0-13).
 *
 * `src/content/legal/*.md`가 원본이다 — 문구를 TSX로 옮기면 두 곳이 갈라져 어느 쪽이
 * 공개된 문안인지 알 수 없게 된다(계획 `docs/plans/2026-09-09-legal-footer-and-support.md`
 * §5.2). 그래서 페이지는 Vite `?raw` import로 원본 문자열을 그대로 받아 이 컴포넌트에
 * 넘기고, 여기서는 렌더만 한다.
 *
 * 표가 있는 문서라 GFM(표·취소선 등)이 필요하다 — 직접 마크다운 파서를 만들지 않고
 * `react-markdown` + `remark-gfm`을 쓴다. 이 컴포넌트는 게시된 문안을 원본 그대로
 * 그린다 — 첫머리의 버전·시행일 표기를 포함해 어떤 부분도 걷어내거나 감추지 않는다.
 */
export function LegalDocument({ content }: { content: string }) {
  return (
    <div className="legal-document">
      <ReactMarkdown
        remarkPlugins={[remarkGfm]}
        components={{
          // 표는 모바일 320px에서 페이지 전체를 가로로 늘리면 안 된다(DESIGN.md §14) —
          // 표 하나만 스스로 스크롤하는 컨테이너로 감싼다.
          table: ({ ...props }: ComponentPropsWithoutRef<'table'>) => (
            <div className="overflow-x-auto">
              <table {...props} />
            </div>
          ),
        }}
      >
        {content}
      </ReactMarkdown>
    </div>
  )
}
