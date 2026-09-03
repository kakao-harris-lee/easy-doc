package kr.easydoc.core.text

/**
 * 두 문자열의 Levenshtein 거리 — **삽입·삭제·치환 각 1회 비용**으로 [left] 를 [right] 로
 * 바꾸는 최소 편집 횟수다. 파일럿 게이트 ① 의 수정률(`conversion_feedback.edit_distance`)이
 * 이 값을 분자로 쓴다.
 *
 * ## 단위는 **코드 포인트**다
 *
 * 이 저장소가 "문자 수"를 코드 포인트로 세기 때문이다(`document/DocumentLimits.kt`
 * `charCountOf`). 코드 단위(`String.length`)로 재면 서로게이트 쌍 하나가 두 글자로 세어지고,
 * 이모지 하나를 고친 것이 두 번 고친 것으로 집계돼 **수정률이 부풀려진다**. 분모(글자 수)를
 * 코드 포인트로 세면서 분자만 코드 단위로 세면 두 값의 단위가 갈린다.
 *
 * ## O(n·m) 의 비용 상한은 **설정에 달려 있다**
 *
 * 이 함수가 받는 두 문자열은 초안(`easy_text`)과 그 검수 수정본이다
 * (`application/document/ConversionFeedbackService.kt` 의 `EditMetrics.of`). **두 끝의
 * 사정이 다르다.**
 *
 * - **검수본**은 코드가 막는다 — `ConversionReviewService.normalize` 가 제어문자를 걷어낸
 *   길이를 재서 [kr.easydoc.core.document.MAX_CONVERTIBLE_CHARS](20,000자)를 넘으면
 *   `InvalidInputException` 으로 거절한다.
 * - **초안에는 길이 검증이 없다.** worker 는 `ProcessConversionJob.finishSuccess` 에서 모델
 *   출력을 `PlainBody` 로 감싸 그대로 암호화·저장하고, `ModelDraft` 와 `PlainBody` 는 길이를
 *   재지 않는 value class 다. 초안을 실질적으로 제한하는 것은 `LlmOptions.maxTokens`
 *   (기본값 `DEFAULT_MAX_TOKENS` = 16,000) 하나뿐이고, 그나마 **길이 검사가 아니라 출력
 *   예산**이다 — 예산에 닿은 응답을 `TRUNCATED` 실패로 버리기 때문에 저장까지 오는 초안이
 *   그 안에 드는 것뿐이다. 토큰은 글자가 아니라서 16,000 토큰이 만드는 한국어 본문은
 *   20,000자보다 훨씬 길 수 있다.
 *
 * 그래서 최악의 표는 「20,000 × 20,000」이 아니라 「20,000 × (그 출력 예산이 허용하는 길이)」이고,
 * `maxTokens` 를 키우면 여기 비용도 함께 커진다. 이 함수의 비용 상한은 코드 불변식이 아니라
 * **설정에 의존한다** — 그 값을 올릴 때 이 구현의 비용 판단을 함께 다시 해야 한다.
 *
 * 초안 쪽에도 길이 검증을 두면 상한이 코드 불변식이 되지만, 그것은 「이미 만들어진 변환
 * 결과를 거절한다」는 정책 결정이라 이 함수의 주석에서 함께 정하지 않는다.
 *
 * → 2026-09-03: 상한을 코드가 강제한다 — 접두·접미 제거 뒤 셀 예산
 * (`easydoc.feedback.edit-distance-cell-budget`)을 넘으면 `null`(측정 대상 아님)이다.
 * [editDistanceWithin] 이 그 예산 판정을 한다. 이 함수([editDistanceOf])는 예산이 없는
 * 자리(테스트 오라클·소형 입력)에 그대로 남는다.
 *
 * 표는 **두 행만** 들고 굴린다. 전체 표(n×m)를 잡으면 필요 없는 `Int` 가 수백만 개가 되고,
 * 우리에게 필요한 것은 마지막 칸의 값 하나뿐이라 경로 복원용 표를 남길 이유가 없다.
 */
fun editDistanceOf(
    left: String,
    right: String,
): Int = distanceOf(trimCommonEnds(codePointsOf(left), codePointsOf(right)))

/**
 * [editDistanceOf] 와 같은 거리를 재되, 표 크기가 [budget] 을 넘으면 계산하지 않고 `null` 을
 * 돌려준다 — **비용 상한을 코드가 강제하는 자리다.**
 *
 * 예산은 **접두·접미를 뗀 뒤** 크기에 적용된다(`rows × columns`, [Long]). 검수자는 보통
 * 문서를 국소적으로 고치므로 긴 공통 접두사·접미사 뒤의 작은 수정은 원문 길이와 무관하게
 * 예산 안에 든다. `null` 은 「거리를 모른다」이지 「거리가 0 이다」가 아니다 — 호출부
 * (`ConversionFeedbackService.EditMetrics`)는 이 값을 「측정 대상 아님」으로 다루고 두
 * 글자 수는 별도로 남긴다.
 */
fun editDistanceWithin(
    left: String,
    right: String,
    budget: EditDistanceBudget,
): Int? {
    val trimmed = trimCommonEnds(codePointsOf(left), codePointsOf(right))
    val cells = trimmed.rows.size.toLong() * trimmed.columns.size.toLong()
    if (cells > budget.cells) return null
    return distanceOf(trimmed)
}

/**
 * `editDistanceOf`/`editDistanceWithin` 이 호출하는 셀 예산(단위 비용 Levenshtein DP 표의
 * 칸 수, `rows × columns`) — **운영 노브다.** 값은 배포 단위 구성값으로 관리한다
 * (`infrastructure/document/DocumentConfiguration.kt` 조립, 바인딩 접두사는
 * `easydoc.feedback`). 여기서는 불변식(1 이상)만 지킨다 — 그 값이 「비용을 감당할 수
 * 있는가」는 composition root 의 판단이지 이 타입의 것이 아니다.
 */
@JvmInline
value class EditDistanceBudget(val cells: Long) {
    init {
        require(cells >= 1) { "cells 는 1 이상이어야 한다" }
    }
}

/** 접두·접미를 뗀 두 코드 포인트 배열 — [rows] 가 [columns] 보다 짧지 않다. */
private class TrimmedCodePoints(
    val rows: IntArray,
    val columns: IntArray,
)

/**
 * 공통 접두사·공통 접미사를 뗀 뒤, 긴 쪽을 [TrimmedCodePoints.rows] 로 둔다. 단위 비용
 * Levenshtein 거리는 양쪽 끝의 공통 부분을 떼도 값이 같다 — 지울 문자와 지워질 문자가
 * 같은 자리에서 같은 수만큼 사라질 뿐이다. 짧은 쪽을 열로 두면 [rollingDistance] 가 도는
 * 표의 폭(따라서 메모리)이 그만큼 준다. 삽입과 삭제의 비용이 같아 거리는 대칭이므로 자리를
 * 바꿔도 값이 달라지지 않는다.
 */
private fun trimCommonEnds(
    left: IntArray,
    right: IntArray,
): TrimmedCodePoints {
    val maxCommon = minOf(left.size, right.size)
    var start = 0
    while (start < maxCommon && left[start] == right[start]) start++

    var leftEnd = left.size
    var rightEnd = right.size
    while (leftEnd > start && rightEnd > start && left[leftEnd - 1] == right[rightEnd - 1]) {
        leftEnd--
        rightEnd--
    }

    val trimmedLeft = left.copyOfRange(start, leftEnd)
    val trimmedRight = right.copyOfRange(start, rightEnd)
    return if (trimmedRight.size > trimmedLeft.size) {
        TrimmedCodePoints(rows = trimmedRight, columns = trimmedLeft)
    } else {
        TrimmedCodePoints(rows = trimmedLeft, columns = trimmedRight)
    }
}

/** [TrimmedCodePoints.rows] 가 [TrimmedCodePoints.columns] 보다 짧지 않으므로 열이 비면 거리는 행 길이다. */
private fun distanceOf(trimmed: TrimmedCodePoints): Int =
    if (trimmed.columns.isEmpty()) {
        trimmed.rows.size
    } else {
        rollingDistance(rows = trimmed.rows, columns = trimmed.columns)
    }

/** 표를 **두 행만** 들고 굴린다. `rows` 를 한 줄씩 내려가며 `columns` 만큼의 칸을 채운다. */
private fun rollingDistance(
    rows: IntArray,
    columns: IntArray,
): Int {
    // previous[j] = rows 의 앞 i-1 글자와 columns 의 앞 j 글자 사이의 거리.
    var previous = IntArray(columns.size + 1) { it }
    var current = IntArray(columns.size + 1)

    for (i in 1..rows.size) {
        // 0 열은 "columns 가 빈 상태" — rows 의 앞 i 글자를 전부 지운 비용이다.
        current[0] = i
        for (j in 1..columns.size) {
            val substitution = previous[j - 1] + if (rows[i - 1] == columns[j - 1]) 0 else 1
            val deletion = previous[j] + 1
            val insertion = current[j - 1] + 1
            current[j] = minOf(substitution, deletion, insertion)
        }
        // 다음 행을 위해 두 배열의 역할을 맞바꾼다. 새로 할당하지 않는다.
        val finished = previous
        previous = current
        current = finished
    }

    return previous[columns.size]
}

/** 문자열을 **코드 포인트 배열**로 편다. 서로게이트 쌍은 원소 하나다. */
private fun codePointsOf(text: String): IntArray = text.codePoints().toArray()
