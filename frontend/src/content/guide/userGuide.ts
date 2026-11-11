/**
 * 이용 가이드 문서를 화면이 쓸 조각으로 나눈다.
 *
 * 원본은 `user-guide.md` 하나다 — 문구를 TSX로 옮겨 적지 않고 Vite `?raw`로 읽어
 * 여기서만 자른다. 화면(`GuidePage`)과 프리렌더(`landing-entry`)가 같은 조각을 쓴다.
 */
import guideMarkdown from './user-guide.md?raw'

// 이 문서의 ## 절만 접는다. 머리말은 접지 않고 본문으로 둔다.
const [introduction = '', ...sections] = guideMarkdown.split(/^## /m)

/** 접지 않는 머리말 — 제목, 사용 순서, 핵심 고지. */
export const GUIDE_INTRODUCTION = introduction

/** 접는 `##` 절. 제목과 그 아래 본문으로 나눠 둔다. */
export const GUIDE_TOPICS = sections.map((section) => {
  const newline = section.indexOf('\n')
  return { title: section.slice(0, newline), content: section.slice(newline + 1) }
})

/**
 * 제목 다음에 오는 첫 문단. 프리렌더의 본문 존재 검사(`buildLandingHtml`의
 * `description`)가 이 문장을 찾는다 — 검색용 설명문(`GUIDE_DESCRIPTION`)은 메타에만
 * 쓰므로, 본문에 실제로 있는 문장을 따로 가리켜야 빈 페이지를 잡을 수 있다.
 */
export const GUIDE_LEAD = introduction.split('\n\n')[1] ?? ''
