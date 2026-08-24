package kr.easydoc.infrastructure.export

import kr.easydoc.core.easyread.ExportFile
import kr.easydoc.core.easyread.ExportFormat
import kr.easydoc.core.easyread.exportFileOf
import kr.easydoc.core.text.stripControlChars
import org.apache.poi.xwpf.usermodel.XWPFDocument
import java.io.ByteArrayOutputStream

/** 복원된 본문을 OOXML 문단으로 담는다. 레이아웃 보존은 Lean MVP 밖이다. */
internal class DocxPackageWriter {
    fun write(
        title: String,
        body: String,
    ): ExportFile {
        val content =
            XWPFDocument().use { document ->
                exportParagraphs(stripControlChars(body)).forEach { line ->
                    document.createParagraph().createRun().setText(line)
                }
                ByteArrayOutputStream().use { out ->
                    document.write(out)
                    out.toByteArray()
                }
            }
        return exportFileOf(title, ExportFormat.DOCX, content)
    }
}
