package kr.easydoc.infrastructure.ingest

import kr.easydoc.core.document.SourceFormat
import kr.easydoc.core.exceptions.DocumentExtractionException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Files

/**
 * HWPX 추출과 **XML 폭탄 방어**(`migration-safety-gate` I-10 검증 2).
 *
 * ## 인코딩 축이 이 클래스의 존재 이유다
 *
 * 본문을 `<!DOCTYPE` 바이트로 훑는 방어는 **UTF-16 으로 인코딩하면 그대로 뚫린다.**
 * 그래서 UTF-8 과 UTF-16 두 벌을 모두 시험한다 — 하나만 두면 바이트 검색 구현이 통과한다.
 */
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
        // UTF-16 으로 쓰면 `<!DOCTYPE` 이 그 바이트열로 나타나지 않는다. 파서 수준 차단만
        // 이 입력을 막는다. 인코딩 선언을 함께 적어야 파서가 UTF-16 으로 읽는다.
        val xml = billionLaughs(encoding = "UTF-16")
        val bomb = hwpxWithSection(xml.toByteArray(StandardCharsets.UTF_16))

        assertThatThrownBy { extractor.extract(bomb) }
            .isInstanceOf(DocumentExtractionException::class.java)
            .hasMessage(ExtractionMessages.broken(SourceFormat.HWPX))
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
    @DisplayName("본문 구역이 없으면 전용 문구로 거절한다")
    fun `구역 없는 패키지를 거절한다`() {
        val empty = IngestFixtures.zipOf(mapOf("mimetype" to "application/hwp+zip".toByteArray()))

        assertThatThrownBy { extractor.extract(empty) }
            .isInstanceOf(DocumentExtractionException::class.java)
            .hasMessage(ExtractionMessages.HWPX_NO_SECTIONS)
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
}
