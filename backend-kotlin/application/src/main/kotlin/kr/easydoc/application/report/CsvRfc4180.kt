package kr.easydoc.application.report

/**
 * CSV 필드 이스케이프 — 운영 리포트(`UsageReportService`)와 접속기록 점검 보고서
 * (`PersonalDataAccessReportService`)가 공유한다. 원래 두 곳(당시 `api` 계층의
 * `AccessLogReportRunner`, `application` 계층의 `UsageReportService`)에 복붙돼 있던 것을
 * 한 곳으로 모은 것이다 — 보안 관련 이스케이프가 두 곳에 있으면 한쪽만 고쳐질 수 있다.
 *
 * CSV 인젝션 방어(RFC 4180 quoting과 별개 축) + RFC 4180 quoting 순으로 적용한다.
 * 워크스페이스 이름·소유자 이메일처럼 사용자가 정한 문자열이 `=`·`+`·`-`·`@`로
 * 시작하면 엑셀·시트가 그 필드를 **수식**으로 해석해 실행할 수 있다(CSV/수식
 * 인젝션). 그 자리에 작은따옴표(`'`)를 하나 앞세우면 대부분의 스프레드시트가
 * 문자열로 취급한다. 숫자 열은 전부 음이 아닌 정수/소수라 `-`로 시작할 일이 없으므로,
 * 이 이스케이프를 **모든 필드에 예외 없이** 적용해도 값이 달라지지 않는다 — 열마다
 * 분기하지 않는다.
 */
object CsvRfc4180 {
    /** RFC 4180 — 이 문자 중 하나라도 있으면 필드를 큰따옴표로 감싼다. */
    private val NEEDS_QUOTING_CHARS = charArrayOf(',', '"', '\n', '\r')

    /** 스프레드시트가 수식 시작으로 해석하는 선두 문자. */
    private val FORMULA_TRIGGER_CHARS = charArrayOf('=', '+', '-', '@', '\t')

    fun field(value: String): String {
        val escaped = escapeFormulaInjection(value)
        return if (escaped.any(NEEDS_QUOTING_CHARS::contains)) {
            "\"" + escaped.replace("\"", "\"\"") + "\""
        } else {
            escaped
        }
    }

    /** `=`·`+`·`-`·`@`·탭으로 시작하는 필드 앞에 `'`를 붙여 수식으로 해석되지 않게 한다. */
    private fun escapeFormulaInjection(value: String): String =
        if (value.isNotEmpty() && value[0] in FORMULA_TRIGGER_CHARS) "'$value" else value
}
