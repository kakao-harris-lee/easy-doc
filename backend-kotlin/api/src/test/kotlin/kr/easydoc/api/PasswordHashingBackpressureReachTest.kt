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

/**
 * **해시 배압(세마포어 대기 상한 초과)의 HTTP 응답을 잰다** — 게이트 21 TST-2.
 *
 * ## 왜 필요한가
 *
 * `Argon2PasswordHasher` 가 대기 상한을 넘기면 [kr.easydoc.infrastructure.auth.PasswordHashingOverloadedException]
 * 을 던지고, 그것이 `GlobalExceptionHandler` 의 백스톱으로 떨어져 **계약이 든 500 + 고정
 * 문구**로 나간다. 그런데 그 선택을 재는 테스트가 **어느 계층에도 없었다** — 예외가
 * 던져지는지만 보는 infrastructure 단위 테스트 한 곳뿐이었고, 「클라이언트가 무엇을
 * 받는가」는 감사 1회가 봤을 뿐 빌드는 보지 않았다(실측 있음 ≠ 강제자 있음).
 *
 * 이 자리는 계약 소유자 판정이 걸려 있는 곳이기도 하다(500 유지 ↔ 503 개정, cross §4-1).
 * 어느 쪽으로 판정이 나든 **바뀌었는지를 빌드가 말해 주어야** 한다.
 *
 * ## 무엇을 재는가
 *
 * ⑴ 상태 코드가 **계약이 그 오퍼레이션에 선언한 것**인가 (상수 대조가 아니다),
 * ⑵ 본문이 계약 `InternalError` 예시의 고정 문구와 **정확히 같고** 최상위 키가 `detail` 하나인가,
 * ⑶ 계정이 있는 요청과 없는 요청의 배압 응답이 **같은 바이트**인가 (배압이 새 열거 채널이 되지 않는다),
 * ⑷ 사적 응답 헤더 2종이 배압 응답에도 붙는가,
 * ⑸ 대기 상한 값·예외 클래스 이름 같은 내부 사정이 본문에 실리지 않는가.
 *
 * ## 배선
 *
 * 자리 1개 · 대기 상한 1ms · 해시 비용은 낮춘 메모리로 수십 ms — 동시에 풀면 앞선 한 건을
 * 뺀 나머지가 반드시 상한에 걸린다. **부하를 만드는 것이 목적이 아니라 그 경로의 응답을
 * 고정하는 것이 목적**이므로, 실제 운영 파라미터로 240 동시를 만들지 않는다.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "easydoc.auth.jwt-secret=$AUTH_REACH_TEST_SECRET",
        // 자리 1개 + 대기 1ms → 동시 요청 대부분이 배압으로 떨어진다.
        "easydoc.auth.max-concurrent-hashes=1",
        "easydoc.auth.max-hash-wait-millis=1",
        // 해시 1회가 대기 상한보다 확실히 오래 걸리면서도 스위트를 느리게 하지 않는 값.
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
        // 반대쪽 — 전부 배압이면 「어떤 요청이든 500」과 구분되지 않는다.
        assertThat(responses.map { it.second.statusCode() }.toSet())
            .withFailMessage("모든 요청이 배압으로 떨어졌다 — 정상 경로가 살아 있는지 알 수 없다")
            .contains(UNAUTHORIZED)

        // ⑴ 계약이 그 코드를 선언했는가.
        assertThat(ContractSpec.responseStatuses(LOGIN_PATH, POST))
            .withFailMessage("계약이 POST %s 에 %d 를 선언하지 않는다", LOGIN_PATH, OVERLOADED_STATUS)
            .contains(OVERLOADED_STATUS.toString())

        val expectedDetail = ContractSpec.responseExampleDetail("InternalError", "unexpected")
        overloaded.forEach { (label, response) ->
            val body = json.readValue(response.body(), Map::class.java)
            // ⑵ 최상위 키가 detail 하나, 값은 계약이 든 고정 문구.
            assertThat(body.keys.map { it.toString() }.toSet())
                .withFailMessage("%s 의 배압 본문 최상위 키가 계약과 다르다: %s", label, body.keys)
                .isEqualTo(ContractSpec.schemaRequired("ErrorResponse"))
            assertThat(body["detail"])
                .withFailMessage("%s 의 배압 문구가 계약 예시와 다르다", label)
                .isEqualTo(expectedDetail)
            // ⑸ 내부 사정이 새지 않는다 — 대기 상한 값도 예외 이름도 본문에 없다.
            assertThat(response.body())
                .withFailMessage("배압 응답이 내부 사정을 노출한다: %s", response.body())
                .doesNotContain("Overloaded", "Semaphore", "argon", "Argon")
            // ⑷ 사적 응답 헤더는 배압 응답에도 붙는다.
            assertPrivateHeaders(response)
        }

        // ⑶ 계정이 있든 없든 같은 바이트다.
        //
        // **먼저 두 집단이 과부하 부분집합에 다 들어 있는지부터 본다.** 종전에는 본문
        // distinct 개수만 봤는데, 그러면 한 집단만 전부 500 이고 다른 집단은 전부 401 이어도
        // 본문이 하나뿐이라 통과한다 — 그리고 **바로 그 상태 코드 분포가 계정 존재 열거
        // 신호다.** 「균일하다」를 재려면 비교 대상이 둘 다 있어야 한다(codex #6).
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
