package kr.easydoc.application.conversion

/**
 * 이 문서에 실을 사전 지침을 구해 오는 포트.
 *
 * 인자는 **프롬프트에 실제로 들어가는 본문**이다.
 *
 * 실을 것이 없으면 **`null`** 이다. 빈 문자열이나 항목 없는 골격을 돌려주지 않는다 — 골격을
 * 프롬프트에 실으면 LLM 에게 "이 문서에 나온 어려운 말"이라 해 놓고 아무것도 주지 않는 꼴이라,
 * 지시문만 늘고 근거는 없는 최악의 조합이 된다.
 */
fun interface DictionaryContextSource {
    fun contextFor(documentText: String): String?
}

/** 사전을 묻지 않는 기본 배선. 주입을 끈 실행과 사전을 적재하지 않는 프로세스가 쓴다. */
object NoDictionaryContext : DictionaryContextSource {
    override fun contextFor(documentText: String): String? = null
}
