package kr.easydoc.infrastructure.ingest

import kr.easydoc.core.document.TableSupportReason
import kr.easydoc.core.document.TableSupportStatus
import kr.easydoc.core.segment.UnitKind
import kr.easydoc.core.segment.splitUnits
import kr.easydoc.infrastructure.export.ExportFixtures
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * 표·목록 구조 힌트 추출(S8-1, 계획 §1.2·§2 표) — 추출기가 원본 단위마다 붙이는
 * [UnitKind] 가 A1~A4 를 만족하는지 고정한다.
 */
class UnitKindExtractionTest {
    @ParameterizedTest
    @ValueSource(strings = ["<w:vMerge/>", "<w:vMerge w:val=\"restart\"/>", "<w:hMerge/>"])
    fun `DOCX merge marker는 숫자 span 없이도 거절한다`(marker: String) {
        val original = IngestFixtures.bytes("sample_table.docx")
        val xml =
            requireNotNull(IngestFixtures.entriesOf(original)["word/document.xml"])
                .decodeToString()
                .replaceFirst("<w:tcPr>", "<w:tcPr>$marker")
        val data = IngestFixtures.withEntryReplaced(original, "word/document.xml", xml.toByteArray())

        assertThat(
            DocxExtractor()
                .extractStructured(data)
                .tables
                .single()
                .supportReason,
        ).isEqualTo(TableSupportReason.MERGED_CELLS)
    }

    @Test
    fun `DOCX firstRow 속성이 없는 tblLook은 제목 근거가 아니다`() {
        val original = IngestFixtures.bytes("sample_table.docx")
        val xml =
            requireNotNull(IngestFixtures.entriesOf(original)["word/document.xml"])
                .decodeToString()
                .replace("w:firstRow=\"1\"", "")
        val data = IngestFixtures.withEntryReplaced(original, "word/document.xml", xml.toByteArray())

        assertThat(
            DocxExtractor()
                .extractStructured(data)
                .tables
                .single()
                .supportReason,
        ).isEqualTo(TableSupportReason.HEADER_ROW_MISSING)
    }

    @Test
    @DisplayName("R4 — OOXML ST_OnOff의 firstRow=\"on\"도 firstRow=\"1\"과 같은 헤더 근거다")
    fun `DOCX firstRow on도 표를 지원한다`() {
        val original = IngestFixtures.bytes("sample_table.docx")
        val xml =
            requireNotNull(IngestFixtures.entriesOf(original)["word/document.xml"])
                .decodeToString()
                .replace("w:firstRow=\"1\"", "w:firstRow=\"on\"")
        val data = IngestFixtures.withEntryReplaced(original, "word/document.xml", xml.toByteArray())

        val table = DocxExtractor().extractStructured(data).tables.single()

        assertThat(table.supportStatus).isEqualTo(TableSupportStatus.SUPPORTED)
    }

    @Test
    fun `너무 많은 DOCX 표는 본문을 유지하고 메타데이터만 미지원으로 접는다`() {
        val original = IngestFixtures.bytes("sample_table.docx")
        val xml =
            requireNotNull(IngestFixtures.entriesOf(original)["word/document.xml"])
                .decodeToString()
                .replace("</w:body>", "<w:tbl/>".repeat(1_001) + "</w:body>")
        val data = IngestFixtures.withEntryReplaced(original, "word/document.xml", xml.toByteArray())

        val outcome = DocxExtractor().extractStructured(data)

        assertThat(outcome.text).isEqualTo(DocxExtractor().extract(original))
        assertThat(outcome.tables.single().supportReason).isEqualTo(TableSupportReason.LIMIT_EXCEEDED)
        assertThat(
            kr.easydoc.core.document.TableStructurePayloadCodec
                .encode(outcome.tables),
        ).isNotBlank()
    }

    @Test
    @DisplayName("R4 — DOCX rectangular table coordinates use the canonical emitted unit indexes")
    fun `DOCX 표 좌표가 builder unit index를 그대로 가리킨다`() {
        val outcome = DocxExtractor().extractStructured(IngestFixtures.bytes("sample_table.docx"))

        assertThat(outcome.tables).hasSize(1)
        val table = outcome.tables.single()
        assertThat(table.supportStatus).isEqualTo(TableSupportStatus.SUPPORTED)
        assertThat(table.rowCount).isEqualTo(2)
        assertThat(table.columnCount).isEqualTo(2)
        assertThat(table.sourceUnitIndexes).containsExactly(1, 2, 3, 4)
        assertThat(table.cells.map { it.sourceUnitIndexes }).containsExactly(
            listOf(1),
            listOf(2),
            listOf(3),
            listOf(4),
        )
        assertThat(table.cells[2].headerRefs).containsExactly(1)
        assertThat(table.cells[3].headerRefs).containsExactly(2)
    }

    @Test
    fun `너무 많은 HWPX 표도 본문 추출은 유지한다`() {
        val xml = EXPLICIT_HEADER_HWPX.replace("</hs:sec>", "<hp:tbl/>".repeat(1_001) + "</hs:sec>")
        val data = IngestFixtures.zipOf(mapOf("Contents/section0.xml" to xml.toByteArray()))

        val outcome = HwpxExtractor().extractStructured(data)

        assertThat(splitUnits(outcome.text)).containsExactly("구분", "내용", "기간", "3월")
        assertThat(outcome.tables.single().supportReason).isEqualTo(TableSupportReason.LIMIT_EXCEEDED)
    }

    @Test
    @DisplayName("R4 — DOCX 는 첫 행을 자동으로 헤더로 승격하지 않는다")
    fun `DOCX header marker가 없으면 표를 지원하지 않는다`() {
        val original = IngestFixtures.bytes("sample_table.docx")
        val documentXml =
            requireNotNull(IngestFixtures.entriesOf(original)["word/document.xml"])
                .decodeToString()
                .replace("w:firstRow=\"1\"", "w:firstRow=\"0\"")
        val data = IngestFixtures.withEntryReplaced(original, "word/document.xml", documentXml.toByteArray())

        val table = DocxExtractor().extractStructured(data).tables.single()

        assertThat(table.supportStatus).isEqualTo(TableSupportStatus.UNSUPPORTED)
        assertThat(table.supportReason).isEqualTo(TableSupportReason.HEADER_ROW_MISSING)
        assertThat(table.cells).isEmpty()
    }

    @Test
    @DisplayName("R4 — DOCX tblHeader val=false는 헤더 증거가 아니다")
    fun `DOCX tblHeader false도 표를 지원하지 않는다`() {
        val original = IngestFixtures.bytes("sample_table.docx")
        val documentXml =
            requireNotNull(IngestFixtures.entriesOf(original)["word/document.xml"])
                .decodeToString()
                .replace("w:firstRow=\"1\"", "w:firstRow=\"0\"")
                .replaceFirst("<w:tr>", "<w:tr><w:trPr><w:tblHeader w:val=\"0\"/></w:trPr>")
        val data = IngestFixtures.withEntryReplaced(original, "word/document.xml", documentXml.toByteArray())

        val table = DocxExtractor().extractStructured(data).tables.single()

        assertThat(table.supportStatus).isEqualTo(TableSupportStatus.UNSUPPORTED)
        assertThat(table.supportReason).isEqualTo(TableSupportReason.HEADER_ROW_MISSING)
    }

    @Test
    @DisplayName("R4 — HWPX 명시적 repeatHeader가 있는 사각 표만 좌표를 내보낸다")
    fun `HWPX repeatHeader가 source unit 좌표를 보존한다`() {
        val data = IngestFixtures.zipOf(mapOf("Contents/section0.xml" to EXPLICIT_HEADER_HWPX.toByteArray()))

        val table = HwpxExtractor().extractStructured(data).tables.single()

        assertThat(table.supportStatus).isEqualTo(TableSupportStatus.SUPPORTED)
        assertThat(table.rowCount).isEqualTo(2)
        assertThat(table.columnCount).isEqualTo(2)
        assertThat(table.sourceUnitIndexes).containsExactly(0, 1, 2, 3)
        assertThat(table.cells.map { it.sourceUnitIndexes }).containsExactly(
            listOf(0),
            listOf(1),
            listOf(2),
            listOf(3),
        )
        assertThat(table.cells[2].headerRefs).containsExactly(0)
        assertThat(table.cells[3].headerRefs).containsExactly(1)
    }

    @Test
    @DisplayName("R4 — HWPX 도 명시적 헤더가 없으면 header_row_missing으로 보류한다")
    fun `HWPX header marker가 없으면 표를 지원하지 않는다`() {
        val data = IngestFixtures.zipOf(mapOf("Contents/section0.xml" to UNMARKED_TABLE_HWPX.toByteArray()))

        val table = HwpxExtractor().extractStructured(data).tables.single()

        assertThat(table.supportStatus).isEqualTo(TableSupportStatus.UNSUPPORTED)
        assertThat(table.supportReason).isEqualTo(TableSupportReason.HEADER_ROW_MISSING)
    }

    @Test
    @DisplayName("R4 — HWPX child cellSpan은 병합 표로 보류한다")
    fun `HWPX child cellSpan은 merged_cells를 낸다`() {
        val data = IngestFixtures.zipOf(mapOf("Contents/section0.xml" to MERGED_HWPX.toByteArray()))

        val table = HwpxExtractor().extractStructured(data).tables.single()

        assertThat(table.supportStatus).isEqualTo(TableSupportStatus.UNSUPPORTED)
        assertThat(table.supportReason).isEqualTo(TableSupportReason.MERGED_CELLS)
    }

    @Test
    @DisplayName("R4 — HWPX child cellAddr가 실제 순서와 다르면 irregular_grid로 보류한다")
    fun `HWPX child cellAddr가 어긋나면 표를 지원하지 않는다`() {
        val data = IngestFixtures.zipOf(mapOf("Contents/section0.xml" to OUT_OF_ORDER_HWPX.toByteArray()))

        val table = HwpxExtractor().extractStructured(data).tables.single()

        assertThat(table.supportStatus).isEqualTo(TableSupportStatus.UNSUPPORTED)
        assertThat(table.supportReason).isEqualTo(TableSupportReason.IRREGULAR_GRID)
    }

    @Test
    @DisplayName("R4 — HWPX nested table는 outer table을 nested_table로 보류한다")
    fun `HWPX 중첩 표는 지원하지 않는다`() {
        val data = IngestFixtures.zipOf(mapOf("Contents/section0.xml" to NESTED_HWPX.toByteArray()))

        val tables = HwpxExtractor().extractStructured(data).tables

        assertThat(tables).hasSize(2)
        assertThat(tables.first().supportStatus).isEqualTo(TableSupportStatus.UNSUPPORTED)
        assertThat(tables.first().supportReason).isEqualTo(TableSupportReason.NESTED_TABLE)
    }

    @Test
    @DisplayName("R4 — HWPX 표 뒤 같은 문단의 바깥 텍스트는 표 좌표를 유실 처리한다")
    fun `HWPX table tail은 셀 값으로 발명하지 않는다`() {
        val data = IngestFixtures.zipOf(mapOf("Contents/section0.xml" to EXPLICIT_HEADER_WITH_TAIL_HWPX.toByteArray()))

        val outcome = HwpxExtractor().extractStructured(data)
        val table = outcome.tables.single()

        assertThat(outcome.text).contains("표 뒤 바깥 문장")
        assertThat(table.supportStatus).isEqualTo(TableSupportStatus.UNSUPPORTED)
        assertThat(table.supportReason).isEqualTo(TableSupportReason.COORDINATES_LOST)
        assertThat(table.cells).isEmpty()
    }

    @Test
    @DisplayName("R4 — DOCX 표 뒤 별도 문단은 표 셀 좌표를 오염시키지 않는다")
    fun `DOCX table 뒤 문단은 표 좌표를 오염시키지 않는다`() {
        val original = IngestFixtures.bytes("sample_table.docx")
        val documentXml =
            requireNotNull(IngestFixtures.entriesOf(original)["word/document.xml"])
                .decodeToString()
                .replace(
                    "</w:tbl>",
                    "</w:tbl><w:p><w:r><w:t>표 뒤 바깥 문장</w:t></w:r></w:p>",
                )
        val data = IngestFixtures.withEntryReplaced(original, "word/document.xml", documentXml.toByteArray())

        val outcome = DocxExtractor().extractStructured(data)
        val table = outcome.tables.single()

        assertThat(outcome.text).contains("표 뒤 바깥 문장")
        assertThat(table.supportStatus).isEqualTo(TableSupportStatus.SUPPORTED)
        assertThat(table.cells).hasSize(4)
    }

    @Test
    @DisplayName("R4 — 명시적인 단위 표식은 해당 source unit 하나에만 연결한다")
    fun `단위 표식이 있는 줄만 unit anchor가 된다`() {
        val original = IngestFixtures.bytes("sample_table.docx")
        val documentXml =
            requireNotNull(IngestFixtures.entriesOf(original)["word/document.xml"])
                .decodeToString()
                .replace("<w:t>구분</w:t>", "<w:t>단위: 원</w:t>")
        val data = IngestFixtures.withEntryReplaced(original, "word/document.xml", documentXml.toByteArray())

        val table = DocxExtractor().extractStructured(data).tables.single()

        assertThat(table.unitAnchors).containsExactly(1)
        assertThat(table.footnoteAnchors).isEmpty()
        assertThat(table.supportStatus).isEqualTo(TableSupportStatus.SUPPORTED)
    }

    @Test
    @DisplayName("R4 — unresolved DOCX footnote reference는 좌표 유실로 보류한다")
    fun `외부 각주 reference가 있으면 표를 지원하지 않는다`() {
        val original = IngestFixtures.bytes("sample_table.docx")
        val documentXml =
            requireNotNull(IngestFixtures.entriesOf(original)["word/document.xml"])
                .decodeToString()
                .replace("<w:t>구분</w:t>", "<w:t>구분</w:t><w:footnoteReference w:id=\"1\"/>")
        val data = IngestFixtures.withEntryReplaced(original, "word/document.xml", documentXml.toByteArray())

        val table = DocxExtractor().extractStructured(data).tables.single()

        assertThat(table.supportStatus).isEqualTo(TableSupportStatus.UNSUPPORTED)
        assertThat(table.supportReason).isEqualTo(TableSupportReason.COORDINATES_LOST)
        assertThat(table.footnoteAnchors).isEmpty()
    }

    @Test
    @DisplayName("R4 — inline ※ 각주는 해당 줄만 footnote anchor로 연결한다")
    fun `inline 각주 표식은 셀 전체가 아니라 해당 source unit만 가리킨다`() {
        val original = IngestFixtures.bytes("sample_table.docx")
        val documentXml =
            requireNotNull(IngestFixtures.entriesOf(original)["word/document.xml"])
                .decodeToString()
                .replace(
                    "<w:r><w:t>구분</w:t></w:r></w:p>",
                    "<w:r><w:t>구분</w:t></w:r></w:p><w:p><w:r><w:t>※ 주의</w:t></w:r></w:p>",
                )
        val data = IngestFixtures.withEntryReplaced(original, "word/document.xml", documentXml.toByteArray())

        val table = DocxExtractor().extractStructured(data).tables.single()

        assertThat(table.supportStatus).isEqualTo(TableSupportStatus.SUPPORTED)
        assertThat(table.footnoteAnchors).containsExactly(2)
        assertThat(table.cells.first().sourceUnitIndexes).containsExactly(1, 2)
    }

    @Test
    @DisplayName("A1 — DOCX 표 셀 줄이 전부 TABLE_CELL 이고 줄 수가 splitUnits 와 같다")
    fun `DOCX 표 셀은 TABLE_CELL 이다`() {
        val data = IngestFixtures.bytes("sample_table.docx")

        val outcome = DocxExtractor().extractStructured(data)

        // 오라클(repo-fixtures-oracle.json) 그대로: "쉬운 글 변환 안내\n구분\n내용\n접수 기간\n
        // 3월 1일부터 3월 31일까지" — 첫 줄만 표 밖 제목이고 나머지 넷은 2x2 표의 칸이다.
        val lines = splitUnits(outcome.text)
        assertThat(lines).hasSize(5)
        assertThat(outcome.structure.kinds).hasSize(lines.size)
        assertThat(outcome.structure.kinds[0]).isEqualTo(UnitKind.BODY)
        assertThat(outcome.structure.kinds.drop(1)).containsOnly(UnitKind.TABLE_CELL)
    }

    @Test
    @DisplayName("A2 — HWPX 표 셀 두 줄은 TABLE_CELL, 표 뒤 문단은 BODY 로 남는다")
    fun `HWPX 표 셀은 TABLE_CELL 이고 표 뒤 문단은 BODY 다`() {
        val data = ExportFixtures.richHwpx()

        val outcome = HwpxExtractor().extractStructured(data)

        val lines = splitUnits(outcome.text)
        // ExportFixtures.richHwpx KDoc 의 추출 순서 그대로: 머리말 문구 → 첫 문단입니다. →
        // 표 셀 하나 → 표 셀 둘 → 표 뒤 문단입니다. → 둘째 구역의 문장입니다.
        assertThat(lines).contains("표 셀 하나", "표 셀 둘", "표 뒤 문단입니다.")
        assertThat(outcome.structure.kinds).hasSize(lines.size)

        val cellOne = lines.indexOf("표 셀 하나")
        val cellTwo = lines.indexOf("표 셀 둘")
        val afterTable = lines.indexOf("표 뒤 문단입니다.")

        assertThat(outcome.structure.kinds[cellOne]).isEqualTo(UnitKind.TABLE_CELL)
        assertThat(outcome.structure.kinds[cellTwo]).isEqualTo(UnitKind.TABLE_CELL)
        // 관찰: 표 뒤 문단은 표 칸 밖으로 나와 있고 글머리 기호도 없으므로 BODY 로 접힌다 —
        // HWPX 는 표 칸만 구조로 정하고 나머지는 텍스트 휴리스틱을 거친다(계획 §1.2).
        assertThat(outcome.structure.kinds[afterTable])
            .describedAs("표 뒤 문단은 표 칸이 아니고 목록 마커도 없어 BODY 다")
            .isEqualTo(UnitKind.BODY)
    }

    @Test
    @DisplayName(
        "표 뒤 문장이 같은 run 에서 이어지면(새 p 가 없으면) 한 줄로 합쳐지고 TABLE_CELL 로 남는다 " +
            "— 텍스트를 가르면 반영기(TextUnitWalk)와 차례가 어긋난다",
    )
    fun `표 뒤 문장이 같은 문단 안에서 이어지면 TABLE_CELL 로 합쳐진다`() {
        val data =
            IngestFixtures.withEntryReplaced(
                IngestFixtures.bytes("sample.hwpx"),
                "Contents/section0.xml",
                TABLE_WITH_TRAILING_TEXT_IN_SAME_RUN_SECTION.toByteArray(Charsets.UTF_8),
            )

        val outcome = HwpxExtractor().extractStructured(data)

        val lines = splitUnits(outcome.text)
        // sample.hwpx 는 구역이 둘이다 — 갈아 끼운 것은 section0 뿐이므로 section1 의
        // "둘째 구역의 문장입니다." 가 뒤이어 그대로 남는다. 새 p 경계가 없으므로 셀 문장과
        // 뒤 문장은 한 줄로 합쳐진다 — 텍스트 분리는 반영기(export/TextUnits.kt)와의 차례
        // 동기화가 소유하므로 여기서 가르지 않는다.
        assertThat(lines).hasSize(2)
        assertThat(outcome.structure.kinds).hasSize(lines.size)
        val mergedLineIndex = lines.indexOf("셀 문장 뒤 문장")
        assertThat(mergedLineIndex).describedAs("셀 문장과 뒤 문장이 한 줄로 합쳐지지 않았다").isNotEqualTo(-1)
        assertThat(outcome.structure.kinds[mergedLineIndex]).isEqualTo(UnitKind.TABLE_CELL)
    }

    @Test
    @DisplayName("A3 — DOCX w:pPr/w:numPr 문단은 LIST_ITEM 이다")
    fun `DOCX numPr 문단은 LIST_ITEM 이다`() {
        val data =
            IngestFixtures.withEntryReplaced(
                IngestFixtures.bytes("sample.docx"),
                "word/document.xml",
                NUM_PR_DOCUMENT_XML.toByteArray(Charsets.UTF_8),
            )

        val outcome = DocxExtractor().extractStructured(data)

        val lines = splitUnits(outcome.text)
        assertThat(lines).containsExactly("안내문 제목", "목록 항목 하나", "일반 문단")
        assertThat(outcome.structure.kinds)
            .containsExactly(UnitKind.BODY, UnitKind.LIST_ITEM, UnitKind.BODY)
    }

    @ParameterizedTest(name = "\"{0}\"")
    @ValueSource(strings = ["1. 첫째 항목", "가) 둘째 항목", "① 셋째 항목", "- 넷째 항목", "※ 유의사항"])
    @DisplayName("A4 — TXT 줄머리 마커·글머리 기호는 LIST_ITEM 이다")
    fun `TXT 목록 줄은 LIST_ITEM 이다`(line: String) {
        val text = "안내문 제목\n$line\n본문 설명입니다."

        val outcome = TxtExtractor().extractStructured(text.toByteArray(Charsets.UTF_8))

        val lines = splitUnits(outcome.text)
        assertThat(outcome.structure.kinds).hasSize(lines.size)
        assertThat(outcome.structure.kinds[lines.indexOf(line)]).isEqualTo(UnitKind.LIST_ITEM)
        assertThat(outcome.structure.kinds[0]).isEqualTo(UnitKind.BODY)
    }

    @Test
    @DisplayName("A4 — TXT 는 표 칸이 없다, 마커 없는 줄은 전부 BODY 다")
    fun `TXT 는 표 칸이 없다`() {
        val text = "2026년 3월 2일부터\n-5도\n그냥 본문입니다."

        val outcome = TxtExtractor().extractStructured(text.toByteArray(Charsets.UTF_8))

        assertThat(outcome.structure.kinds).containsOnly(UnitKind.BODY)
    }

    @Test
    @DisplayName("PDF 는 표·목록 구조 힌트 범위 밖이다 — 전부 BODY")
    fun `PDF 는 전부 BODY 다`() {
        val data = IngestFixtures.bytes("sample.pdf")

        val outcome = PdfExtractor().extractStructured(data)

        val lines = splitUnits(outcome.text)
        assertThat(outcome.structure.kinds).hasSize(lines.size)
        assertThat(outcome.structure.kinds).containsOnly(UnitKind.BODY)
        assertThat(outcome.tables.single().supportReason).isEqualTo(TableSupportReason.UNSUPPORTED_FORMAT)
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["sample.docx", "sample_table.docx", "sample_rich.docx"])
    @DisplayName("불변식 — DOCX 종류 수가 splitUnits(추출 원문) 줄 수와 같다")
    fun `DOCX 종류 수가 줄 수와 같다`(name: String) {
        val outcome = DocxExtractor().extractStructured(IngestFixtures.bytes(name))

        assertThat(outcome.structure.kinds).hasSize(splitUnits(outcome.text).size)
    }

    @Test
    @DisplayName("불변식 — HWPX 종류 수가 splitUnits(추출 원문) 줄 수와 같다")
    fun `HWPX 종류 수가 줄 수와 같다`() {
        val outcome = HwpxExtractor().extractStructured(IngestFixtures.bytes("sample.hwpx"))

        assertThat(outcome.structure.kinds).hasSize(splitUnits(outcome.text).size)
    }

    private companion object {
        val NUM_PR_DOCUMENT_XML =
            """
            <?xml version='1.0' encoding='UTF-8' standalone='yes'?>
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>
            <w:p><w:r><w:t>안내문 제목</w:t></w:r></w:p>
            <w:p><w:pPr><w:numPr><w:ilvl w:val="0"/><w:numId w:val="1"/></w:numPr></w:pPr><w:r><w:t>목록 항목 하나</w:t></w:r></w:p>
            <w:p><w:r><w:t>일반 문단</w:t></w:r></w:p>
            <w:sectPr><w:pgSz w:w="12240" w:h="15840"/></w:sectPr>
            </w:body></w:document>
            """.trimIndent()

        /**
         * 표(`hp:tbl`)를 닫은 뒤에도 바깥 `hp:p` 가 끝나지 않고 같은 run 안에서 문장이
         * 이어지는 구역 — `<hp:p><hp:run><hp:tbl>…</hp:tbl><hp:t>뒤 문장</hp:t></hp:run></hp:p>`.
         * 새 `p` 경계가 없으므로 셀 문장과 뒤 문장이 한 블록으로 합쳐진다.
         */
        val TABLE_WITH_TRAILING_TEXT_IN_SAME_RUN_SECTION =
            """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <hs:sec xmlns:hs="http://www.hancom.co.kr/hwpml/2011/section" xmlns:hp="http://www.hancom.co.kr/hwpml/2011/paragraph">
            <hp:p id="1" paraPrIDRef="0" styleIDRef="0"><hp:run charPrIDRef="0">
            <hp:tbl id="800" borderFillIDRef="1"><hp:tr>
            <hp:tc><hp:subList>
            <hp:p id="801" paraPrIDRef="0" styleIDRef="0"><hp:run charPrIDRef="0"><hp:t>셀 문장</hp:t></hp:run></hp:p>
            </hp:subList></hp:tc>
            </hp:tr></hp:tbl><hp:t> 뒤 문장</hp:t></hp:run></hp:p>
            </hs:sec>
            """.trimIndent()

        val EXPLICIT_HEADER_HWPX =
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <hs:sec xmlns:hs="http://www.hancom.co.kr/hwpml/2011/section" xmlns:hp="http://www.hancom.co.kr/hwpml/2011/paragraph">
            <hp:p><hp:run><hp:tbl rowCnt="2" colCnt="2" repeatHeader="1">
            <hp:tr><hp:tc><hp:cellAddr colAddr="0" rowAddr="0"/><hp:subList><hp:p><hp:run><hp:t>구분</hp:t></hp:run></hp:p></hp:subList></hp:tc>
            <hp:tc><hp:cellAddr colAddr="1" rowAddr="0"/><hp:subList><hp:p><hp:run><hp:t>내용</hp:t></hp:run></hp:p></hp:subList></hp:tc></hp:tr>
            <hp:tr><hp:tc><hp:cellAddr colAddr="0" rowAddr="1"/><hp:subList><hp:p><hp:run><hp:t>기간</hp:t></hp:run></hp:p></hp:subList></hp:tc>
            <hp:tc><hp:cellAddr colAddr="1" rowAddr="1"/><hp:subList><hp:p><hp:run><hp:t>3월</hp:t></hp:run></hp:p></hp:subList></hp:tc></hp:tr>
            </hp:tbl></hp:run></hp:p>
            </hs:sec>
            """.trimIndent()

        val UNMARKED_TABLE_HWPX = EXPLICIT_HEADER_HWPX.replace(" repeatHeader=\"1\"", "")

        val EXPLICIT_HEADER_WITH_TAIL_HWPX =
            EXPLICIT_HEADER_HWPX.replace(
                "</hp:tbl></hp:run></hp:p>",
                "</hp:tbl><hp:t> 표 뒤 바깥 문장</hp:t></hp:run></hp:p>",
            )

        val MERGED_HWPX =
            EXPLICIT_HEADER_HWPX.replace(
                "<hp:cellAddr colAddr=\"0\" rowAddr=\"0\"/>",
                "<hp:cellSpan colSpan=\"2\" rowSpan=\"1\"/><hp:cellAddr colAddr=\"0\" rowAddr=\"0\"/>",
            )

        val OUT_OF_ORDER_HWPX =
            EXPLICIT_HEADER_HWPX.replace(
                "<hp:cellAddr colAddr=\"0\" rowAddr=\"0\"/>",
                "<hp:cellAddr colAddr=\"1\" rowAddr=\"0\"/>",
            )

        val NESTED_HWPX =
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <hs:sec xmlns:hs="http://www.hancom.co.kr/hwpml/2011/section" xmlns:hp="http://www.hancom.co.kr/hwpml/2011/paragraph">
            <hp:p><hp:run><hp:tbl rowCnt="1" colCnt="1" repeatHeader="1"><hp:tr><hp:tc>
            <hp:cellAddr colAddr="0" rowAddr="0"/><hp:subList><hp:p><hp:run>
            <hp:tbl rowCnt="1" colCnt="1" repeatHeader="1"><hp:tr><hp:tc>
            <hp:cellAddr colAddr="0" rowAddr="0"/><hp:subList><hp:p><hp:run><hp:t>중첩</hp:t></hp:run></hp:p></hp:subList>
            </hp:tc></hp:tr></hp:tbl>
            </hp:run></hp:p></hp:subList></hp:tc></hp:tr></hp:tbl></hp:run></hp:p>
            </hs:sec>
            """.trimIndent()
    }
}
