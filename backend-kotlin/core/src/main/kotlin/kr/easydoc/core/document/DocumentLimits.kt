package kr.easydoc.core.document

// 문서 도메인의 **수치 상한**과 그 상한이 재는 단위.
//
// 값의 정본은 계약(`contracts/easy-doc-v1.yaml` `x-input-limits`)이고 여기는 그 값을 코드가
// 쓸 수 있게 옮겨 놓은 자리다. **키 경로를 함께 적는다** — 계약과 갈리면 어디를 봐야 하는지가
// 코드에 남아야 한다(계약 값을 코드에 옮겨 적은 자리가 갈렸던 실측이 이 저장소에 있다).
//
// 추출기 쪽 상한(`MAX_EXTRACTED_CHARS`·zip 예산)은 `infrastructure/ingest/ExtractionLimits.kt`
// 에 있다. 두 묶음을 합치지 않는 이유: 저쪽은 **파서를 지키는 방어선**이고 이쪽은
// **변환이 성공할 수 있는 범위**라 기준이 다르다(원본 `app/services/documents.py` 의
// `MAX_CONVERTIBLE_CHARS` 주석이 같은 구분을 적었다).

/**
 * 한 번에 변환할 수 있는 문서 길이. 계약 `x-input-limits.max_convertible_chars`.
 *
 * 초과는 422 다. 근거는 출력 토큰 상한이고 — 넘으면 LLM 호출 비용을 다 치른 뒤 절단으로
 * 실패한다 — 돈을 쓰기 전에 업로드 시점에 거절한다.
 *
 * **바이트가 아니라 문자 수**로 잰다([charCountOf]). 한국어는 UTF-8 에서 글자당 3바이트라
 * 바이트 기준이면 실제 분량의 1/3 에서 잘린다.
 */
const val MAX_CONVERTIBLE_CHARS: Int = 4_000

/**
 * 업로드 파일 크기 상한(바이트). 계약 `x-input-limits.max_upload_bytes`.
 *
 * 초과는 **413** 이다(422 가 아니다) — 사용자가 취할 조치가 "파일을 나눠 올리기"로 다르다.
 *
 * 이 상수는 **서비스 층 판정**의 기준이다. 서블릿 컨테이너 쪽 상한
 * (`spring.servlet.multipart.*`)은 이 값 **이상**으로 두고 정확 경계는 서비스가 잰다 —
 * 컨테이너가 경계를 판정하면 그 문구와 경계가 구현에 좌우된다(계획 §1.5 설계 지점 2).
 * 컨테이너 설정은 HTTP 표면이 생기는 커밋의 몫이다.
 */
const val MAX_UPLOAD_BYTES: Long = 10L * 1024 * 1024

/**
 * 제목 컬럼 상한. `documents.title` 이 `character varying(255)` 다.
 * 계약 `x-input-limits.max_title_length` — **자르고 거절하지 않는다.**
 */
const val MAX_TITLE_LENGTH: Int = 255

/**
 * 계약이 말하는 "문자 수" — **코드 포인트 수**다.
 *
 * ## 왜 `String.length` 가 아닌가
 *
 * `String.length` 는 UTF-16 **코드 단위** 수라, BMP 밖 문자(이모지·확장 한자)가 2 로 세어진다.
 * 그러면 같은 분량의 문서가 담긴 문자의 종류에 따라 **다른 크레딧**으로 환산되고
 * (계약 `DocumentCreatedResponse.char_count` 가 "공백 포함 문자 수"이자 크레딧 환산의
 * 기준값이라고 적었다), 4,000자 상한도 문서마다 다른 지점에서 걸린다.
 *
 * 코드 포인트로 세면 "사람이 세는 글자 수"에 가깝고, `MAX_CONVERTIBLE_CHARS` 판정과
 * `char_count` 응답이 **같은 단위**가 된다. 한국어 본문은 전부 BMP 라 두 방식의 값이
 * 같지만, 같다는 사실에 기대는 것과 단위를 정하는 것은 다르다.
 */
fun charCountOf(text: String): Int = text.codePointCount(0, text.length)

/**
 * 앞에서부터 [count] **코드 포인트**만 남긴다. 짧으면 그대로 돌려준다.
 *
 * ## 코드 단위로 자르면 안 되는 이유 — 게이트 25 X1 과 같은 자리
 *
 * `take(255)` 는 UTF-16 코드 단위로 자르므로 **서로게이트 쌍 한가운데를 끊을 수 있다.**
 * 그렇게 잘린 문자열은 짝 없는 서로게이트를 갖고, `String.toByteArray(UTF_8)` 가 그것을
 * `?` 로 바꿔 버린다 — 즉 우리가 만든 손상이다(`core/crypto/StoredContent.kt` 의
 * 「짝 없는 서로게이트를 생성 시점에 거부한다」 절이 같은 손상을 다룬다).
 *
 * 제목은 암호화 경로를 지나지 않아 `PlainBody` 검사도 받지 못하므로, 자르는 자리에서
 * 막지 않으면 아무 데서도 막히지 않는다.
 */
fun takeCodePoints(
    text: String,
    count: Int,
): String {
    // 코드 단위 수가 상한 이하면 코드 포인트 수는 반드시 그 이하다 — 셀 필요가 없다.
    if (text.length <= count) return text
    val available = text.codePointCount(0, text.length)
    return if (available <= count) text else text.substring(0, text.offsetByCodePoints(0, count))
}
