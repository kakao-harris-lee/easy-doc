package kr.easydoc.infrastructure.mail

import kr.easydoc.application.mail.NotificationMailFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.ResourceLoader
import java.util.Properties

@ConfigurationProperties(prefix = "easydoc.mail")
data class MailTemplateProperties(val templatesLocation: String = "classpath:mail/notifications.properties")

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MailTemplateProperties::class)
class NotificationMailConfiguration {
    @Bean
    fun notificationMailFactory(
        properties: MailTemplateProperties,
        resources: ResourceLoader,
    ): NotificationMailFactory {
        val templates = Properties()
        resources
            .getResource(
                properties.templatesLocation,
            ).inputStream
            .bufferedReader(Charsets.UTF_8)
            .use(templates::load)
        return TemplateNotificationMailFactory(templates)
    }
}
