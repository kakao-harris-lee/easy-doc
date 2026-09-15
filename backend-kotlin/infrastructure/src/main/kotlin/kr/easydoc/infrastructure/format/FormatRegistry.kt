package kr.easydoc.infrastructure.format

/** 형식별 전략의 중복·누락을 조립 시점에 검사한다. */
internal class FormatRegistry<F, S>(
    strategies: List<S>,
    expectedFormats: Set<F>,
    formatOf: (S) -> F,
) {
    private val byFormat = strategies.associateBy(formatOf)

    init {
        require(byFormat.size == strategies.size) { "Duplicate format strategies" }
        require(byFormat.keys == expectedFormats) {
            "Format strategies must cover $expectedFormats; registered=${byFormat.keys}"
        }
    }

    operator fun get(format: F): S = byFormat.getValue(format)
}
