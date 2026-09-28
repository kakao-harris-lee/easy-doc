/**
 * 검수 편집기 현재 본문의 지문(SHA-256, 16진 소문자 64자) — 계약
 * `ReconvertUnitRequest.easy_text_fingerprint`(`contracts/easy-doc-v1.yaml`)와 같은 형식이다.
 *
 * 서버는 이 값으로 판정하지 않는다 — 응답이 도착한 시점에 에디터 본문이 여전히 같은지
 * 클라이언트가 확인하는 용도다.
 *
 * `crypto.subtle`은 Node 20+·모든 최신 브라우저·jsdom(Node 런타임 위에서 돈다) 전역에
 * 이미 있어 별도 폴리필이 필요 없다.
 */
export async function computeEasyTextFingerprint(text: string): Promise<string> {
  const encoded = new TextEncoder().encode(text)
  const digest = await crypto.subtle.digest('SHA-256', encoded)
  return Array.from(new Uint8Array(digest))
    .map((byte) => byte.toString(16).padStart(2, '0'))
    .join('')
}
