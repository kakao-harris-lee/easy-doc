package kr.easydoc.core.quality

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * 문서 id 의 안전한 파일명 문법([GoldenDocumentLoader.SAFE_DOCUMENT_ID])을 코퍼스 스키마
 * 차원에서 강제하는지 본다.
 *
 * 이 문법은 `LaneTranscript`(변환문을 `<디렉터리>/<id>.txt` 로 쓴다)와 `LaneDictionary` 파일
 * 주입 모드(같은 이름으로 읽는다)가 문서 id 를 검증 없이 파일 경로에 붙이는 자리를 보호한다.
 * 로더가 로드 시점에 거절하면, 그 이후의 모든 소비자가 별도 검사 없이 이 불변식을 물려받는다.
 */
class GoldenDocumentLoaderIdGrammarTest {
    @Test
    @DisplayName("경로 순회 id 를 담은 문서는 로드 시점에 거절된다")
    fun `경로 순회 id 는 거절한다`(
        @TempDir temp: Path,
    ) {
        val file = temp.resolve("evil.json").toFile()
        file.writeText("""{"id":"../report","source_text":"본문","required_facts":[]}""")

        assertThatIllegalArgumentException()
            .isThrownBy { GoldenDocumentLoader.loadFile(file) }
            .withMessageContaining("../report")
            .withMessageContaining("evil.json")
    }

    @Test
    @DisplayName("경로 구분자를 담은 id 는 디렉터리 전체 로드에서도 거절된다")
    fun `디렉터리 전체 로드도 거절한다`(
        @TempDir temp: Path,
    ) {
        temp.resolve("001.json").toFile().writeText(
            """{"id":"001","source_text":"본문","required_facts":[]}""",
        )
        temp.resolve("bad.json").toFile().writeText(
            """{"id":"a/b","source_text":"본문","required_facts":[]}""",
        )

        assertThatIllegalArgumentException()
            .isThrownBy { GoldenDocumentLoader.loadDirectory(temp.toFile()) }
            .withMessageContaining("a/b")
            .withMessageContaining("bad.json")
    }

    @Test
    @DisplayName("영숫자·점·밑줄·붙임표만 쓴 id 는 통과한다")
    fun `안전한 id 는 통과한다`(
        @TempDir temp: Path,
    ) {
        val file = temp.resolve("001.json").toFile()
        file.writeText("""{"id":"001_v2.final-draft","source_text":"본문","required_facts":[]}""")

        val document = GoldenDocumentLoader.loadFile(file)

        assertThat(document.id).isEqualTo("001_v2.final-draft")
    }

    @Test
    @DisplayName("현행 골든 코퍼스 전건이 새 문법을 통과한다")
    fun `현행 코퍼스는 통과한다`() {
        val corpus = GoldenDocumentLoader.loadDirectory(GoldenDocumentLoader.documentsDirectory())

        assertThat(corpus.documents.map { it.id })
            .isNotEmpty()
            .allMatch { GoldenDocumentLoader.SAFE_DOCUMENT_ID.matches(it) }
    }
}
