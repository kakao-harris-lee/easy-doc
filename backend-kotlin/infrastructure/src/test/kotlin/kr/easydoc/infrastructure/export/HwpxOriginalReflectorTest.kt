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

    /**
     * 머리말 컨트롤·표·두 구역을 담은 합성 원본. 개인정보가 없는 문장만 담는다.
     *
     * 추출 순서는 이렇다(`HwpxExtractor` 규칙): `머리말 문구` → `첫 문단입니다.` →
     * `표 셀 하나` → `표 셀 둘` → `표 뒤 문단입니다.` → `둘째 구역의 문장입니다.`
     */
    private val rich: ByteArray by lazy {
        IngestFixtures.withEntryReplaced(
            IngestFixtures.bytes("sample.hwpx"),
            "Contents/section0.xml",
            RICH_SECTION.toByteArray(Charsets.UTF_8),
        )
    }

    @Test
    @DisplayName("합성 원본의 추출 순서가 우리가 세는 단위 순서와 같다")
    fun `추출 순서를 확인한다`() {
        assertThat(extractors.extract("안내.hwpx", rich).text)
            .isEqualTo("머리말 문구\n첫 문단입니다.\n표 셀 하나\n표 셀 둘\n표 뒤 문단입니다.\n둘째 구역의 문장입니다.")
    }

    @Test
    @DisplayName("머리말 자리의 문단은 쓰지 않는다 — 원본 문구가 그대로 남고 판정이 그것을 말한다")
    fun `머리말은 원본으로 남는다`() {
        val lines = listOf("쉬운 머리말", "쉬운 첫 문단", "쉬운 셀 하나", "쉬운 셀 둘", "쉬운 표 뒤", "쉬운 둘째 구역")

        val plan = reflector.outline(rich, lines)!!
        val file = reflector.reflect(rich, "안내", lines)!!

        assertThat(plan.outcome().headerFooterUnits).isEqualTo(1)
        assertThat(plan.outcome().emptiedUnits).isZero()
        assertThat(plan.outcome().appendedLines).isZero()
        assertThat(extractors.extract(file.filename, file.content).text)
            .describedAs("첫 줄은 머리말 자리의 몫이라 본문으로 내려오지 않는다")
            .isEqualTo("머리말 문구\n쉬운 첫 문단\n쉬운 셀 하나\n쉬운 셀 둘\n쉬운 표 뒤\n쉬운 둘째 구역")
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
        assertThat(extractors.extract(file.filename, file.content).text).isEqualTo("머리말 문구\n쉬운 첫 문단")
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
        assertThat(extractors.extract(file.filename, file.content).text)
            .isEqualTo("머리말 문구\n문단 2.\n문단 3.\n문단 4.\n문단 5.\n문단 6.\n문단 7.\n문단 8.")
        assertThat(IngestFixtures.entriesOf(file.content).getValue("Contents/section1.xml").decodeToString())
            .describedAs("덧붙은 문단은 마지막 구역에 선다")
            .contains("문단 7.")
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

    private companion object {
        /** 줄바꿈은 **태그 사이에만** 둔다 — 태그 안에서 끊으면 속성이 붙어 버린다. */
        val RICH_SECTION =
            """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <hs:sec xmlns:hs="http://www.hancom.co.kr/hwpml/2011/section" xmlns:hp="http://www.hancom.co.kr/hwpml/2011/paragraph">
            <hp:p id="1" paraPrIDRef="0" styleIDRef="0"><hp:run charPrIDRef="0"><hp:ctrl>
            <hp:header id="900"><hp:subList>
            <hp:p id="901" paraPrIDRef="0" styleIDRef="0"><hp:run charPrIDRef="0"><hp:t>머리말 문구</hp:t></hp:run></hp:p>
            </hp:subList></hp:header>
            </hp:ctrl></hp:run></hp:p>
            <hp:p id="2" paraPrIDRef="0" styleIDRef="0"><hp:run charPrIDRef="0"><hp:t>첫 문단입니다.</hp:t></hp:run></hp:p>
            <hp:p id="3" paraPrIDRef="0" styleIDRef="0"><hp:run charPrIDRef="0">
            <hp:tbl id="800" borderFillIDRef="1"><hp:tr>
            <hp:tc><hp:subList>
            <hp:p id="801" paraPrIDRef="0" styleIDRef="0"><hp:run charPrIDRef="0"><hp:t>표 셀 하나</hp:t></hp:run></hp:p>
            </hp:subList></hp:tc>
            <hp:tc><hp:subList>
            <hp:p id="802" paraPrIDRef="0" styleIDRef="0"><hp:run charPrIDRef="0"><hp:t>표 셀 둘</hp:t></hp:run></hp:p>
            </hp:subList></hp:tc>
            </hp:tr></hp:tbl></hp:run></hp:p>
            <hp:p id="4" paraPrIDRef="0" styleIDRef="0"><hp:run charPrIDRef="0"><hp:t>표 뒤 문단입니다.</hp:t></hp:run></hp:p>
            </hs:sec>
            """.trimIndent()
    }
}
