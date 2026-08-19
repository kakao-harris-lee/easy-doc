package kr.easydoc.infrastructure.ingest

import kr.easydoc.core.document.SourceFormat
import kr.easydoc.core.exceptions.DocumentExtractionException
import java.io.ByteArrayInputStream
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamException
import javax.xml.stream.XMLStreamReader

/**
 * HWPX(OWPML) 구역 XML 에서 문단 단위 텍스트를 뽑는다.
 *
 * 원본: `app/ingest/extractors.py::_extract_hwpx`·`_read_hwpx_sections`·`_hwpx_blocks`.
 *
 * ## 구조 (KS X 6101)
 *
 * 구역 `hs:sec` > 문단 `hp:p` > 글자 조각 `hp:run`/`hp:t`. 한 문단이 서식 때문에 여러
 * `hp:t` 로 쪼개지므로 조각은 **구분자 없이** 잇고, `hp:p` 를 만날 때만 블록을 끊는다.
 * 네임스페이스 URI 가 버전마다 달라 접두사가 아니라 **로컬 이름**으로 판별한다.
 *
 * ## 직접 조립하는 이유 (계획 §1.4)
 *
 * JVM 생태계에 검증된 HWPX 파서가 없다. 다만 이것은 "바퀴 재발명"이 아니라 **표준
 * 라이브러리 둘의 조합**이다 — 컨테이너는 commons-compress([ZipBudget]), XML 은 StAX
 * ([SecureXml]). 우리가 짜는 것은 그 사이의 20줄짜리 이벤트 처리뿐이다.
 *
 * ## 예산을 두 자리 모두 청크로 읽는다 (원본과 다른 지점 — 계획 §9 질문 ⑪)
 *
 * 원본은 디스패치 예산만 64KB 청크였고 구역 읽기는 `read(budget + 1)` **한 번**이라,
 * 구역 하나가 수십 MB 를 단번에 할당할 수 있었다. `migration-safety-gate` I-10 이 요구하는
 * 성질은 "실제 읽은 바이트로 센다"이지 "Python 과 같다"가 아니므로 고쳤다.
 *
 * ## 한계
 *
 * 표 셀 안의 중첩 문단은 바깥 문단과 별도 블록이 되며(원하는 동작), 바깥 문단이 중첩 문단
 * 뒤에 텍스트를 더 가지면 그 텍스트는 중첩 블록에 붙는다. 실제 hwpx 에서 표를 담은 문단은
 * 자체 텍스트가 없어 문제되지 않는다.
 */
internal class HwpxExtractor {
    fun extract(data: ByteArray): String {
        val sections = readSections(data)
        if (sections.isEmpty()) {
            // 구역이 하나도 없으면 hwpx 패키지가 아니거나 껍데기다.
            ExtractionFailureLog.record(SourceFormat.HWPX, data.size, "no_sections")
            throw DocumentExtractionException(ExtractionMessages.HWPX_NO_SECTIONS)
        }
        val builder = ExtractedTextBuilder(SourceFormat.HWPX, data.size)
        sections.forEach { section -> blocks(section, data.size).forEach(builder::add) }
        return builder.build()
    }

    /**
     * 구역 XML 을 **번호 순서로** 읽는다.
     *
     * zip 기록 순서는 믿지 않고 `section{N}.xml` 의 N 으로 정렬한다.
     */
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

    /**
     * 구역 하나를 훑어 문단 블록 목록을 만든다.
     *
     * DTD 선언은 [SecureXml] 이 파서 수준에서 끊는다. 그 실패는 [XMLStreamException] 으로
     * 오고, **메시지로 사유를 가르지 않는다**(로케일에 따라 번역된다) — 손상 파일과 같은
     * 문구를 내고 로그에는 예외 **타입**만 남긴다.
     */
    private fun blocks(
        section: ByteArray,
        uploadSize: Int,
    ): List<String> {
        val collector = SectionBlocks()
        val reader = SecureXml.newInputFactory().createXMLStreamReader(ByteArrayInputStream(section))
        try {
            readEvents(reader, collector)
        } catch (cause: XMLStreamException) {
            throw broken(uploadSize, cause)
        } finally {
            // StAX 는 `Closeable` 이 아니라 `use` 를 쓸 수 없다. 닫기 실패는 삼킨다 —
            // 메모리 입력이라 실패할 일이 없고, 여기서 던지면 원래 실패 사유가 가려진다.
            runCatching { reader.close() }
        }
        return collector.finish()
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
     * 구역 하나의 문단 블록을 모으는 상태 기계.
     *
     * 원본 `_hwpx_blocks` 의 세 핸들러(`start_element`·`end_element`·`characters`)와 1:1 이다.
     * `hp:t` 안에서만 문자를 모으고 `hp:p` 에서만 블록을 끊는다.
     */
    private class SectionBlocks {
        private val sink = mutableListOf<String>()
        private val current = StringBuilder()
        private var textDepth = 0

        fun startElement(name: String) {
            when (name) {
                "p" -> {
                    sink += current.toString()
                    current.setLength(0)
                }

                "t" -> {
                    textDepth++
                }
            }
        }

        fun endElement(name: String) {
            if (name == "t") textDepth--
        }

        fun characters(text: String) {
            if (textDepth > 0) current.append(text)
        }

        /** 마지막 문단은 닫는 태그가 아니라 문서 끝에서 끊긴다 — 원본과 같다. */
        fun finish(): List<String> = sink + current.toString()
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
