package kr.easydoc.infrastructure.export

import kr.easydoc.core.easyread.ExportFile
import kr.easydoc.core.easyread.ExportFormat
import kr.easydoc.core.easyread.renderTxt

internal interface DocumentPackageWriter {
    val format: ExportFormat

    fun write(
        title: String,
        body: String,
    ): ExportFile
}

internal class TxtPackageWriter : DocumentPackageWriter {
    override val format: ExportFormat = ExportFormat.TXT

    override fun write(
        title: String,
        body: String,
    ): ExportFile = renderTxt(title, body)
}

internal object DocumentExporterFactory {
    fun strategies(): List<DocumentPackageWriter> = listOf(TxtPackageWriter(), DocxPackageWriter(), HwpxPackageWriter())
}
