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
    @DisplayName("내부 서브셋이 없는 DOCTYPE 은 **거절되지 않는다** — SUPPORT_DTD=false 의 실제 범위 (2026-08-20 실측)")
    fun `내부 서브셋 없는 DOCTYPE 은 그대로 파싱된다`() {
        // `SUPPORT_DTD = false` 가 「DOCTYPE 을 만나는 즉시 끊는다」로 읽히기 쉬우나,
        // JDK StAX 는 **펼칠 것이 없는 DOCTYPE**(외부 DTD 참조만)을 조용히 무시하고
        // 문서를 그대로 파싱한다. 보안 성질은 그대로다 — `ACCESS_EXTERNAL_DTD=""` 와
        // `IS_SUPPORTING_EXTERNAL_ENTITIES=false` 때문에 외부 DTD 를 **가져오지 않으므로**
        // 엔터티도 없고 확장도 없다. 위 두 폭탄 케이스가 거절되는 것은 내부 서브셋에
        // 엔터티가 **선언·참조**되기 때문이다.
        //
        // 이 사실을 회귀로 붙들어 두는 이유: 계약 케이스가 「XML 외부 엔터티 선언 → 422」를
        // 잴 때 **이 모양을 쓰면 아무것도 재지 못한다.** 원본(Python `expat`)은 DOCTYPE
        // 자체를 거절했으므로 여기서 동작이 갈린다 — 요구(I-10 검증 2)가 요구하는 것은
        // 「파서 수준에서 엔터티 확장이 시작되지 않는다」이고 그것은 만족된다.
        val section =
            """<?xml version="1.0" encoding="UTF-8"?>
            |<!DOCTYPE sec SYSTEM "http://127.0.0.1/nope.dtd">
            |<sec><p><run><t>안내</t></run></p></sec>
            """.trimMargin()

        // 구역 하나만 든 패키지를 만든다 — sample.hwpx 를 고쳐 쓰면 그 파일의 다른 구역이
        // 결과에 섞여 「이 입력이 어떻게 읽혔는가」가 흐려진다.
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
        // ## 이 케이스가 「사후 검사」와 「누적 중단」을 어떻게 가르는가
        //
        // 두 구현 모두 이 입력을 거절하므로 **거절 여부로는 갈리지 않는다.** 그래서 구역
        // 끝에 **깨진 꼬리**를 둔다 — 파서가 거기까지 읽으면 `XMLStreamException` 이 나고
        // 사용자 문구가 "파일이 손상되었습니다"로 바뀐다.
        //
        // - 누적 중단(지금) → 첫 문단에서 예산이 터져 **꼬리에 닿지 않는다** → "너무 깁니다"
        // - 사후 검사(이전) → 구역 전체를 다 읽은 뒤에야 길이를 재므로 **꼬리에 먼저 닿는다**
        //   → "손상되었습니다"
        //
        // 즉 이 단언은 「어디까지 읽고 멈췄는가」를 사용자 문구로 관측한다.
        val oversized = hwpxWithSection(oversizedParagraphWithBrokenTail().toByteArray(StandardCharsets.UTF_8))

        assertThatThrownBy { extractor.extract(oversized) }
            .isInstanceOf(DocumentExtractionException::class.java)
            .hasMessage(ExtractionMessages.EXTRACTED_TOO_LONG)
    }

    @Test
    @DisplayName("**리더 생성 시점**의 XML 실패도 정화된 422 로 나간다 (게이트 27 codex C-4/C-9)")
    fun `리더 생성 실패도 정화된다`() {
        // `createXMLStreamReader` 는 잘못된 인코딩 선언을 **읽기 전에** 거부한다. 그 호출이
        // `try` 밖에 있으면 라이브러리 예외가 그대로 올라가 계약이 못박은 422 대신 500 이
        // 나가고, 라이브러리 메시지가 로그 규약(형식·바이트·타입만)을 우회한다.
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
    @DisplayName("정상 재포장은 통과한다 — 위 거부들이 재포장 탓이 아님을 세운다")
    fun `대조군은 통과한다`() {
        val original = IngestFixtures.bytes("sample.hwpx")

        assertThat(extractor.extract(IngestFixtures.repackaged(original)))
            .isEqualTo(IngestFixtures.expectedText(IngestFixtures.repoOracle, "sample.hwpx"))
    }

    /** `Contents/section0.xml` 만 갈아 끼운 hwpx. 나머지 항목은 그대로 둔다. */
    private fun hwpxWithSection(section: ByteArray): ByteArray =
        IngestFixtures.withEntryReplaced(IngestFixtures.bytes("sample.hwpx"), "Contents/section0.xml", section)

    /**
     * 문단 **하나**가 상한을 넘고, 그 뒤에 **닫히지 않은 태그**가 오는 구역 XML.
     *
     * 꼬리를 닫지 않는 것이 이 fixture 의 핵심이다 — 파서가 거기까지 읽으면 실패 문구가
     * 달라지므로, 「어디서 멈췄는가」가 관측 가능해진다.
     */
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
    }
}
