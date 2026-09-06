package kr.easydoc.infrastructure.export

import kr.easydoc.core.document.ReflectionOutcome
import kr.easydoc.core.document.ReflectionPlacement
import kr.easydoc.core.segment.SegmentConfidence
import kr.easydoc.core.segment.SegmentMap
import kr.easydoc.core.segment.SegmentUnit
import kr.easydoc.core.segment.splitUnits
import org.w3c.dom.Document
import org.w3c.dom.Element

/**
 * 원본 단위와 검수본 문단의 **자리 맞춤 한 벌**.
 *
 * 판정([OriginalStructureReflectorAdapter.outline])과 실제 반영(`reflect`)이 **이 한 함수를
 * 함께 쓴다.** 두 자리에 규칙을 각각 적으면 응답이 말한 「일부 유지」와 파일 안의 실제가
 * 조용히 갈리고, 그 어긋남은 사용자가 파일을 열어 보기 전까지 아무 신호도 내지 않는다.
 *
 * **검수본 문단은 이 한 벌에서 하나도 빠지지 않는다.** 줄마다 갈 곳이 정확히 하나다 —
 * [written] 으로 원본 자리에 들어가거나, [inserted] 로 원본 단위 문단 바로 뒤에 끼어들거나,
 * [appended] 로 본문 끝에 붙는다. 세 갈래의 어디에도 없는 줄이 생기면 담당자가 검수한 문장이
 * 파일에서 소리 없이 사라진다.
 *
 * **열한 필드**라 `LongParameterList` 를 억제한다 — [kr.easydoc.core.document.ReflectionOutcome]
 * 과 같은 판단이다. 자리 맞춤이 실제로 낼 수 있는 갈래를 하나씩 세는 값 객체라 묶어서 줄이면
 * 그중 무엇이 몇 개인지가 필드 이름이 아니라 순서에 기대게 된다.
 */
@Suppress("LongParameterList")
internal class ReflectionPlan(
    /** 그대로 갈아 끼울 짝. 머리말·꼬리말 단위는 **여기 들어오지 않는다.** */
    val written: List<Assignment>,
    /** 반영할 문단이 없어 비울 본문 단위. 원본 문구를 남기지 않는다. */
    val emptied: List<TextUnit>,
    /**
     * 본문 끝에 덧붙일 문단 — **이미 정해진 차례**(머리말·꼬리말 자리와 겹쳐 옮겨 붙는 문단과
     * 자리가 없어 넘치는 문단이 함께, 원래 줄이 놓인 순서 그대로다).
     *
     * ORDINAL/ORDINAL_FALLBACK 은 옮김이 언제나 넘침보다 앞선 자리에서만 나오므로 이 순서가
     * 「옮김 전부 + 넘침 전부」와 같다. MAPPED 는 지도 순서로 훑으며 옮김·넘침이 **번갈아
     * 나올 수 있어**(계획 §10.2 3항) 만난 순서 그대로 한 목록에 쌓아야 순서가 보존된다 — 둘로
     * 나눠 개수만 셌다가 나중에 이어 붙이면(합치기 전 순서) 순서가 갈린다(2026-09-06 리뷰 F3).
     * 개수는 [displacedCount]·[overflowCount] 로 따로 센다.
     */
    val appended: List<String>,
    /** [appended] 중 머리말·꼬리말 자리와 겹쳐 옮겨 붙은 수. */
    private val displacedCount: Int,
    /** [appended] 중 자리가 없어 넘친 수. */
    private val overflowCount: Int,
    private val headerFooterUnits: Int,
    /**
     * 덧붙일 문단의 서식 본. 마지막으로 쓴 본문 단위이고, 본문 단위가 하나도 없으면 원본의
     * 마지막 단위다 — 본을 못 구해 내보내기가 통째로 실패하는 것보다 머리말의 문단 모양을
     * 빌리는 편이 낫다. 원본에 단위가 아예 없을 때만 `null`.
     */
    val appendTemplate: TextUnit?,
    /**
     * `segment_map` 의 1:N 나눔으로 원본 단위 문단 **바로 뒤에 새로 끼워 넣을** 줄들
     * (계획 §10.2 3항 1:N, S6-2). 지도가 없는 반영기(오늘의 차례 짝짓기)는 언제나 비어 있다.
     */
    val inserted: List<Insertion> = emptyList(),
    /**
     * `segment_map` 의 N:1 합침으로 **비워지는** 원본 단위(계획 §10.2 3항 N:1, S6-2).
     * [emptied] 와 뜻이 다르지만(「받을 줄이 없어서」 대 「이미 다른 단위에 쓰여서」) 반영은
     * 둘 다 `rewrite("")` 로 같다 — 원본 문구를 남기지 않는 것은 같은 규칙이다.
     */
    val merged: List<TextUnit> = emptyList(),
    private val splitLines: Int = 0,
    private val lowConfidenceLines: Int = 0,
    private val placement: ReflectionPlacement = ReflectionPlacement.ORDINAL,
) {
    class Assignment(
        val unit: TextUnit,
        val line: String,
    )

    /**
     * 원본 단위 문단 [afterUnit] **바로 뒤에** 끼워 넣을 줄들(1:N 나눔). 서식은 [afterUnit] 의
     * 속성만 베낀다 — [appendTemplate] 로 덧붙일 때와 같은 방식.
     */
    class Insertion(
        val afterUnit: TextUnit,
        val lines: List<String>,
    )

    /** 응답이 사용자에게 말할 개수. */
    fun outcome(): ReflectionOutcome =
        ReflectionOutcome(
            headerFooterUnits = headerFooterUnits,
            emptiedUnits = emptied.size,
            appendedLines = overflowCount,
            displacedLines = displacedCount,
            mergedUnits = merged.size,
            splitLines = splitLines,
            lowConfidenceLines = lowConfidenceLines,
            placement = placement,
        )
}

/**
 * 자리 맞춤의 진입점 — [map] 이 전제 검사([isWellFormed])를 지나면 지도로 짝짓고
 * ([mappedPlanOf]), 아니면 차례로 짝짓는다([ordinalPlanOf], 계획 §10.2 3항).
 *
 * ## 전제 검사 — 지도가 이 원본·이 줄 목록에 실제로 쓸 수 있는 모양이어야 지도를 쓴다
 *
 * `map.sourceUnitCount == units.size`(내보내기 순회가 본 단위 수, 머리말·꼬리말 포함)이고
 * `map.easyUnitCount == lines.size`. [map] 은 부르는 쪽([PackagedOriginalReflector])이 이미
 * **빈 줄 투영**([projectToContentLines])을 지나 온 값이다 — `lines`([kr.easydoc.core.easyread.exportContentLines])는
 * 빈 줄을 버리지만 `segment_map`([SegmentMap.easyUnitCount])은 [splitUnits] 그대로(빈 줄 포함)라
 * 투영 없이는 색인이 애초에 맞지 않는다. 개수가 같아도 [SegmentMap.units] 의 색인이 실제로
 * `lines`·`units` 범위를 정확히 덮지 않으면(2026-09-06 리뷰 F4 — 훼손된 지도가 예외로 내보내기
 * 전체를 무너뜨리면 안 된다) 마찬가지로 거절한다.
 *
 * 어느 쪽이든 어긋나면 **지도를 버리고 차례 짝짓기로 떨어진다.** [mapAttempted] 가 그 사실을
 * [ReflectionPlacement.ORDINAL_FALLBACK] 과 [ReflectionPlacement.ORDINAL] 로 가른다 — 지도를
 * 아예 받지 못한 반영기(오늘의 차례 짝짓기, S6-1)와 지도를 받았으나 거절한 경우가 같은 「지도
 * 없음」으로 보이면 §7 리스크 2(추출 줄 수 ≠ 내보내기 단위 수)가 조용히 넘어간다.
 */
internal fun planOf(
    units: List<WalkedUnit>,
    lines: List<String>,
    map: SegmentMap?,
    mapAttempted: Boolean,
): ReflectionPlan {
    val usableMap = map?.takeIf { isWellFormed(it, units, lines) }
    return if (usableMap != null) {
        mappedPlanOf(units, lines, usableMap)
    } else {
        val placement = if (mapAttempted) ReflectionPlacement.ORDINAL_FALLBACK else ReflectionPlacement.ORDINAL
        ordinalPlanOf(units, lines, placement)
    }
}

/**
 * [map] 이 [units]·[lines] 에 그대로 쓸 수 있는 모양인가 — 개수만이 아니라 **색인의 완전성**까지
 * 잰다(2026-09-06 리뷰 F4). 어느 하나라도 어긋나면 지도를 버려야 하므로 예외 대신 `false` 다 —
 * 훼손된 지도 한 건이 내보내기 전체를 무너뜨리지 않는다.
 *
 * - 개수: `map.sourceUnitCount == units.size` 이고 `map.easyUnitCount == lines.size`.
 * - **쉬운 글 색인의 전사성**: [SegmentMap.units] 의 `easyUnitIndex` 집합이 `lines.indices` 와
 *   정확히 같다(중복도, 빠짐도 없다) — [mappedPlanOf] 가 `byEasyIndex.getValue(easyIndex)` 로
 *   모든 줄의 자리를 찾으므로, 하나라도 비면 그 조회가 예외로 터진다.
 * - **원본 색인의 유효성**: 모든 `sourceUnitIndexes` 값이 `units.indices` 안이다 —
 *   그렇지 않으면 [MappedPlanBuilder] 가 `units[sources.first()]` 에서 범위를 벗어난다.
 */
private fun isWellFormed(
    map: SegmentMap,
    units: List<WalkedUnit>,
    lines: List<String>,
): Boolean {
    val easyIndexes = map.units.map { it.easyUnitIndex }
    return map.sourceUnitCount == units.size &&
        map.easyUnitCount == lines.size &&
        easyIndexes.size == lines.size &&
        easyIndexes.toSet() == lines.indices.toSet() &&
        map.units.all { unit -> unit.sourceUnitIndexes.all { it in units.indices } }
}

/**
 * 쉬운 글 전체 단위([splitUnits], 빈 줄 포함) 중 [kr.easydoc.core.easyread.exportContentLines] 가
 * **실제로 세는 자리**로 `segment_map` 을 다시 색인한다 — **빈 줄 투영**(계획 §10.2 3항 전제
 * 검사 첫 문장).
 *
 * `segment_map`([SegmentMap.easyUnitCount])은 [splitUnits] 그대로라 빈 줄에도 자리가 있지만,
 * 내보내기가 원본에 짝지을 `lines` 는 빈 줄을 버린다([kr.easydoc.core.easyread.exportContentLines]
 * KDoc — 추출기가 애초에 빈 블록을 버려서 원본 단위 쪽에 그 자리가 없다). 두 색인이 다른 채로
 * `segment_map` 을 그대로 쓰면 빈 줄 하나만 있어도 그 뒤 모든 자리가 밀린다.
 *
 * [map] 이 [body] 에서 유도된 지도와 애초에 모양이 다르면(2026-09-06 리뷰 F4 —
 * `map.easyUnitCount` 가 [splitUnits] 로 본 전체 단위 수와 다르거나, [SegmentMap.units] 개수가
 * `easyUnitCount` 와 다르면) **투영을 포기하고 `null`**. [lineCount]([lines].size)와 실제로
 * 살아남는 자리 수가 다를 때도 같다. 부르는 쪽이 그 신호를 [mapAttempted] 로 살려 차례 짝짓기로
 * 떨어뜨린다 — 조용히 잘못된 자리를 짚지 않는다.
 */
internal fun projectToContentLines(
    map: SegmentMap,
    body: String,
    lineCount: Int,
): SegmentMap? {
    val easyUnits = splitUnits(body)
    val invalidShape = map.easyUnitCount != easyUnits.size || map.units.size != map.easyUnitCount
    val keep = easyUnits.indices.filter { easyUnits[it].isNotBlank() }
    if (invalidShape || keep.size != lineCount) return null

    val projected =
        keep.mapIndexedNotNull { newIndex, oldIndex ->
            map.units.getOrNull(oldIndex)?.let { unit ->
                SegmentUnit(newIndex, unit.sourceUnitIndexes, unit.confidence)
            }
        }
    return projected.takeIf { it.size == keep.size }?.let { SegmentMap(map.sourceUnitCount, lineCount, it) }
}

/**
 * [ReflectionPlan.Insertion] 하나를 적용한다 — DOCX·HWPX 반영기의 `insertAfter` 가 이 함수 하나를
 * 공유한다(2026-09-06 리뷰 F5, [ReflectionPlan] KDoc의 「같은 자리 맞춤」과 같은 이유로 「같은
 * 삽입」도 하나여야 한다). [newParagraph] 는 형식별 문단 조립 방식([TextUnit.anchor]·`texts`에서
 * 속성만 복사)을 알 뿐 DOM 삽입 위치는 모른다 — 그 위치가 여기서 정해진다.
 *
 * 앵커([ReflectionPlan.Insertion.afterUnit] 의 [TextUnit.anchor])가 없으면 **끼워 넣을 줄을
 * 조용히 버리지 않고 예외로 멈춘다** — [mappedPlanOf] 가 원본 단위에서 뽑은 앵커이므로 `null`
 * 이면 반영기 자체의 불변식이 깨진 것이지, 사용자 입력으로 정상적으로 일어날 수 있는 일이 아니다.
 */
internal fun applyInsertion(
    insertion: ReflectionPlan.Insertion,
    newParagraph: (Document, TextUnit, String) -> Element,
) {
    val anchor = insertion.afterUnit.anchor ?: error("문단 조상이 없는 단위 뒤에는 끼워 넣을 수 없다")
    val parent = anchor.parentNode ?: error("문단 조상이 없는 단위 뒤에는 끼워 넣을 수 없다")
    val owner = anchor.ownerDocument
    val refNode = anchor.nextSibling
    insertion.lines.forEach { line -> parent.insertBefore(newParagraph(owner, insertion.afterUnit, line), refNode) }
}

/**
 * 자리 맞춤 규칙 — **문서 순서로 앞에서부터 짝짓는다**(계획 §10.2 3항 폴백 갈래, `map = null`
 * 이거나 전제 검사에 실패했을 때).
 *
 * ## 왜 내용이 아니라 자리로 맞추는가
 *
 * 원본 단위와 검수본 문단 사이에 1:1 대응이 보장되지 않는다. 모델이 두 문단을 하나로 합치거나
 * 한 문단을 둘로 나눌 수 있고, 그 결과는 **텍스트로만** 남아 어느 문단이 어느 원본에서 왔는지
 * 말해 주는 표식이 없다. 내용 유사도로 맞추는 방법도 생각할 수 있지만, 쉬운 글 변환은 문장을
 * **다시 쓰는** 일이라 유사도가 가장 낮아지는 자리에서 가장 크게 틀린다 — 정확히 매핑이 가장
 * 필요한 곳에서 못 미더워진다는 뜻이다. 그래서 지도가 없을 때는 우리가 실제로 아는 것 하나
 * (**차례**)만 쓴다.
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
private fun ordinalPlanOf(
    units: List<WalkedUnit>,
    lines: List<String>,
    placement: ReflectionPlacement,
): ReflectionPlan {
    val paired = minOf(units.size, lines.size)
    val written =
        (0 until paired)
            .filter { units[it].isBody }
            .map { ReflectionPlan.Assignment(units[it].unit, lines[it]) }
    // 옮김(머리말·꼬리말 자리와 겹침)이 언제나 넘침(자리가 없어 남음)보다 앞선 줄 번호에서만
    // 나오므로, 여기서는 「옮김 전부 + 넘침 전부」가 곧 원래 줄 번호 순서다(MAPPED 는 다르다 —
    // [MappedPlanBuilder] 의 `appendedTail` KDoc 참고).
    val displaced = (0 until paired).filterNot { units[it].isBody }.map { lines[it] }
    val overflow = lines.drop(units.size)
    return ReflectionPlan(
        written = written,
        emptied = units.drop(paired).filter { it.isBody }.map { it.unit },
        appended = displaced + overflow,
        displacedCount = displaced.size,
        overflowCount = overflow.size,
        headerFooterUnits = units.count { !it.isBody },
        appendTemplate = written.lastOrNull()?.unit ?: units.lastOrNull()?.unit,
        placement = placement,
    )
}

/**
 * `segment_map` 이 짝지은 자리 맞춤(계획 §10.2 3항, S6-2) — **대응을 확신한 반영**이다.
 *
 * 쉬운 글 줄을 지도 순서([SegmentMap.units], `easyUnitIndex` 오름차순)로 훑으며 각 줄의
 * `sourceUnitIndexes` 첫 원본 단위(`s1`)를 그 줄의 자리로 삼는다.
 *
 * - **`s1` 이 머리말·꼬리말이면 자리에 쓰지 않고 옮겨 붙인다**(displaced) — 나머지
 *   `sourceUnitIndexes` 는 살펴보지 않는다. 머리말·꼬리말은 이 갈래 안에서 절대 갈아 끼우거나
 *   끼워 넣지 않는다.
 * - **`s1` 이 본문이고 아직 아무 줄도 받지 않았으면 갈아 끼운다**(written) — 1:1 이든 N:1
 *   합침의 첫 줄이든 같다.
 * - **`s1` 이 이미 다른 줄을 받았으면 그 문단 바로 뒤에 새 문단으로 끼워 넣는다**(inserted,
 *   `splitLines` 로 센다) — 1:N 나눔의 둘째 줄부터가 이 갈래다. 어느 쪽이 이 갈래를 타든
 *   끼워 넣는 동작 자체는 같아서 `splitLines` 를 나눠 세지 않는다(리뷰 시 확인할 선택).
 * - **`s1` 뒤의 나머지 원본 단위**(`s2..sk`, N:1 합침의 «나머지»)는 본문이고 아직 아무 줄도
 *   받지 않았으면 **비운다**(merged) — 이미 받았으면(자기 차례에 `s1` 로 쓰였으면) 건드리지
 *   않는다. 머리말·꼬리말은 셈하지 않는다 — 원본 문구를 그대로 두는 규칙이 이 갈래보다 앞선다.
 * - **`sourceUnitIndexes` 가 비었으면 덧붙인다**(overflow, 퇴화 지도).
 * - **`LOW` confidence 로 자리를 받은 줄**(written 이든 inserted 든)은 원칙적으로
 *   `lowConfidenceLines` 로 센다 — 단 **차례 짝짓기와 자리가 완전히 같으면 세지 않는다**
 *   (2026-09-06 리뷰 F1 정정, [MappedPlanBuilder.placeInBody] 참고). displaced·overflow 는
 *   자리 자체가 지도의 몫이 아니라 세지 않는다.
 * - 끝까지 아무 줄도 받지 못하고 합침으로도 비워지지 않은 본문 단위는 비운다(emptied, 오늘과
 *   같은 뜻 — 「받을 줄이 없어서」).
 */
private fun mappedPlanOf(
    units: List<WalkedUnit>,
    lines: List<String>,
    map: SegmentMap,
): ReflectionPlan {
    val byEasyIndex = map.units.associateBy { it.easyUnitIndex }
    val builder = MappedPlanBuilder(units)
    lines.indices.forEach { easyIndex -> builder.place(lines[easyIndex], byEasyIndex.getValue(easyIndex)) }
    return builder.build()
}

/**
 * [mappedPlanOf] 의 상태 기계 — 지도 순서로 줄을 하나씩 받아 [written]·[inserted]·[merged]·
 * [appendedTail]와 확신 카운터를 함께 쌓는다.
 *
 * 별도 클래스로 뺀 것은 **중첩 깊이**를 낮추기 위해서다 — `when`·`if`·`forEach` 가 한 함수 안에
 * 겹치면 detekt `NestedBlockDepth` 를 넘는다. 갈래마다 함수 하나씩(`place`·`placeInBody`·
 * `markMerged`)으로 나누면 그 깊이가 함수 경계로 흩어진다.
 */
private class MappedPlanBuilder(private val units: List<WalkedUnit>) {
    private val written = mutableListOf<ReflectionPlan.Assignment>()
    private val insertionsByUnitIndex = linkedMapOf<Int, MutableList<String>>()
    private val usedUnitIndex = mutableSetOf<Int>()
    private val mergedUnitIndex = mutableSetOf<Int>()

    /**
     * 옮김(머리말·꼬리말 자리와 겹침)과 넘침(퇴화 지도)이 **만난 순서 그대로** 쌓인다
     * (2026-09-06 리뷰 F3). 지도 순서는 문서 순서와 달리 옮김·넘침이 번갈아 나올 수 있어
     * ([ReflectionPlan.appended] KDoc), 둘로 나눠 세었다가 나중에 이어 붙이면(옮김 전부 +
     * 넘침 전부) 실제 줄 번호 순서가 깨진다. 개수는 [displacedCount]·[overflowCount] 로
     * 따로 센다.
     */
    private val appendedTail = mutableListOf<String>()
    private var displacedCount = 0
    private var overflowCount = 0
    private var splitLines = 0
    private var lowConfidenceLines = 0

    /** 쉬운 글 줄 하나의 자리를 정한다 — [segment] 의 `sourceUnitIndexes` 첫 원본 단위가 그 자리다. */
    fun place(
        line: String,
        segment: SegmentUnit,
    ) {
        val sources = segment.sourceUnitIndexes
        when {
            sources.isEmpty() -> {
                appendedTail += line
                overflowCount++
            }

            !units[sources.first()].isBody -> {
                appendedTail += line
                displacedCount++
            }

            else -> {
                placeInBody(line, segment, sources)
            }
        }
    }

    /** `s1` 이 본문일 때만 타는 갈래 — 갈아 끼우거나(첫 줄) 바로 뒤에 끼워 넣는다(둘째 줄부터). */
    private fun placeInBody(
        line: String,
        segment: SegmentUnit,
        sources: List<Int>,
    ) {
        val target = sources.first()
        val insertedInstead = target in usedUnitIndex
        if (insertedInstead) {
            insertionsByUnitIndex.getOrPut(target) { mutableListOf() } += line
            splitLines++
        } else {
            written += ReflectionPlan.Assignment(units[target].unit, line)
            usedUnitIndex += target
        }
        markMerged(sources.drop(1))
        // 계획 §10.2 3항(2026-09-06 리뷰 F1) 면제 — 원본 색인이 하나이고 그 색인이 이 줄의
        // 투영된 쉬운 글 색인과 같고(차례 그대로) 갈아 끼워졌으면(끼워 넣기가 아니면) LOW 라도
        // 세지 않는다. 앵커가 없어 지도가 전부 LOW 인 문서가 개수만 같을 때, 결론이 차례
        // 짝짓기와 바이트 단위로 같은데도 `partial` 로 낮추는 것은 정보가 아니라 상수다.
        val atOrdinalSlot = sources.size == 1 && target == segment.easyUnitIndex && !insertedInstead
        if (segment.confidence == SegmentConfidence.LOW && !atOrdinalSlot) lowConfidenceLines++
    }

    /** `s1` 뒤의 나머지 원본 단위(N:1 합침의 «나머지») 중 아직 제 몫을 못 받은 본문 단위를 합침으로 표시한다. */
    private fun markMerged(extras: List<Int>) {
        extras.forEach { extra -> if (units[extra].isBody && extra !in usedUnitIndex) mergedUnitIndex += extra }
    }

    fun build(): ReflectionPlan {
        // 나중에 처리된 줄이 `extra` 자리를 자기 몫으로 써 버렸으면(자리가 「합침」이 아니라
        // 「제 것」이 된 것) 합침 목록에서 뺀다 — 갈아 끼운 단위를 다시 비우면 그 줄이 사라진다.
        val merged = (mergedUnitIndex - usedUnitIndex).map { units[it].unit }
        val emptied =
            units.indices
                .filter { units[it].isBody && it !in usedUnitIndex && it !in mergedUnitIndex }
                .map { units[it].unit }
        val inserted =
            insertionsByUnitIndex.entries
                .sortedBy { it.key }
                .map { (index, insertedLines) -> ReflectionPlan.Insertion(units[index].unit, insertedLines) }

        return ReflectionPlan(
            written = written,
            emptied = emptied,
            appended = appendedTail,
            displacedCount = displacedCount,
            overflowCount = overflowCount,
            headerFooterUnits = units.count { !it.isBody },
            appendTemplate = written.lastOrNull()?.unit ?: units.lastOrNull()?.unit,
            inserted = inserted,
            merged = merged,
            splitLines = splitLines,
            lowConfidenceLines = lowConfidenceLines,
            placement = ReflectionPlacement.MAPPED,
        )
    }
}
