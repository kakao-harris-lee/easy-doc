package kr.easydoc.infrastructure.export

import kr.dogfoot.hwpxlib.`object`.HWPXFile
import kr.dogfoot.hwpxlib.`object`.content.section_xml.SectionXMLFile
import kr.dogfoot.hwpxlib.`object`.content.section_xml.paragraph.Para
import kr.dogfoot.hwpxlib.`object`.content.section_xml.paragraph.Run
import kr.dogfoot.hwpxlib.`object`.content.section_xml.paragraph.T
import kr.dogfoot.hwpxlib.tool.blankfilemaker.BlankFileMaker
import kr.dogfoot.hwpxlib.writer.HWPXWriter
import kr.easydoc.core.easyread.ExportFile
import kr.easydoc.core.easyread.ExportFormat
import kr.easydoc.core.easyread.exportFileOf
import kr.easydoc.core.text.stripControlChars
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.CRC32
import java.util.zip.ZipEntry

/**
 * 복원된 본문을 HWPX(OWPML) 패키지로 담는다.
 *
 * 패키지 뼈대는 손 XML 이 아니라 [BlankFileMaker] 다. 한컴이 공개한 구조
 * (`Contents/header.xml`, `content.hpf` 의 manifest 항목을 spine 이 참조)를
 * 라이브러리가 채운다. zip 의 `mimetype` 만 개방형 컨테이너 규칙에 맞춰
 * 첫 STORED 항목으로 다시 얹는다.
 */
internal class HwpxPackageWriter {
    fun write(
        title: String,
        body: String,
    ): ExportFile {
        val hwpx = BlankFileMaker.make()
        fillBody(hwpx, stripControlChars(body))
        return exportFileOf(title, ExportFormat.HWPX, withStoredMimetypeFirst(HWPXWriter.toBytes(hwpx)))
    }

    private fun fillBody(
        hwpx: HWPXFile,
        body: String,
    ) {
        val section = sectionOf(hwpx)
        val lines = exportParagraphs(body)
        val first = section.getPara(0)
        textOf(first.getRun(0)).addText(lines.first())
        lines.drop(1).forEach { line -> appendParagraph(section, first, line) }
    }

    private fun sectionOf(hwpx: HWPXFile): SectionXMLFile {
        val sections = hwpx.sectionXMLFileList()
        check(sections.count() > 0) { "빈 HWPX 뼈대에 구역이 없다" }
        return sections.get(0)
    }

    private fun appendParagraph(
        section: SectionXMLFile,
        template: Para,
        line: String,
    ) {
        val para =
            section
                .addNewPara()
                .paraPrIDRefAnd(template.paraPrIDRef())
                .styleIDRefAnd(template.styleIDRef())
                .pageBreakAnd(false)
                .columnBreakAnd(false)
                .mergedAnd(false)
        val run = para.addNewRun()
        run.charPrIDRef(template.getRun(0).charPrIDRef())
        run.addNewT().addText(line)
    }

    private fun textOf(run: Run): T {
        for (index in 0 until run.countOfRunItem()) {
            val item = run.getRunItem(index)
            if (item is T) return item
        }
        return run.addNewT()
    }

    /**
     * hwpxlib 는 `mimetype` 을 DEFLATED 로 쓴다. 개방형 HWPX/OCF 는 이 항목이
     * 압축되지 않은 채 zip 의 첫 자리에 있어야 한다.
     */
    private fun withStoredMimetypeFirst(packaged: ByteArray): ByteArray {
        val parts = hwpxZipEntries(packaged)
        val mimetype = parts.remove(MIMETYPE_NAME) ?: error("hwpxlib 패키지에 mimetype 이 없다")
        val sink = ByteArrayOutputStream()
        ZipArchiveOutputStream(sink).use { zip ->
            zip.setEncoding(StandardCharsets.UTF_8.name())
            putStored(zip, MIMETYPE_NAME, mimetype)
            parts.forEach { (name, bytes) -> putDeflated(zip, name, bytes) }
        }
        return sink.toByteArray()
    }

    private fun putStored(
        zip: ZipArchiveOutputStream,
        name: String,
        bytes: ByteArray,
    ) {
        val entry = ZipArchiveEntry(name)
        entry.method = ZipEntry.STORED
        entry.size = bytes.size.toLong()
        entry.crc = crc32(bytes)
        zip.putArchiveEntry(entry)
        zip.write(bytes)
        zip.closeArchiveEntry()
    }

    private fun putDeflated(
        zip: ZipArchiveOutputStream,
        name: String,
        bytes: ByteArray,
    ) {
        zip.putArchiveEntry(ZipArchiveEntry(name))
        zip.write(bytes)
        zip.closeArchiveEntry()
    }

    private fun crc32(bytes: ByteArray): Long = CRC32().apply { update(bytes) }.value

    private companion object {
        const val MIMETYPE_NAME: String = "mimetype"
    }
}
