package kr.easydoc.application.report

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 리뷰 지적 3 고정판 — `UsageReportService`·`PersonalDataAccessReportService`가 공유하는
 * 이스케이프가 한 곳에서만 잰다는 것을 고정한다(`UsageReportServiceTest`의 이스케이프
 * 케이스와 같은 값들).
 */
class CsvRfc4180Test {
    @Test
    @DisplayName("특수 문자가 없으면 그대로다")
    fun `평범한 값은 바뀌지 않는다`() {
        assertThat(CsvRfc4180.field("hello")).isEqualTo("hello")
    }

    @Test
    @DisplayName("콤마·큰따옴표·개행이 있으면 큰따옴표로 감싼다 — RFC 4180")
    fun `특수 문자는 인용된다`() {
        assertThat(CsvRfc4180.field("공간, \"특별\"")).isEqualTo("\"공간, \"\"특별\"\"\"")
    }

    @Test
    @DisplayName("=·+·-·@·탭으로 시작하면 작은따옴표를 앞세운다 — CSV/수식 인젝션 방어")
    fun `수식으로 해석될 수 있는 값은 이스케이프된다`() {
        assertThat(CsvRfc4180.field("=1+1")).isEqualTo("'=1+1")
        assertThat(CsvRfc4180.field("@x")).isEqualTo("'@x")
        assertThat(CsvRfc4180.field("+1")).isEqualTo("'+1")
        assertThat(CsvRfc4180.field("-1")).isEqualTo("'-1")
        assertThat(CsvRfc4180.field("\tx")).isEqualTo("'\tx")
    }

    @Test
    @DisplayName("빈 문자열은 인젝션 방어 대상이 아니다")
    fun `빈 문자열은 그대로다`() {
        assertThat(CsvRfc4180.field("")).isEqualTo("")
    }
}
