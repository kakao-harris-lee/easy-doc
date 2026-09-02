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
        if (manifestDeclaresEncryption(data)) {
            // 암호화된 구역은 XML 이 아니라 암호문 바이트다 — 그대로 두면 아래 readSections 가
            // "손상" 문구를 낸다. 원인을 먼저 짚어 준다.
            ExtractionFailureLog.record(SourceFormat.HWPX, data.size, "encrypted_container")
            throw DocumentExtractionException(ExtractionMessages.ENCRYPTED)
        }
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

    /**
     * hwpx(OWPML, KS X 6101)는 ODF 패키지의 매니페스트를 그대로 재사용한다. 항목별 암호화
     * 여부는 `META-INF/manifest.xml` 안 `manifest:encryption-data` 요소로 표시된다 —
     * OASIS OpenDocument v1.2 Part 3 "Packages", §4.4 `<manifest:encryption-data>`
     * (https://docs.oasis-open.org/office/v1.2/os/OpenDocument-v1.2-os-part3.html, 2026-09-02 확인).
     * 접두사는 패키지마다 다를 수 있어(실제 암호화된 hwpx 표본은 `odf:manifest`/`odf:encryption-data`
     * 를 쓴다) 접두사가 아니라 **로컬 이름**만 본다.
     *
     * 매니페스트가 없거나 못 읽으면 조용히 `false` 다 — 그 경우는 기존 `broken`/`no_sections`
     * 경로가 이미 다루므로 여기서 새 실패 모드를 만들지 않는다.
     */
    private fun manifestDeclaresEncryption(data: ByteArray): Boolean {
        val manifest = readManifest(data) ?: return false
        return try {
            val reader = SecureXml.newInputFactory().createXMLStreamReader(ByteArrayInputStream(manifest))
            try {
                var found = false
                while (!found && reader.hasNext()) {
                    found =
                        reader.next() == XMLStreamConstants.START_ELEMENT &&
                        reader.localName == ENCRYPTION_DATA_ELEMENT
                }
                found
            } finally {
                runCatching { reader.close() }
            }
        } catch (_: XMLStreamException) {
            // 매니페스트가 못 읽는 XML 이면 이 검사만 접는다 — 새 실패 모드를 만들지 않는다.
            false
        }
    }

    private fun readManifest(data: ByteArray): ByteArray? =
        try {
            ZipBudget.readEntries(data, SourceFormat.HWPX) { name -> name == MANIFEST_NAME }[MANIFEST_NAME]
        } catch (_: DocumentExtractionException) {
            // 매니페스트가 없거나 zip 층에서 걸려도 이 검사만 접는다 — 뒤이은 readSections 가
            // 같은 원인을 다시 만나 원래 사유(손상·예산 초과)로 거절한다.
            null
        }

    private companion object {
        /** OWPML 패키지에서 본문을 담는 항목. 번호가 구역 순서다. */
        val SECTION_NAME = Regex("""Contents/section(\d+)\.xml""")
        const val MANIFEST_NAME = "META-INF/manifest.xml"
        const val ENCRYPTION_DATA_ELEMENT = "encryption-data"
    }
}
