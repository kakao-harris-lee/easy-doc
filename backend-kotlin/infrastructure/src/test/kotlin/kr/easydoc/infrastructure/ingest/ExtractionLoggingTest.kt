package kr.easydoc.infrastructure.ingest

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import kr.easydoc.core.exceptions.DocumentExtractionException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * 추출 실패 로그 규약 (계획 §5 D-16·D-17 · 프로젝트 `CLAUDE.md` 보안 규칙 · I-4).
 *
 * 남기는 것은 **형식명 · 바이트 길이 · 사유 코드** 뿐이다. 막으려는 것 셋:
 *
 * - **파일 이름** — 그 자체가 개인정보일 수 있다(`홍길동_주민등록등본.pdf`).
 * - **문서 본문** — 개인정보 포함 여부와 무관하게 금지.
 * - **라이브러리 예외 메시지** — 임시 경로·원문 조각이 섞이고 로케일에 따라 번역된다.
 *   그래서 사유는 언제나 우리가 정한 코드이거나 예외 **타입 이름**이다.
 *
 * ## 「0건」이 캡처가 비어서 참이 되지 않게 한다
 *
 * 각 케이스가 **실패 로그가 실제로 찍혔는지 먼저 확인**하고 그다음에 부재를 단언한다.
 */
class ExtractionLoggingTest {
    private val extractors = DocumentExtractors()

    @Test
    @DisplayName("손상 파일 로그에 파일 이름·본문·라이브러리 메시지가 없다")
    fun `손상 파일 로그가 최소한만 남긴다`() {
        val secretName = "홍길동_주민등록등본.docx"
        val body = "주민등록번호 900101-1234567 이 담긴 본문"
        val broken = "이것은 zip 이 아니다: $body".toByteArray()

        val events = capture { runCatching { extractors.extract(secretName, broken) } }

        val rendered = render(events)
        assertThat(rendered)
            .withFailMessage("추출 실패 로그가 한 줄도 찍히지 않았다 — 이 케이스는 아무것도 재지 않는다.")
            .contains("문서 추출 실패")
        assertThat(rendered).contains("format=docx")
        assertThat(rendered).contains("bytes=${broken.size}")
        assertThat(rendered)
            .withFailMessage("로그에 파일 이름이 실렸다 — 파일 이름은 개인정보일 수 있다.")
            .doesNotContain("홍길동")
        assertThat(rendered)
            .withFailMessage("로그에 문서 본문이 실렸다.")
            .doesNotContain("900101")
    }

    @Test
    @DisplayName("사유는 우리가 정한 코드이거나 예외 **타입 이름**이다")
    fun `사유가 예외 타입 이름이다`() {
        val events = capture { runCatching { extractors.extract("안내문.docx", "zip 이 아니다".toByteArray()) } }

        val message = render(events)
        assertThat(message).contains("reason=")
        // 라이브러리 예외 **메시지**가 아니라 타입 이름만 나가야 한다. 메시지에는 임시 경로가
        // 섞이고 로케일에 따라 번역된다(spike S-5 실측).
        assertThat(message).doesNotContain("Exception:")
    }

    @Test
    @DisplayName("사유 코드가 갈래마다 다르다 — 집계할 수 있어야 한다")
    fun `사유 코드가 갈래마다 다르다`() {
        val encrypted =
            capture {
                runCatching {
                    extractors.extract("안내문.docx", ole2With("EncryptedPackage"))
                }
            }
        val legacy = capture { runCatching { extractors.extract("안내문.docx", ole2With("WordDocument")) } }
        val scanned = capture { runCatching { extractors.extract("안내문.pdf", IngestFixtures.bytes("empty.pdf")) } }

        assertThat(render(encrypted)).contains("reason=encrypted_container")
        assertThat(render(legacy)).contains("reason=legacy_ole2_document")
        assertThat(render(scanned)).contains("reason=no_text_layer")
    }

    @Test
    @DisplayName("사용자에게 나가는 문구에도 파일 이름·본문이 없다")
    fun `사용자 문구가 입력을 반향하지 않는다`() {
        val thrown =
            runCatching {
                extractors.extract("홍길동_주민등록등본.hwpx", "주민등록번호 900101-1234567".toByteArray())
            }.exceptionOrNull()

        assertThat(thrown).isInstanceOf(DocumentExtractionException::class.java)
        assertThat(thrown?.message).doesNotContain("홍길동")
        assertThat(thrown?.message).doesNotContain("900101")
    }

    /** 실행 중 루트 로거에 찍힌 이벤트를 모은다. */
    private fun capture(block: () -> Unit): List<ILoggingEvent> {
        val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as ch.qos.logback.classic.Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        val previousLevel = root.level
        root.addAppender(appender)
        // 억제 때문에 0건이 되는 상태와 "찍히지 않는다"를 구분하려면 전부 받아야 한다.
        root.level = Level.TRACE
        try {
            block()
        } finally {
            root.level = previousLevel
            root.detachAppender(appender)
            appender.stop()
        }
        return appender.list.toList()
    }

    /** 메시지 · 인자 · 예외 체인까지 펼친 문자열. 어디로 새든 여기에 걸린다. */
    private fun render(events: List<ILoggingEvent>): String =
        buildString {
            events.forEach { event ->
                appendLine(event.formattedMessage)
                var throwable = event.throwableProxy
                while (throwable != null) {
                    appendLine("${throwable.className}: ${throwable.message}")
                    throwable.stackTraceElementProxyArray?.forEach { appendLine(it.steAsString) }
                    throwable = throwable.cause
                }
            }
        }

    private fun ole2With(streamName: String): ByteArray =
        byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte()) +
            ByteArray(OLE2_PADDING_BYTES) +
            streamName.toByteArray(Charsets.UTF_16LE)

    private companion object {
        const val OLE2_PADDING_BYTES = 64
    }
}
