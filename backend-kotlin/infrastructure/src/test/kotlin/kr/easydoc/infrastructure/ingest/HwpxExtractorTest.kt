package kr.easydoc.infrastructure.ingest

import kr.easydoc.core.document.SourceFormat
import kr.easydoc.core.exceptions.DocumentExtractionException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Files

/** HWPX 추출과 XML 폭탄 방어(`migration-safety-gate` I-10 검증 2). */
class HwpxExtractorTest {
    private val extractor = HwpxExtractor()

    @Test
    @DisplayName("구역을 번호 순서로 이어 붙인다")
    fun `본문이 참고값과 같다`() {
        assertThat(extractor.extract(IngestFixtures.bytes("sample.hwpx")))
            .isEqualTo(IngestFixtures.expectedText(IngestFixtures.repoOracle, "sample.hwpx"))
    }

    @Test
    @DisplayName("billion laughs (UTF-8) 를 파서 수준에서 거부한다")
    fun `UTF-8 DTD 폭탄을 거부한다`() {
        val bomb = hwpxWithSection(billionLaughs().toByteArray(StandardCharsets.UTF_8))

        assertThatThrownBy { extractor.extract(bomb) }
            .isInstanceOf(DocumentExtractionException::class.java)
            .hasMessage(ExtractionMessages.broken(SourceFormat.HWPX))
    }

    @Test
    @DisplayName("billion laughs (UTF-16) 도 거부한다 — 바이트 검색 방어가 뚫리는 자리")
    fun `UTF-16 DTD 폭탄을 거부한다`() {
        val xml = billionLaughs(encoding = "UTF-16")
        val bomb = hwpxWithSection(xml.toByteArray(StandardCharsets.UTF_16))

        assertThatThrownBy { extractor.extract(bomb) }
            .isInstanceOf(DocumentExtractionException::class.java)
            .hasMessage(ExtractionMessages.broken(SourceFormat.HWPX))
    }

    @Test
    @DisplayName("내부 서브셋이 없는 DOCTYPE 은 **거절되지 않는다** — SUPPORT_DTD=false 의 실제 범위 (2026-08-20 실측)")
    fun `내부 서브셋 없는 DOCTYPE 은 그대로 파싱된다`() {
        val section =
            """<?xml version="1.0" encoding="UTF-8"?>
            |<!DOCTYPE sec SYSTEM "http://127.0.0.1/nope.dtd">
            |<sec><p><run><t>안내</t></run></p></sec>
            """.trimMargin()

        val onlySection =
            IngestFixtures.zipOf(
                mapOf("Contents/section0.xml" to section.toByteArray(StandardCharsets.UTF_8)),
            )

        assertThat(extractor.extract(onlySection)).isEqualTo("안내")
    }

    @Test
    @DisplayName("외부 엔터티(XXE)로 파일을 읽어 오지 못한다 — 유출 0")
    fun `외부 엔터티를 확장하지 않는다`() {
        val secret = Files.createTempFile("easydoc-xxe", ".txt")
        val marker = "CANARY-EXTERNAL-ENTITY-VALUE"
        Files.writeString(secret, marker)
        try {
            val xml =
                """<?xml version="1.0" encoding="UTF-8"?>
                |<!DOCTYPE hs:sec [<!ENTITY leak SYSTEM "file://${secret.toAbsolutePath()}">]>
                |<hs:sec xmlns:hs="http://www.hancom.co.kr/hwpml/2011/section"
                | xmlns:hp="http://www.hancom.co.kr/hwpml/2011/paragraph">
                |<hp:p><hp:run><hp:t>&leak;</hp:t></hp:run></hp:p></hs:sec>
                """.trimMargin()

            val thrown =
                runCatching { extractor.extract(hwpxWithSection(xml.toByteArray())) }
                    .exceptionOrNull()

            assertThat(thrown).isInstanceOf(DocumentExtractionException::class.java)
            assertThat(thrown?.message)
                .withFailMessage("예외 메시지에 외부 파일 내용이 실렸다 — 유출 경로다.")
                .doesNotContain(marker)
        } finally {
            Files.deleteIfExists(secret)
        }
    }

    @Test
    @DisplayName("문단 하나가 상한을 넘으면 **문서 끝에 닿기 전에** 끊는다 (게이트 27 지적 ②)")
    fun `상한을 문단 조립 중에 건다`() {
        val oversized = hwpxWithSection(oversizedParagraphWithBrokenTail().toByteArray(StandardCharsets.UTF_8))

        assertThatThrownBy { extractor.extract(oversized) }
            .isInstanceOf(DocumentExtractionException::class.java)
            .hasMessage(ExtractionMessages.EXTRACTED_TOO_LONG)
    }

    @Test
    @DisplayName("**리더 생성 시점**의 XML 실패도 정화된 422 로 나간다 (게이트 27 codex C-4/C-9)")
    fun `리더 생성 실패도 정화된다`() {
        val bogus =
            """<?xml version="1.0" encoding="BOGUS-ENCODING"?>
            |<hs:sec xmlns:hs="http://www.hancom.co.kr/hwpml/2011/section"
            | xmlns:hp="http://www.hancom.co.kr/hwpml/2011/paragraph">
            |<hp:p><hp:run><hp:t>본문</hp:t></hp:run></hp:p></hs:sec>
            """.trimMargin()

        val thrown =
            runCatching { extractor.extract(hwpxWithSection(bogus.toByteArray(StandardCharsets.UTF_8))) }
                .exceptionOrNull()

        assertThat(thrown)
            .withFailMessage("리더 생성 예외가 도메인 예외로 바뀌지 않았다 — 계약상 422 가 500 이 된다.")
            .isInstanceOf(DocumentExtractionException::class.java)
        assertThat(thrown?.message).isEqualTo(ExtractionMessages.broken(SourceFormat.HWPX))
    }

    @Test
    @DisplayName("본문 구역이 없으면 전용 문구로 거절한다")
    fun `구역 없는 패키지를 거절한다`() {
        val empty = IngestFixtures.zipOf(mapOf("mimetype" to "application/hwp+zip".toByteArray()))

        assertThatThrownBy { extractor.extract(empty) }
            .isInstanceOf(DocumentExtractionException::class.java)
            .hasMessage(ExtractionMessages.HWPX_NO_SECTIONS)
    }

    @Test
    @DisplayName(
        "매니페스트에 encryption-data 가 있으면 **손상 문구가 아니라 암호 안내**로 거절한다 " +
            "(실제 표본: docs/golden/golden-collection-plan.hwpx, AES-256-CBC)",
    )
    fun `암호화된 hwpx 를 암호 안내로 거절한다`() {
        val encrypted =
            IngestFixtures.zipOf(
                mapOf(
                    "META-INF/manifest.xml" to ENCRYPTED_MANIFEST.toByteArray(StandardCharsets.UTF_8),
                    // 암호화된 구역은 암호문 바이트다 — XML 이 아니다. 이 바이트가 파싱되면
                    // (검사가 없다면) "손상" 문구가 나가는 게 이 버그였다.
                    "Contents/section0.xml" to GARBAGE_SECTION_BYTES,
                ),
            )

        assertThatThrownBy { extractor.extract(encrypted) }
            .isInstanceOf(DocumentExtractionException::class.java)
            .hasMessage(ExtractionMessages.ENCRYPTED)
    }

    @Test
    @DisplayName("매니페스트는 있어도 encryption-data 가 없으면 그대로 추출한다 (대조군)")
    fun `암호화 표시 없는 매니페스트는 통과시킨다`() {
        val plain =
            IngestFixtures.zipOf(
                mapOf(
                    "META-INF/manifest.xml" to PLAIN_MANIFEST.toByteArray(StandardCharsets.UTF_8),
                    "Contents/section0.xml" to VALID_SECTION.toByteArray(StandardCharsets.UTF_8),
                ),
            )

        assertThat(extractor.extract(plain)).isEqualTo("안내")
    }

    @Test
    @DisplayName("매니페스트 자체가 없어도 그대로 추출한다 — 검사가 선택적임을 보인다 (대조군)")
    fun `매니페스트 없는 패키지도 통과시킨다`() {
        val noManifest =
            IngestFixtures.zipOf(mapOf("Contents/section0.xml" to VALID_SECTION.toByteArray(StandardCharsets.UTF_8)))

        assertThat(extractor.extract(noManifest)).isEqualTo("안내")
    }

    @Test
    @DisplayName(
        "BinData 만 암호화되고 구역은 평문이면 그대로 추출한다 — 사전 차단이 아니라 " +
            "구역 파싱 실패 뒤에만 진단해야 하는 이유(오탐 방지)",
    )
    fun `다른 항목만 암호화된 매니페스트는 통과시킨다`() {
        val onlyImageEncrypted =
            IngestFixtures.zipOf(
                mapOf(
                    "META-INF/manifest.xml" to BINDATA_ONLY_ENCRYPTED_MANIFEST.toByteArray(StandardCharsets.UTF_8),
                    "Contents/section0.xml" to VALID_SECTION.toByteArray(StandardCharsets.UTF_8),
                ),
            )

        assertThat(extractor.extract(onlyImageEncrypted)).isEqualTo("안내")
    }

    @Test
    @DisplayName(
        "매니페스트가 무관한 항목(BinData)만 암호화로 표시하고 구역 자체가 깨졌다면 " +
            "**손상**으로 거절한다 — 암호화라고 단정하지 않는다",
    )
    fun `무관한 항목의 암호화 표시로 손상을 암호화로 오판하지 않는다`() {
        val unrelatedEncryptionButBrokenSection =
            IngestFixtures.zipOf(
                mapOf(
                    "META-INF/manifest.xml" to BINDATA_ONLY_ENCRYPTED_MANIFEST.toByteArray(StandardCharsets.UTF_8),
                    "Contents/section0.xml" to GARBAGE_SECTION_BYTES,
                ),
            )

        assertThatThrownBy { extractor.extract(unrelatedEncryptionButBrokenSection) }
            .isInstanceOf(DocumentExtractionException::class.java)
            .hasMessage(ExtractionMessages.broken(SourceFormat.HWPX))
    }

    @Test
    @DisplayName("정상 재포장은 통과한다 — 위 거부들이 재포장 탓이 아님을 세운다")
    fun `대조군은 통과한다`() {
        val original = IngestFixtures.bytes("sample.hwpx")

        assertThat(extractor.extract(IngestFixtures.repackaged(original)))
            .isEqualTo(IngestFixtures.expectedText(IngestFixtures.repoOracle, "sample.hwpx"))
    }

    /** `Contents/section0.xml` 만 갈아 끼운 hwpx. 나머지 항목은 그대로 둔다. */
    private fun hwpxWithSection(section: ByteArray): ByteArray =
        IngestFixtures.withEntryReplaced(IngestFixtures.bytes("sample.hwpx"), "Contents/section0.xml", section)

    /** 문단 하나가 상한을 넘고, 그 뒤에 닫히지 않은 태그가 오는 구역 XML. */
    private fun oversizedParagraphWithBrokenTail(): String =
        """<?xml version="1.0" encoding="UTF-8"?>
        |<hs:sec xmlns:hs="http://www.hancom.co.kr/hwpml/2011/section"
        | xmlns:hp="http://www.hancom.co.kr/hwpml/2011/paragraph">
        |<hp:p><hp:run><hp:t>${"가".repeat(OVERSIZED_PARAGRAPH_CHARS)}</hp:t></hp:run></hp:p>
        |<hp:p><hp:run><hp:t>꼬리
        """.trimMargin()

    private fun billionLaughs(encoding: String = "UTF-8"): String =
        """<?xml version="1.0" encoding="$encoding"?>
        |<!DOCTYPE hs:sec [
        |<!ENTITY a "aaaaaaaaaa">
        |<!ENTITY b "&a;&a;&a;&a;&a;&a;&a;&a;&a;&a;">
        |<!ENTITY c "&b;&b;&b;&b;&b;&b;&b;&b;&b;&b;">
        |<!ENTITY d "&c;&c;&c;&c;&c;&c;&c;&c;&c;&c;">
        |]>
        |<hs:sec xmlns:hs="http://www.hancom.co.kr/hwpml/2011/section"
        | xmlns:hp="http://www.hancom.co.kr/hwpml/2011/paragraph">
        |<hp:p><hp:run><hp:t>&d;</hp:t></hp:run></hp:p></hs:sec>
        """.trimMargin()

    private companion object {
        /** 상한을 확실히 넘기되 fixture 가 불필요하게 커지지 않을 만큼만. */
        const val OVERSIZED_PARAGRAPH_CHARS = MAX_EXTRACTED_CHARS + 20_000

        /** `<sec><p><run><t>` 만 있으면 되는 최소 유효 구역 — 네임스페이스는 로컬 이름만 보므로 없어도 된다. */
        const val VALID_SECTION = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><sec><p><run><t>안내</t></run></p></sec>"

        /**
         * `manifest:encryption-data` 를 담은 매니페스트 — 실제 표본(golden-collection-plan.hwpx)이
         * 쓰는 `odf:` 접두사 그대로다. 검사는 접두사가 아니라 로컬 이름을 보므로 이 접두사가
         * 핵심 검증 대상이다.
         */
        const val ENCRYPTED_MANIFEST =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                "<odf:manifest xmlns:odf=\"urn:oasis:names:tc:opendocument:xmlns:manifest:1.0\">" +
                "<odf:file-entry full-path=\"Contents/section0.xml\" media-type=\"application/xml\">" +
                "<odf:encryption-data checksum-type=\"x\" checksum=\"y\">" +
                "<odf:algorithm algorithm-name=\"http://www.w3.org/2001/04/xmlenc#aes256-cbc\" " +
                "initialisation-vector=\"z\"/>" +
                "</odf:encryption-data>" +
                "</odf:file-entry>" +
                "</odf:manifest>"

        /** [ENCRYPTED_MANIFEST] 와 같은 모양이지만 `encryption-data` 가 없는 대조군. */
        const val PLAIN_MANIFEST =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                "<odf:manifest xmlns:odf=\"urn:oasis:names:tc:opendocument:xmlns:manifest:1.0\">" +
                "<odf:file-entry full-path=\"Contents/section0.xml\" media-type=\"application/xml\"/>" +
                "</odf:manifest>"

        /**
         * [ENCRYPTED_MANIFEST] 와 달리 암호화 표시가 구역이 아니라 `BinData/x.jpg` 에 붙는다 —
         * 실제 표본(golden-collection-plan.hwpx)이 이미지·구역을 각각 따로 암호화 표시하는
         * 모양 그대로다. 구역(`Contents/section0.xml`)은 이 매니페스트에 아예 등장하지 않는다.
         */
        const val BINDATA_ONLY_ENCRYPTED_MANIFEST =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                "<odf:manifest xmlns:odf=\"urn:oasis:names:tc:opendocument:xmlns:manifest:1.0\">" +
                "<odf:file-entry full-path=\"BinData/x.jpg\" media-type=\"image/jpeg\">" +
                "<odf:encryption-data checksum-type=\"x\" checksum=\"y\"/>" +
                "</odf:file-entry>" +
                "</odf:manifest>"

        /** 암호문 흉내 — 유효한 XML 이 아니다. */
        val GARBAGE_SECTION_BYTES =
            byteArrayOf(0x01, 0x02, 0xAB.toByte(), 0xCD.toByte(), 0xEF.toByte(), 0x00, 0x10, 0x20)
    }
}
