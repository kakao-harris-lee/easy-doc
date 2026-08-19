package kr.easydoc.infrastructure.ingest

import kr.easydoc.core.exceptions.DocumentExtractionException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * DOCX 추출의 **동등성 9항목**과 파서 방어를 고정한다.
 *
 * 항목은 Phase 0 spike §2 의 대조표 그대로다. spike 는 Kotlin **2.2.0** 위에서 통과했고
 * 현재 조합은 Kotlin 2.3.21 / Boot 4.1.0 / commons-compress 1.28.0 이라, 이 클래스가
 * 카탈로그 주석이 예고한 **재확인**(계획 §7.1 P2)이다.
 *
 * ## 대조 단위가 `blocks()` 인 이유
 *
 * 정규화(`_join_blocks`) 뒤에 대조하면 **정규화가 차이를 덮는다** — 빈 블록 하나가 사라지는
 * 회귀는 이어 붙인 문자열에서 보이지 않는다. spike 도 같은 이유로 raw 블록을 비교했다.
 */
class DocxExtractorTest {
    private val extractor = DocxExtractor()

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["sample.docx", "sample_table.docx", "sample_rich.docx"])
    @DisplayName("정규화 이전 블록이 참고값과 일치한다 (동등성 1·2·4·5·7·8)")
    fun `블록 목록이 참고값과 같다`(name: String) {
        val actual = extractor.blocks(IngestFixtures.bytes(name))
        val expected = IngestFixtures.expectedBlocks(IngestFixtures.repoOracle, "_raw_docx_blocks", name)

        assertThat(actual)
            .withFailMessage {
                "블록이 참고값과 갈린다. **갈림 자체가 결함은 아니다** — 어느 쪽이 DOC-01 에 맞는지 " +
                    "판단해 산출물에 기록하라(master-plan 6.2).\n" +
                    "  기대: $expected\n  실제: $actual"
            }.isEqualTo(expected)
    }

    @Test
    @DisplayName("SDT·도형 텍스트(a:t)·수식(m:t) 를 걷는다 (동등성 3·6)")
    fun `SDT 와 도형 수식 텍스트를 걷는다`() {
        val data = IngestFixtures.bytes("sdt_shape_math.docx")

        assertThat(extractor.blocks(data))
            .isEqualTo(IngestFixtures.expectedBlocks(IngestFixtures.spikeOracle, "sdt_shape_math.docx::blocks"))
        assertThat(extractor.extract(data))
            .isEqualTo(IngestFixtures.expectedText(IngestFixtures.spikeOracle, "sdt_shape_math.docx"))
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["sample.docx", "sample_table.docx", "sample_rich.docx"])
    @DisplayName("정규화 결과가 참고값과 일치한다 (동등성 9 — 공백뿐인 문단 제거)")
    fun `이어 붙인 본문이 참고값과 같다`(name: String) {
        assertThat(extractor.extract(IngestFixtures.bytes(name)))
            .isEqualTo(IngestFixtures.expectedText(IngestFixtures.repoOracle, name))
    }

    @Test
    @DisplayName("텍스트박스 문구가 정확히 한 번만 나온다 — mc:Fallback 하강 중단 (동등성 5)")
    fun `mc Fallback 을 걷지 않아 중복이 없다`() {
        val text = extractor.extract(IngestFixtures.bytes("sample_rich.docx"))

        // 하강을 막지 않으면 DrawingML(mc:Choice)과 VML(mc:Fallback)에서 두 번 수집돼
        // 크레딧이 두 배로 청구되고 마스킹 결과까지 오염된다.
        assertThat(text.split("텍스트 상자 안 문장입니다.")).hasSize(2)
    }

    @Test
    @DisplayName("변경 추적: 삽입문은 포함되고 삭제문은 빠진다 (동등성 4)")
    fun `삭제문을 걷지 않는다`() {
        val text = extractor.extract(IngestFixtures.bytes("sample_rich.docx"))

        assertThat(text).contains("변경 추적으로 삽입된 문장입니다.")
        assertThat(text).doesNotContain("변경 추적으로 삭제된 문장입니다.")
    }

    @Test
    @DisplayName("물려받은 머리글·바닥글을 다시 걷지 않는다 (동등성 7)")
    fun `물려받은 머리글을 건너뛴다`() {
        val text = extractor.extract(IngestFixtures.bytes("sample_rich.docx"))

        // 구역이 둘인데 둘째 구역은 앞 구역을 물려받는다. 걷으면 문구가 두 번 나온다.
        assertThat(text.split("머리글 문구")).hasSize(2)
        assertThat(text.split("바닥글 문구")).hasSize(2)
        // 머리글 → 바닥글 순서 (동등성 8)
        assertThat(text.indexOf("머리글 문구")).isLessThan(text.indexOf("바닥글 문구"))
    }

    @Test
    @DisplayName("DOCTYPE 이 주입된 docx 를 거부한다 — 대조군은 통과한다 (계획 §5 D-7)")
    fun `DOCTYPE 주입 docx 를 거부한다`() {
        val original = IngestFixtures.bytes("sample.docx")
        val document = requireNotNull(IngestFixtures.entriesOf(original)["word/document.xml"])

        // 대조군: 내용은 그대로 두고 다시 포장만 한다. 이것이 통과해야 아래 거부가
        // "재포장 탓"이 아님이 선다.
        assertThat(extractor.extract(IngestFixtures.repackaged(original))).isNotEmpty()

        val injected = injectDoctype(document.decodeToString()).toByteArray()
        val bomb = IngestFixtures.withEntryReplaced(original, "word/document.xml", injected)

        assertThatThrownBy { extractor.extract(bomb) }
            .isInstanceOf(DocumentExtractionException::class.java)
            .hasMessage(ExtractionMessages.broken(kr.easydoc.core.document.SourceFormat.DOCX))
    }

    @Test
    @DisplayName("걷지 않는 요소를 **선언 상수**로 둔다 — 조용한 누락 금지 (DOC-02)")
    fun `걷지 않는 요소 목록이 선언돼 있다`() {
        // "문서화했다"는 자동 게이트가 아니다. 목록이 코드에 있어야 산출물·리뷰와 대조된다.
        assertThat(DocxExtractor.SKIPPED_PARTS)
            .withFailMessage("걷지 않는 요소 목록이 비었다 — DOC-02 의 판정 근거가 사라진다.")
            .isNotEmpty()
        assertThat(DocxExtractor.SKIPPED_PARTS).allSatisfy { entry -> assertThat(entry).isNotBlank() }
    }

    /** 루트 요소 앞에 내부 DTD 를 끼운다. 확장을 시도하지 않고 **선언만** 넣어도 거부여야 한다. */
    private fun injectDoctype(xml: String): String {
        val declarationEnd = xml.indexOf("?>")
        val head = if (declarationEnd >= 0) xml.substring(0, declarationEnd + 2) else ""
        val body = if (declarationEnd >= 0) xml.substring(declarationEnd + 2) else xml
        val doctype =
            "<!DOCTYPE w:document [" +
                "<!ENTITY a \"aaaaaaaaaa\">" +
                "<!ENTITY b \"&a;&a;&a;&a;&a;&a;&a;&a;&a;&a;\">" +
                "<!ENTITY c \"&b;&b;&b;&b;&b;&b;&b;&b;&b;&b;\">" +
                "]>"
        return head + doctype + body
    }
}
