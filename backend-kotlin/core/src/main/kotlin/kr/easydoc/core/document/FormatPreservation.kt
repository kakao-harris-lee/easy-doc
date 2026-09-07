@file:Suppress("TooManyFunctions")

package kr.easydoc.core.document

// 원본 서식 유지 상태 — 계약 `FormatPreservationStatus`·`FormatPreservation`.
//
// 값 집합의 정본은 계약이고 이 파일은 그 대응이다. **`checking` 은 양쪽 어디에도 없다** —
// 사유는 [FormatPreservationStatus] KDoc 끝. 값 집합 대조: `ConversionFormatContractTest`.
//
// **`TooManyFunctions` 를 억제한다.** [FormatPreservation.details] KDoc 이 이미 정한 규칙 —
// 「이 파일이 문구를 전부 소유한다」 — 을 지키려면 판정마다 문구 함수 하나씩이 이 파일 안에
// 있어야 한다. 문구 함수를 다른 파일로 옮기면 그 규칙이 파일 경계 하나로 나뉘어 깨진다
// (`DocumentConfiguration` 이 조립 지점을 하나로 지키려고 같은 억제를 쓰는 것과 같은 판단).

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
     * 저장돼 있지 않아 되살릴 수 없는 문서이거나, PDF처럼 원본을 열어 반영한다는 개념
     * 자체가 적용되지 않는 문서다([choiceExportPreservation]). 모두 **영구히 참**이라
     * 구조 보존이 구현된 뒤에도 이 판정은 뒤집히지 않는다.
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
 * **[details] 에 문서 본문을 담지 않는다.** 담을 수 있는 것은 구조 요소의 종류와 개수뿐이다.
 * 이 파일이 문구를 **전부 소유하는 것**이 그 규칙의 형태다 — 문서에서 읽은 문자열이 흘러들
 * 자리가 없다.
 */
class FormatPreservation(
    val status: FormatPreservationStatus,
    val details: List<String>,
) {
    /** 상태와 **개수**만 남긴다 — 항목 문구는 사용자에게 보여 줄 값이지 로그에 남길 값이 아니다. */
    override fun toString(): String = "FormatPreservation(${status.wireName}, 항목 ${details.size}건)"
}

/**
 * 반영이 원본 구조와 검수본을 **어떻게 짝지었는가**(계획 §10.2 결정 4, 2026-09-06 리뷰 F1).
 *
 * boolean 플래그(`ordinalFallback`) 대신 세 값 enum 인 이유: S6-1 단독으로도 오늘의 판정이
 * 바이트 그대로 보존돼야 하는데, 두 값으로는 「지도 없음」과 「지도 거절」을 구분할 수 없어
 * 둘 중 하나의 문구가 틀린다.
 */
enum class ReflectionPlacement {
    /** 지도 없이 오늘처럼 차례로 짝짓는다 — 지도를 아직 쓰지 않는 반영기(S6-1)의 기본값. */
    ORDINAL,

    /** `segment_map` 으로 놓았다 — 대응을 확신한 반영이다(S6-2). */
    MAPPED,

    /** 지도를 받았으나 전제 검사에 실패해 차례 짝짓기로 떨어졌다(계획 §10.2 3항 첫 bullet). */
    ORDINAL_FALLBACK,
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
 *
 * 뒤 넷([mergedUnits]·[splitLines]·[lowConfidenceLines]·[placement])은 계획 §10.2 결정 4 —
 * `segment_map` 으로 짝짓는 S6 부터다. 기본값이 0/[ReflectionPlacement.ORDINAL] 인 것은
 * 지도를 아직 쓰지 않는 반영기(오늘의 차례 짝짓기)가 이 값을 몰라도 빌드가 서게 하려는 것이다.
 *
 * **여덟 필드**라 `LongParameterList` 를 억제한다 — 자리 맞춤이 실제로 낼 수 있는 여덟 갈래를
 * 하나씩 세는 값 객체이고, 묶어서 줄이면 그중 무엇이 몇 개인지가 필드 이름이 아니라 순서에
 * 기대게 된다(이 클래스가 막으려는 바로 그 실수).
 */
@Suppress("LongParameterList")
class ReflectionOutcome(
    /** 원본 문구를 그대로 두는 머리말·꼬리말 단위 수. **쓰지 않는 것**이 §6.5 의 「유지」다. */
    val headerFooterUnits: Int,
    /** 반영할 문단이 없어 **비워지는** 본문 단위 수. 원본 문구를 남기지 않는다. */
    val emptiedUnits: Int,
    /** 원본에 자리가 없어 본문 끝에 **덧붙는** 문단 수. 버리면 검수한 내용이 사라진다. */
    val appendedLines: Int,
    /**
     * 머리말·꼬리말 자리와 겹쳐 본문 끝으로 **옮겨 붙는** 검수본 문단 수.
     *
     * 추출기가 머리말 문구까지 읽어 가므로 검수본에도 그 줄이 들어 있다. 원본 머리말은 그대로
     * 두는 것이 「유지」라 그 자리에는 쓸 수 없지만, 그렇다고 줄을 버리면 담당자가 검수한
     * 문장이 파일에서 사라진다. 그래서 옮겨 붙이고, 옮겼다는 사실을 이 수로 말한다 —
     * [appendedLines] 와 나눠 세는 것은 **사유가 다르기 때문**이다(자리가 없었던 것이 아니라
     * 자리가 머리말의 몫이었다).
     */
    val displacedLines: Int,
    /**
     * `segment_map` 의 N:1 합침으로 **비워지는** 원본 단위 수(계획 §10.2 3항 N:1).
     *
     * [emptiedUnits] 와 뜻이 다르다 — 저쪽은 「받을 줄이 없어서」 비고, 이쪽은 「쉬운 글 한
     * 줄이 다른 원본 단위에 이미 쓰여서」 비운다. **뒤 문단이 밀리지 않으므로** 이 값만으로는
     * [SHIFTED_DETAIL] 을 붙이지 않는다 — 지도가 자리를 정확히 짚었기 때문이다.
     */
    val mergedUnits: Int = 0,
    /**
     * `segment_map` 의 1:N 나눔으로 원본 단위 문단 **바로 뒤에 새로 끼워 넣는** 줄 수
     * (계획 §10.2 3항 1:N). 끼워 넣을 뿐 뒤 문단을 밀지 않으므로 역시 [SHIFTED_DETAIL] 을
     * 붙이지 않는다.
     */
    val splitLines: Int = 0,
    /**
     * `LOW` confidence(순서 비례 보간)로 자리가 **차례 짝짓기와 다른 곳으로 실제로 옮겨진**
     * 줄 수(계획 §10.2 3항, 2026-09-06 리뷰 F1 정정). 원본 색인이 하나이고 그 색인이 투영된
     * 쉬운 글 색인과 같고(즉 차례 그대로) 그 자리에 **갈아 끼워졌으면**(끼워 넣기가 아니면)
     * 이 수에 넣지 않는다 — 앵커가 하나도 없는 문서는 지도가 전부 `LOW` 인데 개수가 같으면
     * 지도의 결론이 차례 짝짓기와 바이트 단위로 같아, 그 경우까지 세면 「정보가 아니라
     * 상수」가 된다. 실제로 자리를 옮긴 줄만 짐작이며, 그 수만큼이 [FormatPreservation] 이
     * `partial` 로 남는 근거다.
     */
    val lowConfidenceLines: Int = 0,
    /** 이 반영이 지도로 놓였는지, 차례로 짝지었는지, 지도를 거절하고 떨어졌는지. */
    val placement: ReflectionPlacement = ReflectionPlacement.ORDINAL,
) {
    override fun toString(): String =
        "ReflectionOutcome(머리말·꼬리말 $headerFooterUnits, 비움 $emptiedUnits, " +
            "덧붙임 $appendedLines, 옮김 $displacedLines, 합침 $mergedUnits, 나눔 $splitLines, " +
            "저확신 $lowConfidenceLines, 자리 맞춤 $placement)"
}

/** 되살릴 원본이 **없다**는 판정. 붙여넣기와 원본 바이트가 없는 옛 문서 — 둘 다 영구히 참이다. */
fun noOriginalPreservation(): FormatPreservation =
    FormatPreservation(FormatPreservationStatus.NOT_APPLICABLE, emptyList())

/**
 * **선택지가 있는 원본**(오늘은 PDF)의 판정 — 반영할 대상이 없다.
 *
 * [noOriginalPreservation] 과 사유가 다르다: 원본 파일 바이트는 저장돼 있을 수 있지만
 * (`hasStoredOriginal`) 그 원본을 **열어 반영하지 않는다** — 사용자가 고른 형식으로
 * 새 문서를 조립할 뿐이다(2.6.0 재결정, `ConversionExportService`). 「원본이 없다」가
 * 아니라 「원본은 있어도 반영이라는 개념이 이 갈래에 적용되지 않는다」는 것이 이 함수와
 * [noOriginalPreservation] 을 나누는 이유다. 완료 전후를 가리지 않고 **즉시** 이 값이다 —
 * 짝지을 검수본을 기다릴 이유가 없다(`ExportFormat.choicesFor` 가 이미 아는 사실이다).
 */
fun choiceExportPreservation(): FormatPreservation =
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
 * - 머리말·꼬리말 자리와 겹친 문단도 본문 끝으로 **옮겨 붙인다** — 같은 사유다. 자리를
 *   건너뛰고 뒤 문단을 당겨 오면 본문 전체가 한 칸씩 밀리므로 자리는 그대로 두고 줄만 옮긴다.
 *
 * ## `segment_map` 이 짝지은 경우 (계획 §10.2 결정 4, S6)
 *
 * 지도가 놓은 합침(N:1)·나눔(1:N)은 **대응을 확신한 반영**이라 그 자체로는 `available`을
 * 깨지 않는다 — 다만 무슨 일이 일어났는지는 [FormatPreservation.details] 로 말한다(그래서
 * `available` 에도 details 가 붙을 수 있다). `LOW` confidence 로 짐작한 자리(저확신)와
 * 지도 전제 검사가 어긋나 차례 짝짓기로 떨어진 경우([ReflectionPlacement.ORDINAL_FALLBACK])는
 * 여전히 「대응을 확신하지 못한 반영」이라 `partial` 의 근거다.
 *
 * [SHIFTED_DETAIL] 은 [ReflectionPlacement.MAPPED] 가 **아니고** 비움·덧붙임이 있을 때
 * (오늘의 규칙 그대로) 붙고, [ReflectionPlacement.ORDINAL_FALLBACK] 이면 **언제나** 붙는다 —
 * 폴백은 오늘의 차례 짝짓기와 같은 자리 맞춤이라 짝이 하나만 어긋나도 그 뒤 모든 문단이
 * 밀리기 때문이다. `MAPPED` 에서는 절대 붙지 않는다 — 지도가 놓은 문단은 밀리지 않는다.
 *
 * 머리말·꼬리말은 이 축과 무관하다 — 그 문구가 원본으로 남는 한(§6.5 의 「유지」) 자리 맞춤이
 * 무엇이든 `available` 이 아니다.
 */
fun reflectedPreservation(outcome: ReflectionOutcome): FormatPreservation {
    val details = detailsOf(outcome)
    return if (isAvailable(outcome)) {
        FormatPreservation(FormatPreservationStatus.AVAILABLE, details)
    } else {
        FormatPreservation(FormatPreservationStatus.PARTIAL, details)
    }
}

/** [reflectedPreservation] 이 말할 항목 전부 — 갈래마다 하나씩, 순서가 사용자에게 보이는 순서다. */
private fun detailsOf(outcome: ReflectionOutcome): List<String> =
    buildList {
        if (outcome.headerFooterUnits > 0) add(headerFooterDetail(outcome.headerFooterUnits))
        if (outcome.displacedLines > 0) add(displacedDetail(outcome.displacedLines))
        if (outcome.emptiedUnits > 0) add(emptiedDetail(outcome.emptiedUnits))
        if (outcome.appendedLines > 0) add(appendedDetail(outcome.appendedLines))
        if (outcome.mergedUnits > 0) add(mergedDetail(outcome.mergedUnits))
        if (outcome.splitLines > 0) add(splitDetail(outcome.splitLines))
        if (outcome.lowConfidenceLines > 0) add(lowConfidenceDetail(outcome.lowConfidenceLines))
        if (outcome.placement == ReflectionPlacement.ORDINAL_FALLBACK) add(FALLBACK_DETAIL)
        if (isShifted(outcome)) {
            // 지도가 놓은(`MAPPED`) 문단은 자리를 지도가 직접 짚었거나(합침·나눔) 자리를
            // 소비한 채 끝으로 갔으므로(옮김) 뒤 문단이 밀리지 않는다 — `MAPPED` 에서는
            // 이 갈래에 넣지 않는다.
            add(SHIFTED_DETAIL)
        }
    }

/**
 * 뒤쪽 문단이 밀릴 수 있는가 — [ReflectionPlacement.MAPPED] 가 아니고 비움·덧붙임이 있을
 * 때(오늘의 규칙 그대로), 그리고 [ReflectionPlacement.ORDINAL_FALLBACK] 이면 언제나.
 */
private fun isShifted(outcome: ReflectionOutcome): Boolean =
    (outcome.placement != ReflectionPlacement.MAPPED && (outcome.emptiedUnits > 0 || outcome.appendedLines > 0)) ||
        outcome.placement == ReflectionPlacement.ORDINAL_FALLBACK

/** 짝이 하나도 어긋나지 않았는가 — 합침·나눔은 세지 않는다(대응을 확신한 반영이다). */
private fun isAvailable(outcome: ReflectionOutcome): Boolean =
    outcome.headerFooterUnits == 0 &&
        outcome.displacedLines == 0 &&
        outcome.emptiedUnits == 0 &&
        outcome.appendedLines == 0 &&
        outcome.lowConfidenceLines == 0 &&
        outcome.placement != ReflectionPlacement.ORDINAL_FALLBACK

/**
 * 사용자에게 그대로 보이는 문구들. **개수와 요소의 종류만 넣는다** — 문서에서 읽은 문자열을
 * 끼워 넣는 형식 인자가 하나도 없어야 한다.
 */
private fun headerFooterDetail(count: Int): String = "머리말·꼬리말 ${count}곳은 원본 문구를 그대로 둡니다."

private fun displacedDetail(count: Int): String = "머리말·꼬리말 자리와 겹친 문단 ${count}개는 본문 끝으로 옮겨 붙습니다."

private fun emptiedDetail(count: Int): String = "원본 문단 ${count}개는 반영할 내용이 없어 빈 문단으로 남습니다."

private fun appendedDetail(count: Int): String = "문단 ${count}개는 원본에 자리가 없어 본문 끝에 덧붙습니다."

private fun mergedDetail(count: Int): String = "원본 문단 ${count}개는 앞 문단과 합쳐져 빈 문단으로 남습니다."

private fun splitDetail(count: Int): String = "문단 ${count}개는 원본 문단이 나뉘어 그 뒤에 새 문단으로 들어갑니다."

private fun lowConfidenceDetail(count: Int): String = "문단 ${count}개는 원본 자리를 확신할 수 없어 앞뒤 비율로 자리를 옮겨 넣었습니다."

private const val SHIFTED_DETAIL: String = "문단 수가 원본과 달라 뒤쪽 문단의 서식이 밀릴 수 있습니다."

private const val FALLBACK_DETAIL: String = "원본 구조와 문단 수를 맞출 수 없어 차례대로 반영합니다."

private const val UNREADABLE_ORIGINAL_DETAIL: String = "원본 파일을 열 수 없어 같은 형식으로 다시 만들 수 없습니다."
