package kr.easydoc.infrastructure.dictionary

import kr.easydoc.core.dictionary.DictionaryIndex
import kr.easydoc.core.dictionary.TermCandidate
import kr.easydoc.core.dictionary.TermLookup
import kr.easydoc.core.dictionary.TermQuery
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.io.File

/**
 * P0-5 조각 Q - `docs/plans/2026-09-04-p0-5-easy-word-dictionary-rag.md` §3.6.
 *
 * 픽스처(`core/src/test/resources/kr/easydoc/core/dictionary/lookup-fixture.json`)는 **core**
 * 모듈에 있지만, 이 실측 테스트는 여기 **infrastructure** 에 둔다. 이유는 계획이 지키려는
 * 다른 불변식과 충돌하기 때문이다 — core 본 소스에는 JSON 라이브러리가 없고
 * (`core/build.gradle.kts`, `DictionaryFixtures.kt` KDoc), 실제 커밋된 색인
 * (`easy_dict.index.json`, 엔트리 2,179·표면형 40,189)과의 대조는 그 파일을 파싱하는
 * infrastructure 어댑터 테스트 몫이라는 관례를 `DictionaryReferenceContextTest` 가 이미
 * 세웠다. 여기서 새 JSON 리더를 만들지 않고 기존 [DictionaryIndexJsonReader] 를 그대로 쓴다.
 *
 * 픽스처 파일은 `easydoc.kotlin.source.root` 시스템 속성(루트 `build.gradle.kts` 가 모든
 * 서브프로젝트 테스트에 주입한다)으로 절대 경로를 만들어 읽는다 — `GoldenDocumentLoader` 가
 * `data/golden/documents` 를 찾을 때 쓰는 것과 같은 관례이며, 새 Gradle 배선이 필요 없다.
 *
 * 지표 셋(§3.6)은 임계값을 낮추지 않는다 — 실패하면 실측 수치와 실패 사례를 그대로 보고한다.
 */
class TermLookupFixtureTest {
    private val index: DictionaryIndex = DictionaryIndexJsonReader().readClasspathResource()
    private val fixture: Fixture = loadFixture()

    @Test
    @DisplayName("픽스처는 50건 이상이고 절반 이상이 손으로 쓴 케이스다")
    fun `픽스처 구성이 계획을 지킨다`() {
        assertThat(fixture.cases.size).isGreaterThanOrEqualTo(MIN_FIXTURE_SIZE)
        assertThat(fixture.handWrittenRatio).isGreaterThanOrEqualTo(MIN_HAND_WRITTEN_RATIO)

        val actualHandCount = fixture.cases.count { it.source == "hand" }
        val actualRatio = actualHandCount.toDouble() / fixture.cases.size
        assertThat(actualRatio)
            .describedAs("픽스처 파일이 적어 둔 hand_written_ratio 와 실제 cases 구성이 어긋난다")
            .isCloseTo(
                fixture.handWrittenRatio,
                org.assertj.core.data.Offset
                    .offset(0.001),
            )
    }

    @Test
    @DisplayName("top-1 엔트리 정확도, 위험한 applicable, 양성 케이스 무결과율")
    fun `실제 색인이 세 지표 임계값을 만족한다`() {
        val outcomes =
            fixture.cases.map { case ->
                Outcome(case, TermLookup.candidates(TermQuery.of(case.query), index))
            }

        val positives = outcomes.filter { it.case.expectedTerm != null }
        val top1Correct = positives.count { it.actual.firstOrNull()?.term == it.case.expectedTerm }
        val top1Accuracy = if (positives.isEmpty()) 1.0 else top1Correct.toDouble() / positives.size

        val unsafeApplicable =
            outcomes.filter { outcome ->
                !outcome.case.expectedApplicable && outcome.actual.any(TermCandidate::applicable)
            }

        val noResultAmongPositives = positives.count { it.actual.isEmpty() }
        val noResultRate = if (positives.isEmpty()) 0.0 else noResultAmongPositives.toDouble() / positives.size

        val report =
            buildString {
                appendLine("총 케이스 ${outcomes.size}건, 양성 케이스 ${positives.size}건")
                appendLine(
                    "top-1 정확도 = $top1Correct/${positives.size} = " +
                        "%.4f (임계값 >= %.2f)".format(top1Accuracy, TOP1_ACCURACY_THRESHOLD),
                )
                appendLine("위험한 applicable = ${unsafeApplicable.size}건 (임계값 = 0)")
                unsafeApplicable.forEach { outcome ->
                    appendLine("  - 질의=\"${outcome.case.query}\" 기대=false 실제=${outcome.actual.map { it.applicable }}")
                }
                appendLine(
                    "양성 케이스 무결과율 = $noResultAmongPositives/${positives.size} = " +
                        "%.4f (임계값 <= %.2f)".format(noResultRate, NO_RESULT_RATE_THRESHOLD),
                )
                val top1Failures = positives.filter { it.actual.firstOrNull()?.term != it.case.expectedTerm }
                if (top1Failures.isNotEmpty()) {
                    appendLine("top-1 불일치 사례:")
                    top1Failures.forEach { outcome ->
                        appendLine(
                            "  - 질의=\"${outcome.case.query}\" 기대=${outcome.case.expectedTerm} " +
                                "실제=${outcome.actual.map { it.term }}",
                        )
                    }
                }
            }
        println(report)

        assertThat(top1Accuracy)
            .describedAs("top-1 엔트리 정확도\n$report")
            .isGreaterThanOrEqualTo(TOP1_ACCURACY_THRESHOLD)
        assertThat(unsafeApplicable)
            .describedAs("위험한 applicable(기대 거짓인데 참)\n$report")
            .isEmpty()
        assertThat(noResultRate)
            .describedAs("양성 케이스 무결과율\n$report")
            .isLessThanOrEqualTo(NO_RESULT_RATE_THRESHOLD)
    }

    private data class Outcome(
        val case: FixtureCase,
        val actual: List<TermCandidate>,
    )

    private data class FixtureCase(
        val query: String,
        val source: String,
        val expectedTerm: String?,
        val expectedApplicable: Boolean,
    )

    private data class Fixture(
        val handWrittenRatio: Double,
        val cases: List<FixtureCase>,
    )

    private fun loadFixture(): Fixture {
        val root = File(System.getProperty(SOURCE_ROOT_PROPERTY) ?: error("시스템 속성 $SOURCE_ROOT_PROPERTY 이 없다"))
        val file = root.resolve(FIXTURE_RELATIVE_PATH)
        check(file.isFile) { "조회 픽스처가 없다: $file" }

        val mapper = JsonMapper.builder().build()
        val rootNode = mapper.readTree(file)
        val ratio = rootNode.path("hand_written_ratio").asDouble()
        val cases =
            rootNode.path("cases").toList().map { node ->
                FixtureCase(
                    query = requiredString(node, "query"),
                    source = requiredString(node, "source"),
                    expectedTerm = optionalString(node, "expected_term"),
                    expectedApplicable = node.path("expected_applicable").booleanValue(false),
                )
            }
        return Fixture(handWrittenRatio = ratio, cases = cases)
    }

    private fun requiredString(
        node: JsonNode,
        field: String,
    ): String = node.path(field).stringValue("").ifEmpty { error("픽스처 케이스에 '$field' 값이 없다: $node") }

    private fun optionalString(
        node: JsonNode,
        field: String,
    ): String? {
        val value = node.path(field)
        return if (value.isNull || value.isMissingNode) null else value.stringValue("").ifEmpty { null }
    }

    private companion object {
        const val SOURCE_ROOT_PROPERTY: String = "easydoc.kotlin.source.root"
        const val FIXTURE_RELATIVE_PATH: String =
            "core/src/test/resources/kr/easydoc/core/dictionary/lookup-fixture.json"
        const val MIN_FIXTURE_SIZE: Int = 50
        const val MIN_HAND_WRITTEN_RATIO: Double = 0.5
        const val TOP1_ACCURACY_THRESHOLD: Double = 0.90
        const val NO_RESULT_RATE_THRESHOLD: Double = 0.10
    }
}
