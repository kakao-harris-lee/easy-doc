package kr.easydoc.infrastructure.ingest

import kr.easydoc.core.document.SourceFormat
import kr.easydoc.core.exceptions.DocumentExtractionException
import kr.easydoc.core.exceptions.UnsupportedFormatException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.NullAndEmptySource
import org.junit.jupiter.params.provider.ValueSource

/** 디스패치 · 확장자 판별 · OLE2 3분기. */
class DocumentExtractorsTest {
    private val extractors = DocumentExtractors()

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(
        "안내문.docx, docx",
        "안내문.DOCX, docx",
        "안내문.Pdf, pdf",
        "안내문.hwpx, hwpx",
        "안내문.txt, txt",
        "안내문.TXT, txt",
        "C:\\Users\\hong\\안내문.docx, docx",
        "/tmp/../안내문.pdf, pdf",
        "a.b.c.hwpx, hwpx",
    )
    @DisplayName("확장자로 형식을 가린다 — 대소문자 무시, 경로 흡수")
    fun `확장자로 형식을 가린다`(
        filename: String,
        expected: String,
    ) {
        assertThat(SourceFormat.ofUploadFilename(filename)?.wireName).isEqualTo(expected)
    }

    @ParameterizedTest(name = "\"{0}\"")
    @NullAndEmptySource
    @ValueSource(strings = ["안내문", "안내문.hwp", "안내문.doc", ".hwpx", "안내문.docx.exe", "안내문."])
    @DisplayName("지원하지 않는 이름은 형식이 아니다")
    fun `지원하지 않는 확장자는 형식이 없다`(filename: String?) {
        assertThat(SourceFormat.ofUploadFilename(filename)).isNull()
    }

    @Test
    @DisplayName("지원하지 않는 형식은 계약 문구 그대로 거절한다")
    fun `지원하지 않는 형식을 거절한다`() {
        assertThatThrownBy { extractors.extract("안내문.hwp", byteArrayOf()) }
            .isInstanceOf(UnsupportedFormatException::class.java)
            .hasMessage("지원 형식: docx, pdf, hwpx, txt")
    }

    @Test
    @DisplayName("안내 문구가 지원 형식 집합에서 유도된다 — 형식을 늘리면 안내도 는다")
    fun `안내 문구가 형식 집합에서 나온다`() {
        assertThat(ExtractionMessages.UNSUPPORTED_FORMAT)
            .isEqualTo("지원 형식: " + SourceFormat.UPLOAD_FORMATS.joinToString(", ") { it.wireName })
    }

    @Test
    @DisplayName("업로드 형식 집합이 **손으로 적은 값**과 같다 — 계약 대조는 UploadFormatContractTest 가 한다")
    fun `업로드 형식 집합이 못박은 값과 같다`() {
        assertThat(SourceFormat.UPLOAD_FORMATS.map { it.wireName }).containsExactly("docx", "pdf", "hwpx", "txt")
        assertThat(SourceFormat.entries.map { it.wireName })
            .containsExactly("text", "docx", "pdf", "hwpx", "txt")
        assertThat(SourceFormat.UPLOAD_FORMATS).doesNotContain(SourceFormat.TEXT)
    }

    @Test
    @DisplayName("zip 계열만 압축 예산을 지난다")
    fun `zip 컨테이너 판정`() {
        assertThat(SourceFormat.DOCX.isZipContainer).isTrue()
        assertThat(SourceFormat.HWPX.isZipContainer).isTrue()
        assertThat(SourceFormat.PDF.isZipContainer).isFalse()
        assertThat(SourceFormat.TEXT.isZipContainer).isFalse()
        assertThat(SourceFormat.TXT.isZipContainer).isFalse()
    }

    @Test
    @DisplayName("암호 걸린 OOXML 은 암호 안내로 거절한다 (OLE2 1분기)")
    fun `암호 컨테이너를 가려낸다`() {
        assertThatThrownBy { extractors.extract("안내문.docx", ole2With("EncryptedPackage")) }
            .isInstanceOf(DocumentExtractionException::class.java)
            .hasMessage(ExtractionMessages.ENCRYPTED)
    }

    @Test
    @DisplayName("구버전 doc 은 **전용 문구**로 거절한다 (OLE2 2분기 — 계약 legacy_doc_policy)")
    fun `구버전 doc 을 가려낸다`() {
        assertThatThrownBy { extractors.extract("안내문.docx", ole2With("WordDocument")) }
            .isInstanceOf(DocumentExtractionException::class.java)
            .hasMessage(ExtractionMessages.LEGACY_DOC)
    }

    @Test
    @DisplayName("OLE2 이지만 어느 쪽인지 모르면 단정하지 않는다 (OLE2 3분기)")
    fun `미상 OLE2 는 두 가능성을 함께 안내한다`() {
        assertThatThrownBy { extractors.extract("안내문.hwpx", ole2With("SomethingElse")) }
            .isInstanceOf(DocumentExtractionException::class.java)
            .hasMessage(ExtractionMessages.UNKNOWN_OLE2)
    }

    @Test
    @DisplayName("세 문구가 서로 다르다 — 3분기를 합치면 사용자가 취할 조치를 알 수 없다")
    fun `세 안내 문구가 서로 다르다`() {
        assertThat(
            setOf(
                ExtractionMessages.ENCRYPTED,
                ExtractionMessages.LEGACY_DOC,
                ExtractionMessages.UNKNOWN_OLE2,
            ),
        ).hasSize(3)
    }

    @Test
    @DisplayName("정상 파일 세 형식이 포트를 통해 형식과 본문을 함께 돌려준다")
    fun `세 형식이 포트를 통해 나온다`() {
        val docx = extractors.extract("안내문.docx", IngestFixtures.bytes("sample.docx"))
        val pdf = extractors.extract("안내문.pdf", IngestFixtures.bytes("sample.pdf"))
        val hwpx = extractors.extract("안내문.hwpx", IngestFixtures.bytes("sample.hwpx"))

        assertThat(docx.format).isEqualTo(SourceFormat.DOCX)
        assertThat(pdf.format).isEqualTo(SourceFormat.PDF)
        assertThat(hwpx.format).isEqualTo(SourceFormat.HWPX)
        assertThat(docx.text).isEqualTo(IngestFixtures.expectedText(IngestFixtures.repoOracle, "sample.docx"))
        assertThat(pdf.text).isEqualTo(IngestFixtures.expectedText(IngestFixtures.repoOracle, "sample.pdf"))
        assertThat(hwpx.text).isEqualTo(IngestFixtures.expectedText(IngestFixtures.repoOracle, "sample.hwpx"))
    }

    @Test
    @DisplayName("txt 도 포트를 통해 형식과 본문을 함께 돌려준다")
    fun `txt 가 포트를 통해 나온다`() {
        val txt = extractors.extract("안내문.txt", "안내문 첫 줄\n둘째 줄".toByteArray(Charsets.UTF_8))

        assertThat(txt.format).isEqualTo(SourceFormat.TXT)
        assertThat(txt.text).isEqualTo("안내문 첫 줄\n둘째 줄")
    }

    @Test
    @DisplayName("추출 결과의 toString 이 본문을 찍지 않는다")
    fun `추출 결과가 본문을 찍지 않는다`() {
        val result = extractors.extract("안내문.docx", IngestFixtures.bytes("sample.docx"))

        assertThat(result.toString()).doesNotContain("쉬운 글 변환 안내")
        assertThat(result.toString()).contains("docx")
    }

    /** OLE2 매직 + UTF-16LE 스트림 이름을 담은 최소 바이트. */
    private fun ole2With(streamName: String): ByteArray =
        byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte()) +
            ByteArray(OLE2_PADDING_BYTES) +
            streamName.toByteArray(Charsets.UTF_16LE)

    private companion object {
        const val OLE2_PADDING_BYTES = 64
    }
}
