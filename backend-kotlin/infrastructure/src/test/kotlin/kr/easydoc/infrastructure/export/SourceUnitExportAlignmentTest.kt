package kr.easydoc.infrastructure.export

import kr.easydoc.core.segment.splitUnits
import kr.easydoc.infrastructure.ingest.DocumentExtractors
import kr.easydoc.infrastructure.ingest.IngestFixtures
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * A7(계획 §6 S1 수용 기준, 2026-09-05 S2 로 이연) — `splitUnits(추출 원문).size` 가
 * `TextUnitWalk` 가 낸 단위 수(본문+머리글·꼬리말)와 같은가.
 *
 * 계획 §1 항 2 는 「추출과 반영이 같은 순서로 DOM 을 순회한다」를 이미 참으로 두고 그 위에
 * `segment_map` 의 원본 단위 색인을 세운다. 그 전제가 **줄 수**에서도 성립하는지는 지금까지
 * `PackagedOriginalReflectorTest` 의 「짝이 맞으면 유지 가능이다」가 간접으로만 지켰다 — 이
 * 파일은 그 대조를 fixture 마다 직접 잰다. 어긋나면 오늘의 내보내기 자리 맞춤에 잠재 결함이
 * 있다는 신호이므로(계획 §7 리스크 2), **약화하지 않는다.**
 */
class SourceUnitExportAlignmentTest {
    private val extractors = DocumentExtractors()
    private val docx = DocxOriginalReflector()
    private val hwpx = HwpxOriginalReflector()

    @Test
    @DisplayName("A7 — splitUnits(추출 원문).size == TextUnitWalk 총 단위 수, 내보내기 export 시험이 쓰는 fixture 전부")
    fun `추출 줄 수가 순회 단위 수와 같다`() {
        val fixtures =
            listOf(
                Fixture("sample.docx") { IngestFixtures.bytes("sample.docx") },
                Fixture("sample_table.docx") { IngestFixtures.bytes("sample_table.docx") },
                Fixture("sample.hwpx") { IngestFixtures.bytes("sample.hwpx") },
                // 머리말·꼬리말이 있는 fixture — `PackagedOriginalReflectorTest.headerFooterFixtures`.
                Fixture("sample_rich.docx") { IngestFixtures.bytes("sample_rich.docx") },
                Fixture("rich.hwpx") { ExportFixtures.richHwpx() },
            )

        fixtures.forEach { fixture ->
            val bytes = fixture.bytes()
            val extracted = extractors.extract(fixture.name, bytes).text
            val extractedUnitCount = splitUnits(extracted).size
            val walkedUnitCount =
                when {
                    fixture.name.endsWith(".docx") -> docx.unitCount(bytes)
                    fixture.name.endsWith(".hwpx") -> hwpx.unitCount(bytes)
                    else -> error("알 수 없는 확장자: ${fixture.name}")
                }

            assertThat(walkedUnitCount)
                .withFailMessage("%s: TextUnitWalk 가 원본을 열지 못했다", fixture.name)
                .isNotNull()
            assertThat(extractedUnitCount)
                .withFailMessage(
                    "%s: 추출 줄 수(%d)와 TextUnitWalk 단위 수(%d)가 다르다 — 내보내기 자리 맞춤이 " +
                        "이 fixture 에서 이미 어긋난다는 신호다(계획 §7 리스크 2, §6 A7). " +
                        "약화하지 말고 이 fixture 와 두 수를 그대로 보고하라.",
                    fixture.name,
                    extractedUnitCount,
                    walkedUnitCount,
                ).isEqualTo(walkedUnitCount)
        }
    }

    private class Fixture(
        val name: String,
        val bytes: () -> ByteArray,
    )
}
