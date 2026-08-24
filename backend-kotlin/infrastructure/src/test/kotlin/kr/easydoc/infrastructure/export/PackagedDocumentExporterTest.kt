package kr.easydoc.infrastructure.export

import kr.easydoc.core.easyread.ExportFormat
import kr.easydoc.infrastructure.ingest.DocumentExtractors
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.zip.ZipEntry

/** DOCX·HWPX 패키지가 우리 추출기로 다시 읽히는가. */
class PackagedDocumentExporterTest {
    private val exporter = PackagedDocumentExporter()
    private val extractors = DocumentExtractors()

    @Test
    @DisplayName("TXT 는 BOM 없이 본문만 담는다")
    fun `txt 본문이 그대로다`() {
        val file = exporter.export("제목", "한 줄.\n두 줄.", ExportFormat.TXT)

        assertThat(file.filename).isEqualTo("제목.txt")
        assertThat(file.mediaType).isEqualTo(ExportFormat.TXT.mediaType)
        assertThat(String(file.content, Charsets.UTF_8)).isEqualTo("한 줄.\n두 줄.")
    }

    @Test
    @DisplayName("DOCX 문단이 추출기로 다시 읽힌다")
    fun `docx 왕복이 본문을 지킨다`() {
        val file = exporter.export("보고서", "첫 문단\n둘째 문단", ExportFormat.DOCX)

        assertThat(file.filename).isEqualTo("보고서.docx")
        assertThat(file.mediaType).isEqualTo(ExportFormat.DOCX.mediaType)
        val extracted = extractors.extract(file.filename, file.content)
        assertThat(extracted.text).contains("첫 문단").contains("둘째 문단")
    }

    @Test
    @DisplayName("HWPX 구역 텍스트가 추출기로 다시 읽히고 mimetype 이 첫 STORED 항목이다")
    fun `hwpx 왕복이 본문을 지킨다`() {
        val file = exporter.export("안내", "한글 본문\n둘째 줄", ExportFormat.HWPX)

        assertThat(file.filename).isEqualTo("안내.hwpx")
        assertThat(file.mediaType).isEqualTo(ExportFormat.HWPX.mediaType)
        val extracted = extractors.extract(file.filename, file.content)
        assertThat(extracted.text).contains("한글 본문").contains("둘째 줄")

        ZipFile.builder().setSeekableByteChannel(SeekableInMemoryByteChannel(file.content)).get().use { zip ->
            val first: ZipArchiveEntry = zip.entries.nextElement()
            assertThat(first.name).isEqualTo("mimetype")
            assertThat(first.method).isEqualTo(ZipEntry.STORED)
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
