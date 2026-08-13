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

/**
 * 큐레이션 데이터가 이식 과정에서 한 글자도 표류하지 않았는지 **전건 대조**한다.
 *
 * ## 왜 스냅샷 대조인가
 *
 * 사전 246개·제외 목록·복합어 앞자리 낱말은 사람이 실측 튜닝으로 만든 것이고 코드에서
 * 유도되지 않는다(`docs/migration/_workspace/03_rebuild-extraction-list.md` P1-4).
 * "옮겼다"는 주장은 대조 없이는 근거가 아니다 — 246개 중 한 글자가 바뀌어도 눈으로는
 * 찾을 수 없고, 증상은 골든셋 통과율이 조용히 몇 퍼센트 떨어지는 형태로만 나타난다.
 *
 * 그래서 Python 원본에서 **기계로 뽑은** 스냅샷을 리소스로 두고 여기서 값·순서·개수를
 * 전부 대조한다. 이 리소스 자체가 P1-4 가 요구한 "기계가독 파일로 복사"의 산출물이기도
 * 하다 — `app/` 을 지워도 이 값들은 남는다.
 *
 * ## 파생 집합까지 대조하는 이유
 *
 * [NOMINAL_GLOSSES]·[MODIFIER_CHECKED_GLOSSES]·[GLOSS_COLLISION_PATTERNS] 는 사전에서
 * **유도**된다. 유도 규칙(종성 ㅁ 판정, 꼬리 겹침 배제, 정렬 순서)을 잘못 옮기면 사전이
 * 멀쩡해도 검출 결과가 달라진다. 유도 결과를 함께 고정해 두면 데이터 이식과 유도 로직
 * 이식이 각각 검증된다.
 *
 * ## 이 테스트가 깨지면
 *
 * 사전을 "고치는" 것이 아니라 **왜 갈라졌는지 먼저 찾는다.** 값을 바꾸는 판단마다
 * 골든셋 통과율 실측이 뒤에 있으므로, 품질 개선 의도의 수정은 별건이고 그때는 스냅샷을
 * 다시 뽑는 것이 아니라 승인 경로를 밟는다.
 */
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

        // 개수를 리터럴로도 못박는다 — 스냅샷과 구현이 같이 줄어드는 사고를 잡는다.
        assertThat(expected).hasSize(EXPECTED_DICTIONARY_SIZE)
        assertThat(DIFFICULT_WORD_REPLACEMENTS).hasSize(EXPECTED_DICTIONARY_SIZE)

        // 순서까지 본다. findDifficultWords 의 결과 순서가 곧 보정 프롬프트의 지적 순서다.
        assertThat(DIFFICULT_WORD_REPLACEMENTS.toList()).containsExactlyElementsOf(expected)
    }

    @Test
    @DisplayName("자동 채점 제외 낱말이 같다")
    fun `PROMPT_ONLY_WORDS 가 일치한다`() {
        assertThat(PROMPT_ONLY_WORDS.sorted()).containsExactlyElementsOf(strings("PROMPT_ONLY_WORDS"))
        // 제외 낱말은 전부 사전 키여야 한다 — 키가 아닌 것을 제외해 봐야 아무 효과가 없다.
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
        // 이 문구는 프롬프트에 그대로 실린다. 보간이 어긋나면 모델이 지키는 수치와
        // 채점 수치가 갈라지고, 통과율이 실력이 아니라 그 차이를 재게 된다.
        assertThat(STYLE_PRINCIPLES).containsExactlyElementsOf(strings("STYLE_PRINCIPLES"))
    }

    @Test
    @DisplayName("치환 비문 검출용 큐레이션 목록이 같다")
    fun `치환 비문 목록이 일치한다`() {
        assertThat(LEXICALIZED_GLOSSES.sorted()).containsExactlyElementsOf(strings("LEXICALIZED_GLOSSES"))
        assertThat(COMPOUND_TAIL_KEYS.sorted()).containsExactlyElementsOf(strings("COMPOUND_TAIL_KEYS"))
        assertThat(COMPOUND_HEAD_NOUNS.sorted()).containsExactlyElementsOf(strings("COMPOUND_HEAD_NOUNS"))

        // 제외 목록은 전부 사전 값이어야 한다. 사전에 없는 값을 제외해 두면 사전이 바뀔 때
        // 그 항목이 조용히 검출 대상으로 편입되는 것을 아무도 눈치채지 못한다.
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
        // 순서가 결과에 영향을 준다 — 같은 길이의 매치가 겹칠 때 먼저 온 것이 남는다.
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
