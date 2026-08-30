package kr.easydoc.application.conversion

import kr.easydoc.core.privacy.MaskedText

/**
 * 이 문서에 실을 사전 지침을 구해 오는 포트.
 *
 * 인자가 [MaskedText] 인 것이 이 포트의 요점이다 — 컨텍스트는 **프롬프트에 실제로 들어가는
 * 본문**을 보고 만들어야 하고, 그 본문은 마스킹을 마친 쪽이다. 원문을 받게 두면 배선이
 * 「LLM 호출 전 마스킹 완료」(프로젝트 CLAUDE.md)를 우회하는 통로가 하나 생긴다.
 *
 * 실을 것이 없으면 **`null`** 이다. 빈 문자열이나 항목 없는 골격을 돌려주지 않는다 — 골격을
 * 프롬프트에 실으면 LLM 에게 "이 문서에 나온 어려운 말"이라 해 놓고 아무것도 주지 않는 꼴이라,
 * 지시문만 늘고 근거는 없는 최악의 조합이 된다.
 */
fun interface DictionaryContextSource {
    fun contextFor(maskedText: MaskedText): String?
}

/** 사전을 묻지 않는 기본 배선. 주입을 끈 실행과 사전을 적재하지 않는 프로세스가 쓴다. */
object NoDictionaryContext : DictionaryContextSource {
    override fun contextFor(maskedText: MaskedText): String? = null
}
