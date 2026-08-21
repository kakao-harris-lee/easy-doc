package kr.easydoc.core.easyread

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** 큐레이션 데이터가 이식 과정에서 한 글자도 표류하지 않았는지 전건 대조한다. */
class StyleRuleDataSnapshotTest {
    private val snapshot: JsonObject = loadSnapshot()

    private fun strings(key: String): List<String> = snapshot.getValue(key).jsonArray.map { it.jsonPrimitive.content }

    private fun number(key: String): Int =
        snapshot
            .getValue(key)
            .jsonPrimitive.int

    @Test
    @DisplayName("어려운 말 사전 246개가 키·값·순서까지 원본과 같다")
    fun `사전이 전건 일치한다`() {
        val expected =
            snapshot.getValue("DIFFICULT_WORD_REPLACEMENTS").jsonArray.map { entry ->
                val pair = entry.jsonArray
                pair[0].jsonPrimitive.content to pair[1].jsonPrimitive.content
            }

        assertThat(expected).hasSize(EXPECTED_DICTIONARY_SIZE)
        assertThat(DIFFICULT_WORD_REPLACEMENTS).hasSize(EXPECTED_DICTIONARY_SIZE)

        assertThat(DIFFICULT_WORD_REPLACEMENTS.toList()).containsExactlyElementsOf(expected)
    }

    @Test
    @DisplayName("자동 채점 제외 낱말이 같다")
    fun `PROMPT_ONLY_WORDS 가 일치한다`() {
        assertThat(PROMPT_ONLY_WORDS.sorted()).containsExactlyElementsOf(strings("PROMPT_ONLY_WORDS"))

        assertThat(DIFFICULT_WORD_REPLACEMENTS.keys).containsAll(PROMPT_ONLY_WORDS)
    }

    @Test
    @DisplayName("임계값·쉼표·이중 피동 상수가 같다")
    fun `스칼라 상수가 일치한다`() {
        assertThat(MAX_SENTENCE_CHARS).isEqualTo(number("MAX_SENTENCE_CHARS"))
        assertThat(MAX_COMMAS_PER_SENTENCE).isEqualTo(number("MAX_COMMAS_PER_SENTENCE"))
        assertThat(COMMA_CHARS.map(Char::toString)).containsExactlyElementsOf(strings("COMMA_CHARS"))
        assertThat(DOUBLE_PASSIVE_PATTERNS).containsExactlyElementsOf(strings("DOUBLE_PASSIVE_PATTERNS"))
    }

    @Test
    @DisplayName("원칙 문구가 임계값 보간까지 같다")
    fun `STYLE_PRINCIPLES 가 일치한다`() {
        assertThat(STYLE_PRINCIPLES).containsExactlyElementsOf(strings("STYLE_PRINCIPLES"))
    }

    @Test
    @DisplayName("치환 비문 검출용 큐레이션 목록이 같다")
    fun `치환 비문 목록이 일치한다`() {
        assertThat(LEXICALIZED_GLOSSES.sorted()).containsExactlyElementsOf(strings("LEXICALIZED_GLOSSES"))
        assertThat(COMPOUND_TAIL_KEYS.sorted()).containsExactlyElementsOf(strings("COMPOUND_TAIL_KEYS"))
        assertThat(COMPOUND_HEAD_NOUNS.sorted()).containsExactlyElementsOf(strings("COMPOUND_HEAD_NOUNS"))

        assertThat(DIFFICULT_WORD_REPLACEMENTS.values).containsAll(LEXICALIZED_GLOSSES)
        assertThat(DIFFICULT_WORD_REPLACEMENTS.keys).containsAll(COMPOUND_TAIL_KEYS)
    }

    @Test
    @DisplayName("사전에서 유도한 집합이 원본 유도 결과와 같다")
    fun `파생 집합이 일치한다`() {
        assertThat(NOMINAL_GLOSSES.sorted()).containsExactlyElementsOf(strings("NOMINAL_GLOSSES"))
        assertThat(
            MODIFIER_CHECKED_GLOSSES.sorted(),
        ).containsExactlyElementsOf(strings("MODIFIER_CHECKED_GLOSSES"))
    }

    @Test
    @DisplayName("검출 패턴의 개수와 순서가 같다")
    fun `GLOSS_COLLISION_PATTERNS 가 일치한다`() {
        assertThat(
            GLOSS_COLLISION_PATTERNS.map { it.first },
        ).containsExactlyElementsOf(strings("GLOSS_COLLISION_PATTERN_GLOSSES"))
    }

    private companion object {
        const val EXPECTED_DICTIONARY_SIZE = 246
        const val SNAPSHOT_RESOURCE = "/kr/easydoc/core/easyread/python-style-rules-snapshot.json"

        fun loadSnapshot(): JsonObject {
            val stream =
                StyleRuleDataSnapshotTest::class.java.getResourceAsStream(SNAPSHOT_RESOURCE)
                    ?: error(
                        "큐레이션 데이터 스냅샷이 없다: $SNAPSHOT_RESOURCE — " +
                            "이 파일이 없으면 사전 이식이 검증되지 않은 채 통과한다.",
                    )
            return Json.parseToJsonElement(stream.bufferedReader().use { it.readText() }).jsonObject
        }
    }
}
