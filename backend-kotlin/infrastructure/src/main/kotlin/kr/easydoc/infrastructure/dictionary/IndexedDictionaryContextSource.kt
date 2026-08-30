package kr.easydoc.infrastructure.dictionary

import kr.easydoc.application.conversion.DictionaryContextSource
import kr.easydoc.core.dictionary.DictionaryContextPolicy
import kr.easydoc.core.dictionary.DictionaryIndex
import kr.easydoc.core.dictionary.RenderedDictionaryContext
import kr.easydoc.core.privacy.MaskedText
import org.slf4j.LoggerFactory

/**
 * 적재된 색인으로 문서별 사전 컨텍스트를 만드는 어댑터.
 *
 * ## 실린 항목이 0개면 싣지 않는다
 *
 * core 는 참조 구현대로 **언제나** 블록을 돌려준다 — 매칭이 없어도, 예산이 항목을 전부
 * 밀어내도 섹션 제목만 있는 골격이 나온다. 그 골격을 프롬프트에 실으면 "이 문서에 나온 어려운
 * 말"이라 해 놓고 아무것도 주지 않는 꼴이고, "6개 중 0개만 표시했습니다"가 붙은 판본은 한술 더
 * 떠 **어려운 말이 6개 있다고 알려 주면서 그중 무엇에도 지침을 주지 않는다.** 예산만 쓰고
 * 남는 것은 불안한 신호뿐이라 안 싣느니만 못하다.
 *
 * 두 경우를 가르지 않는 이유가 그것이다 — 매칭 0건도 「실린 항목이 0개」의 한 갈래일 뿐이다.
 *
 * **이것은 참조 구현으로부터의 이탈이 아니다.** `build_prompt_context` 의 책임은 컨텍스트를
 * 렌더링하는 것이고 주입 여부는 제품 배선의 판단이다. core 의 출력 문자열은 참조 구현과 한
 * 글자도 다르지 않으며(픽스처 56건 대조가 그것을 지킨다), 여기서 보는 것은 그 문자열이 아니라
 * [RenderedDictionaryContext.renderedTerms] 다 — 줄 형식이 바뀌면 조용히 틀리는 출력 훑기를
 * 배선에 두지 않기 위해서다.
 */
class IndexedDictionaryContextSource(
    private val index: DictionaryIndex,
    private val policy: DictionaryContextPolicy,
) : DictionaryContextSource {
    private val log = LoggerFactory.getLogger(IndexedDictionaryContextSource::class.java)

    override fun contextFor(maskedText: MaskedText): String? {
        val rendered = index.renderPromptContext(maskedText.value, policy)
        if (rendered.renderedTerms == 0) {
            // **개수만 남긴다.** 본문·용어·컨텍스트는 로그에 넣지 않는다(CLAUDE.md 관측 규칙).
            log.debug("사전 {}건을 찾았으나 실린 항목이 없다 — 컨텍스트를 싣지 않는다", rendered.totalTerms)
            return null
        }

        log.debug("사전 {}건 중 {}건을 컨텍스트에 실었다", rendered.totalTerms, rendered.renderedTerms)
        return rendered.text
    }
}
