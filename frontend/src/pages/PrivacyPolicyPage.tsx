// 원본 Markdown을 Vite `?raw`로 읽어 그대로 그릴 뿐 문구를 옮겨 두지 않는다.
import privacyMarkdown from '../content/legal/privacy-policy.md?raw'
import { LegalDocument } from '../components/LegalDocument'

/**
 * 개인정보처리방침 화면. 로그인 없이 열린다 — 회원가입 전에도 방침을 읽을 수
 * 있어야 한다.
 */
export function PrivacyPolicyPage() {
  return <LegalDocument content={privacyMarkdown} />
}
