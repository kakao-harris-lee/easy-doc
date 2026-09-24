package kr.easydoc.core.illustration.suggestion

import kr.easydoc.core.exceptions.InvalidInputException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.UUID

/** 저장 평문 codec 의 왕복과 거절 갈래. 손상된 암호문이 서비스로 새지 않는지 본다. */
class IllustrationSuggestionStorageCodecTest {
    private val set =
        IllustrationSuggestionSet(
            schemaVersion = 1,
            analysisVersion = ILLUSTRATION_SUGGESTION_ANALYSIS_VERSION,
            suggestions =
                listOf(
                    IllustrationSuggestion(
                        suggestionId = UUID.fromString("11111111-1111-1111-1111-111111111111"),
                        purpose = IllustrationSuggestionPurpose.COMPARISON,
                        reason = "두 경로의 차이를 그림으로 보면 이해가 쉽다",
                        bodyRange = IllustrationSuggestionBodyRange(2, 5),
                        sourceAnchors =
                            listOf(
                                IllustrationSuggestionSourceAnchor(listOf(0, 1), "온라인과 방문 접수 중 하나를 고릅니다"),
                            ),
                        scenes = listOf("온라인 접수 장면", "방문 접수 장면"),
                        preservedFacts = listOf("둘 중 하나만 고른다"),
                        altTextDraft = "두 접수 방법을 나란히 보여 주는 그림",
                    ),
                ),
            droppedCount = 3,
        )

    @Test
    @DisplayName("왕복이 값 하나도 바꾸지 않는다 — 식별자·버전·버린 수까지 그대로다")
    fun `왕복이 값을 보존한다`() {
        val decoded = IllustrationSuggestionStorageCodec.decode(IllustrationSuggestionStorageCodec.encode(set))

        assertThat(decoded).isEqualTo(set)
    }

    @Test
    @DisplayName("제안 0건('제안 없음')도 왕복한다 — 결과 없음과 구분되는 정상 결과다")
    fun `제안 0건도 왕복한다`() {
        val empty = set.copy(suggestions = emptyList(), droppedCount = 0)

        val decoded = IllustrationSuggestionStorageCodec.decode(IllustrationSuggestionStorageCodec.encode(empty))

        assertThat(decoded.suggestions).isEmpty()
        assertThat(decoded.droppedCount).isZero()
    }

    @Test
    @DisplayName("LLM 출력 스키마는 저장 평문이 아니다 — 서버가 붙인 키가 없으면 거절한다")
    fun `LLM 출력 형식은 저장 평문으로 읽지 않는다`() {
        val llmShaped = """{"schema_version":1,"suggestions":[]}"""

        assertThatThrownBy { IllustrationSuggestionStorageCodec.decode(llmShaped) }
            .isInstanceOf(InvalidInputException::class.java)
            .hasMessage(IllustrationSuggestionStorageCodec.CORRUPT_SUGGESTIONS_MESSAGE)
    }

    @Test
    @DisplayName("모르는 키·잘못된 타입·깨진 JSON·모르는 purpose 를 같은 문구로 끊는다")
    fun `손상된 평문을 거절한다`() {
        val encoded = IllustrationSuggestionStorageCodec.encode(set)
        val corrupted =
            listOf(
                "not json at all",
                encoded.replace("\"dropped_count\"", "\"dropped\""),
                encoded.replace("\"dropped_count\":3", "\"dropped_count\":\"3\""),
                encoded.replace("\"comparison\"", "\"decoration\""),
                encoded.replace("\"11111111-1111-1111-1111-111111111111\"", "\"not-a-uuid\""),
            )

        corrupted.forEach { raw ->
            assertThatThrownBy { IllustrationSuggestionStorageCodec.decode(raw) }
                .describedAs("손상된 평문을 읽어 냈다: %s", raw.take(60))
                .isInstanceOf(InvalidInputException::class.java)
                .hasMessage(IllustrationSuggestionStorageCodec.CORRUPT_SUGGESTIONS_MESSAGE)
        }
    }

    @Test
    @DisplayName("상한을 넘는 평문은 파싱 전에 자른다 — 손상된 거대 암호문이 파서를 태우지 않는다")
    fun `너무 긴 평문은 파싱하지 않는다`() {
        val huge = "{" + " ".repeat(IllustrationSuggestionStorageCodec.MAX_PLAINTEXT_CHARS) + "}"

        assertThatThrownBy { IllustrationSuggestionStorageCodec.decode(huge) }
            .isInstanceOf(InvalidInputException::class.java)
    }
}
