package kr.easydoc.core.document

// 원본 서식 유지 상태 — 계약 `FormatPreservationStatus`·`FormatPreservation`.
//
// 값 집합의 정본은 계약이고 이 파일은 그 대응이다. **`checking` 은 양쪽 어디에도 없다** —
// 사유는 [FormatPreservationStatus] KDoc 끝. 값 집합 대조: `ConversionFormatContractTest`.

/**
 * 원본 서식 유지 상태.
 *
 * §6.5 가 제시한 다섯 중 **넷**이 여기 있다. `checking` 만 빠졌고, 그 사유는 62ec898 이
 * 적어 둔 것과 같은 성질이다: 이 판정은 조회 한 번 안에서 **동기로 끝난다.** 클라이언트가
 * 지켜볼 진행 상태가 없으므로 「확인 중」은 끝나지 않을 스피너를 약속하는 값으로 남는다.
 * 판정에 필요한 변환 결과가 아직 없는 동안은 이 enum 의 값이 아니라 `null` 이다
 * (「서버가 아직 판정하지 않았다」).
 */
enum class FormatPreservationStatus(val wireName: String) {
    /**
     * **유지할 원본 서식이 없다.** 붙여넣기라 파일이 아니었거나, 원본 파일 바이트가
     * 저장돼 있지 않아 되살릴 수 없는 문서다. 둘 다 **영구히 참**이라 구조 보존이
     * 구현된 뒤에도 이 판정은 뒤집히지 않는다.
     */
    NOT_APPLICABLE("not_applicable"),

    /**
     * **원본 구조 그대로 나간다.** 원본의 본문 단위 하나하나에 검수본 문단 하나씩이
     * 들어가고(수가 정확히 같다), 원본 문구가 남는 머리말·꼬리말도 없다.
     */
    AVAILABLE("available"),

    /**
     * **일부는 달라진다.** 유지되는 것과 달라지는 것을 [FormatPreservation.details] 가
     * 개수로 말한다 — 무엇이 달라지는지 모르면 이 값을 쓸 수 없다.
     */
    PARTIAL("partial"),

    /**
     * **같은 형식으로 다시 만들 수 없다.** 저장된 원본을 열 수 없다는 뜻이고, 내보내기도
     * 같은 사유로 실패한다 — 텍스트 전용 파일로 조용히 바꿔 내보내지 않는다(§6.5).
     */
    FAILED("failed"),
}

/**
 * 서식 유지 판정 한 건. 계약 `FormatPreservation` — 두 필드가 전부다.
 *
 * **[details] 에 문서 본문·개인정보를 담지 않는다.** 담을 수 있는 것은 구조 요소의 종류와
 * 개수뿐이며, 그 규칙은 `missing_placeholders` 가 라벨만 싣는 것과 같은 판단이다. 이 파일이
 * 문구를 **전부 소유하는 것**이 그 규칙의 형태다 — 문서에서 읽은 문자열이 흘러들 자리가 없다.
 */
class FormatPreservation(
    val status: FormatPreservationStatus,
    val details: List<String>,
) {
    /** 상태와 **개수**만 남긴다 — 항목 문구는 사용자에게 보여 줄 값이지 로그에 남길 값이 아니다. */
    override fun toString(): String = "FormatPreservation(${status.wireName}, 항목 ${details.size}건)"
}

/**
 * 원본에 검수본을 반영하면 **실제로 일어나는 일**의 개수.
 *
 * 개수만 든다. 원본 문단의 **문구는 이 경계를 넘지 않는다** — 판정 문구가 문서 본문을 담을
 * 수 있는 통로를 아예 두지 않으려는 것이고, 그것이 [FormatPreservation.details] 의 개인정보
 * 규칙을 타입으로 지키는 방법이다.
 *
 * 자리 맞춤 자체는 원본 구조를 쥔 쪽(infrastructure `export/ReflectionPlan`)이 하고 여기로는
 * 결과만 온다. 「미리 말한 것」과 「실제로 한 것」이 갈릴 수 없는 것은 그 자리 맞춤을 판정과
 * 내보내기가 **같은 함수 하나로** 하기 때문이다.
 */
class ReflectionOutcome(
    /** 원본 문구를 그대로 두는 머리말·꼬리말 단위 수. **쓰지 않는 것**이 §6.5 의 「유지」다. */
    val headerFooterUnits: Int,
    /** 반영할 문단이 없어 **비워지는** 본문 단위 수. 원본 문구를 남기지 않는다. */
    val emptiedUnits: Int,
    /** 원본에 자리가 없어 본문 끝에 **덧붙는** 문단 수. 버리면 검수한 내용이 사라진다. */
    val appendedLines: Int,
) {
    override fun toString(): String =
        "ReflectionOutcome(머리말·꼬리말 $headerFooterUnits, 비움 $emptiedUnits, 덧붙임 $appendedLines)"
}

/** 되살릴 원본이 **없다**는 판정. 붙여넣기와 원본 바이트가 없는 옛 문서 — 둘 다 영구히 참이다. */
fun noOriginalPreservation(): FormatPreservation =
    FormatPreservation(FormatPreservationStatus.NOT_APPLICABLE, emptyList())

/**
 * 저장된 원본을 **열 수 없다**는 판정. 내보내기도 같은 사유로 실패하고, 그 실패는 오류로
 * 드러난다 — §6.5 가 금지한 「텍스트 전용 파일로 조용히 대체」를 하지 않는다는 뜻이다.
 */
fun unreadableOriginalPreservation(): FormatPreservation =
    FormatPreservation(FormatPreservationStatus.FAILED, listOf(UNREADABLE_ORIGINAL_DETAIL))

/**
 * 반영 결과의 판정 — **짝이 하나라도 어긋나면 「유지 가능」이 아니다.**
 *
 * ## 자리 맞춤 규칙 (내보내기가 실제로 하는 일)
 *
 * 원본 단위는 추출기가 훑은 **문서 순서** 그대로이고, 검수본 문단이 그 순서에 앞에서부터
 * 짝지어진다. 짝이 된 단위 중 머리말·꼬리말은 **쓰지 않고 원본 문구를 그대로 둔다.**
 *
 * - 본문 단위가 남으면 그 문단은 **비운다** — 원본 문구를 남기지 않는다. 검수를 지나지 않은
 *   원본 문장이 「쉬운 글」 파일에 섞이는 것이 조용한 거짓말이기 때문이다.
 * - 단위가 모자라면 남은 문단을 본문 끝에 **덧붙인다** — 버리면 검수한 내용이 사라진다.
 *
 * 어느 쪽도 「대응을 확신한 반영」이 아니다. 그래서 그 수만큼이 그대로 `partial` 의 근거이고
 * [FormatPreservation.details] 가 그것을 개수로 말한다(§6.5 "낙관적으로 추측하지 않는다").
 */
fun reflectedPreservation(outcome: ReflectionOutcome): FormatPreservation {
    val details =
        buildList {
            if (outcome.headerFooterUnits > 0) add(headerFooterDetail(outcome.headerFooterUnits))
            if (outcome.emptiedUnits > 0) add(emptiedDetail(outcome.emptiedUnits))
            if (outcome.appendedLines > 0) add(appendedDetail(outcome.appendedLines))
            if (outcome.emptiedUnits > 0 || outcome.appendedLines > 0) add(SHIFTED_DETAIL)
        }
    return if (details.isEmpty()) {
        FormatPreservation(FormatPreservationStatus.AVAILABLE, emptyList())
    } else {
        FormatPreservation(FormatPreservationStatus.PARTIAL, details)
    }
}

/**
 * 사용자에게 그대로 보이는 문구들. **개수와 요소의 종류만 넣는다** — 문서에서 읽은 문자열을
 * 끼워 넣는 형식 인자가 하나도 없어야 한다.
 */
private fun headerFooterDetail(count: Int): String = "머리말·꼬리말 ${count}곳은 원본 문구를 그대로 둡니다."

private fun emptiedDetail(count: Int): String = "원본 문단 ${count}개는 반영할 내용이 없어 빈 문단으로 남습니다."

private fun appendedDetail(count: Int): String = "문단 ${count}개는 원본에 자리가 없어 본문 끝에 덧붙습니다."

private const val SHIFTED_DETAIL: String = "문단 수가 원본과 달라 뒤쪽 문단의 서식이 밀릴 수 있습니다."

private const val UNREADABLE_ORIGINAL_DETAIL: String = "원본 파일을 열 수 없어 같은 형식으로 다시 만들 수 없습니다."
