package kr.easydoc.api.support

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** 업로드 계약 케이스가 쓰는 입력 바이트. */
object UploadFixtures {
    private const val ROOT = "/fixtures/ingest"

    /** OOXML 워드 문서 하나. 이 파일의 본문이 정상 추출된다. */
    fun sampleDocx(): ByteArray = bytes("sample.docx")

    fun samplePdf(): ByteArray = bytes("sample.pdf")

    fun sampleHwpx(): ByteArray = bytes("sample.hwpx")

    fun bytes(name: String): ByteArray =
        requireNotNull(UploadFixtures::class.java.getResourceAsStream("$ROOT/$name")) {
            "fixture 를 찾지 못했다: $ROOT/$name — infrastructure testFixtures 리소스가 클래스패스에 없다"
        }.use { it.readBytes() }

    /** 정확히 [targetBytes] 크기인 정상 docx 를 만든다 (DC-13 의 경계). */
    fun docxOfExactSize(targetBytes: Int): ByteArray {
        val entries = readEntries(sampleDocx())
        val document =
            requireNotNull(entries[DOCUMENT_PART]) { "sample.docx 에 $DOCUMENT_PART 가 없다" }
                .toString(Charsets.UTF_8)
        val baseline = rebuild(entries, withComment(document, 0)).size
        val padding = targetBytes - baseline
        require(padding >= 0) {
            "목표 크기 $targetBytes 가 무압축 재조립 최소치 $baseline 보다 작다 — 이 방법으로 만들 수 없다"
        }
        val padded = rebuild(entries, withComment(document, padding))
        check(padded.size == targetBytes) {
            "크기를 맞추지 못했다: 기대 $targetBytes / 실제 ${padded.size}. STORED 가정이 깨졌다."
        }
        return padded
    }

    /** 압축 해제량이 예산을 넘는 zip (DC-15 의 압축 폭탄 갈래). */
    fun zipOverBudget(uncompressedBytes: Int): ByteArray {
        val sink = ByteArrayOutputStream()
        ZipOutputStream(sink).use { zip ->
            zip.putNextEntry(ZipEntry(DOCUMENT_PART))
            zip.write(ByteArray(uncompressedBytes))
            zip.closeEntry()
        }
        return sink.toByteArray()
    }

    /** 외부 엔터티를 선언하고 참조하는 hwpx (DC-15 의 외부 엔터티 선언 갈래). */
    fun hwpxWithDoctype(): ByteArray {
        val sink = ByteArrayOutputStream()
        ZipOutputStream(sink).use { zip ->
            zip.putNextEntry(ZipEntry(HWPX_SECTION_PART))
            zip.write(DOCTYPE_SECTION.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return sink.toByteArray()
    }

    /** OLE2 매직 + UTF-16LE 스트림 이름 — 구버전 워드 컨테이너로 진단되는 최소 바이트. */
    fun legacyWordContainer(): ByteArray =
        byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte()) +
            ByteArray(OLE2_PADDING_BYTES) +
            WORD_STREAM_NAME.toByteArray(Charsets.UTF_16LE)

    private fun readEntries(archive: ByteArray): LinkedHashMap<String, ByteArray> {
        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(archive.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory) entries[entry.name] = zip.readBytes()
                zip.closeEntry()
            }
        }
        return entries
    }

    /** [DOCUMENT_PART] 만 무압축으로 다시 넣는다. 나머지는 원래대로 압축한다. */
    private fun rebuild(
        entries: Map<String, ByteArray>,
        document: String,
    ): ByteArray {
        val sink = ByteArrayOutputStream()
        ZipOutputStream(sink).use { zip ->
            entries.forEach { (name, content) ->
                if (name == DOCUMENT_PART) {
                    writeStored(zip, name, document.toByteArray(Charsets.UTF_8))
                } else {
                    writeDeflated(zip, name, content)
                }
            }
        }
        return sink.toByteArray()
    }

    /**
     * 무압축 항목. 크기를 산술로 맞출 수 있는 유일한 방법이라 이 자리에만 쓴다 —
     * STORED 는 규격상 크기·CRC 를 미리 적어야 한다.
     */
    private fun writeStored(
        zip: ZipOutputStream,
        name: String,
        content: ByteArray,
    ) {
        val entry =
            ZipEntry(name).apply {
                method = ZipEntry.STORED
                size = content.size.toLong()
                compressedSize = content.size.toLong()
                crc = CRC32().apply { update(content) }.value
            }
        zip.putNextEntry(entry)
        zip.write(content)
        zip.closeEntry()
    }

    private fun writeDeflated(
        zip: ZipOutputStream,
        name: String,
        content: ByteArray,
    ) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(content)
        zip.closeEntry()
    }

    /**
     * XML 선언 뒤, 루트 요소 앞에 정확히 [length] 바이트짜리 주석을 끼운다.
     * 선언보다 앞에 두면 문서가 깨진다.
     */
    private fun withComment(
        document: String,
        length: Int,
    ): String {
        if (length == 0) return document
        require(length >= COMMENT_OVERHEAD) { "주석 골격보다 짧은 길이는 만들 수 없다: $length" }
        val declarationEnd = document.indexOf(DECLARATION_END)
        require(declarationEnd >= 0) { "$DOCUMENT_PART 에 XML 선언이 없다 — 주석을 끼울 자리를 찾지 못했다" }
        val insertAt = declarationEnd + DECLARATION_END.length
        val filler = COMMENT_FILLER.repeat(length - COMMENT_OVERHEAD)
        return document.substring(0, insertAt) + "<!--$filler-->" + document.substring(insertAt)
    }

    private const val DOCUMENT_PART = "word/document.xml"
    private const val HWPX_SECTION_PART = "Contents/section0.xml"
    private const val DECLARATION_END = "?>"

    /** `<!--` + `-->` 일곱 바이트. 주석 자체가 차지하는 몫이라 채움 길이에서 뺀다. */
    private const val COMMENT_OVERHEAD = 7

    /** 주석 안에서 안전한 한 바이트. `-` 는 `--` 를 만들어 주석을 깨뜨리므로 쓰지 않는다. */
    private const val COMMENT_FILLER = "x"

    private const val OLE2_PADDING_BYTES = 64
    private const val WORD_STREAM_NAME = "WordDocument"

    /** 외부 엔터티를 선언하고 참조하는 구역 XML — 고전적인 XXE 모양이다. */
    private val DOCTYPE_SECTION =
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE sec [<!ENTITY leak SYSTEM "file:///etc/hostname">]>
        <sec><p><run><t>&leak;</t></run></p></sec>
        """.trimIndent()
}
