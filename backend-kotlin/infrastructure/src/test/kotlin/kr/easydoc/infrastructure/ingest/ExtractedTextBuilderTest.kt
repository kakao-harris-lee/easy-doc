package kr.easydoc.infrastructure.ingest

import kr.easydoc.core.document.SourceFormat
import kr.easydoc.core.exceptions.DocumentExtractionException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 블록 정규화와 **추출 길이 상한**(계획 §5 D-4).
 *
 * 상한을 **누적 중에** 거는 것이 원본과 다른 지점이다. 사후 검사는 이미 수백만 자가 힙에
 * 올라온 뒤라 "거절"만 하고 "소모"는 막지 못한다. 재는 대상(이어 붙인 결과의 길이)이 같아
 * **같은 입력에 같은 판정**이 나오는지도 함께 본다.
 */
class ExtractedTextBuilderTest {
    @Test
    @DisplayName("줄 단위로 좌우 공백을 털고 빈 줄을 버린 뒤 개행 하나로 잇는다")
    fun `블록을 정규화해 잇는다`() {
        val builder = ExtractedTextBuilder(SourceFormat.DOCX, UPLOAD_SIZE)

        builder.add("  첫 문단  ")
        builder.add("   ")
        builder.add("")
        builder.add("둘째 줄\n\n  셋째 줄  ")

        assertThat(builder.build()).isEqualTo("첫 문단\n둘째 줄\n셋째 줄")
    }

    @Test
    @DisplayName("경계값(상한과 정확히 같은 길이)은 통과한다")
    fun `상한과 같은 길이는 통과한다`() {
        val builder = ExtractedTextBuilder(SourceFormat.PDF, UPLOAD_SIZE)

        builder.add("가".repeat(MAX_EXTRACTED_CHARS))

        assertThat(builder.build()).hasSize(MAX_EXTRACTED_CHARS)
    }

    @Test
    @DisplayName("상한을 한 글자 넘기면 거절한다 — 자릿점이 로케일에 좌우되지 않는다")
    fun `상한을 넘기면 거절한다`() {
        val builder = ExtractedTextBuilder(SourceFormat.PDF, UPLOAD_SIZE)

        assertThatThrownBy { builder.add("가".repeat(MAX_EXTRACTED_CHARS + 1)) }
            .isInstanceOf(DocumentExtractionException::class.java)
            .hasMessage("문서가 너무 깁니다 (최대 500,000자)")
    }

    @Test
    @DisplayName("여러 블록에 걸쳐 누적된 길이도 함께 센다")
    fun `블록에 걸친 누적을 센다`() {
        val builder = ExtractedTextBuilder(SourceFormat.HWPX, UPLOAD_SIZE)
        // 두 블록 + 그 사이 개행 하나가 정확히 상한이 되게 잡는다.
        val half = (MAX_EXTRACTED_CHARS - 1) / 2

        builder.add("가".repeat(half))
        builder.add("나".repeat(half))
        assertThat(builder.build()).hasSize(half * 2 + 1)

        // 여기서 개행 + 한 글자가 더해져 상한을 넘는다.
        assertThatThrownBy { builder.add("다") }
            .isInstanceOf(DocumentExtractionException::class.java)
            .hasMessage(ExtractionMessages.EXTRACTED_TOO_LONG)
    }

    @Test
    @DisplayName("아직 블록이 아닌 **조립 중 조각**도 상한이 진다 — 붙이기 전에 끊는다")
    fun `조립 중 조각을 예산에 넣는다`() {
        val builder = ExtractedTextBuilder(SourceFormat.HWPX, UPLOAD_SIZE)

        // 확정 결과가 절반, 조립 중 조각이 절반 하고 한 글자 — 합치면 상한을 넘는다.
        builder.add("가".repeat(MAX_EXTRACTED_CHARS / 2))
        builder.ensureRoomFor(MAX_EXTRACTED_CHARS / 2)

        assertThatThrownBy { builder.ensureRoomFor(MAX_EXTRACTED_CHARS / 2 + 1) }
            .isInstanceOf(DocumentExtractionException::class.java)
            .hasMessage(ExtractionMessages.EXTRACTED_TOO_LONG)
    }

    @Test
    @DisplayName("조각 길이를 더할 때 `Int` 로 넘치지 않는다 — 넘치면 음수가 되어 검사가 통과한다")
    fun `조각 길이가 넘치지 않는다`() {
        val builder = ExtractedTextBuilder(SourceFormat.DOCX, UPLOAD_SIZE)

        builder.add("가".repeat(MAX_EXTRACTED_CHARS))

        assertThatThrownBy { builder.ensureRoomFor(Int.MAX_VALUE) }
            .isInstanceOf(DocumentExtractionException::class.java)
    }

    @Test
    @DisplayName("대조용 BlockList 도 **같은 예산**을 진다 — 예산 없는 sink 를 두지 않는다")
    fun `블록 목록도 예산을 진다`() {
        val collected = BlockList(SourceFormat.DOCX, UPLOAD_SIZE)

        collected.add("가".repeat(MAX_EXTRACTED_CHARS))
        assertThat(collected.blocks).hasSize(1)

        assertThatThrownBy { collected.add("나") }
            .isInstanceOf(DocumentExtractionException::class.java)
            .hasMessage(ExtractionMessages.EXTRACTED_TOO_LONG)
    }

    @Test
    @DisplayName("빈 입력은 빈 결과다 — 형식별 추출기가 '텍스트 없음'을 스스로 판정한다")
    fun `빈 입력은 빈 결과다`() {
        val builder = ExtractedTextBuilder(SourceFormat.PDF, UPLOAD_SIZE)

        builder.add("")
        builder.add("   \n\t  ")

        assertThat(builder.build()).isEmpty()
    }

    private companion object {
        const val UPLOAD_SIZE = 1024
    }
}
