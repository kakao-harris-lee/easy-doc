package kr.easydoc.infrastructure.export

import kr.easydoc.application.document.DocumentExporter
import kr.easydoc.core.easyread.ExportFile
import kr.easydoc.core.easyread.ExportFormat
import kr.easydoc.core.easyread.renderTxt

/** 형식별 패키지 조립. TXT 는 core 순수 함수, DOCX·HWPX 는 이 모듈의 작성기다. */
class PackagedDocumentExporter : DocumentExporter {
    private val docx = DocxPackageWriter()
    private val hwpx = HwpxPackageWriter()

    override fun export(
        title: String,
        body: String,
        format: ExportFormat,
    ): ExportFile =
        when (format) {
            ExportFormat.TXT -> renderTxt(title, body)
            ExportFormat.DOCX -> docx.write(title, body)
            ExportFormat.HWPX -> hwpx.write(title, body)
        }
}
