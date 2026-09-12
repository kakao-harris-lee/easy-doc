// 원본은 `src/content/guide/user-guide.md` 하나다 — 이 화면은 그 파일을 Vite `?raw`로
// 읽어 그대로 그릴 뿐 문구를 옮겨 두지 않는다(P0-12).
import guideMarkdown from '../content/guide/user-guide.md?raw'
import { LegalDocument } from '../components/LegalDocument'

/**
 * 이용 가이드 화면(P0-12). 로그인 없이 열린다 — 가입 전에도 사용법을 읽을 수 있어야
 * 한다. 푸터와 로그인 뒤 상단 메뉴가 이리로 보낸다.
 */
export function GuidePage() {
  return <LegalDocument content={guideMarkdown} />
}
