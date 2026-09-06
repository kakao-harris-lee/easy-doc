package kr.easydoc.core.segment

import kr.easydoc.core.easyread.checkStyle

// P0-4 S7 — 계획 §11 「segment_map.compliant_source_units」.
//
// 이미 쉬운 글 규칙을 통과한 원본 단위를 표시해 화면이 「다시 쓰면 나빠질 수 있다」고
// 경고할 수 있게 한다(§5 「이미 쉬운 글인 입력의 저변경 보장」이 미뤄 둔 자리). 재변환을
// 막지는 않는다 — 판단은 사용자 몫이다(계획 §11.2).

/**
 * [sourceUnits] 중 [kr.easydoc.core.easyread.checkStyle] 위반이 0건이고 공백만이 아닌
 * 단위의 0 기반 색인을, 원래 순서 그대로(오름차순·중복 없이) 돌려준다.
 *
 * 공백만인 단위는 통과로 세지 않는다 — 재변환할 것도 경고할 것도 없는 빈 줄에 배지를
 * 달면 화면이 잘못을 말하게 된다. `sourceUnits.withIndex()` 를 그대로 걸러서 만들기 때문에
 * 결과는 저절로 오름차순·중복 없음을 만족한다.
 */
fun compliantSourceUnits(sourceUnits: List<String>): List<Int> =
    sourceUnits
        .withIndex()
        .filter { (_, unit) -> unit.isNotBlank() && checkStyle(unit).passed }
        .map { it.index }
