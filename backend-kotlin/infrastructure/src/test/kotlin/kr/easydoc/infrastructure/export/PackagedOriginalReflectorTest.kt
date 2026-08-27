package kr.easydoc.infrastructure.export

import kr.easydoc.application.document.OriginalDocument
import kr.easydoc.core.crypto.PlainBytes
import kr.easydoc.core.document.FormatPreservationStatus
import kr.easydoc.core.document.SourceFormat
import kr.easydoc.core.document.reflectedPreservation
import kr.easydoc.infrastructure.ingest.DocumentExtractors
import kr.easydoc.infrastructure.ingest.IngestFixtures
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** 형식 디스패치와 **자리 순서** — 추출이 본 차례와 반영이 쓰는 차례가 같은가. */
class PackagedOriginalReflectorTest {
    private val reflector = PackagedOriginalReflector()
    private val extractors = DocumentExtractors()

    /** 머리말·꼬리말이 없는 fixture 들. 여기서는 추출 줄과 본문 단위가 **정확히** 같아야 한다. */
    private val plainFixtures =
        mapOf(
            "sample.docx" to SourceFormat.DOCX,
            "sample_table.docx" to SourceFormat.DOCX,
            "sample.hwpx" to SourceFormat.HWPX,
        )

    @Test
    @DisplayName("추출이 본 차례와 반영이 쓰는 차례가 자리마다 같다")
    fun `추출 순서와 반영 순서가 같다`() {
        plainFixtures.forEach { (name, format) ->
            val original = originalOf(name, format)
            val places = extractors.extract(name, original.bytes.value).text.split("\n")
            val marked = places.indices.map { "${it + 1}번 자리" }

            val file = reflector.reflect(original, "차례", marked.joinToString("\n"))!!

            assertThat(extractors.extract(file.filename, file.content).text.split("\n"))
                .withFailMessage(
                    "%s: 반영이 추출과 다른 차례로 썼다. 한 칸이라도 밀리면 검수본이 엉뚱한 문단 서식에 들어간다.",
                    name,
                ).isEqualTo(marked)
        }
    }

    @Test
    @DisplayName("자리가 정확히 맞는 원본은 `available` 로 판정된다")
    fun `짝이 맞으면 유지 가능이다`() {
        plainFixtures.forEach { (name, format) ->
            val original = originalOf(name, format)
            val body = extractors.extract(name, original.bytes.value).text

            val outcome = reflector.outline(original, body)!!

            assertThat(reflectedPreservation(outcome).status)
                .withFailMessage("%s: 원본과 문단 수가 같은데도 유지 가능이 아니다", name)
                .isEqualTo(FormatPreservationStatus.AVAILABLE)
        }
    }

    @Test
    @DisplayName("빈 줄은 문단으로 세지 않는다 — 판정과 반영이 같은 함수로 나눈다")
    fun `빈 줄은 자리를 차지하지 않는다`() {
        val original = originalOf("sample.docx", SourceFormat.DOCX)
        val body = "\n쉬운 제목\n\n\n쉬운 본문\n\n"

        val outcome = reflector.outline(original, body)!!
        val file = reflector.reflect(original, "안내", body)!!

        assertThat(reflectedPreservation(outcome).status).isEqualTo(FormatPreservationStatus.AVAILABLE)
        assertThat(extractors.extract(file.filename, file.content).text).isEqualTo("쉬운 제목\n쉬운 본문")
    }

    @Test
    @DisplayName("PDF 원본은 반영하지 않는다 — 같은 형식으로 내보낼 수단이 없다")
    fun `pdf 는 반영하지 않는다`() {
        val original = originalOf("sample.pdf", SourceFormat.PDF)

        assertThat(reflector.outline(original, "쉬운 본문")).isNull()
        assertThat(reflector.reflect(original, "안내", "쉬운 본문")).isNull()
    }

    @Test
    @DisplayName("압축 예산을 넘는 원본은 열지 않는다 — 저장된 바이트에도 방어가 걸린다")
    fun `예산을 넘으면 열지 않는다`() {
        val bomb = originalOf("oversized.zip", SourceFormat.DOCX)

        assertThat(reflector.outline(bomb, "쉬운 본문")).isNull()
        assertThat(reflector.reflect(bomb, "안내", "쉬운 본문")).isNull()
    }

    private fun originalOf(
        name: String,
        format: SourceFormat,
    ): OriginalDocument = OriginalDocument(format, PlainBytes(IngestFixtures.bytes(name)))
}
