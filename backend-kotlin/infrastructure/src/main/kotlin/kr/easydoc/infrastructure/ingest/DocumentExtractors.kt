package kr.easydoc.infrastructure.ingest

import kr.easydoc.application.document.DocumentTextExtractor
import kr.easydoc.application.document.ExtractedDocument
import kr.easydoc.core.document.SourceFormat
import kr.easydoc.core.exceptions.UnsupportedFormatException
import kr.easydoc.infrastructure.format.FormatRegistry

/** 확장자로 형식을 가려 형식별 추출기로 넘기는 **디스패치 한 곳**. */
class DocumentExtractors internal constructor(strategies: List<StructuredTextExtractor>) : DocumentTextExtractor {
    private val extractors = FormatRegistry(strategies, SourceFormat.UPLOAD_FORMATS.toSet()) { it.format }

    constructor() : this(DocumentExtractorFactory.strategies())

    override fun extract(
        filename: String?,
        bytes: ByteArray,
    ): ExtractedDocument {
        val format = uploadFormat(filename)
        validateContainer(format, bytes)
        val outcome = extractors[format].extractStructured(bytes)
        return ExtractedDocument(format, outcome.text, outcome.structure)
    }

    private fun uploadFormat(filename: String?): SourceFormat =
        SourceFormat.ofUploadFilename(filename)
            ?: throw UnsupportedFormatException(
                ExtractionMessages.LEGACY_EXTENSION_MESSAGES[SourceFormat.extensionOf(filename)]
                    ?: ExtractionMessages.UNSUPPORTED_FORMAT,
            )

    private fun validateContainer(
        format: SourceFormat,
        bytes: ByteArray,
    ) {
        if (!format.isZipContainer) return
        if (Ole2Diagnosis.looksLikeOle2(bytes)) throw Ole2Diagnosis.rejection(bytes, format)
        ZipBudget.ensureWithinBudget(bytes, format)
    }
}
