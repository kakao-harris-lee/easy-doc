package kr.easydoc.infrastructure.quality

import kr.easydoc.core.document.ReadingLevel
import java.math.BigDecimal

/**
 * 이번 회차를 **무엇으로 쟀는지** 한 줄.
 *
 * 리포트 머리줄([LaneReport.render])과 변환문 디렉터리의 `conditions.txt`
 * ([LaneTranscript.writeConditions])가 같은 문자열을 쓴다 — 두 곳이 갈리면 디렉터리를
 * 나중에 여는 사람이 리포트와 다른 조건을 읽는다. 조립을 여기 두는 이유도 그것이다:
 * 유료 레인 본문에서 문자열을 이어 붙이면 그 조립을 유료 호출 없이 시험할 수 없다.
 *
 * 유료 호출 전에 정해지는 **노브**만 들고, 조립이 내는 설명 문구는 [describe] 의 인자로
 * 받는다([LanePlan.Ready.description] 등) — 그쪽은 이미 사람이 읽는 문자열이라 여기서 다시
 * 해석할 것이 없고, 노브와 섞어 들면 이 타입이 레인 조립의 복사본이 된다.
 */
internal data class LaneConditions(
    val variant: GoldenLaneVariant,
    val readingLevel: ReadingLevel,
    val runs: Int,
    val budgetUsd: BigDecimal,
) {
    /** 조립 쪽 설명을 끼워 완성한 조건 줄. 순서만 정하고 문구는 그대로 싣는다. */
    fun describe(
        provider: String,
        dictionary: String,
        transcript: String,
        selection: String,
    ): String =
        "variant=${variant.wireName} · readingLevel=${readingLevel.wireName} · " +
            "$provider · $dictionary · $transcript · $selection · runs=$runs cap_usd=$budgetUsd"
}
