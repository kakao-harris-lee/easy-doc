package kr.easydoc.infrastructure.ingest

import kr.easydoc.core.document.SourceFormat
import kr.easydoc.core.exceptions.DocumentExtractionException
import java.io.ByteArrayInputStream
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamException
import javax.xml.stream.XMLStreamReader

/** HWPX(OWPML) 구역 XML 에서 문단 단위 텍스트를 뽑는다. */
internal class HwpxExtractor {
    fun extract(data: ByteArray): String {
        val sections = readSections(data)
        if (sections.isEmpty()) {
            // 구역이 하나도 없으면 hwpx 패키지가 아니거나 껍데기다.
            ExtractionFailureLog.record(SourceFormat.HWPX, data.size, "no_sections")
            throw DocumentExtractionException(ExtractionMessages.HWPX_NO_SECTIONS)
        }
        val builder = ExtractedTextBuilder(SourceFormat.HWPX, data.size)
        sections.forEach { section -> readSection(section, data.size, builder) }
        return builder.build()
    }

    /** 구역 XML 을 **번호 순서로** 읽는다. */
    private fun readSections(data: ByteArray): List<ByteArray> =
        ZipBudget
            .readEntries(data, SourceFormat.HWPX) { name -> SECTION_NAME.matches(name) }
            .entries
            .sortedBy { (name, _) -> sectionNumber(name) }
            .map { (_, content) -> content }

    private fun sectionNumber(name: String): Int =
        SECTION_NAME
            .matchEntire(name)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull() ?: Int.MAX_VALUE

    /** 구역 하나를 훑어 문단 블록을 [sink] 로 흘려보낸다. */
    private fun readSection(
        section: ByteArray,
        uploadSize: Int,
        sink: BlockSink,
    ) {
        val collector = SectionBlocks(sink)
        var reader: XMLStreamReader? = null
        try {
            reader = SecureXml.newInputFactory().createXMLStreamReader(ByteArrayInputStream(section))
            readEvents(reader, collector)
            collector.finish()
        } catch (cause: XMLStreamException) {
            throw broken(uploadSize, cause)
        } finally {
            // StAX 는 `Closeable` 이 아니라 `use` 를 쓸 수 없다. 닫기 실패는 삼킨다 —
            // 메모리 입력이라 실패할 일이 없고, 여기서 던지면 원래 실패 사유가 가려진다.
            reader?.let { runCatching { it.close() } }
        }
    }

    private fun readEvents(
        reader: XMLStreamReader,
        collector: SectionBlocks,
    ) {
        while (reader.hasNext()) {
            when (reader.next()) {
                XMLStreamConstants.START_ELEMENT -> {
                    collector.startElement(reader.localName)
                }

                XMLStreamConstants.END_ELEMENT -> {
                    collector.endElement(reader.localName)
                }

                XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA, XMLStreamConstants.SPACE -> {
                    collector.characters(reader.text)
                }
            }
        }
    }

    /** 구역 하나의 문단 블록을 [sink] 로 흘려보내는 상태 기계. */
    private class SectionBlocks(private val sink: BlockSink) {
        private val current = StringBuilder()
        private var textDepth = 0

        fun startElement(name: String) {
            when (name) {
                "p" -> flush()
                "t" -> textDepth++
            }
        }

        fun endElement(name: String) {
            if (name == "t") textDepth--
        }

        fun characters(text: String) {
            if (textDepth == 0) return
            // 붙이기 **전에** 묻는다. 붙인 뒤에 물으면 그 한 번의 할당이 이미 일어난 뒤다.
            sink.ensureRoomFor(current.length + text.length)
            current.append(text)
        }

        /** 마지막 문단은 닫는 태그가 아니라 문서 끝에서 끊긴다 — 원본과 같다. */
        fun finish() = flush()

        private fun flush() {
            sink.add(current.toString())
            current.setLength(0)
        }
    }

    private fun broken(
        uploadSize: Int,
        cause: Throwable,
    ): DocumentExtractionException {
        ExtractionFailureLog.recordCause(SourceFormat.HWPX, uploadSize, cause)
        return DocumentExtractionException(ExtractionMessages.broken(SourceFormat.HWPX))
    }

    private companion object {
        /** OWPML 패키지에서 본문을 담는 항목. 번호가 구역 순서다. */
        val SECTION_NAME = Regex("""Contents/section(\d+)\.xml""")
    }
}
