/**
 * 백엔드/계약과 같은 글자 수 세기 방식.
 *
 * `string.length`는 UTF-16 코드 유닛 수라 이모지 같은 surrogate pair 문자를 2로
 * 센다. 백엔드 `DocumentLimits.charCountOf`(Kotlin `codePointCount`)는 유니코드
 * 코드 포인트를 기준으로 하므로, 같은 입력에서 브라우저와 서버의 글자 수가
 * 달라질 수 있다 — surrogate pair 문자가 1만 자 넘게 들어가면 브라우저는
 * 상한을 넘겼다고 막지만 서버는 받아들이는 식이다. `Array.from`은 문자열을
 * 코드 포인트 단위로 순회하므로 이 값이 코드 포인트 수와 같다.
 */
export function countChars(text: string): number {
  return Array.from(text).length
}
