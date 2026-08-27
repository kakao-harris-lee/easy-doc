package kr.easydoc.infrastructure.export

import kr.easydoc.core.document.ReflectionOutcome

/**
 * 원본 단위와 검수본 문단의 **자리 맞춤 한 벌**.
 *
 * 판정([OriginalStructureReflectorAdapter.outline])과 실제 반영(`reflect`)이 **이 한 함수를
 * 함께 쓴다.** 두 자리에 규칙을 각각 적으면 응답이 말한 「일부 유지」와 파일 안의 실제가
 * 조용히 갈리고, 그 어긋남은 사용자가 파일을 열어 보기 전까지 아무 신호도 내지 않는다.
 *
 * **검수본 문단은 이 한 벌에서 하나도 빠지지 않는다.** 줄마다 갈 곳이 정확히 하나다 —
 * [written] 으로 원본 자리에 들어가거나, [appended] 로 본문 끝에 붙는다. 세 목록의 어디에도
 * 없는 줄이 생기면 담당자가 검수한 문장이 파일에서 소리 없이 사라진다.
 */
internal class ReflectionPlan(
    /** 그대로 갈아 끼울 짝. 머리말·꼬리말 단위는 **여기 들어오지 않는다.** */
    val written: List<Assignment>,
    /** 반영할 문단이 없어 비울 본문 단위. 원본 문구를 남기지 않는다. */
    val emptied: List<TextUnit>,
    /** 머리말·꼬리말 단위와 자리가 겹쳐 원본에 쓸 수 없는 문단. **버리지 않고 옮겨 붙인다.** */
    private val displaced: List<String>,
    /** 원본 단위보다 문단이 많아 자리가 남지 않은 문단. */
    private val overflow: List<String>,
    private val headerFooterUnits: Int,
    /**
     * 덧붙일 문단의 서식 본. 마지막으로 쓴 본문 단위이고, 본문 단위가 하나도 없으면 원본의
     * 마지막 단위다 — 본을 못 구해 내보내기가 통째로 실패하는 것보다 머리말의 문단 모양을
     * 빌리는 편이 낫다. 원본에 단위가 아예 없을 때만 `null`.
     */
    val appendTemplate: TextUnit?,
) {
    /**
     * 본문 끝에 덧붙일 문단 — **검수본 차례 그대로**.
     *
     * [displaced] 가 [overflow] 보다 앞서는 것이 곧 원래 줄 번호 순서다(겹친 자리는 언제나
     * 넘친 자리보다 앞에 있다). 두 갈래를 개수로는 나눠 세지만([outcome]) 파일에 쓸 때는
     * 하나의 차례로 만난다.
     */
    val appended: List<String> = displaced + overflow

    class Assignment(
        val unit: TextUnit,
        val line: String,
    )

    /** 응답이 사용자에게 말할 개수. */
    fun outcome(): ReflectionOutcome =
        ReflectionOutcome(
            headerFooterUnits = headerFooterUnits,
            emptiedUnits = emptied.size,
            appendedLines = overflow.size,
            displacedLines = displaced.size,
        )
}

/**
 * 자리 맞춤 규칙 — **문서 순서로 앞에서부터 짝짓는다.**
 *
 * ## 왜 내용이 아니라 자리로 맞추는가
 *
 * 원본 단위와 검수본 문단 사이에 1:1 대응이 보장되지 않는다. 모델이 두 문단을 하나로 합치거나
 * 한 문단을 둘로 나눌 수 있고, 그 결과는 **텍스트로만** 남아 어느 문단이 어느 원본에서 왔는지
 * 말해 주는 표식이 없다. 내용 유사도로 맞추는 방법도 생각할 수 있지만, 쉬운 글 변환은 문장을
 * **다시 쓰는** 일이라 유사도가 가장 낮아지는 자리에서 가장 크게 틀린다 — 정확히 매핑이 가장
 * 필요한 곳에서 못 미더워진다는 뜻이다. 그래서 우리가 실제로 아는 것 하나(**차례**)만 쓴다.
 *
 * 짝을 확신할 수 없다는 사실은 감추지 않고 [ReflectionPlan.outcome] 으로 나가 `partial` 이
 * 된다. 짝이 하나도 어긋나지 않을 때만 「유지 가능」이다.
 *
 * ## 머리말·꼬리말 단위도 자리를 **차지한다**
 *
 * 추출기는 머리말·꼬리말 문구까지 읽어 가므로 검수본에도 그 줄이 들어 있다. 그래서 짝짓기는
 * 머리말 단위를 건너뛰지 않고 **자리만 소비한다** — 건너뛰면 그 뒤의 본문 단위가 한 칸씩
 * 당겨져 엉뚱한 문단에 엉뚱한 문장이 들어간다. 머리말이 본문 **사이**에 오는 HWPX 에서는
 * 그 어긋남이 문서 전체로 번진다(DOCX 는 머리글 파트가 본문 뒤라 우연히 티가 나지 않는다).
 *
 * 자리를 소비하되 **그 줄을 버리지는 않는다.** 머리말 자리와 겹친 줄은
 * [ReflectionPlan.appended] 로 흘러 본문 끝에 선다. 원본 머리말 문구는 그대로 남고
 * (§6.5 의 「유지」), 담당자가 검수한 문장도 파일 어딘가에 남는다. 옮겨 붙었다는 사실은
 * `displacedLines` 로 나가 응답이 개수로 말한다.
 *
 * ## 네 갈래 — **줄마다 갈 곳이 정확히 하나다**
 *
 * - **본문 단위와 짝이 되면 갈아 끼운다.**
 * - **머리말·꼬리말 자리와 겹치면 본문 끝으로 옮긴다.** 원본 문구를 그대로 두는 것이 §6.5 의
 *   「유지」이므로 그 자리에는 쓸 수 없지만, 버리면 검수한 내용이 사라진다.
 * - **단위가 남으면 비운다.** 원본 문구를 남기면 검수를 지나지 않은 문장이 「쉬운 글」 파일에
 *   섞인다 — 그 파일은 조용히 거짓말을 한다.
 * - **문단이 남으면 덧붙인다.** 버리면 담당자가 검수한 내용이 사라진다.
 */
internal fun planOf(
    units: List<WalkedUnit>,
    lines: List<String>,
): ReflectionPlan {
    val paired = minOf(units.size, lines.size)
    val written =
        (0 until paired)
            .filter { units[it].isBody }
            .map { ReflectionPlan.Assignment(units[it].unit, lines[it]) }
    return ReflectionPlan(
        written = written,
        emptied = units.drop(paired).filter { it.isBody }.map { it.unit },
        displaced = (0 until paired).filterNot { units[it].isBody }.map { lines[it] },
        overflow = lines.drop(units.size),
        headerFooterUnits = units.count { !it.isBody },
        appendTemplate = written.lastOrNull()?.unit ?: units.lastOrNull()?.unit,
    )
}
