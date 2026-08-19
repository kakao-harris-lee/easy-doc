package kr.easydoc.infrastructure.document

import org.slf4j.LoggerFactory
import java.sql.SQLException

/**
 * 문서·변환 저장소가 남기는 **유일한** 로그 — 제약 위반의 SQLSTATE.
 *
 * ## 왜 예외 메시지를 쓰지 않는가 (계획 §9.1 · `migration-safety-gate` I-4)
 *
 * PostgreSQL 은 제약 위반의 `DETAIL` 에 **실패한 행 전체**를 담는다. 문서 행이면 제목과
 * 원문 암호문이, 변환 행이면 세 암호문 열이 그 문자열에 실린다. JDBC 드라이버는 그것을
 * `SQLException.getMessage()` 에 그대로 넣고, Spring 은 `DataIntegrityViolationException`
 * 메시지에 다시 감싼다. 그래서 **예외를 그대로 로깅하는 한 줄이 곧 본문 유출**이다.
 *
 * SQLSTATE 는 다섯 글자 코드이고 데이터를 담지 않는다. 진단에 필요한 최소치이면서
 * 로그 허용목록(*"문서 ID·길이·처리 상태까지만"*, 프로젝트 `CLAUDE.md`)을 넘지 않는다.
 *
 * ## SQLSTATE 로 **갈래를 나누지 않는다**
 *
 * 코드를 읽어 23505/23503 을 구분해 다른 응답을 내지 않는다는 뜻이다. 저장 경로에서 터질
 * 수 있는 제약은 전부 코드·스키마 버그(소유권은 같은 트랜잭션에서 이미 확인했고, 세대
 * 번호는 조립과 도메인 타입이 이미 걸렀다)라 사용자에게 다르게 안내할 것이 없다.
 * 값은 **로그에만** 남는다.
 */
internal object DocumentStorageLog {
    private val logger = LoggerFactory.getLogger(DocumentStorageLog::class.java)

    /** 코드가 없거나 읽을 수 없을 때. 값 자리를 비워 두면 "안 찍혔다"와 구분되지 않는다. */
    private const val UNKNOWN_SQLSTATE = "unknown"

    /** 원인 사슬을 훑을 때의 상한. 순환 참조가 있어도 멈춘다. */
    private const val MAX_CAUSE_DEPTH = 10

    /**
     * 제약 위반 한 건을 남긴다. **테이블 이름과 SQLSTATE 뿐이다.**
     *
     * @param table 어느 테이블에서 났는가. 상수 문자열이며 사용자 입력이 아니다.
     */
    fun constraintViolation(
        table: String,
        failure: Throwable,
    ) {
        logger.warn("저장 제약 위반: table={} sqlstate={}", table, sqlStateOf(failure))
    }

    /**
     * 저장된 값이 우리가 쓴 형식이 아니다.
     *
     * @param where `테이블.컬럼`. 상수 문자열이다.
     * @param reason **고정 토큰**이어야 한다 — 예외 클래스 이름이나 `not-a-string-array`
     *   같은 판정명. 라이브러리 예외 **메시지**를 넣지 마라: 파싱 실패 메시지에는 저장된
     *   값 조각이 위치와 함께 실린다(`AnthropicProvider.readTree` 가 같은 이유로 메시지를
     *   버리고 타입 이름만 남긴다).
     */
    fun malformedStoredValue(
        where: String,
        reason: String,
    ) {
        logger.warn("저장된 값 형식 오류: at={} reason={}", where, reason)
    }

    private fun sqlStateOf(failure: Throwable): String {
        var cause: Throwable? = failure
        var depth = 0
        while (cause != null && depth < MAX_CAUSE_DEPTH) {
            val state = (cause as? SQLException)?.sqlState
            if (state != null) return state
            cause = cause.cause
            depth++
        }
        return UNKNOWN_SQLSTATE
    }
}
