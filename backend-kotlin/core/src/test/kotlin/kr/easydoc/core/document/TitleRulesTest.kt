package kr.easydoc.core.document

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 제목 규칙과 문자 수 세기 — **Spring 도 DB 도 없이 돈다**(계획 §3.2).
 *
 * ## 이 파일의 중심 축: **본문이 제목이 되지 않는다** (게이트 27 Critical ①)
 *
 * `documents.title` 은 평문 컬럼이고 업로드 경로에는 마스킹이 없다. 본문 첫 줄을 제목으로
 * 삼으면 원문 조각이 평문으로 남는다. 그래서 제목의 바탕은 **사용자가 이름으로 준 값**뿐이고,
 * 아래 케이스들이 그 성질을 각각 다른 방향에서 잰다. 저장 층의 대응 회귀는
 * `JdbcDocumentStoreTest` 의 「본문 표식이 평문 열에 남지 않는다」다 — 이쪽은 순수 규칙,
 * 저쪽은 실 DB 의 **행 전체**를 본다.
 *
 * ## 여기서 재는 또 하나의 축 — 코드 포인트 경계
 *
 * Python `len`·슬라이스는 코드 포인트 단위라 서로게이트 쌍을 쪼갤 수 없지만 Kotlin
 * `String.length`·`take` 는 UTF-16 코드 단위라 **쪼갤 수 있다.** 쪼개진 문자열은 짝 없는
 * 서로게이트를 갖고, 그것이 UTF-8 로 인코딩될 때 `?` 로 바뀐다 — 즉 우리가 만든 손상이다
 * (게이트 25 X1 과 같은 자리). 제목은 암호화 경로를 지나지 않아 `PlainBody` 검사도 받지
 * 못하므로 여기서 막지 않으면 아무 데서도 막히지 않는다.
 */
class TitleRulesTest {
    // ============================================================ 본문을 쓰지 않는다

    @Test
    @DisplayName("제목도 파일 이름도 없으면 **대체 제목**이다 — 본문에서 만들지 않는다")
    fun `근거가 없으면 대체 제목이다`() {
        assertThat(resolveTitle(null, null)).isEqualTo(FALLBACK_TITLE)
        assertThat(resolveTitle("   ", null)).isEqualTo(FALLBACK_TITLE)
    }

    @Test
    @DisplayName("제목 자리에 본문을 넘길 통로 자체가 없다 — 인자는 이름 둘뿐이다")
    fun `본문을 받을 자리가 없다`() {
        // 이 케이스가 재는 것은 **시그니처**다. 본문 유도 갈래가 되살아나려면 이 호출이
        // 컴파일되지 않게 되거나(인자 추가) 두 인자 중 하나가 본문을 받게 되어야 하고,
        // 둘 다 diff 에 드러난다. 값 단언은 위 케이스가 진다.
        val bodyLikeInput = "복지 급여 안내\n둘째 줄"

        assertThat(resolveTitle(null, null)).isEqualTo(FALLBACK_TITLE)
        // 같은 문자열을 **파일 이름 자리**에 주면 이름으로 다뤄진다(경로·확장자 규칙만 적용).
        assertThat(resolveTitle(null, bodyLikeInput)).isNotEqualTo(FALLBACK_TITLE)
    }

    // ============================================================ 사용자가 준 제목

    @Test
    @DisplayName("사용자가 준 제목은 앞뒤 공백만 털고 그대로 쓴다 — 짧게 줄이지 않는다")
    fun `사용자 제목은 그대로 쓴다`() {
        val given = "  2026년 상반기 복지 안내문 발송 계획 알림 문서입니다  "

        assertThat(resolveTitle(given, "무관.docx")).isEqualTo(given.trim())
    }

    @Test
    @DisplayName("사용자 제목의 제어문자를 자르기 **전에** 걷어낸다 — 잘린 길이가 보이는 글자 수와 맞는다")
    fun `사용자 제목의 제어문자를 먼저 걷어낸다`() {
        // 제어문자는 **이스케이프로만** 적는다. 원시 바이트를 소스에 넣으면
        // `tests/test_raw_control_chars.py` 가 잡는다 — 이 저장소에서 반복해 재발한 자리다.
        val control = "\u0000".repeat(10)
        val name = "가".repeat(MAX_TITLE_LENGTH)

        val resolved = resolveTitle(control + name, "무관.docx")

        assertThat(resolved).isEqualTo(name)
        assertThat(resolved).hasSize(MAX_TITLE_LENGTH)
    }

    @Test
    @DisplayName("사용자 제목이 상한을 넘으면 **거절하지 않고 자른다** (계약 x-input-limits.max_title_length)")
    fun `사용자 제목은 상한에서 잘린다`() {
        val resolved = resolveTitle("나".repeat(MAX_TITLE_LENGTH + 50), "무관.docx")

        assertThat(charCountOf(resolved)).isEqualTo(MAX_TITLE_LENGTH)
    }

    @Test
    @DisplayName("제어문자만 적어 준 제목은 대체 제목이다 — 파일 이름으로 덮지 않는다")
    fun `제어문자뿐인 제목은 대체 제목이다`() {
        assertThat(resolveTitle("\u0001\u0002\u0003", "안내문.docx")).isEqualTo(FALLBACK_TITLE)
    }

    @Test
    @DisplayName("빈 제목 문자열은 미지정과 같게 다룬다 — 파일 이름으로 넘어간다")
    fun `빈 제목은 미지정이다`() {
        assertThat(resolveTitle("   ", "복지급여안내.hwpx")).isEqualTo("복지급여안내")
    }

    // ============================================================ 파일 이름

    @Test
    @DisplayName("제목이 없으면 **파일 이름**에서 확장자를 떼고 쓴다")
    fun `파일 이름에서 확장자를 뗀다`() {
        assertThat(resolveTitle(null, "2026년 복지 안내문.docx")).isEqualTo("2026년 복지 안내문")
    }

    @Test
    @DisplayName("경로가 실려 와도 **마지막 조각만** 쓴다 — 로컬 디렉터리 구조를 저장하지 않는다")
    fun `경로를 떼고 마지막 조각만 쓴다`() {
        // 보낸 쪽 OS 가 구분자를 정한다 — 서버가 POSIX 라도 백슬래시가 온다.
        assertThat(resolveTitle(null, "C:\\Users\\hong\\문서\\안내문.docx")).isEqualTo("안내문")
        assertThat(resolveTitle(null, "/home/hong/문서/안내문.pdf")).isEqualTo("안내문")
    }

    @Test
    @DisplayName("점이 여럿이면 **마지막 하나만** 떼고, 맨 앞의 점은 확장자가 아니라 이름이다")
    fun `점 처리 규칙`() {
        assertThat(resolveTitle(null, "보고서.최종.docx")).isEqualTo("보고서.최종")
        assertThat(resolveTitle(null, "안내문.")).isEqualTo("안내문")
        // 맨 앞의 점은 떼지 않는다 — 그때는 확장자가 아니라 이름 전체다.
        assertThat(resolveTitle(null, ".gitignore")).isEqualTo(".gitignore")
        assertThat(resolveTitle(null, ".docx")).isEqualTo(".docx")
    }

    @Test
    @DisplayName("이름 조각이 남지 않으면 대체 제목이다 — 빈 제목을 만들지 않는다")
    fun `이름이 남지 않으면 대체 제목이다`() {
        assertThat(resolveTitle(null, "   ")).isEqualTo(FALLBACK_TITLE)
        assertThat(resolveTitle(null, "/")).isEqualTo(FALLBACK_TITLE)
        assertThat(resolveTitle(null, "폴더/")).isEqualTo(FALLBACK_TITLE)
    }

    @Test
    @DisplayName("파일 이름도 제어문자를 걷어내고 상한에서 자른다 — 적어 준 제목과 같은 규칙이다")
    fun `파일 이름도 같은 규칙으로 다듬는다`() {
        assertThat(resolveTitle(null, "안\u0000내\u0001문.docx")).isEqualTo("안내문")
        assertThat(charCountOf(resolveTitle(null, "다".repeat(MAX_TITLE_LENGTH + 50) + ".pdf")))
            .isEqualTo(MAX_TITLE_LENGTH)
    }

    // ============================================================ 코드 포인트 경계

    @Test
    @DisplayName("상한에서 자를 때 서로게이트 쌍을 쪼개지 않는다 — 쪼개면 우리가 만든 손상이다")
    fun `자르기가 서로게이트 쌍을 쪼개지 않는다`() {
        // BMP 밖 문자(U+1D4D0). UTF-16 으로 두 코드 단위다.
        val astral = "𝓐"
        val given = astral.repeat(MAX_TITLE_LENGTH + 10)

        val resolved = resolveTitle(given, "무관.docx")

        assertThat(charCountOf(resolved)).isEqualTo(MAX_TITLE_LENGTH)
        assertThat(resolved.any { it.isSurrogate() })
            .describedAs("서로게이트가 있어야 이 케이스가 무언가를 재고 있다")
            .isTrue()
        assertThat(resolved.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8))
            .describedAs("UTF-8 왕복에서 값이 바뀌면 짝 없는 서로게이트가 남은 것이다")
            .isEqualTo(resolved)
    }

    @Test
    @DisplayName("파일 이름을 자를 때도 서로게이트 쌍을 쪼개지 않는다")
    fun `파일 이름 자르기가 서로게이트 쌍을 쪼개지 않는다`() {
        val astral = "𝓐"

        val resolved = resolveTitle(null, astral.repeat(MAX_TITLE_LENGTH + 10) + ".docx")

        assertThat(charCountOf(resolved)).isEqualTo(MAX_TITLE_LENGTH)
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
