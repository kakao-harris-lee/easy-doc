package kr.easydoc.infrastructure.mail

import kr.easydoc.application.mail.NotificationMailFactory
import org.springframework.core.io.DefaultResourceLoader

fun defaultNotificationMailFactory(): NotificationMailFactory =
    NotificationMailConfiguration().notificationMailFactory(MailTemplateProperties(), DefaultResourceLoader())
