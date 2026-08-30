package kr.easydoc.infrastructure.export

import kr.easydoc.infrastructure.ingest.DocumentExtractors
import kr.easydoc.infrastructure.ingest.IngestFixtures
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.zip.ZipEntry

/** 원본 HWPX 구조에 검수본을 반영한다 — **구역 XML 말고는 바이트도 건드리지 않는다.** */
class HwpxOriginalReflectorTest {
    private val reflector = HwpxOriginalReflector()
    private val extractors = DocumentExtractors()

    /** 머리말 컨트롤·표·두 구역을 담은 합성 원본 — 머리말이 본문 **사이**에 오는 쪽이다. */
    private val rich: ByteArray by lazy { ExportFixtures.richHwpx() }

    @Test
    @DisplayName("합성 원본의 추출 순서가 우리가 세는 단위 순서와 같다")
    fun `추출 순서를 확인한다`() {
        assertThat(extractors.extract("안내.hwpx", rich).text)
            .isEqualTo("머리말 문구\n첫 문단입니다.\n표 셀 하나\n표 셀 둘\n표 뒤 문단입니다.\n둘째 구역의 문장입니다.")
    }

    /**
     * 머리말 자리는 원본 문구를 지키고, 그 자리와 겹친 검수본 줄은 **버리지 않고** 본문 끝으로
     * 옮긴다. 자리를 건너뛰어 뒤 문단을 당겨 오지 않는 것도 함께 잰다 — HWPX 는 머리말이 본문
     * 사이에 있어서 한 칸만 당겨도 문서 전체가 밀린다.
     */
    @Test
    @DisplayName("머리말 자리의 문단은 쓰지 않되 버리지도 않는다 — 본문 끝으로 옮겨 붙는다")
    fun `머리말은 원본으로 남고 겹친 줄은 옮겨 붙는다`() {
        val lines = listOf("쉬운 머리말", "쉬운 첫 문단", "쉬운 셀 하나", "쉬운 셀 둘", "쉬운 표 뒤", "쉬운 둘째 구역")

        val plan = reflector.outline(rich, lines)!!
        val file = reflector.reflect(rich, "안내", lines)!!

        assertThat(plan.outcome().headerFooterUnits).isEqualTo(1)
        assertThat(plan.outcome().emptiedUnits).isZero()
        assertThat(plan.outcome().appendedLines).isZero()
        assertThat(plan.outcome().displacedLines)
            .describedAs("머리말 자리와 겹친 한 줄은 옮겨 붙는다 — 판정이 그 수를 말한다")
            .isEqualTo(1)
        assertThat(extractors.extract(file.filename, file.content).text)
            .describedAs("본문 자리는 한 칸도 밀리지 않고, 겹친 줄은 끝에 선다")
            .isEqualTo("머리말 문구\n쉬운 첫 문단\n쉬운 셀 하나\n쉬운 셀 둘\n쉬운 표 뒤\n쉬운 둘째 구역\n쉬운 머리말")
    }

    @Test
    @DisplayName("표·머리말·다른 항목은 그대로 남고 `mimetype` 이 첫 STORED 항목이다")
    fun `원본 요소가 살아남는다`() {
        val lines = listOf("쉬운 머리말", "쉬운 첫 문단", "쉬운 셀 하나", "쉬운 셀 둘", "쉬운 표 뒤", "쉬운 둘째 구역")

        val file = reflector.reflect(rich, "안내", lines)!!

        val before = IngestFixtures.entriesOf(rich)
        val after = IngestFixtures.entriesOf(file.content)
        assertThat(after.keys).isEqualTo(before.keys)
        listOf("version.xml", "Contents/content.hpf", "META-INF/container.xml").forEach { name ->
            assertThat(after.getValue(name))
                .describedAs("구역 XML 말고는 바이트가 그대로다: %s", name)
                .isEqualTo(before.getValue(name))
        }
        val section = after.getValue("Contents/section0.xml").decodeToString()
        assertThat(section).contains("hp:tbl").contains("hp:header")
        assertThat(countOf(section, "<hp:tc>")).describedAs("표 셀 둘이 그대로다").isEqualTo(2)

        ZipFile.builder().setSeekableByteChannel(SeekableInMemoryByteChannel(file.content)).get().use { zip ->
            val first = zip.entries.nextElement()
            assertThat(first.name).isEqualTo("mimetype")
            assertThat(first.method).isEqualTo(ZipEntry.STORED)
        }
    }

    @Test
    @DisplayName("문단이 모자라면 남은 본문 단위를 비운다 — 원본 문구를 남기지 않는다")
    fun `모자라면 비운다`() {
        val short = listOf("쉬운 머리말", "쉬운 첫 문단")

        val plan = reflector.outline(rich, short)!!
        val file = reflector.reflect(rich, "안내", short)!!

        assertThat(plan.outcome().emptiedUnits).isEqualTo(4)
        assertThat(extractors.extract(file.filename, file.content).text)
            .describedAs("비우는 갈래에서도 머리말과 겹친 줄은 살아남는다")
            .isEqualTo("머리말 문구\n쉬운 첫 문단\n쉬운 머리말")
        assertThat(file.content.decodeToString(throwOnInvalidSequence = false))
            .describedAs("검수를 지나지 않은 원본 문장이 파일에 남으면 안 된다")
            .doesNotContain("표 뒤 문단입니다")
    }

    @Test
    @DisplayName("문단이 남으면 마지막 구역 끝에 덧붙는다 — 버리지 않는다")
    fun `남으면 덧붙인다`() {
        val many = List(8) { "문단 ${it + 1}." }

        val plan = reflector.outline(rich, many)!!
        val file = reflector.reflect(rich, "안내", many)!!

        assertThat(plan.outcome().appendedLines).isEqualTo(2)
        assertThat(plan.outcome().displacedLines).isEqualTo(1)
        assertThat(extractors.extract(file.filename, file.content).text)
            .describedAs("덧붙는 문단은 원래 줄 차례 그대로 선다 — 머리말과 겹친 `문단 1.` 이 먼저다")
            .isEqualTo("머리말 문구\n문단 2.\n문단 3.\n문단 4.\n문단 5.\n문단 6.\n문단 1.\n문단 7.\n문단 8.")
        assertThat(IngestFixtures.entriesOf(file.content).getValue("Contents/section1.xml").decodeToString())
            .describedAs("덧붙은 문단은 마지막 구역에 선다")
            .contains("문단 7.")
    }

    /**
     * 유실 0 을 **개수가 아니라 내용으로** 잰다. 줄 수를 바꿔 가며 자리 맞춤의 네 갈래를 모두
     * 지나게 하고, 그때마다 검수본의 모든 문단이 결과 문서 안에 남아 있는지 확인한다.
     */
    @Test
    @DisplayName("어떤 줄 수에서도 검수본 문단이 하나도 사라지지 않는다")
    fun `검수본이 사라지지 않는다`() {
        listOf(1, 2, 5, 6, 7, 9).forEach { count ->
            val lines = List(count) { "검수한 문단 ${it + 1}." }

            val file = reflector.reflect(rich, "안내", lines)!!

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
    @DisplayName("기존 fixture(머리말·표 없음)는 본문 단위만으로 정확히 짝이 맞는다")
    fun `단순 원본은 짝이 맞는다`() {
        val original = IngestFixtures.bytes("sample.hwpx")
        val lines = listOf("쉬운 한 줄", "쉬운 두 줄", "쉬운 세 줄")

        val plan = reflector.outline(original, lines)!!
        val file = reflector.reflect(original, "안내", lines)!!

        assertThat(plan.outcome().headerFooterUnits).isZero()
        assertThat(plan.outcome().emptiedUnits).isZero()
        assertThat(plan.outcome().appendedLines).isZero()
        assertThat(plan.outcome().displacedLines).isZero()
        assertThat(extractors.extract(file.filename, file.content).text).isEqualTo(lines.joinToString("\n"))
    }

    @Test
    @DisplayName("열 수 없는 원본은 `null` 이다 — 새 문서로 접지 않는다")
    fun `열 수 없으면 null 이다`() {
        val broken = "zip 이 아니다".toByteArray()

        assertThat(reflector.outline(broken, listOf("한 줄"))).isNull()
        assertThat(reflector.reflect(broken, "안내", listOf("한 줄"))).isNull()
    }

    @Test
    @DisplayName("구역이 없는 껍데기는 `null` 이다")
    fun `구역이 없으면 null 이다`() {
        val hollow = IngestFixtures.zipOf(mapOf("mimetype" to "application/hwp+zip".toByteArray()))

        assertThat(reflector.outline(hollow, listOf("한 줄"))).isNull()
    }

    private fun countOf(
        haystack: String,
        needle: String,
    ): Int = haystack.split(needle).size - 1
}
