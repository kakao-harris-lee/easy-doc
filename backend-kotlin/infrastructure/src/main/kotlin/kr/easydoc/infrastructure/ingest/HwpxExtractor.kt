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
        sections.forEach { (name, content) -> readSection(data, name, content, data.size, builder) }
        return builder.build()
    }

    /** 구역 XML 을 **번호 순서로** 읽는다. 항목 이름을 함께 남긴다 — 실패 시 매니페스트를 그 이름으로 찾는다. */
    private fun readSections(data: ByteArray): List<Pair<String, ByteArray>> =
        ZipBudget
            .readEntries(data, SourceFormat.HWPX) { name -> SECTION_NAME.matches(name) }
            .entries
            .sortedBy { (name, _) -> sectionNumber(name) }
            .map { (name, content) -> name to content }

    private fun sectionNumber(name: String): Int =
        SECTION_NAME
            .matchEntire(name)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull() ?: Int.MAX_VALUE

    /** 구역 하나를 훑어 문단 블록을 [sink] 로 흘려보낸다. */
    private fun readSection(
        archive: ByteArray,
        sectionName: String,
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
            throw diagnoseSectionFailure(archive, sectionName, uploadSize, cause)
        } finally {
            // StAX 는 `Closeable` 이 아니라 `use` 를 쓸 수 없다. 닫기 실패는 삼킨다 —
            // 메모리 입력이라 실패할 일이 없고, 여기서 던지면 원래 실패 사유가 가려진다.
            reader?.let { runCatching { it.close() } }
        }
    }

    /**
     * 구역 파싱이 실패한 **뒤에만** 원인을 되짚는다 — 매니페스트가 있어도 실제로 파싱되는
     * 구역은 절대 이 경로에 오지 않는다(불변식: 구역이 파싱되면 이 함수는 호출되지 않는다).
     * 매니페스트는 항목별로 암호화 여부가 갈릴 수 있다 — 예컨대 `BinData` 안의 이미지만 암호화되고
     * 구역 XML 은 평문일 수 있다. 그래서 사전 검사가 아니라, 실패한 **그 구역의
     * `full-path`** 가 매니페스트에서 암호화로 표시돼 있는지만 사후에 확인한다.
     */
    private fun diagnoseSectionFailure(
        archive: ByteArray,
        sectionName: String,
        uploadSize: Int,
        cause: Throwable,
    ): DocumentExtractionException {
        if (manifestDeclaresEncryption(archive, sectionName)) {
            ExtractionFailureLog.record(SourceFormat.HWPX, uploadSize, "encrypted_container")
            return DocumentExtractionException(ExtractionMessages.ENCRYPTED)
        }
        return broken(uploadSize, cause)
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
     * 여부는 `META-INF/manifest.xml` 안, 그 항목의 `manifest:file-entry`(속성 `full-path`)
     * 밑에 자식으로 오는 `manifest:encryption-data` 요소로 표시된다 — OASIS OpenDocument v1.2
     * Part 3 "Packages", §4.4 `<manifest:encryption-data>`
     * (https://docs.oasis-open.org/office/v1.2/os/OpenDocument-v1.2-os-part3.html, 2026-09-02 확인).
     * 접두사는 패키지마다 다를 수 있어(실제 암호화된 hwpx 표본은 `odf:manifest`/`odf:file-entry`/
     * `odf:encryption-data` 를 쓴다) 접두사가 아니라 **로컬 이름**만 본다.
     *
     * [sectionName] 은 방금 파싱에 실패한 구역의 zip 항목 이름(`full-path`) 이다 — 매니페스트
     * 전체가 아니라 **그 항목**이 암호화로 표시돼 있는지만 본다. 매니페스트는 항목마다 다를 수
     * 있으므로(예: `BinData` 안의 이미지만 암호화되고 구역은 평문) 어느 한 항목의 암호화만으로
     * 다른 항목까지 암호화됐다고 단정하지 않는다.
     *
     * 매니페스트가 없거나 못 읽으면 조용히 `false` 다 — 그 경우는 기존 `broken` 경로가 이미
     * 다루므로 여기서 새 실패 모드를 만들지 않는다.
     */
    private fun manifestDeclaresEncryption(
        archive: ByteArray,
        sectionName: String,
    ): Boolean {
        val manifest = readManifest(archive) ?: return false
        return try {
            val reader = SecureXml.newInputFactory().createXMLStreamReader(ByteArrayInputStream(manifest))
            try {
                findEncryptedEntry(reader, sectionName)
            } finally {
                runCatching { reader.close() }
            }
        } catch (_: XMLStreamException) {
            // 매니페스트가 못 읽는 XML 이면 이 검사만 접는다 — 새 실패 모드를 만들지 않는다.
            false
        }
    }

    /** [targetFullPath] 를 가리키는 `file-entry` 안에 `encryption-data` 자식이 있는지 스트리밍으로 본다. */
    private fun findEncryptedEntry(
        reader: XMLStreamReader,
        targetFullPath: String,
    ): Boolean {
        var currentFullPath: String? = null
        while (reader.hasNext()) {
            val event = reader.next()
            when {
                event == XMLStreamConstants.START_ELEMENT && reader.localName == FILE_ENTRY_ELEMENT -> {
                    currentFullPath =
                        (0 until reader.attributeCount)
                            .firstOrNull { index -> reader.getAttributeLocalName(index) == FULL_PATH_ATTRIBUTE }
                            ?.let { index -> reader.getAttributeValue(index) }
                }

                event == XMLStreamConstants.START_ELEMENT &&
                    reader.localName == ENCRYPTION_DATA_ELEMENT &&
                    currentFullPath == targetFullPath -> {
                    return true
                }

                event == XMLStreamConstants.END_ELEMENT && reader.localName == FILE_ENTRY_ELEMENT -> {
                    currentFullPath = null
                }
            }
        }
        return false
    }

    private fun readManifest(data: ByteArray): ByteArray? =
        try {
            ZipBudget.readEntries(data, SourceFormat.HWPX) { name -> name == MANIFEST_NAME }[MANIFEST_NAME]
        } catch (_: DocumentExtractionException) {
            // 매니페스트가 없거나 zip 층에서 걸려도 이 검사만 접는다 — 원래 실패(broken)가
            // 그대로 나간다.
            null
        }

    private companion object {
        /** OWPML 패키지에서 본문을 담는 항목. 번호가 구역 순서다. */
        val SECTION_NAME = Regex("""Contents/section(\d+)\.xml""")
        const val MANIFEST_NAME = "META-INF/manifest.xml"
        const val FILE_ENTRY_ELEMENT = "file-entry"
        const val FULL_PATH_ATTRIBUTE = "full-path"
        const val ENCRYPTION_DATA_ELEMENT = "encryption-data"
    }
}
