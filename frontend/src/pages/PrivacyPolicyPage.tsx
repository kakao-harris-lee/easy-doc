// 원본은 `src/content/legal/privacy-policy.md` 하나다(그 옆 README와
// `docs/legal/README.md`의 「고칠 때」 참고) — 이 화면은 그 파일을 Vite `?raw`로 읽어
// 그대로 그릴 뿐 문구를 옮겨 두지 않는다.
import privacyMarkdown from '../content/legal/privacy-policy.md?raw'
import { LegalDocument } from '../components/LegalDocument'

/**
 * 개인정보처리방침 화면(P0-13). 로그인 없이 열린다 — 회원가입 전에도 방침을 읽을 수
 * 있어야 하고, 푸터 링크도 다른 정책 링크와 시각적으로 구분해 이 화면으로 보낸다
 * (개인정보 보호법이 요구하는 구분 표시, `Footer.tsx`).
 */
export function PrivacyPolicyPage() {
  return <LegalDocument content={privacyMarkdown} />
}
