package kr.easydoc.api.support

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 업로드 계약 케이스가 쓰는 **입력 바이트**.
 *
 * 정상 fixture 는 `infrastructure` 의 testFixtures 리소스에서 그대로 읽는다 — 두 모듈이
 * **같은 파일**을 본다. 복사해 오면 두 벌이 되고, 파서 테스트와 계약 테스트가 서로 다른
 * 입력을 재는 날이 온다.
 *
 * 깨진 파일·폭탄·경계값 파일은 **커밋하지 않고 여기서 만든다**(fixture README 의 규약).
 */
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

    /**
     * **정확히 [targetBytes] 크기인 정상 docx** 를 만든다 (DC-13 의 경계).
     *
     * ## 왜 이렇게 만드는가
     *
     * 경계 케이스가 필요한 것은 「업로드 바이트가 **정확히** 상한」이고, 그러려면 파일 크기를
     * 바이트 단위로 맞출 수 있어야 한다. 압축 항목은 내용에 따라 크기가 비선형으로 변해
     * 맞추기 어렵다. 그래서 `word/document.xml` 을 **무압축(STORED)** 으로 다시 넣고,
     * XML 선언 뒤에 **주석**을 채워 넣는다 — STORED 는 채운 바이트가 그대로 파일 크기라
     * 필요한 길이를 한 번의 산술로 얻는다.
     *
     * 주석은 XML 문법상 루트 앞에 올 수 있고 파서가 무시하므로 **추출 결과가 달라지지
     * 않는다.** 압축 해제량도 파일 크기와 같아 zip 예산(계약
     * `x-input-limits.zip_uncompressed_budget_bytes`, 업로드 상한의 5배) 안이다.
     */
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

    /**
     * **압축 해제량이 예산을 넘는 zip** (DC-15 의 압축 폭탄 갈래).
     *
     * 0 바이트는 극단적으로 잘 압축되므로 작은 업로드가 예산을 넘긴다 — 그것이 이 방어가
     * 막으려는 형태다. 항목 이름을 docx 본문 자리로 두어 디스패치가 zip 계열로 태우게 한다.
     */
    fun zipOverBudget(uncompressedBytes: Int): ByteArray {
        val sink = ByteArrayOutputStream()
        ZipOutputStream(sink).use { zip ->
            zip.putNextEntry(ZipEntry(DOCUMENT_PART))
            zip.write(ByteArray(uncompressedBytes))
            zip.closeEntry()
        }
        return sink.toByteArray()
    }

    /**
     * **외부 엔터티를 선언하고 참조하는 hwpx** (DC-15 의 외부 엔터티 선언 갈래).
     *
     * **내부 서브셋에 엔터티를 선언한다.** 2026-08-20 실측: `SUPPORT_DTD = false` 인
     * JDK StAX 는 **내부 서브셋이 없는 DOCTYPE**(외부 DTD 참조만)을 조용히 무시하고
     * 문서를 그대로 파싱한다 — 거절하지 않는다. 보안상으로는 문제가 없지만
     * (`ACCESS_EXTERNAL_DTD=""` 라 외부 DTD 를 가져오지 않고 펼칠 엔터티도 없다)
     * **그 모양으로는 이 케이스가 422 를 재지 못한다.** 사실의 정본과 회귀는
     * `HwpxExtractorTest` 의 「내부 서브셋 없는 DOCTYPE」 케이스다.
     */
    fun hwpxWithDoctype(): ByteArray {
        val sink = ByteArrayOutputStream()
        ZipOutputStream(sink).use { zip ->
            zip.putNextEntry(ZipEntry(HWPX_SECTION_PART))
            zip.write(DOCTYPE_SECTION.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return sink.toByteArray()
    }

    /** **OLE2 매직 + UTF-16LE 스트림 이름** — 구버전 워드 컨테이너로 진단되는 최소 바이트. */
    fun legacyWordContainer(): ByteArray =
        byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte()) +
            ByteArray(OLE2_PADDING_BYTES) +
            WORD_STREAM_NAME.toByteArray(Charsets.UTF_16LE)

    // ── zip 조립 ──────────────────────────────────────────────────────────

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

    /** [DOCUMENT_PART] 만 **무압축**으로 다시 넣는다. 나머지는 원래대로 압축한다. */
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
     * 무압축 항목. **크기를 산술로 맞출 수 있는 유일한 방법**이라 이 자리에만 쓴다 —
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
     * XML 선언 **뒤**, 루트 요소 **앞**에 정확히 [length] 바이트짜리 주석을 끼운다.
     * 선언보다 앞에 두면 문서가 깨진다.
     *
     * **[length] 가 0 이면 주석 자체를 넣지 않는다.** 기준선을 잴 때 주석 골격
     * ([COMMENT_OVERHEAD] 바이트)이 함께 들어가면 그만큼 최종 크기가 모자란다 —
     * 실측으로 7바이트 어긋났다.
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

    /**
     * 외부 엔터티를 **선언하고 참조하는** 구역 XML — 고전적인 XXE 모양이다.
     *
     * 파서가 이것을 거절해야 하는 이유는 두 겹이다: 외부 자원을 가져오면 안 되고
     * (`IS_SUPPORTING_EXTERNAL_ENTITIES=false`·`ACCESS_EXTERNAL_DTD=""`), DTD 자체를
     * 지원하지 않으므로 엔터티 참조를 해석할 수 없다(`SUPPORT_DTD=false`).
     */
    private val DOCTYPE_SECTION =
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE sec [<!ENTITY leak SYSTEM "file:///etc/hostname">]>
        <sec><p><run><t>&leak;</t></run></p></sec>
        """.trimIndent()
}
