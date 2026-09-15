package kr.easydoc.infrastructure.ingest

import kr.easydoc.core.document.SourceFormat

internal interface StructuredTextExtractor {
    val format: SourceFormat

    fun extractStructured(data: ByteArray): ExtractionOutcome
}

internal object DocumentExtractorFactory {
    fun strategies(): List<StructuredTextExtractor> =
        listOf(DocxExtractor(), PdfExtractor(), HwpxExtractor(), TxtExtractor())
}
