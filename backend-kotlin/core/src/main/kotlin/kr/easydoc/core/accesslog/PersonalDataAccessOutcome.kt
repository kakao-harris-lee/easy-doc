package kr.easydoc.core.accesslog

/**
 * 개인정보처리시스템 접속기록(`personal_data_access_logs`, V22) `outcome` 열 — 고시
 * (개인정보의 안전성 확보조치 기준)가 요구하는 접속기록 항목 중 하나다(계획
 * `docs/plans/2026-09-11-access-log-retention.md` §3.1).
 *
 * [REJECTED]가 없으면 권한 없는 접근 시도가 표에 남지 않아 월 1회 점검의 의미를 잃는다
 * (같은 계획 수용 기준 2 — 「거절된 접속(403)도 기록된다」).
 */
enum class PersonalDataAccessOutcome {
    SUCCESS,
    REJECTED,
    ;

    /** DB `outcome` 열의 값 — 소문자, `credit_transactions.kind`(V15)와 같은 규약. */
    val wireName: String get() = name.lowercase()
}
