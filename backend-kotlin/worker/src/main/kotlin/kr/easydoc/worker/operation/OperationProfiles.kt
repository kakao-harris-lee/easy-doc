package kr.easydoc.worker.operation

internal const val ROTATE_KEYS_PROFILE = "rotate-keys"
internal const val USAGE_REPORT_PROFILE = "usage-report"
internal const val CREDIT_GRANT_PROFILE = "credit-grant"
internal const val INVOICE_HANDLE_PROFILE = "invoice-handle"
internal const val ADMIN_GRANT_PROFILE = "admin-grant"
internal const val ACCESS_LOG_REPORT_PROFILE = "access-log-report"

internal val OPERATION_PROFILES: Set<String> =
    setOf(
        ROTATE_KEYS_PROFILE,
        USAGE_REPORT_PROFILE,
        CREDIT_GRANT_PROFILE,
        INVOICE_HANDLE_PROFILE,
        ADMIN_GRANT_PROFILE,
        ACCESS_LOG_REPORT_PROFILE,
    )
