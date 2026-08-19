package kr.easydoc.core.document

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 제목 규칙과 문자 수 세기 — **Spring 도 DB 도 없이 돈다**(계획 §3.2).
 *
 * 원본: `app/services/documents.py::_resolve_title`·`_shorten_derived_title`.
 *
 * ## 여기서 재는 것 중 원본에 **없던** 축
 *
 * 코드 포인트 경계다. Python `len`·슬라이스는 코드 포인트 단위라 서로게이트 쌍을 쪼갤 수
 * 없지만 Kotlin `String.length`·`take` 는 UTF-16 코드 단위라 **쪼갤 수 있다.** 쪼개진
 * 문자열은 짝 없는 서로게이트를 갖고, 그것이 UTF-8 로 인코딩될 때 `?` 로 바뀐다 —
 * 즉 우리가 만든 손상이다(게이트 25 X1 과 같은 자리). 제목은 암호화 경로를 지나지 않아
 * `PlainBody` 검사도 받지 못하므로 여기서 막지 않으면 아무 데서도 막히지 않는다.
 */
class TitleRulesTest {
    // ============================================================ 사용자가 준 제목

    @Test
    @DisplayName("사용자가 준 제목은 앞뒤 공백만 털고 그대로 쓴다 — 짧게 줄이지 않는다")
    fun `사용자 제목은 그대로 쓴다`() {
        val given = "  2026년 상반기 복지 안내문 발송 계획 알림 문서입니다  "

        assertThat(resolveTitle(given, "본문 첫 줄")).isEqualTo(given.trim())
    }

    @Test
    @DisplayName("사용자 제목의 제어문자를 자르기 **전에** 걷어낸다 — 잘린 길이가 보이는 글자 수와 맞는다")
    fun `사용자 제목의 제어문자를 먼저 걷어낸다`() {
        // 제어문자는 **이스케이프로만** 적는다. 원시 바이트를 소스에 넣으면
        // `tests/test_raw_control_chars.py` 가 잡는다 — 이 저장소에서 반복해 재발한 자리다.
        val control = "\u0000".repeat(10)
        val body = "가".repeat(MAX_TITLE_LENGTH)

        val resolved = resolveTitle(control + body, "무관")

        assertThat(resolved).isEqualTo(body)
        assertThat(resolved).hasSize(MAX_TITLE_LENGTH)
    }

    @Test
    @DisplayName("사용자 제목이 상한을 넘으면 **거절하지 않고 자른다** (계약 x-input-limits.max_title_length)")
    fun `사용자 제목은 상한에서 잘린다`() {
        val resolved = resolveTitle("나".repeat(MAX_TITLE_LENGTH + 50), "무관")

        assertThat(charCountOf(resolved)).isEqualTo(MAX_TITLE_LENGTH)
    }

    @Test
    @DisplayName("제어문자만 적어 준 제목은 대체 제목이 된다 — 본문으로 덮지 않는다")
    fun `제어문자뿐인 제목은 대체 제목이다`() {
        assertThat(resolveTitle("\u0001\u0002\u0003", "본문 첫 줄")).isEqualTo(FALLBACK_TITLE)
    }

    // ============================================================ 본문에서 유도

    @Test
    @DisplayName("제목이 없으면 본문의 **첫 번째 내용 있는 줄**에서 유도한다")
    fun `첫 내용 줄에서 유도한다`() {
        val body = "\n   \n복지 급여 안내\n둘째 줄"

        assertThat(resolveTitle(null, body)).isEqualTo("복지 급여 안내")
    }

    @Test
    @DisplayName("빈 제목 문자열은 미지정과 같게 다룬다")
    fun `빈 제목은 미지정이다`() {
        assertThat(resolveTitle("   ", "복지 급여 안내")).isEqualTo("복지 급여 안내")
    }

    @Test
    @DisplayName("본문에 내용 있는 줄이 없으면 대체 제목이다")
    fun `빈 본문은 대체 제목이다`() {
        assertThat(resolveTitle(null, "\n \t \n")).isEqualTo(FALLBACK_TITLE)
    }

    @Test
    @DisplayName("유도한 제목이 목표 길이 이하면 말줄임표를 붙이지 않는다")
    fun `짧은 유도 제목은 그대로다`() {
        val line = "가".repeat(AUTO_TITLE_TARGET_LENGTH)

        assertThat(resolveTitle(null, line)).isEqualTo(line)
    }

    @Test
    @DisplayName("긴 유도 제목은 **어절 경계**에서 자르고 말줄임표를 붙인다 — 어절 중간이 잘리면 다른 말로 읽힌다")
    fun `유도 제목은 어절 경계에서 잘린다`() {
        // 30자 창 안의 마지막 공백에서 자른다.
        val line = "복지 급여 신청 안내문 배포 일정 변경 알림 드립니다 추가 문단"

        val resolved = resolveTitle(null, line)

        assertThat(resolved).endsWith(TITLE_ELLIPSIS)
        assertThat(resolved.removeSuffix(TITLE_ELLIPSIS)).doesNotEndWith(" ")
        assertThat(line).startsWith(resolved.removeSuffix(TITLE_ELLIPSIS))
        assertThat(charCountOf(resolved)).isLessThanOrEqualTo(AUTO_TITLE_TARGET_LENGTH + 1)
    }

    @Test
    @DisplayName("목표 길이 안에 어절 경계가 없으면 하드컷 한다 — 한 줄을 통째로 남기지 않는다")
    fun `경계가 없으면 하드컷 한다`() {
        val line = "가".repeat(AUTO_TITLE_TARGET_LENGTH + 20)

        val resolved = resolveTitle(null, line)

        assertThat(resolved).isEqualTo("가".repeat(AUTO_TITLE_TARGET_LENGTH) + TITLE_ELLIPSIS)
    }

    @Test
    @DisplayName("어절이 정확히 목표 길이에서 끝나면 그 경계를 살린다 — 한 어절을 통째로 잃지 않는다")
    fun `경계가 목표 길이에 걸리면 살린다`() {
        val head = "가".repeat(AUTO_TITLE_TARGET_LENGTH)

        val resolved = resolveTitle(null, "$head 뒤에 더 있다")

        assertThat(resolved).isEqualTo(head + TITLE_ELLIPSIS)
    }

    // ============================================================ 코드 포인트 경계

    @Test
    @DisplayName("상한에서 자를 때 서로게이트 쌍을 쪼개지 않는다 — 쪼개면 우리가 만든 손상이다")
    fun `자르기가 서로게이트 쌍을 쪼개지 않는다`() {
        // BMP 밖 문자(U+1D4D0). UTF-16 으로 두 코드 단위다.
        val astral = "𝓐"
        val given = astral.repeat(MAX_TITLE_LENGTH + 10)

        val resolved = resolveTitle(given, "무관")

        assertThat(charCountOf(resolved)).isEqualTo(MAX_TITLE_LENGTH)
        assertThat(resolved.any { it.isSurrogate() })
            .describedAs("서로게이트가 있어야 이 케이스가 무언가를 재고 있다")
            .isTrue()
        assertThat(resolved.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8))
            .describedAs("UTF-8 왕복에서 값이 바뀌면 짝 없는 서로게이트가 남은 것이다")
            .isEqualTo(resolved)
    }

    @Test
    @DisplayName("유도 제목의 하드컷도 서로게이트 쌍을 쪼개지 않는다")
    fun `유도 하드컷이 서로게이트 쌍을 쪼개지 않는다`() {
        val astral = "𝓐"
        val line = astral.repeat(AUTO_TITLE_TARGET_LENGTH + 10)

        val resolved = resolveTitle(null, line)

        assertThat(resolved).isEqualTo(astral.repeat(AUTO_TITLE_TARGET_LENGTH) + TITLE_ELLIPSIS)
        assertThat(resolved.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8)).isEqualTo(resolved)
    }

    @Test
    @DisplayName("문자 수는 **코드 포인트**로 센다 — UTF-16 코드 단위로 세면 이모지 문서가 두 배로 계산된다")
    fun `문자 수는 코드 포인트다`() {
        val astral = "𝓐"

        assertThat(charCountOf(astral)).isEqualTo(1)
        assertThat(astral.length).describedAs("전제: 이 문자는 코드 단위 둘이다").isEqualTo(2)
        assertThat(charCountOf("가나다 라마")).isEqualTo(6)
    }

    @Test
    @DisplayName("takeCodePoints 는 짧은 입력을 그대로 돌려준다")
    fun `짧은 입력은 그대로다`() {
        assertThat(takeCodePoints("가나다", 10)).isEqualTo("가나다")
        assertThat(takeCodePoints("", 10)).isEmpty()
    }
}
