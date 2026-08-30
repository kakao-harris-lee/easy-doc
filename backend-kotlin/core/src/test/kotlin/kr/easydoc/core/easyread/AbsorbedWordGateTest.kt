package kr.easydoc.core.easyread

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 흡수 낱말 재도입 차단 — easy-dictionary 로 소유권이 넘어간 낱말이 이쪽 치환 목록에
 * 되살아나는지 본다.
 *
 * `scripts/check-dict-overlap.sh` 와 층이 다르다. 그쪽은 살아있는 사전 색인과 대조해
 * **사전이 자라면서 새로 생기는 중복**을 잡지만, 사전 저장소가 옆에 있어야 돌아서
 * GitHub CI 에서는 항상 [건너뜀]이다. 이 테스트는 사전 저장소 없이 도는 스냅샷 대조라
 * 태그 없는 일반 `test` 로 두었고, 그래서 `./gradlew build` = CI 매 실행에서 상시
 * 차단이 걸린다 — 정책이 요구한 "기계로 차단"(consumer-overlap-policy.md §4)이
 * 실효하는 지점이다.
 *
 * 스냅샷은 [ABSORBED_WORDS_RESOURCE]. 흡수를 되돌리려면 사전 쪽 소유권 반납이 먼저이고,
 * 그 파일 머리 주석에 절차를 적어 두었다.
 */
class AbsorbedWordGateTest {
    private val absorbed: List<String> = loadAbsorbedWords()

    @Test
    @DisplayName("흡수 낱말이 치환 사전에 되살아나지 않았다")
    fun `DIFFICULT_WORD_REPLACEMENTS 에 흡수 낱말이 없다`() {
        val revived = absorbed.filter { it in DIFFICULT_WORD_REPLACEMENTS }

        assertThat(revived)
            .withFailMessage { failMessage("DIFFICULT_WORD_REPLACEMENTS", revived) }
            .isEmpty()
    }

    @Test
    @DisplayName("흡수 낱말이 자동 채점 제외 목록에도 되살아나지 않았다")
    fun `PROMPT_ONLY_WORDS 에 흡수 낱말이 없다`() {
        // PROMPT_ONLY_WORDS 는 채점에서만 빠질 뿐 프롬프트에는 그대로 실린다.
        // 여기로 우회해 넣는 것도 사전과의 지시 충돌이라 같은 기준으로 막는다.
        val revived = absorbed.filter { it in PROMPT_ONLY_WORDS }

        assertThat(revived)
            .withFailMessage { failMessage("PROMPT_ONLY_WORDS", revived) }
            .isEmpty()
    }

    @Test
    @DisplayName("스냅샷 자체가 비어 있지 않고 중복이 없다")
    fun `스냅샷이 온전하다`() {
        // 스냅샷이 비면 위 두 단언은 아무것도 막지 않으면서 통과한다.
        // 게이트가 조용히 무력화되는 경로라 목록 자체를 먼저 확인한다.
        assertThat(absorbed).hasSize(ABSORBED_WORD_COUNT).doesNotHaveDuplicates()
    }

    private fun failMessage(
        target: String,
        revived: List<String>,
    ): String =
        "$target 에 흡수 낱말이 되살아났다: ${revived.joinToString(", ")} — " +
            "이 낱말은 easy-dictionary 로 흡수됐다(단일 출처 정책). 양쪽에 같이 두면 " +
            "한 프롬프트에 '바꿔라'와 '그대로 둬라'가 함께 실린다. 되살리려면 사전 쪽 " +
            "소유권 반납(consumer-overlap-policy.md 갱신)과 $ABSORBED_WORDS_RESOURCE " +
            "스냅샷 갱신이 같은 변경으로 함께 필요하다."

    private companion object {
        const val ABSORBED_WORD_COUNT = 81
        const val ABSORBED_WORDS_RESOURCE = "/kr/easydoc/core/easyread/absorbed-words-snapshot.json"

        fun loadAbsorbedWords(): List<String> {
            val stream =
                AbsorbedWordGateTest::class.java.getResourceAsStream(ABSORBED_WORDS_RESOURCE)
                    ?: error(
                        "흡수 낱말 스냅샷이 없다: $ABSORBED_WORDS_RESOURCE — " +
                            "이 파일이 없으면 재도입 차단이 통째로 사라진 채 통과한다.",
                    )
            val snapshot: JsonObject =
                Json.parseToJsonElement(stream.bufferedReader().use { it.readText() }).jsonObject
            return snapshot.getValue("words").jsonArray.map { it.jsonPrimitive.content }
        }
    }
}
