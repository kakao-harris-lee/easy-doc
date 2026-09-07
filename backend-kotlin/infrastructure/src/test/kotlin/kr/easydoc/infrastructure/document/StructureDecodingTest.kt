package kr.easydoc.infrastructure.document

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import kr.easydoc.core.segment.SourceStructure
import kr.easydoc.core.segment.UnitKind
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * `decodeStructureOrNull` — `documents.source_unit_kinds` 를 읽는 DB 경계 전용 안전판
 * (리뷰 BLOCK 1). [SourceStructure.decode] 자신은 fail-fast 를 유지한다(`UnitKindTest`
 * 「알 수 없는 코드는 던진다」) — 이 함수는 그 예외를 **경계에서** 삼켜 구조 힌트 하나 때문에
 * 변환 작업 전체나 조회가 실패하지 않게 한다(계획 §1.2 「구조는 파생 정보이지 변환을 막을
 * 이유가 아니다」).
 */
class StructureDecodingTest {
    private val documentId: UUID = UUID.randomUUID()

    @Test
    @DisplayName("정상 인코딩은 그대로 디코딩된다")
    fun `정상 값은 디코딩된다`() {
        val encoded = SourceStructure(listOf(UnitKind.BODY, UnitKind.TABLE_CELL)).encode()

        val decoded = decodeStructureOrNull(encoded, documentId)

        assertThat(decoded?.kinds).containsExactly(UnitKind.BODY, UnitKind.TABLE_CELL)
    }

    @Test
    @DisplayName("알 수 없는 코드 문자는 예외 대신 null 을 낸다")
    fun `손상된 값은 null 이다`() {
        val decoded = decodeStructureOrNull("BTX", documentId)

        assertThat(decoded).isNull()
    }

    @Test
    @DisplayName("삼킨 예외는 WARN 으로 문서 id 와 길이만 남긴다 — 값 자체는 남기지 않는다")
    fun `WARN 로그가 값을 남기지 않는다`() {
        val corrupt = "BTX여기는와야할리없는서명입니다"

        val events = capture { decodeStructureOrNull(corrupt, documentId) }

        assertThat(events).isNotEmpty()
        val event = events.single()
        assertThat(event.level).isEqualTo(Level.WARN)
        assertThat(event.formattedMessage).contains(documentId.toString())
        assertThat(event.formattedMessage).contains(corrupt.length.toString())
        assertThat(event.formattedMessage)
            .withFailMessage("WARN 로그에 손상된 값 자체가 실렸다 — 길이만 남겨야 한다")
            .doesNotContain(corrupt)
    }

    /** 실행 중 이 파일의 로거에 찍힌 이벤트를 모은다(`ExtractionLoggingTest` 와 같은 방식). */
    private fun capture(block: () -> Unit): List<ILoggingEvent> {
        val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as ch.qos.logback.classic.Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        val previousLevel = root.level
        root.addAppender(appender)

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
}
