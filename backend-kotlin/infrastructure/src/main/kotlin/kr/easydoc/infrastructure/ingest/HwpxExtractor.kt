package kr.easydoc.infrastructure.ingest

import kr.easydoc.core.document.SourceFormat
import kr.easydoc.core.exceptions.DocumentExtractionException
import kr.easydoc.core.segment.SourceStructure
import kr.easydoc.core.segment.UnitKind
import kr.easydoc.core.segment.inferUnitKinds
import kr.easydoc.core.segment.splitUnits
import java.io.ByteArrayInputStream
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamException
import javax.xml.stream.XMLStreamReader

/** HWPX(OWPML) 구역 XML 에서 문단 단위 텍스트를 뽑는다. */
internal class HwpxExtractor {
    fun extract(data: ByteArray): String = extractStructured(data).text

    /**
     * 이어 붙인 본문과, 그 줄마다 하나씩 붙은 원본 단위 종류(표·목록 구조 힌트 계획 §1.2 표) —
     * 표 칸(`hp:tc` 조상)은 [UnitKind.TABLE_CELL] 로 XML 구조에서 바로 정하고, 그 외 줄은
     * [kr.easydoc.core.segment.inferUnitKinds] 텍스트 휴리스틱으로 [UnitKind.LIST_ITEM] 인지
     * [UnitKind.BODY] 인지 가른다 — HWPX 번호 매김은 스타일 참조라 1차에서 풀지 않는다(계획 §1.2).
     */
    fun extractStructured(data: ByteArray): ExtractionOutcome {
        val sections = readSections(data)
        if (sections.isEmpty()) {
            // 구역이 하나도 없으면 hwpx 패키지가 아니거나 껍데기다.
            ExtractionFailureLog.record(SourceFormat.HWPX, data.size, "no_sections")
            throw DocumentExtractionException(ExtractionMessages.HWPX_NO_SECTIONS)
        }
        val builder = ExtractedTextBuilder(SourceFormat.HWPX, data.size)
        sections.forEach { (name, content) -> readSection(data, name, content, data.size, builder) }
        val text = builder.build()
        return ExtractionOutcome(text, refineNonCellKinds(splitUnits(text), builder.structure()))
    }

    /**
     * 구역 XML 을 **번호 순서로** 읽는다. 항목 이름을 함께 남긴다 — 실패 시 매니페스트를 그
     * 이름으로 찾는다. 정렬 키(구역 번호)를 별도 멤버 함수로 두지 않고 여기 인라인한 것은
     * [HwpxExtractor] 의 멤버 함수 수 상한(`TooManyFunctions`)을 넘기지 않기 위해서다.
     */
    private fun readSections(data: ByteArray): List<Pair<String, ByteArray>> =
        ZipBudget
            .readEntries(data, SourceFormat.HWPX) { name -> SECTION_NAME.matches(name) }
            .entries
            .sortedBy { (name, _) ->
                SECTION_NAME
                    .matchEntire(name)
                    ?.groupValues
                    ?.get(1)
                    ?.toIntOrNull() ?: Int.MAX_VALUE
            }.map { (name, content) -> name to content }

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

    /**
     * 구역 하나의 문단 블록을 [sink] 로 흘려보내는 상태 기계.
     *
     * [tableDepth] 는 `hp:tc` 중첩 깊이다 — HWPX 표는 문단의 run **안에** 오므로(계획 §0-1),
     * 블록이 끊기는 시점(다음 `p` 시작)에 그 깊이가 이미 0 이 아니면 지금 막 시작하는 새
     * 블록이 표 칸 안이라는 뜻이다. [pendingKind] 가 그 판정을 다음 flush 까지 들고 간다.
     *
     * **표 뒤 문장이 같은 문단(run) 안에서 이어지면 종류를 나누지 않는다.**
     * `<hp:p><hp:run><hp:tbl>…</hp:tbl><hp:t>뒤 문장</hp:t></hp:run></hp:p>` 처럼 표
     * (`hp:tbl`)를 닫은 뒤에도 같은 바깥 `p` 가 끝나지 않았으면 `startElement("p")` 가
     * 다시 불리지 않으므로 [flush] 도, [pendingKind] 갱신도 일어나지 않는다 — 마지막 칸의
     * 문단에서 시작한 [current] 조각에 그 뒤 문장이 **그대로 이어 붙는다.** 텍스트를 갈라
     * 새 블록으로 끊지 않는 것은 **일부러다** — 반영 쪽 `export/TextUnits.kt` 의
     * `TextUnitWalk` 가 추출기와 **같은 차례로** 덩어리를 만들어야 하고
     * (`PackagedOriginalReflectorTest`), 그 파일 KDoc 이 이미 "표 뒤에 남은 조각은 마지막
     * 셀 문단의 덩어리에 붙는다"고 못박았다 — 여기서 `tc` 깊이가 0 으로 돌아왔다고 새로
     * flush 하면 추출과 반영의 차례가 어긋나 내보내기 자리 맞춤이 깨진다. 그래서 병합된
     * 한 줄은 **셀에서 시작했다는 사실 그대로** [UnitKind.TABLE_CELL] 로 남는다 —
     * `UnitKindExtractionTest` 의 "표 뒤 문장이 같은 run 에서 이어지면" 케이스가 고정한다.
     */
    private class SectionBlocks(private val sink: BlockSink) {
        private val current = StringBuilder()
        private var textDepth = 0
        private var tableDepth = 0
        private var pendingKind = UnitKind.BODY

        fun startElement(name: String) {
            when (name) {
                "p" -> flush(nextKind = if (tableDepth > 0) UnitKind.TABLE_CELL else UnitKind.BODY)
                "t" -> textDepth++
                TABLE_CELL_ELEMENT -> tableDepth++
            }
        }

        fun endElement(name: String) {
            when (name) {
                "t" -> textDepth--
                TABLE_CELL_ELEMENT -> tableDepth--
            }
        }

        fun characters(text: String) {
            if (textDepth == 0) return
            // 붙이기 **전에** 묻는다. 붙인 뒤에 물으면 그 한 번의 할당이 이미 일어난 뒤다.
            sink.ensureRoomFor(current.length + text.length)
            current.append(text)
        }

        /** 마지막 문단은 닫는 태그가 아니라 문서 끝에서 끊긴다 — 원본과 같다. */
        fun finish() = flush(nextKind = UnitKind.BODY)

        /** 지금까지 쌓은 텍스트를 **그 블록이 시작될 때 정해진 종류**로 내보내고, 다음 블록의 종류를 받아 둔다. */
        private fun flush(nextKind: UnitKind) {
            sink.add(current.toString(), pendingKind)
            current.setLength(0)
            pendingKind = nextKind
        }

        private companion object {
            const val TABLE_CELL_ELEMENT = "tc"
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

/**
 * 표 칸이 아닌(=[UnitKind.BODY] 로 잠정 표시된) 줄에만 [inferUnitKinds] 를 적용해
 * [UnitKind.LIST_ITEM] 인지 다시 가른다. 표 칸([UnitKind.TABLE_CELL])은 그대로 둔다(표·목록
 * 구조 힌트 계획 §1.2 — HWPX 번호 매김은 스타일 참조라 1차에서 풀지 않는다). 최상위 함수인
 * 것은 [HwpxExtractor] 의 멤버 함수 수 상한(`TooManyFunctions`)을 넘기지 않기 위해서다 —
 * `hp:tc` 깊이 추적 자체와는 독립된, 순수한 후처리다.
 */
private fun refineNonCellKinds(
    lines: List<String>,
    structural: SourceStructure,
): SourceStructure {
    val kinds = structural.kinds
    val bodyIndexes = kinds.indices.filter { kinds[it] == UnitKind.BODY }
    val refinedBody = inferUnitKinds(bodyIndexes.map { lines[it] })
    val merged = kinds.toMutableList()
    bodyIndexes.forEachIndexed { position, index -> merged[index] = refinedBody[position] }
    return SourceStructure(merged)
}
