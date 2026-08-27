package kr.easydoc.infrastructure.export

import kr.dogfoot.hwpxlib.reader.HWPXReader
import kr.dogfoot.hwpxlib.tool.textextractor.TextExtractMethod
import kr.dogfoot.hwpxlib.tool.textextractor.TextExtractor
import kr.dogfoot.hwpxlib.tool.textextractor.TextMarks
import kr.easydoc.core.easyread.ExportFormat
import kr.easydoc.infrastructure.ingest.DocumentExtractors
import kr.easydoc.infrastructure.ingest.SecureXml
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamReader

/** DOCX·HWPX 패키지가 추출기·공식 OWPML 모델로 다시 열리는가. */
class PackagedDocumentExporterTest {
    private val exporter = PackagedDocumentExporter()
    private val extractors = DocumentExtractors()

    @Test
    @DisplayName("TXT 는 BOM 없이 본문만 담는다")
    fun `txt 본문이 그대로다`() {
        val file = exporter.export("제목", "한 줄.\n두 줄.", ExportFormat.TXT)

        assertThat(file.filename).isEqualTo("제목-쉬운글.txt")
        assertThat(file.mediaType).isEqualTo(ExportFormat.TXT.mediaType)
        assertThat(String(file.content, Charsets.UTF_8)).isEqualTo("한 줄.\n두 줄.")
    }

    @Test
    @DisplayName("DOCX 문단이 추출기로 다시 읽힌다")
    fun `docx 왕복이 본문을 지킨다`() {
        val file = exporter.export("보고서", "첫 문단\n둘째 문단", ExportFormat.DOCX)

        assertThat(file.filename).isEqualTo("보고서-쉬운글.docx")
        assertThat(file.mediaType).isEqualTo(ExportFormat.DOCX.mediaType)
        val extracted = extractors.extract(file.filename, file.content)
        assertThat(extracted.text).contains("첫 문단").contains("둘째 문단")
    }

    @Test
    @DisplayName("HWPX 구역 텍스트가 추출기로 다시 읽히고 mimetype 이 첫 STORED 항목이다")
    fun `hwpx 왕복이 본문을 지킨다`() {
        val file = exporter.export("안내", "한글 본문\n둘째 줄", ExportFormat.HWPX)

        assertThat(file.filename).isEqualTo("안내-쉬운글.hwpx")
        assertThat(file.mediaType).isEqualTo(ExportFormat.HWPX.mediaType)
        val extracted = extractors.extract(file.filename, file.content)
        assertThat(extracted.text).contains("한글 본문").contains("둘째 줄")

        ZipFile.builder().setSeekableByteChannel(SeekableInMemoryByteChannel(file.content)).get().use { zip ->
            val first: ZipArchiveEntry = zip.entries.nextElement()
            assertThat(first.name).isEqualTo(MIMETYPE_NAME)
            assertThat(first.method).isEqualTo(ZipEntry.STORED)
        }
    }

    @Test
    @DisplayName("HWPX 는 한컴 OPF 모델이다 — header.xml 이 있고 spine idref 가 manifest 항목이다")
    fun `hwpx 가 공식 패키지 구조를 지킨다`() {
        val file = exporter.export("안내", "한글 본문", ExportFormat.HWPX)
        val parts = hwpxZipEntries(file.content)

        assertThat(parts.keys)
            .withFailMessage("Contents/header.xml 이 없다 — 한컴 본문 서식 설정 파일이다")
            .contains(HEADER_PATH)
        assertThat(parts.keys)
            .contains(CONTENT_HPF_PATH, SECTION_PATH)

        val opf = parseOpf(parts.getValue(CONTENT_HPF_PATH))
        assertThat(opf.items.keys)
            .withFailMessage("manifest 에 header·section0 이 없다: %s", opf.items)
            .contains(HEADER_ID, SECTION_ID)
        assertThat(opf.spine)
            .withFailMessage("spine 이 비었다 — 한컴은 읽기 순서를 spine 으로 정한다")
            .isNotEmpty()
        assertThat(opf.spine)
            .withFailMessage("spine idref 가 manifest 에 없다. spine=%s manifest=%s", opf.spine, opf.items.keys)
            .allMatch { it in opf.items }
        assertThat(resolvedHref(opf.items.getValue(HEADER_ID)))
            .isEqualTo(HEADER_PATH)
        assertThat(resolvedHref(opf.items.getValue(SECTION_ID)))
            .isEqualTo(SECTION_PATH)
        opf.items.values.forEach { href ->
            assertThat(parts.keys)
                .withFailMessage("manifest href %s 가 zip 에 없다", href)
                .contains(resolvedHref(href))
        }

        val header = parts.getValue(HEADER_PATH)
        assertThat(headerRoot(header))
            .isEqualTo(HEAD_LOCAL_NAME)
        assertThat(headerSecCnt(header))
            .withFailMessage("header.xml 의 secCnt 가 1 이 아니다 — 구역 하나짜리 본문이다")
            .isEqualTo("1")
    }

    @Test
    @DisplayName("생성한 HWPX 를 hwpxlib 공식 모델로 열면 본문이 같다")
    fun `hwpx 가 공식 리더로 열린다`() {
        val body = "한글 본문\n둘째 줄"
        val file = exporter.export("안내", body, ExportFormat.HWPX)
        val tmp = Files.createTempFile("easydoc-export", ".hwpx")
        try {
            Files.write(tmp, file.content)
            val opened = HWPXReader.fromFile(tmp.toFile())
            val marks = TextMarks().apply { paraSeparator("\n") }
            val extracted =
                TextExtractor.extract(
                    opened,
                    TextExtractMethod.InsertControlTextBetweenParagraphText,
                    false,
                    marks,
                )
            assertThat(extracted).contains("한글 본문").contains("둘째 줄")
            assertThat(opened.headerXMLFile().secCnt()).isEqualTo(1.toShort())
            assertThat(
                opened
                    .contentHPFFile()
                    .manifest()
                    .items()
                    .map { it.id() },
            ).contains(HEADER_ID, SECTION_ID)
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    @Test
    @DisplayName("XML 특수문자가 hwpx 에서 이스케이프되어도 추출 본문은 원문이다")
    fun `hwpx 가 xml 특수문자를 담는다`() {
        val body = "A < B & C > D"
        val file = exporter.export("제목", body, ExportFormat.HWPX)

        assertThat(extractors.extract(file.filename, file.content).text).contains(body)
    }
}

private fun parseOpf(hpf: ByteArray): OpfPackage {
    val items = LinkedHashMap<String, String>()
    val spine = mutableListOf<String>()
    val reader = SecureXml.newInputFactory().createXMLStreamReader(ByteArrayInputStream(hpf))
    try {
        while (reader.hasNext()) recordOpf(reader, items, spine)
    } finally {
        reader.close()
    }
    return OpfPackage(items = items, spine = spine)
}

private fun recordOpf(
    reader: XMLStreamReader,
    items: MutableMap<String, String>,
    spine: MutableList<String>,
) {
    if (reader.next() != XMLStreamConstants.START_ELEMENT) return
    when (reader.localName) {
        ITEM_LOCAL_NAME -> recordManifestItem(reader, items)
        ITEMREF_LOCAL_NAME -> reader.getAttributeValue(null, IDREF_ATTR)?.let(spine::add)
    }
}

private fun recordManifestItem(
    reader: XMLStreamReader,
    items: MutableMap<String, String>,
) {
    val id = reader.getAttributeValue(null, ID_ATTR)
    val href = reader.getAttributeValue(null, HREF_ATTR)
    if (id != null && href != null) items[id] = href
}

private fun headerRoot(header: ByteArray): String? = firstLocalName(header)

private fun headerSecCnt(header: ByteArray): String? = firstAttribute(header, HEAD_LOCAL_NAME, SEC_CNT_ATTR)

private fun firstLocalName(xml: ByteArray): String? {
    val reader = SecureXml.newInputFactory().createXMLStreamReader(ByteArrayInputStream(xml))
    try {
        return nextStart(reader)?.localName
    } finally {
        reader.close()
    }
}

private fun firstAttribute(
    xml: ByteArray,
    localName: String,
    attribute: String,
): String? {
    val reader = SecureXml.newInputFactory().createXMLStreamReader(ByteArrayInputStream(xml))
    try {
        val start = nextStart(reader)
        return start?.takeIf { it.localName == localName }?.getAttributeValue(null, attribute)
    } finally {
        reader.close()
    }
}

private fun nextStart(reader: XMLStreamReader): XMLStreamReader? {
    while (reader.hasNext()) {
        if (reader.next() == XMLStreamConstants.START_ELEMENT) return reader
    }
    return null
}

/** 한컴 content.hpf 의 href 는 패키지 루트 기준이다. */
private fun resolvedHref(href: String): String = href.trimStart('/')

private data class OpfPackage(
    val items: Map<String, String>,
    val spine: List<String>,
)

private const val MIMETYPE_NAME: String = "mimetype"
private const val HEADER_PATH: String = "Contents/header.xml"
private const val CONTENT_HPF_PATH: String = "Contents/content.hpf"
private const val SECTION_PATH: String = "Contents/section0.xml"
private const val HEADER_ID: String = "header"
private const val SECTION_ID: String = "section0"
private const val HEAD_LOCAL_NAME: String = "head"
private const val ITEM_LOCAL_NAME: String = "item"
private const val ITEMREF_LOCAL_NAME: String = "itemref"
private const val ID_ATTR: String = "id"
private const val HREF_ATTR: String = "href"
private const val IDREF_ATTR: String = "idref"
private const val SEC_CNT_ATTR: String = "secCnt"
