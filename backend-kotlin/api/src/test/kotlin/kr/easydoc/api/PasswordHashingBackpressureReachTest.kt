package kr.easydoc.api

import kr.easydoc.api.support.ContractSpec
import kr.easydoc.infrastructure.DatabaseHandle
import kr.easydoc.infrastructure.PostgresTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CyclicBarrier

/** 해시 배압(세마포어 대기 상한 초과)의 HTTP 응답을 잰다 — 게이트 21 TST-2. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "easydoc.auth.jwt-secret=$AUTH_REACH_TEST_SECRET",

        "easydoc.auth.max-concurrent-hashes=1",
        "easydoc.auth.max-hash-wait-millis=1",

        "easydoc.auth.argon2.memory-kib=16384",
    ],
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PasswordHashingBackpressureReachTest {
    @LocalServerPort
    private var port: Int = 0

    private val json = ObjectMapper()

    @Test
    @DisplayName("해시 대기 상한 초과가 계약이 선언한 상태 코드와 고정 문구로 나가고, 계정 존재로 갈리지 않는다")
    fun `배압 응답이 계약 형태다`() {
        val known = uniqueEmail()
        assertThat(post("/auth/signup", credentials(known, PASSWORD)).statusCode())
            .withFailMessage("배압을 만들기 전 준비 요청부터 실패했다 — 이 측정의 전제가 깨졌다")
            .isEqualTo(ContractSpec.successStatus(SIGNUP_PATH, POST))

        val responses = floodLogins(known)

        val overloaded = responses.filter { it.second.statusCode() == OVERLOADED_STATUS }
        assertThat(overloaded)
            .withFailMessage(
                "대기 상한을 넘긴 요청이 하나도 없다 — 무한 대기로 되돌아갔거나 배선이 바뀌었다. 관측된 코드: %s",
                responses.map { it.second.statusCode() }.toSortedSet(),
            ).isNotEmpty()

        assertThat(responses.map { it.second.statusCode() }.toSet())
            .withFailMessage("모든 요청이 배압으로 떨어졌다 — 정상 경로가 살아 있는지 알 수 없다")
            .contains(UNAUTHORIZED)

        assertThat(ContractSpec.responseStatuses(LOGIN_PATH, POST))
            .withFailMessage("계약이 POST %s 에 %d 를 선언하지 않는다", LOGIN_PATH, OVERLOADED_STATUS)
            .contains(OVERLOADED_STATUS.toString())

        val expectedDetail = ContractSpec.responseExampleDetail("InternalError", "unexpected")
        overloaded.forEach { (label, response) ->
            val body = json.readValue(response.body(), Map::class.java)

            assertThat(body.keys.map { it.toString() }.toSet())
                .withFailMessage("%s 의 배압 본문 최상위 키가 계약과 다르다: %s", label, body.keys)
                .isEqualTo(ContractSpec.schemaRequired("ErrorResponse"))
            assertThat(body["detail"])
                .withFailMessage("%s 의 배압 문구가 계약 예시와 다르다", label)
                .isEqualTo(expectedDetail)

            assertThat(response.body())
                .withFailMessage("배압 응답이 내부 사정을 노출한다: %s", response.body())
                .doesNotContain("Overloaded", "Semaphore", "argon", "Argon")

            assertPrivateHeaders(response)
        }

        val byAccount = overloaded.groupBy({ it.first }, { it.second.body() })
        assertThat(byAccount.keys)
            .withFailMessage(
                "과부하가 한 계정 집단에만 걸렸다 — 균일성을 잴 대상이 없다. 집단별 개수: %s",
                overloaded.groupingBy { it.first }.eachCount(),
            ).containsExactlyInAnyOrder(ABSENT_LABEL, KNOWN_LABEL)
        assertThat(byAccount.values)
            .withFailMessage("한 집단의 과부하 표본이 0 건이다: %s", byAccount.mapValues { it.value.size })
            .allSatisfy { assertThat(it).isNotEmpty() }

        assertThat(byAccount.values.flatten().distinct())
            .withFailMessage("배압 응답이 계정 존재 여부로 갈린다 — 새 열거 채널이다: %s", byAccount)
            .hasSize(1)
    }

    /** 절반은 없는 이메일, 절반은 있는 이메일로 동시에 로그인한다. 반환은 (라벨, 응답). */
    private fun floodLogins(known: String): List<Pair<String, HttpResponse<String>>> {
        val start = CyclicBarrier(CONTENDERS)
        val collected = CopyOnWriteArrayList<Pair<String, HttpResponse<String>>>()
        val threads =
            (1..CONTENDERS).map { index ->
                val absent = index % 2 == 0
                val email = if (absent) uniqueEmail() else known
                val label = if (absent) ABSENT_LABEL else KNOWN_LABEL
                Thread {
                    start.await()
                    collected += label to post("/auth/login", credentials(email, WRONG_PASSWORD))
                }
            }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        return collected.toList()
    }

    private fun assertPrivateHeaders(response: HttpResponse<String>) {
        ContractSpec.globalHeaderValues().forEach { (header, value) ->
            assertThat(response.headers().allValues(header))
                .withFailMessage("배압 응답의 %s 가 %s 다", header, response.headers().allValues(header))
                .containsExactly(value)
        }
    }

    private fun credentials(
        email: String,
        password: String,
    ): String = json.writeValueAsString(mapOf("email" to email, "password" to password))

    private fun post(
        path: String,
        payload: String,
    ): HttpResponse<String> =
        HttpClient.newHttpClient().send(
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port$path"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload, Charsets.UTF_8))
                .build(),
            HttpResponse.BodyHandlers.ofString(Charsets.UTF_8),
        )

    private fun uniqueEmail(): String = "backpressure${counter++}@example.test"

    companion object {
        private const val UNAUTHORIZED = 401

        /** 배압이 나가는 코드. 계약 선언 대조는 본문에서 따로 한다. */
        private const val OVERLOADED_STATUS = 500

        private const val LOGIN_PATH = "/auth/login"
        private const val SIGNUP_PATH = "/auth/signup"
        private const val POST = "post"
        private const val PASSWORD = "correct horse battery"
        private const val WRONG_PASSWORD = "correct horse batteryX"

        /** 자리(1개)보다 충분히 많아야 대기가 생기고, 절반씩 갈라야 두 라벨이 다 나온다. */
        private const val CONTENDERS = 12

        /** 계정 집단의 라벨. 「양쪽이 다 과부하됐는가」 단언이 이 두 값을 이름으로 쓴다. */
        private const val ABSENT_LABEL = "없는 이메일"
        private const val KNOWN_LABEL = "있는 이메일"

        private var counter = 0

        val database: DatabaseHandle by lazy { PostgresTestSupport.createEmptyDatabase("auth_backpressure") }

        @JvmStatic
        @DynamicPropertySource
        fun datasourceProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { database.jdbcUrl }
            registry.add("spring.datasource.username") { database.username }
            registry.add("spring.datasource.password") { database.password }
        }
    }
}
