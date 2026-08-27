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

    /**
     * **검수본 문단은 어느 갈래에서도 사라지지 않는다.**
     *
     * 두 형식의 머리말 자리가 달라서 같은 시험을 둘 다에 건다. DOCX 는 머리글 파트가 본문
     * 뒤라 겹치는 자리가 검수본의 끝줄이고, HWPX 는 머리말이 본문 사이에 들어가 겹치는 자리가
     * 가운데다. 어느 쪽이든 그 자리와 겹친 줄은 본문 끝으로 옮겨 붙고, 판정은 머리말이 있다는
     * 이유만으로도 `available` 이 아니다.
     */
    @Test
    @DisplayName("머리말이 있는 원본에서도 검수본 문단이 하나도 사라지지 않는다")
    fun `머리말이 있어도 검수본이 사라지지 않는다`() {
        headerFooterFixtures.forEach { (name, original) ->
            listOf(1, 5, 8, 12).forEach { count ->
                val lines = List(count) { "검수한 문단 ${it + 1}." }
                val body = lines.joinToString("\n")

                val outcome = reflector.outline(original, body)!!
                val file = reflector.reflect(original, "안내", body)!!

                val written = extractors.extract(file.filename, file.content).text
                assertThat(lines)
                    .withFailMessage(
                        "%s 에 %d 줄을 반영했더니 결과에 없는 검수본 문단이 있다. 검수한 문장이 소리 없이 사라진다.%n결과: %s",
                        name,
                        count,
                        written,
                    ).allMatch { line -> written.contains(line) }
                assertThat(reflectedPreservation(outcome).status)
                    .withFailMessage("%s: 머리말 문구가 원본으로 남는데 「그대로 나간다」고 말했다", name)
                    .isEqualTo(FormatPreservationStatus.PARTIAL)
            }
        }
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

    /** 머리말·꼬리말이 **있는** 원본. 두 형식의 머리말 자리가 다르다는 것이 여기 둘의 차이다. */
    private val headerFooterFixtures: Map<String, OriginalDocument> by lazy {
        mapOf(
            "sample_rich.docx" to
                OriginalDocument(SourceFormat.DOCX, PlainBytes(IngestFixtures.bytes("sample_rich.docx"))),
            "머리말이 본문 사이에 오는 hwpx" to
                OriginalDocument(SourceFormat.HWPX, PlainBytes(ExportFixtures.richHwpx())),
        )
    }

    private fun originalOf(
        name: String,
        format: SourceFormat,
    ): OriginalDocument = OriginalDocument(format, PlainBytes(IngestFixtures.bytes(name)))
}
