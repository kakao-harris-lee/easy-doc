package kr.easydoc.infrastructure.export

import kr.easydoc.core.document.ReflectionOutcome

/**
 * 원본 단위와 검수본 문단의 **자리 맞춤 한 벌**.
 *
 * 판정([OriginalStructureReflectorAdapter.outline])과 실제 반영(`reflect`)이 **이 한 함수를
 * 함께 쓴다.** 두 자리에 규칙을 각각 적으면 응답이 말한 「일부 유지」와 파일 안의 실제가
 * 조용히 갈리고, 그 어긋남은 사용자가 파일을 열어 보기 전까지 아무 신호도 내지 않는다.
 */
internal class ReflectionPlan(
    /** 그대로 갈아 끼울 짝. 머리말·꼬리말 단위는 **여기 들어오지 않는다.** */
    val written: List<Assignment>,
    /** 반영할 문단이 없어 비울 본문 단위. 원본 문구를 남기지 않는다. */
    val emptied: List<TextUnit>,
    /** 원본에 자리가 없어 본문 끝에 덧붙일 문단. */
    val appended: List<String>,
    private val headerFooterUnits: Int,
) {
    class Assignment(
        val unit: TextUnit,
        val line: String,
    )

    /** 응답이 사용자에게 말할 개수. */
    fun outcome(): ReflectionOutcome =
        ReflectionOutcome(
            headerFooterUnits = headerFooterUnits,
            emptiedUnits = emptied.size,
            appendedLines = appended.size,
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
 * ## 세 갈래
 *
 * - **짝이 된 머리말·꼬리말 단위는 쓰지 않는다.** 원본 문구를 그대로 두는 것이 §6.5 의 「유지」다.
 * - **단위가 남으면 비운다.** 원본 문구를 남기면 검수를 지나지 않은 문장이 「쉬운 글」 파일에
 *   섞인다 — 그 파일은 조용히 거짓말을 한다.
 * - **문단이 남으면 덧붙인다.** 버리면 담당자가 검수한 내용이 사라진다.
 */
internal fun planOf(
    units: List<WalkedUnit>,
    lines: List<String>,
): ReflectionPlan {
    val paired = minOf(units.size, lines.size)
    return ReflectionPlan(
        written =
            (0 until paired)
                .filter { units[it].isBody }
                .map { ReflectionPlan.Assignment(units[it].unit, lines[it]) },
        emptied = units.drop(paired).filter { it.isBody }.map { it.unit },
        appended = lines.drop(units.size),
        headerFooterUnits = units.count { !it.isBody },
    )
}
