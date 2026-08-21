package kr.easydoc.infrastructure.document

import org.slf4j.LoggerFactory
import java.sql.SQLException

/** 문서·변환 저장소가 남기는 **유일한** 로그 — 제약 위반의 SQLSTATE. */
internal object DocumentStorageLog {
    private val logger = LoggerFactory.getLogger(DocumentStorageLog::class.java)

    /** 코드가 없거나 읽을 수 없을 때. 값 자리를 비워 두면 "안 찍혔다"와 구분되지 않는다. */
    private const val UNKNOWN_SQLSTATE = "unknown"

    /** 원인 사슬을 훑을 때의 상한. 순환 참조가 있어도 멈춘다. */
    private const val MAX_CAUSE_DEPTH = 10

    /** 제약 위반 한 건을 남긴다. **테이블 이름과 SQLSTATE 뿐이다.** */
    fun constraintViolation(
        table: String,
        failure: Throwable,
    ) {
        logger.warn("저장 제약 위반: table={} sqlstate={}", table, sqlStateOf(failure))
    }

    /** 저장된 값이 우리가 쓴 형식이 아니다. */
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
