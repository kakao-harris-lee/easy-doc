// 원본은 `src/content/legal/terms-of-service.md` 하나다(그 옆 README와
// `docs/legal/README.md`의 「고칠 때」 참고) — 이 화면은 그 파일을 Vite `?raw`로 읽어
// 그대로 그릴 뿐 문구를 옮겨 두지 않는다.
import termsMarkdown from '../content/legal/terms-of-service.md?raw'
import { LegalDocument } from '../components/LegalDocument'

/**
 * 이용약관 화면(P0-13). 로그인 없이 열린다 — 회원가입 전에도 약관을 읽을 수 있어야
 * 한다.
 */
export function TermsPage() {
  return <LegalDocument content={termsMarkdown} />
}
