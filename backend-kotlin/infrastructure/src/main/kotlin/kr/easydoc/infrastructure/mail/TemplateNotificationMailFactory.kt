package kr.easydoc.infrastructure.mail

import kr.easydoc.application.mail.EmailAddress
import kr.easydoc.application.mail.NotificationMailFactory
import kr.easydoc.application.mail.NotificationType
import kr.easydoc.application.mail.OutboundMail
import java.util.Properties

internal class TemplateNotificationMailFactory(properties: Properties) : NotificationMailFactory {
    private val templates =
        NotificationType.entries.associateWith { type ->
            val subject = properties.required("${type.templateKey}.subject")
            val body = properties.required("${type.templateKey}.body")
            require(!PLACEHOLDER.containsMatchIn(subject)) { "Subject parameters are not allowed: ${type.templateKey}" }
            val parameters = PLACEHOLDER.findAll(body).map { it.groupValues[1] }.toSet()
            require(parameters == type.parameters) { "Invalid template parameters: ${type.templateKey}" }
            MailTemplate(subject, body)
        }

    override fun create(
        type: NotificationType,
        recipient: EmailAddress,
        parameters: Map<String, String>,
    ): OutboundMail {
        require(parameters.keys == type.parameters) { "Invalid notification parameters: ${type.templateKey}" }
        val template = templates.getValue(type)
        val body = PLACEHOLDER.replace(template.body) { parameters.getValue(it.groupValues[1]) }
        return OutboundMail(recipient, template.subject, body)
    }

    private fun Properties.required(key: String): String =
        getProperty(key)?.takeIf { it.isNotBlank() } ?: error("Missing mail template: $key")

    private class MailTemplate(
        val subject: String,
        val body: String,
    ) {
        override fun toString(): String = "MailTemplate(subjectLength=${subject.length}, bodyLength=${body.length})"
    }

    private companion object {
        val PLACEHOLDER = Regex("\\{([a-zA-Z][a-zA-Z0-9]*)}")
    }
}
