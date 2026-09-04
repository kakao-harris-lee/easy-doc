package kr.easydoc.core.segment

import kr.easydoc.core.easyread.extractFacts
import kr.easydoc.core.privacy.MaskCategory

// P0-4 문단 대응 — 계획 §2 「정렬 알고리즘 — patience 방식 앵커 + 차례 보간」.
//
// 차례만 쓰면 대응이 대부분의 문서에서 틀린다(문단을 여러 줄로 나누는 것이 프롬프트 규칙상
// 정상이라 그 뒤가 전부 밀린다). 그래서 다시 쓰기를 견뎌 살아남는 토큰만 앵커로 쓴다 —
// ⑴ 마스킹 자리표시자(`[[…]]`, 개수 보존이 프롬프트 규칙이자 검사 대상) ⑵
// `FactPreservation.extractFacts` 의 사실 추출 결과(숫자·날짜·시각·금액·백분율·연락처·URL).
//
// Spring 도 DB 도 I/O 도 모른다 — (원본 단위 목록, 쉬운 글 단위 목록) 의 순수 함수다.

/**
 * 쉬운 글 단위 하나가 원본 단위에 대응하는지에 대한 신뢰도.
 *
 * [HIGH] 는 앵커(자리표시자·사실)로 뒷받침된 대응이고, [LOW] 는 앵커가 없어 순서 비례
 * 보간으로만 나온 추정이다. 화면은 [HIGH] 만 대응으로 주장하고 [LOW] 는 「대응을 확인하지
 * 못했습니다」로 표시한다(계획 §2).
 */
enum class SegmentConfidence { HIGH, LOW }

/** 쉬운 글 단위 하나와 그것이 대응하는 원본 단위 색인들. */
data class SegmentUnit(
    val easyUnitIndex: Int,
    val sourceUnitIndexes: List<Int>,
    val confidence: SegmentConfidence,
)

/**
 * 원본 단위와 쉬운 글 단위 사이의 대응표. **저장하지 않고** (원본, 쉬운 글) 문자열 쌍에서
 * [alignSegments] 로 매번 유도한다(계획 §2 결정 2) — 검수 저장마다 낡는 두 번째 진실을
 * 만들지 않는다.
 */
data class SegmentMap(
    val sourceUnitCount: Int,
    val easyUnitCount: Int,
    val units: List<SegmentUnit>,
) {
    /** 색인·개수만 남긴다 — 본문을 담지 않는 값 타입이지만 다른 값 타입과 같은 규약을 지킨다. */
    override fun toString(): String =
        "SegmentMap(sourceUnitCount=$sourceUnitCount, easyUnitCount=$easyUnitCount, units=${units.size})"
}

/** 마스킹 자리표시자 모양(`[[주민등록번호1]]` 등) — `Masking.kt`·`FactPreservation.kt` 와 같은 구성. */
private val PLACEHOLDER: Regex =
    run {
        val categories = MaskCategory.entries.joinToString("|") { Regex.escape(it.label) }
        Regex("""\[\[(?:$categories)[0-9]+]]""")
    }

/**
 * 원본 단위 목록과 쉬운 글 단위 목록 사이의 대응을 구한다.
 *
 * 모든 쉬운 글 단위가 [SegmentMap.units] 에 정확히 한 번 나오고(전사성), 같은 입력을 두 번
 * 호출하면 결과가 완전히 같다(결정성) — 순서에 의존하는 `Map` 반복을 출력에 쓰지 않는다.
 */
fun alignSegments(
    sourceUnits: List<String>,
    easyUnits: List<String>,
): SegmentMap {
    val degenerate = degenerateSegmentMap(sourceUnits, easyUnits)
    if (degenerate != null) return degenerate

    val anchoredSourcesByEasyIndex = chooseAnchors(sourceUnits, easyUnits)
    val result = arrayOfNulls<SegmentUnit>(easyUnits.size)

    val realBreakpoints =
        anchoredSourcesByEasyIndex.entries
            .sortedBy { it.key }
            .map { (easyIndex, sources) -> Breakpoint(easyIndex, sources.first(), sources.last()) }
    realBreakpoints.forEach { breakpoint ->
        result[breakpoint.easyIndex] =
            SegmentUnit(
                breakpoint.easyIndex,
                anchoredSourcesByEasyIndex.getValue(breakpoint.easyIndex),
                SegmentConfidence.HIGH,
            )
    }

    val end = Breakpoint(easyUnits.size, sourceUnits.size, sourceUnits.size)
    val boundary = listOf(Breakpoint(-1, -1, -1)) + realBreakpoints + listOf(end)
    for (i in 0 until boundary.size - 1) {
        fillGap(boundary[i], boundary[i + 1], sourceUnits.size, result)
    }

    return SegmentMap(sourceUnits.size, easyUnits.size, result.map { requireNotNull(it) })
}

/** 원본·쉬운 글 어느 한쪽이라도 단위가 하나도 없는 경우를 앞서 처리한다 — 둘 다 있을 때만 정렬이 의미 있다. */
private fun degenerateSegmentMap(
    sourceUnits: List<String>,
    easyUnits: List<String>,
): SegmentMap? =
    when {
        easyUnits.isEmpty() -> {
            SegmentMap(sourceUnits.size, 0, emptyList())
        }

        sourceUnits.isEmpty() -> {
            val units = easyUnits.indices.map { SegmentUnit(it, emptyList(), SegmentConfidence.LOW) }
            SegmentMap(0, easyUnits.size, units)
        }

        else -> {
            null
        }
    }

/** 앵커로 뒷받침된 쉬운 글 단위 하나 — 원본 색인 하나 이상(구간의 양 끝)을 가진 tie point. */
private data class Breakpoint(
    val easyIndex: Int,
    val loSource: Int,
    val hiSource: Int,
)

/**
 * [from] 과 [to] 사이(양 끝 제외)의 쉬운 글 단위를 비례 배분으로 채운다 — 구간의 쉬운 글
 * 단위 `m` 개, 원본 단위 `n` 개일 때 구간 내 `j`번째 쉬운 글 단위 → `floor(j * n / m)`번째
 * 원본 단위(계획 §2 4항). 두 앵커 사이에 원본 단위가 하나도 남지 않으면([n]이 0 이하이면)
 * 앞 앵커의 바로 다음 자리로 몰아준다 — 갈 곳이 없을 때의 최선이다.
 */
private fun fillGap(
    from: Breakpoint,
    to: Breakpoint,
    sourceUnitCount: Int,
    result: Array<SegmentUnit?>,
) {
    val gapStart = from.easyIndex + 1
    val gapSize = to.easyIndex - gapStart
    if (gapSize <= 0) return

    val sourceStart = from.hiSource + 1
    val sourceGapSize = (to.loSource - sourceStart).coerceAtLeast(0)

    for (j in 0 until gapSize) {
        val offset = if (sourceGapSize > 0) (j * sourceGapSize) / gapSize else 0
        val sourceIndex = (sourceStart + offset).coerceIn(0, sourceUnitCount - 1)
        result[gapStart + j] = SegmentUnit(gapStart + j, listOf(sourceIndex), SegmentConfidence.LOW)
    }
}

/** 앵커 하나 — 원본·쉬운 글 양쪽에서 유일한 키가 만든 (쉬운 글 색인, 원본 색인) 쌍. */
private data class Anchor(
    val easyIndex: Int,
    val sourceIndex: Int,
)

/**
 * 앵커 후보를 골라 비감소 최장 부분수열(LIS)로 걸러낸 뒤, 쉬운 글 색인별 원본 색인 목록으로
 * 묶는다. 같은 쉬운 글 단위가 서로 다른 원본 색인의 앵커를 두 개 이상 얻으면(N:1 병합, A4)
 * 그 목록에 둘 다 남는다 — LIS 는 "강증가"가 아니라 "비감소"라 같은 쉬운 글 색인에서
 * 나온 서로 다른 원본 색인들이 함께 통과한다.
 */
private fun chooseAnchors(
    sourceUnits: List<String>,
    easyUnits: List<String>,
): Map<Int, List<Int>> {
    val sourceUnique = uniqueAnchorPositions(sourceUnits)
    val easyUnique = uniqueAnchorPositions(easyUnits)

    val candidates =
        easyUnique.keys
            .intersect(sourceUnique.keys)
            .map { key -> Anchor(easyUnique.getValue(key), sourceUnique.getValue(key)) }
            .distinct()
            .sortedWith(compareBy({ it.easyIndex }, { it.sourceIndex }))

    val kept =
        longestNonDecreasingSubsequence(candidates.map { it.sourceIndex }, sourceUnits.size)
            .map { candidates[it] }

    return kept
        .groupBy({ it.easyIndex }, { it.sourceIndex })
        .mapValues { (_, sources) -> sources.distinct().sorted() }
}

/**
 * 단위 하나에서 앵커 키를 뽑는다. 원본·쉬운 글 양쪽에 같은 함수를 적용해 비교한다 — 표기가
 * 달라도 [kr.easydoc.core.easyread.ExtractedFact.compareKey] 가 같으면 같은 앵커로 본다.
 */
private fun anchorKeys(unit: String): Set<String> {
    val keys = mutableSetOf<String>()
    PLACEHOLDER.findAll(unit).forEach { keys += it.value }
    extractFacts(unit).forEach { fact -> keys += "FACT:${fact.kind}:${fact.compareKey}" }
    return keys
}

/**
 * 키가 단위 목록 전체에서 **정확히 한 단위**에만 나타날 때만 그 단위 색인을 돌려준다
 * (patience diff 의 unique-line 발상, 계획 §2 2항). 같은 단위 안에서 같은 키가 여러 번
 * 나와도(같은 사실이 한 줄에 두 번) 그 키가 가리키는 단위는 하나뿐이므로 유일성은 유지된다.
 */
private fun uniqueAnchorPositions(units: List<String>): Map<String, Int> {
    val positions = mutableMapOf<String, MutableSet<Int>>()
    units.forEachIndexed { index, unit ->
        anchorKeys(unit).forEach { key -> positions.getOrPut(key) { mutableSetOf() }.add(index) }
    }
    return positions.filterValues { it.size == 1 }.mapValues { it.value.single() }
}

/**
 * [values] 의 **비감소** 최장 부분수열을 이루는 색인들을 원래 순서로 돌려준다,
 * `O(n log 원본 단위 수)`. 강증가가 아니라 비감소인 것이 1:N·N:1 대응을 허용하는 자리다
 * (계획 §2 3항).
 *
 * **동점이면 쉬운 글 색인이 작은 쪽을 남긴다**(계획 §2 3항 결정성 규칙) — 최장 길이를 여전히
 * 이룰 수 있는 후보 중 **가장 앞선 색인부터 그리디로 채택**해 구현한다. patience-sort
 * overwrite 로 재구성하면(피벗을 계속 덮어써 마지막에 갱신된 원소를 따라가면) 오히려 **더
 * 늦은** 쉬운 글 색인이 남는 반례가 있다 — `(easy0,src2), (easy1,src1), (easy2,src2)` 는 길이
 * 2 인 비감소 부분수열 후보가 `{easy0,easy2}`·`{easy1,easy2}` 둘이고, overwrite 재구성은
 * `easy1,easy2` 를 남겨 `easy0` 를 잘못 떨어뜨린다(리뷰 HIGH). 아래 [suffixLengths] 로
 * "이 색인에서 시작해 낼 수 있는 최장 길이"를 먼저 구해 두면, 왼쪽부터 훑으며 "아직 남은
 * 길이를 채울 수 있는" 첫 후보를 고르는 것만으로 가장 이른 색인을 남기는 재구성이 된다.
 */
private fun longestNonDecreasingSubsequence(
    values: List<Int>,
    sourceUnitCount: Int,
): List<Int> {
    if (values.isEmpty()) return emptyList()

    val suffixLength = suffixLengths(values, sourceUnitCount)
    val targetLength = suffixLength.max()

    val kept = mutableListOf<Int>()
    var remaining = targetLength
    var lastValue = -1
    for (i in values.indices) {
        if (remaining == 0) break
        if (values[i] >= lastValue && suffixLength[i] >= remaining) {
            kept += i
            remaining--
            lastValue = values[i]
        }
    }
    return kept
}

/**
 * 반환값의 색인 `i` 자리 = 원소 `i` 를 **첫 원소로 삼는**, 색인 `i` 이후 구간만 쓰는 비감소
 * 부분수열 중 가장 긴 것의 길이. 오른쪽에서 왼쪽으로 훑으며 원본 색인(값)을 키로 하는
 * [FenwickMax] 에 "그 값 이상에서 시작 가능한 최장 길이"를 누적한다 — 원본 색인 범위가
 * `0 until sourceUnitCount` 로 이미 좁아 좌표 압축이 필요 없다.
 */
private fun suffixLengths(
    values: List<Int>,
    sourceUnitCount: Int,
): IntArray {
    val maxIndex = sourceUnitCount - 1
    val bestFromValue = FenwickMax(sourceUnitCount)
    val length = IntArray(values.size)
    for (i in values.indices.reversed()) {
        val value = values[i]
        // 미러링(`maxIndex - value`)으로 "value 이상" 구간의 최댓값 질의를 접두사 질의로 바꾼다.
        val bestAfter = bestFromValue.prefixMax(maxIndex - value)
        length[i] = 1 + maxOf(bestAfter, 0)
        bestFromValue.update(maxIndex - value, length[i])
    }
    return length
}

/**
 * 점 갱신·접두사 최댓값 질의만 지원하는 Fenwick 트리. 값이 없는 자리는 [NONE] 이다.
 * [suffixLengths] 전용이라 이 파일 밖으로 나가지 않는다.
 */
private class FenwickMax(size: Int) {
    private val tree = IntArray(size + 1) { NONE }

    /** `[0, index]` 구간(0-based, 양 끝 포함)의 최댓값. 아무것도 없으면 [NONE]. */
    fun prefixMax(index: Int): Int {
        if (index < 0) return NONE
        var i = index + 1
        var result = NONE
        while (i > 0) {
            if (tree[i] > result) result = tree[i]
            i -= i and (-i)
        }
        return result
    }

    /** `index` 자리(0-based)의 값을 [value] 로 **끌어올린다**(더 작으면 그대로 둔다). */
    fun update(
        index: Int,
        value: Int,
    ) {
        var i = index + 1
        while (i < tree.size) {
            if (tree[i] < value) tree[i] = value
            i += i and (-i)
        }
    }

    private companion object {
        const val NONE = -1
    }
}
