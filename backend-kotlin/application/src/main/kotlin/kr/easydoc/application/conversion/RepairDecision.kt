package kr.easydoc.application.conversion

import kr.easydoc.core.easyread.checkStyle

/**
 * 보정 채택 판정의 결과.
 *
 * `accepted` 하나만 돌려주지 않는 이유: 판정의 **입력이 된 두 건수**를 함께 내야 parity
 * 하네스가 "같은 건수를 받았을 때 같은 결정을 내리는가"를 판정할 수 있다. 건수 자체가 옳은지는
 * `style` 도메인의 질문이고, 두 질문을 섞으면 실패했을 때 어느 쪽이 원인인지 알 수 없다
 * (`02_parity-verifier_conversion-spec.md` §2.1).
 *
 * @property lostPlaceholders 1차 결과에 있었는데 보정 결과에서 사라진 자리표시자.
 *   비어 있지 않으면 [accepted] 는 무조건 거짓이다. 진단용으로만 쓴다.
 */
data class RepairDecision(
    val accepted: Boolean,
    val originalIssueCount: Int,
    val candidateIssueCount: Int,
    val lostPlaceholders: List<String>,
)

/**
 * 보정 결과를 채택할지 판정한다.
 *
 * > **채택 = (자리표시자를 하나도 잃지 않았다) AND (규칙 위반 건수가 늘지 않았다)**
 *
 * 요구사항 정본: 인벤토리 §3.1 (다) (CNV-04). fixture: `repair-adoption` 정책 8건.
 *
 * ## 두 축의 세부
 *
 * - **자리표시자 축** — *하나만* 잃어도 기각이다(전부-아니면-전무가 아니다). 판정은 **존재
 *   여부**이지 위치·순서가 아니다 — 쉬운 글 변환은 문장을 쪼개고 묶으므로 정상 재작성에서도
 *   자리가 옮겨간다. 1차 결과에 **애초에 없던** 자리표시자는 "잃은 것"이 아니다.
 * - **건수 축** — 늘면 기각, **같으면 채택**(경계값). 같은 건수는 고친 자리와 새로 생긴 자리가
 *   맞바꿈된 경우인데, 지적받은 쪽을 고친 결과를 남긴다.
 *
 * ## 이 판정식이 보지 **않는** 것 (미해결 요구 공백 — 기록)
 *
 * **본문·팩트 손실을 보지 않는다.** 보정이 문서 절반을 지워도 위반 건수가 줄면 채택된다.
 * 실제로 위반은 **본문이 짧을수록 줄기 쉬우므로**, 이 판정식은 축약을 개선으로 오인하는
 * 방향으로 기울어 있다. 마스킹 대상이 없는 문서에서는 자리표시자 축이 비어 있어 가드가
 * 사실상 존재하지 않는다.
 *
 * 지금 고치지 않는 이유는 **"본문을 잃지 않았다"의 기준이 요구사항에 없기** 때문이다
 * (길이 비율? 팩트 잔존?). 잘못 고르면 정상적인 축약형 보정을 전부 기각해 보정이 죽는다.
 * 리더 판정 대기 — `02_parity-verifier_conversion-spec.md` §6 갈림 후보 ①.
 *
 * @param original 1차 변환 결과(후처리 완료).
 * @param candidate 보정 결과(후처리 완료).
 * @param placeholders 마스킹이 만든 자리표시자 라벨 전부.
 */
fun decideRepairAdoption(
    original: String,
    candidate: String,
    placeholders: List<String>,
): RepairDecision {
    val lost = placeholders.filter { it in original && it !in candidate }
    val before = checkStyle(original).issues.size
    val after = checkStyle(candidate).issues.size
    return RepairDecision(
        accepted = lost.isEmpty() && after <= before,
        originalIssueCount = before,
        candidateIssueCount = after,
        lostPlaceholders = lost,
    )
}
