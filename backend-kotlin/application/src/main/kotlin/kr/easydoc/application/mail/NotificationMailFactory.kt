package kr.easydoc.application.mail

enum class NotificationType(
    val templateKey: String,
    val parameters: Set<String> = emptySet(),
) {
    EMAIL_VERIFICATION("email-verification", setOf("code", "minutes")),
    PASSWORD_RESET("password-reset", setOf("code", "minutes")),
    PASSWORD_CREATED("password-created"),
    PASSWORD_CHANGED("password-changed"),
    CONVERSION_COMPLETED("conversion-completed", setOf("title", "url")),
}

interface NotificationMailFactory {
    fun create(
        type: NotificationType,
        recipient: EmailAddress,
        parameters: Map<String, String> = emptyMap(),
    ): OutboundMail
}
