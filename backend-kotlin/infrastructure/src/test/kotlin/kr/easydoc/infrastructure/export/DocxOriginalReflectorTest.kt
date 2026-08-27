package kr.easydoc.infrastructure.export

import kr.easydoc.infrastructure.ingest.DocumentExtractors
import kr.easydoc.infrastructure.ingest.IngestFixtures
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** 원본 DOCX 구조에 검수본을 반영한다 — **새로 만들지 않고 고쳐 쓴다.** */
class DocxOriginalReflectorTest {
    private val reflector = DocxOriginalReflector()
    private val extractors = DocumentExtractors()

    /** `sample_rich.docx` 의 본문 단위 일곱 — 오라클 `_raw_docx_blocks` 에서 빈 블록만 뺀 것이다. */
    private val richBodyLines =
        listOf(
            "첫 문단입니다.",
            "바깥 표 셀",
            "중첩 표 셀",
            "표 뒤에 오는 문단입니다.",
            "텍스트 상자 안 문장입니다.",
            "변경 추적으로 삽입된 문장입니다.",
            "둘째 구역 본문입니다.",
        )

    @Test
    @DisplayName("본문 단위 수와 문단 수가 같으면 아무것도 비우거나 덧붙이지 않는다")
    fun `짝이 맞으면 손대는 것이 없다`() {
        val plan = reflector.outline(IngestFixtures.bytes("sample_rich.docx"), richBodyLines)

        val outcome = plan!!.outcome()
        assertThat(plan.written).hasSize(7)
        assertThat(outcome.emptiedUnits).isZero()
        assertThat(outcome.appendedLines).isZero()
        assertThat(outcome.displacedLines).isZero()
        assertThat(outcome.headerFooterUnits)
            .describedAs("머리글·바닥글 문구는 추출됐지만 되돌려 쓰지 않는다 — 원본 문구로 남는다")
            .isEqualTo(2)
    }

    @Test
    @DisplayName("반영한 파일을 다시 추출하면 본문은 검수본이고 머리글·바닥글은 원본 그대로다")
    fun `본문만 바뀌고 머리글은 남는다`() {
        val rewritten = List(7) { "쉬운 문단 ${it + 1}." }

        val file = reflector.reflect(IngestFixtures.bytes("sample_rich.docx"), "안내문", rewritten)!!

        assertThat(file.filename).isEqualTo("안내문-쉬운글.docx")
        assertThat(extractors.extract(file.filename, file.content).text)
            .isEqualTo((rewritten + listOf("머리글 문구", "바닥글 문구")).joinToString("\n"))
    }

    @Test
    @DisplayName("본문에 없던 원본 요소는 그대로 남는다 — 표·텍스트 상자·삭제 추적·패키지 파트")
    fun `원본 요소가 살아남는다`() {
        val original = IngestFixtures.bytes("sample_rich.docx")

        val file = reflector.reflect(original, "안내문", List(7) { "쉬운 문단 ${it + 1}." })!!

        val before = IngestFixtures.entriesOf(original)
        val after = IngestFixtures.entriesOf(file.content)
        assertThat(after.keys).containsAll(before.keys)
        assertThat(after.getValue("docProps/thumbnail.jpeg"))
            .describedAs("본문과 무관한 파트는 바이트까지 그대로다")
            .isEqualTo(before.getValue("docProps/thumbnail.jpeg"))
        assertThat(after.getValue("word/header1.xml").decodeToString()).contains("머리글 문구")

        val body = after.getValue("word/document.xml").decodeToString()
        assertThat(countOf(body, "<w:tbl>")).describedAs("표 둘(중첩 포함)이 그대로다").isEqualTo(2)
        assertThat(body).contains("mc:AlternateContent").contains("w:delText")
    }

    @Test
    @DisplayName("문단이 모자라면 남은 원본 문단을 비운다 — 원본 문구를 남기지 않는다")
    fun `모자라면 비운다`() {
        val original = IngestFixtures.bytes("sample_rich.docx")
        val short = listOf("쉬운 문단 하나.", "쉬운 문단 둘.")

        val plan = reflector.outline(original, short)!!
        val file = reflector.reflect(original, "안내문", short)!!

        assertThat(plan.outcome().emptiedUnits).isEqualTo(5)
        assertThat(extractors.extract(file.filename, file.content).text)
            .isEqualTo("쉬운 문단 하나.\n쉬운 문단 둘.\n머리글 문구\n바닥글 문구")
        assertThat(String(file.content, Charsets.ISO_8859_1))
            .describedAs("검수를 지나지 않은 원본 문장이 파일에 남으면 안 된다")
            .doesNotContain("표 뒤에 오는 문단")
    }

    /**
     * 머리글·바닥글 자리와 겹친 두 줄은 원본 머리글에 쓸 수 없다. 그렇다고 **버리지 않는다** —
     * 본문 끝으로 옮겨 붙이고 판정이 그 수를 말한다.
     */
    @Test
    @DisplayName("머리글·바닥글 자리와 겹친 문단도 본문 끝에 남는다 — 버리지 않는다")
    fun `겹친 문단도 남는다`() {
        val original = IngestFixtures.bytes("sample_rich.docx")
        val many = richBodyLines.map { "쉬운 $it" } + listOf("덧붙는 문단 하나.", "덧붙는 문단 둘.")

        val plan = reflector.outline(original, many)!!
        val file = reflector.reflect(original, "안내문", many)!!

        assertThat(plan.outcome().appendedLines)
            .describedAs("원본 단위가 아홉이고 줄도 아홉이라 넘치는 줄은 없다")
            .isZero()
        assertThat(plan.outcome().displacedLines)
            .describedAs("머리말·꼬리말 두 단위 몫과 겹친 두 줄이 옮겨 붙는다")
            .isEqualTo(2)
        assertThat(extractors.extract(file.filename, file.content).text)
            .describedAs("겹친 두 줄은 본문 끝(머리글·바닥글 앞)에 선다 — 어디에도 없어지지 않는다")
            .isEqualTo(
                (
                    richBodyLines.map { "쉬운 $it" } +
                        listOf("덧붙는 문단 하나.", "덧붙는 문단 둘.", "머리글 문구", "바닥글 문구")
                ).joinToString("\n"),
            )
    }

    @Test
    @DisplayName("단위보다 문단이 많으면 넘치는 만큼 본문 끝에 덧붙는다")
    fun `단위를 넘어서면 덧붙는다`() {
        val original = IngestFixtures.bytes("sample_rich.docx")
        val many = List(11) { "문단 ${it + 1}." }

        val plan = reflector.outline(original, many)!!
        val file = reflector.reflect(original, "안내문", many)!!

        assertThat(plan.outcome().appendedLines).isEqualTo(2)
        assertThat(plan.outcome().displacedLines).isEqualTo(2)
        assertThat(extractors.extract(file.filename, file.content).text)
            .describedAs("머리글이 본문 뒤인 DOCX 에서는 겹친 줄과 넘친 줄이 이어져 열한 줄이 차례대로 남는다")
            .isEqualTo((many + listOf("머리글 문구", "바닥글 문구")).joinToString("\n"))
    }

    /**
     * 유실 0 을 **개수가 아니라 내용으로** 잰다. 줄 수를 바꿔 가며 자리 맞춤의 네 갈래를 모두
     * 지나게 하고, 그때마다 검수본의 모든 문단이 결과 문서 안에 남아 있는지 확인한다.
     */
    @Test
    @DisplayName("어떤 줄 수에서도 검수본 문단이 하나도 사라지지 않는다")
    fun `검수본이 사라지지 않는다`() {
        val original = IngestFixtures.bytes("sample_rich.docx")

        listOf(1, 6, 7, 8, 9, 12).forEach { count ->
            val lines = List(count) { "검수한 문단 ${it + 1}." }

            val file = reflector.reflect(original, "안내문", lines)!!

            val written = extractors.extract(file.filename, file.content).text
            assertThat(lines)
                .withFailMessage(
                    "%d 줄을 반영했더니 결과에 없는 검수본 문단이 있다. 담당자가 검수한 문장이 소리 없이 사라진다.%n결과: %s",
                    count,
                    written,
                ).allMatch { line -> written.contains(line) }
        }
    }

    @Test
    @DisplayName("표만 있는 원본도 셀 자리에 그대로 반영된다")
    fun `표 셀에 반영된다`() {
        val original = IngestFixtures.bytes("sample_table.docx")
        val lines = listOf("쉬운 제목", "구분은 이렇습니다", "내용은 이렇습니다", "언제까지", "3월 31일까지")

        val file = reflector.reflect(original, "표 문서", lines)!!

        assertThat(extractors.extract(file.filename, file.content).text).isEqualTo(lines.joinToString("\n"))
        assertThat(IngestFixtures.entriesOf(file.content).getValue("word/document.xml").decodeToString())
            .describedAs("표 구조는 그대로다 — 셀 안의 문단에 글자만 갈아 끼운다")
            .contains("<w:tbl>")
    }

    @Test
    @DisplayName("열 수 없는 원본은 `null` 이다 — 새 문서로 접지 않는다")
    fun `열 수 없으면 null 이다`() {
        val broken = "zip 이 아니다".toByteArray()

        assertThat(reflector.outline(broken, richBodyLines)).isNull()
        assertThat(reflector.reflect(broken, "안내문", richBodyLines)).isNull()
    }

    private fun countOf(
        haystack: String,
        needle: String,
    ): Int = haystack.split(needle).size - 1
}
