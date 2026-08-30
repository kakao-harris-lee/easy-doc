package kr.easydoc.infrastructure.dictionary

import kr.easydoc.core.dictionary.ReplaceStrategy
import kr.easydoc.core.dictionary.RiskLevel
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** 배포 색인 JSON → core 색인 어댑터. core 에는 JSON 라이브러리가 없으므로 이 경계가 유일한 파싱 지점이다. */
class DictionaryIndexJsonReaderTest {
    private val reader = DictionaryIndexJsonReader()

    @Test
    @DisplayName("축약 wire 키를 도메인 값으로 옮긴다")
    fun `엔트리 필드를 옮긴다`() {
        val index = reader.read(json(ENTRY_SAMPLE).byteInputStream())

        val match = index.findAll("차상위계층 안내입니다.").single()
        assertThat(match.surface).isEqualTo("차상위계층")
        assertThat(match.entry.term).isEqualTo("차상위계층")
        assertThat(match.entry.easyTerm).isEqualTo("소득이 적은 사람")
        assertThat(match.entry.definition).isEqualTo("소득이 기준보다 적은 사람입니다.")
        assertThat(match.entry.strategy).isEqualTo(ReplaceStrategy.GLOSS)
        assertThat(match.entry.risk).isEqualTo(RiskLevel.HIGH)
        assertThat(match.entry.priority).isEqualTo(150)
        assertThat(match.entry.tags).containsExactly("welfare")
        assertThat(match.entry.caution).isEqualTo("기초생활수급자와 다른 자격입니다.")
        assertThat(match.entry.examples).hasSize(1)
        assertThat(match.entry.examples[0].before).isEqualTo("차상위계층 안내")
        assertThat(match.entry.examples[0].after).isEqualTo("소득이 적은 사람 안내")
        assertThat(match.entry.examples[0].isGolden).isTrue()
    }

    @Test
    @DisplayName("null 로 오는 필드는 없는 값으로 옮긴다 — 문자열 \"null\" 이 되면 프롬프트에 실린다")
    fun `null 필드를 빈 값으로 옮긴다`() {
        val index = reader.read(json(NULLABLE_SAMPLE).byteInputStream())

        val entry = index.findAll("내방 안내").single().entry
        assertThat(entry.definition).isNull()
        assertThat(entry.caution).isNull()
        assertThat(entry.tags).isEmpty()
        assertThat(entry.examples).isEmpty()
    }

    @Test
    @DisplayName("표면형 변형형도 같은 엔트리로 잇는다")
    fun `변형형을 싣는다`() {
        val index = reader.read(json(ENTRY_SAMPLE, extraSurface = "\"차상위 계층\": [1],").byteInputStream())

        val entry = index.findAll("차상위 계층 안내입니다.").single().entry
        assertThat(entry.term).isEqualTo("차상위계층")
    }

    @Test
    @DisplayName("지원하지 않는 schema_version 이면 기동에서 거절한다 — 조용히 넘기면 바뀐 스키마를 옛 규칙으로 읽는다")
    fun `스키마 버전을 단언한다`() {
        val other = json(ENTRY_SAMPLE).replace(""""1.0.0"""", """"2.0.0"""")

        assertThatThrownBy { reader.read(other.byteInputStream()) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("2.0.0")
            .hasMessageContaining(DictionaryIndexJsonReader.SUPPORTED_SCHEMA_VERSION)
    }

    @Test
    @DisplayName("schema_version 이 아예 없어도 거절한다")
    fun `스키마 버전이 없으면 거절한다`() {
        val missing = json(ENTRY_SAMPLE).replace(""""schema_version": "1.0.0",""", "")

        assertThatThrownBy { reader.read(missing.byteInputStream()) }
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    @DisplayName("모르는 wire 값은 거절한다 — core 의 ofWire 판정을 어댑터가 삼키지 않는다")
    fun `알 수 없는 전략을 거절한다`() {
        val broken = json(ENTRY_SAMPLE).replace(""""s": "gloss"""", """"s": "rewrite"""")

        assertThatThrownBy { reader.read(broken.byteInputStream()) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    @DisplayName("배포 사본을 클래스패스에서 읽는다 — 도커가 쓰는 바로 그 경로다")
    fun `클래스패스 사본을 읽는다`() {
        val index = reader.readClasspathResource()

        val match = index.findAll("차상위계층 지원 안내문입니다.").firstOrNull()
        assertThat(match).isNotNull
        assertThat(match!!.entry.term).isEqualTo("차상위계층")
    }

    private companion object {
        const val ENTRY_SAMPLE: String =
            """
            "1": {
              "t": "차상위계층", "e": "소득이 적은 사람",
              "d": "소득이 기준보다 적은 사람입니다.",
              "s": "gloss", "r": "high", "p": 150, "g": ["welfare"],
              "c": "기초생활수급자와 다른 자격입니다.",
              "x": [{"b": "차상위계층 안내", "a": "소득이 적은 사람 안내", "y": true}]
            }
            """

        const val NULLABLE_SAMPLE: String =
            """
            "1": {
              "t": "내방", "e": "방문", "d": null,
              "s": "substitute", "r": "none", "p": 120, "g": [], "c": null, "x": []
            }
            """

        /** 엔트리 조각 하나를 최소 색인 문서로 감싼다. 표면형은 표제어에서 뽑는다. */
        fun json(
            entry: String,
            extraSurface: String = "",
        ): String {
            val term = Regex(""""t": "([^"]+)"""").find(entry)!!.groupValues[1]
            return """
                {
                  "schema_version": "1.0.0",
                  "josa": ["은", "는", "이", "가"],
                  "surface_index": { $extraSurface "$term": [1] },
                  "entries": { $entry }
                }
                """
        }
    }
}
