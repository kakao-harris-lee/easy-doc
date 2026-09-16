package kr.easydoc.infrastructure.sms

import kr.easydoc.application.auth.PhoneVerificationSmsSender
import kr.easydoc.core.exceptions.ExternalServiceUnavailableException
import kr.easydoc.core.security.HmacSha256
import kr.easydoc.core.security.Secret
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import tools.jackson.core.JacksonException
import tools.jackson.databind.json.JsonMapper
import java.net.http.HttpClient
import java.time.Clock
import java.time.Duration

data class SensSmsSettings(
    val serviceId: String,
    val accessKey: String,
    val secretKey: Secret,
    val fromNumber: String,
    val timeout: Duration,
    /** 운영 기본값은 실제 SENS API — 테스트가 로컬 스텁 서버로 바꿔 끼운다(생성자 주입, Spring 없이). */
    val baseUrl: String = DEFAULT_BASE_URL,
) {
    override fun toString(): String =
        "SensSmsSettings(serviceId=[REDACTED], accessKey=[REDACTED], secretKey=$secretKey, " +
            "fromNumber=[REDACTED], timeout=$timeout, baseUrl=$baseUrl)"

    companion object {
        const val DEFAULT_BASE_URL = "https://sens.apigw.ntruss.com"
    }
}

/** NAVER Cloud SENS SMS v2 어댑터. 전화번호·인증번호·키는 로그에 남기지 않는다. */
class SensSmsSender(
    private val settings: SensSmsSettings,
    private val clock: Clock = Clock.systemUTC(),
) : PhoneVerificationSmsSender {
    private val log = LoggerFactory.getLogger(SensSmsSender::class.java)
    private val json = JsonMapper.builder().build()
    private val requestPath = "/sms/v2/services/${settings.serviceId}/messages"
    private val client =
        RestClient
            .builder()
            .baseUrl(settings.baseUrl)
            .requestFactory(
                JdkClientHttpRequestFactory(
                    HttpClient.newBuilder().connectTimeout(settings.timeout).build(),
                ).apply { setReadTimeout(settings.timeout) },
            ).build()

    override fun send(
        phoneNumber: String,
        code: String,
        validMinutes: Long,
    ) {
        val timestamp = clock.millis().toString()
        val body =
            mapOf(
                "type" to "SMS",
                "contentType" to "COMM",
                "countryCode" to "82",
                "from" to settings.fromNumber,
                "content" to "[Easy-Doc] 인증번호는 ${code}입니다. ${validMinutes}분 안에 입력해 주세요.",
                "messages" to listOf(mapOf("to" to phoneNumber)),
            )
        try {
            val response =
                client
                    .post()
                    .uri(requestPath)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("x-ncp-apigw-timestamp", timestamp)
                    .header("x-ncp-iam-access-key", settings.accessKey)
                    .header(
                        "x-ncp-apigw-signature-v2",
                        SensSignature.create(requestPath, timestamp, settings.accessKey, settings.secretKey),
                    ).body(body)
                    .retrieve()
                    .body(String::class.java)
            val accepted = response != null && json.readTree(response).path("statusCode").asString() == "202"
            if (!accepted) throw unavailable("unexpected_response")
        } catch (failure: ExternalServiceUnavailableException) {
            throw failure
        } catch (failure: RestClientException) {
            log.warn("SENS SMS 발송에 실패했다: 예외={}", failure::class.java.simpleName)
            throw unavailable("request_failed")
        } catch (failure: JacksonException) {
            log.warn("SENS SMS 발송에 실패했다: 예외={}", failure::class.java.simpleName)
            throw unavailable("invalid_response")
        }
    }

    private fun unavailable(reason: String): ExternalServiceUnavailableException {
        log.warn("SENS SMS 발송이 완료되지 않았다: 사유={}", reason)
        return ExternalServiceUnavailableException("인증 문자를 보내지 못했습니다. 잠시 후 다시 시도해 주세요")
    }
}

internal object SensSignature {
    fun create(
        path: String,
        timestamp: String,
        accessKey: String,
        secretKey: Secret,
    ): String = HmacSha256.base64(secretKey, "POST $path\n$timestamp\n$accessKey")
}
