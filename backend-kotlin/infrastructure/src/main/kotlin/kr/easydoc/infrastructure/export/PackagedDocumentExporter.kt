package kr.easydoc.infrastructure.export

import kr.easydoc.application.document.DocumentExporter
import kr.easydoc.core.easyread.ExportFile
import kr.easydoc.core.easyread.ExportFormat
import kr.easydoc.infrastructure.format.FormatRegistry

/** 형식별 패키지 조립. TXT 는 core 순수 함수, DOCX·HWPX 는 이 모듈의 작성기다. */
class PackagedDocumentExporter internal constructor(strategies: List<DocumentPackageWriter>) : DocumentExporter {
    private val writers = FormatRegistry(strategies, ExportFormat.entries.toSet()) { it.format }

    constructor() : this(DocumentExporterFactory.strategies())

    override fun export(
        title: String,
        body: String,
        format: ExportFormat,
    ): ExportFile = writers[format].write(title, body)
}
