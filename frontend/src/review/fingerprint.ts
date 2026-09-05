/**
 * 검수 편집기 현재 본문의 지문(SHA-256, 16진 소문자 64자) — 계약
 * `ReconvertUnitRequest.easy_text_fingerprint`(`contracts/easy-doc-v1.yaml`)와 같은 형식이다.
 *
 * 서버는 이 값으로 아무 판정도 하지 않는다(계획
 * `docs/plans/2026-09-04-p0-4-paragraph-mapping-reconversion.md` §4 결정 3) — 응답이 도착한
 * 시점에 에디터 본문이 여전히 같은지 **클라이언트가** 스스로 확인하는 용도다.
 *
 * `crypto.subtle`은 Node 20+·모든 최신 브라우저·jsdom(Node 런타임 위에서 돈다) 전역에
 * 이미 있어 별도 폴리필이 필요 없다(실측: 이 저장소 jsdom 테스트 환경에서
 * `globalThis.crypto.subtle`이 존재).
 */
export async function computeEasyTextFingerprint(text: string): Promise<string> {
  const encoded = new TextEncoder().encode(text)
  const digest = await crypto.subtle.digest('SHA-256', encoded)
  return Array.from(new Uint8Array(digest))
    .map((byte) => byte.toString(16).padStart(2, '0'))
    .join('')
}
