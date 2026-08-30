package kr.easydoc.core.dictionary

/**
 * 렌더링을 마친 프롬프트 컨텍스트 한 장과, **그 장에 실제로 무엇이 실렸는지**.
 *
 * ## 왜 문자열만으로는 모자란가
 *
 * 예산이 빠듯하면 매칭이 있어도 항목이 한 줄도 살아남지 못한다 — 남는 것은 구역 제목과
 * "6개 중 0개만 표시했습니다" 안내뿐인 골격이다(`maxCharsRatio` 가 짧은 문서에서 실제로
 * 그렇게 된다). 그 골격을 프롬프트에 실으면 LLM 에게 "이 문서에 어려운 말이 6개 있다"고
 * 알려 주고 그중 무엇에도 지침을 주지 않는 꼴이라, 예산만 쓰고 남는 것은 불안한 신호뿐이다.
 *
 * **싣지 않는 판단은 여기서 하지 않는다.** 렌더링은 core 의 일이고 주입은 배선의 판단이다
 * (매칭 0건 골격에서 이미 그렇게 갈라 뒀다). 이 타입은 배선이 그 판단을 **출력 문자열을
 * 훑지 않고** 내릴 수 있게 [renderedTerms] 를 함께 준다 — 줄 형식이 바뀌면 조용히 틀리는
 * `- ` 세기 같은 것을 배선에 두지 않기 위해서다.
 *
 * @property text 프롬프트에 실을 블록. 참조 구현(`lookup.py`)의 출력과 한 글자도 다르지 않다.
 * @property renderedTerms [text] 에 항목 줄이 실제로 찍힌 고유 용어 수. `0` 이면 골격뿐이다.
 * @property totalTerms 잘리기 전 문서에서 찾은 고유 용어 수. [renderedTerms] 와의 차이가
 *   예산이 밀어낸 양이다 — **개수라서 로그·메트릭에 남겨도 된다**(프로젝트 CLAUDE.md 관측
 *   규칙: 본문은 안 되지만 개수는 된다). 예산이 과하게 조인다는 것을 알아채는 신호다.
 */
class RenderedDictionaryContext(
    val text: String,
    val renderedTerms: Int,
    val totalTerms: Int,
) {
    /**
     * **본문을 찍지 않는다.** [text] 자체는 사용자 문서가 아니라 사전 데이터(표제어·순화어·
     * 뜻풀이)지만, 길이와 개수만으로 진단에 필요한 것이 전부 나오므로 굳이 흘리지 않는다.
     */
    override fun toString(): String =
        "RenderedDictionaryContext(text=${text.length}자, rendered=$renderedTerms/$totalTerms)"
}
