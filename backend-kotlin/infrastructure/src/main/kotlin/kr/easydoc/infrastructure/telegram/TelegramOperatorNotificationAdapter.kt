package kr.easydoc.infrastructure.telegram

import kr.easydoc.application.notification.OperatorNotificationPort
import org.springframework.http.MediaType
import org.springframework.web.client.RestClient
import tools.jackson.databind.json.JsonMapper

class TelegramOperatorNotificationAdapter(
    private val client: RestClient,
    private val properties: TelegramProperties,
) : OperatorNotificationPort {
    private val json = JsonMapper.builder().build()

    override fun send(message: String) {
        val response =
            client
                .post()
                .uri("/bot${properties.botToken.reveal()}/sendMessage")
                .contentType(MediaType.APPLICATION_JSON)
                .body(mapOf("chat_id" to properties.chatId, "text" to message))
                .retrieve()
                .body(String::class.java)
        val body = requireNotNull(response) { "Telegram sendMessage returned no body" }
        val ok = json.readTree(body).get("ok")
        check(ok != null && ok.isBoolean && ok.booleanValue()) { "Telegram sendMessage failed" }
    }
}
