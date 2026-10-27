package kr.easydoc.api

import kr.easydoc.infrastructure.notification.OperatorNotificationProperties
import kr.easydoc.infrastructure.telegram.TelegramProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.io.FileSystemResource
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/** 운영 알림 채널·큐 설정이 두 실행 모듈의 yml 과 `.env.example` 과 같은지. */
class OperatorNotificationBindingTest {
    @Test
    fun `Telegram 설정과 두 실행 모듈의 환경변수 계약이 일치한다`() {
        val properties =
            bindProperties(
                "easydoc.telegram",
                TelegramProperties::class.java,
                mapOf(
                    "easydoc.telegram.enabled" to "true",
                    "easydoc.telegram.bot-token" to BINDING_SECRET_VALUE,
                    "easydoc.telegram.chat-id" to "-100999",
                    "easydoc.telegram.connect-timeout" to "3",
                    "easydoc.telegram.read-timeout" to "7",
                ),
            )
        assertThat(properties.enabled).isTrue()
        assertThat(properties.botToken.reveal()).isEqualTo(BINDING_SECRET_VALUE)
        assertThat(properties.chatId).isEqualTo("-100999")
        assertThat(properties.connectTimeout).isEqualTo(Duration.ofSeconds(3))
        assertThat(properties.readTimeout).isEqualTo(Duration.ofSeconds(7))

        val block =
            """
            telegram:
              enabled: ${'$'}{EASYDOC_TELEGRAM_ENABLED:false}
              bot-token: ${'$'}{EASYDOC_TELEGRAM_BOT_TOKEN:}
              chat-id: ${'$'}{EASYDOC_TELEGRAM_CHAT_ID:}
              connect-timeout: ${'$'}{EASYDOC_TELEGRAM_CONNECT_TIMEOUT:2}
              read-timeout: ${'$'}{EASYDOC_TELEGRAM_READ_TIMEOUT:5}
            """.trimIndent().prependIndent("  ")
        val apiPath = Path.of("src/main/resources/application.yml")
        val workerPath = Path.of("../worker/src/main/resources/application.yml")
        assertThat(Files.readString(apiPath)).contains(block)
        assertThat(Files.readString(workerPath)).contains(block)
        listOf(apiPath, workerPath).forEach { path ->
            val sources = YamlPropertySourceLoader().load(path.toString(), FileSystemResource(path))
            assertThat(sources).allSatisfy { source ->
                assertThat(source.getProperty("easydoc.telegram.base-url")).isNull()
            }
        }

        val expected =
            mapOf(
                "EASYDOC_TELEGRAM_ENABLED" to "false",
                "EASYDOC_TELEGRAM_BOT_TOKEN" to "",
                "EASYDOC_TELEGRAM_CHAT_ID" to "",
                "EASYDOC_TELEGRAM_CONNECT_TIMEOUT" to "2",
                "EASYDOC_TELEGRAM_READ_TIMEOUT" to "5",
            )
        val assignments =
            environmentAssignments(Path.of("../../.env.example"))
                .filterKeys { it.startsWith("EASYDOC_TELEGRAM_") }
        assertThat(assignments).containsExactlyInAnyOrderEntriesOf(expected)
    }

    @Test
    fun `운영 알림 큐 설정과 두 실행 모듈의 환경변수 계약이 일치한다`() {
        val properties =
            bindProperties(
                "easydoc.notification",
                OperatorNotificationProperties::class.java,
                mapOf(
                    "easydoc.notification.queue-capacity" to "7",
                    "easydoc.notification.shutdown-timeout" to "9",
                ),
            )
        assertThat(properties.queueCapacity).isEqualTo(7)
        assertThat(properties.shutdownTimeout).isEqualTo(Duration.ofSeconds(9))

        val block =
            """
            notification:
              queue-capacity: ${'$'}{EASYDOC_NOTIFICATION_QUEUE_CAPACITY:100}
              shutdown-timeout: ${'$'}{EASYDOC_NOTIFICATION_SHUTDOWN_TIMEOUT:10}
            """.trimIndent().prependIndent("  ")
        val apiPath = Path.of("src/main/resources/application.yml")
        val workerPath = Path.of("../worker/src/main/resources/application.yml")
        assertThat(Files.readString(apiPath)).contains(block)
        assertThat(Files.readString(workerPath)).contains(block)

        val expected =
            mapOf(
                "EASYDOC_NOTIFICATION_QUEUE_CAPACITY" to "100",
                "EASYDOC_NOTIFICATION_SHUTDOWN_TIMEOUT" to "10",
            )
        val assignments =
            environmentAssignments(Path.of("../../.env.example"))
                .filterKeys { it.startsWith("EASYDOC_NOTIFICATION_") }
        assertThat(assignments).containsExactlyInAnyOrderEntriesOf(expected)
    }

    private fun environmentAssignments(path: Path): Map<String, String> {
        val assignments =
            Files
                .readAllLines(path)
                .map(String::trim)
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .map { line ->
                    val separator = line.indexOf('=')
                    check(separator > 0) { "환경 예시의 할당 형식이 아닙니다: $line" }
                    line.substring(0, separator) to line.substring(separator + 1)
                }
        val duplicates =
            assignments
                .groupingBy { it.first }
                .eachCount()
                .filterValues { it > 1 }
                .keys
        check(duplicates.isEmpty()) { "환경 예시에 중복 변수가 있습니다: $duplicates" }
        return assignments.toMap()
    }
}
