package kr.easydoc.infrastructure.auth

import kr.easydoc.application.auth.AuthService
import kr.easydoc.application.auth.PasswordHasher
import kr.easydoc.core.exceptions.InvalidCredentialsException
import kr.easydoc.core.security.Secret
import kr.easydoc.core.user.PasswordHash
import kr.easydoc.infrastructure.DatabaseHandle
import kr.easydoc.infrastructure.PostgresTestSupport
import kr.easydoc.infrastructure.db.SpringTransactionRunner
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID
import javax.sql.DataSource

/**
 * **401 로 끝나는 갈래들이 같은 양의 일을 한다** — 계약 `x-auth.failure_uniformity` 의 시간
 * 문장을 구조로 강제한다 (게이트 23: codex C-2 · privacy-gate 기록 ①).
 *
 * ## 무엇이 문제였나
 *
 * 바이트 축은 X-1 이 닫았다 — 다섯 경로가 같은 401·같은 문구·같은 헤더를 낸다. 남은 것이
 * 시간 축이었다. 서명이 깨진 토큰은 `AccessTokens.verify` 에서 끊겨 DB 를 아예 타지 않고,
 * 서명이 유효한 삭제 계정 토큰만 `exists` 왕복을 돌았다. privacy-gate 실측(표본 각 101,
 * 교차 순서, 워밍업 20라운드) p50: 삭제 계정 **1.067ms** / 위조 0.539 / 만료 0.547 —
 * **비 1.95~1.98**. 즉 반복 측정으로 「이 토큰이 한때 유효하게 서명됐는가」를 가를 수 있었다.
 *
 * ## 왜 시간이 아니라 구조를 재는가
 *
 * 같은 배치가 시간 축 게이트의 한계를 실측으로 확인했다 — 소유 조건을 SQL 에서 빼낸 변이가
 * 응답 시간 비 1.013~1.090 으로 문턱 1.5 를 전혀 건드리지 않았다(X-3ⓒ). 밀리초 응답에서
 * 왕복 하나의 차이는 잡음에 묻히거나 CI 부하에 따라 흔들린다. 흔들리는 게이트는 곧 꺼진다.
 *
 * 그 격차가 **실제로 무엇인가**는 잡음이 없는 정수다 — **DB 왕복 수**다. 그래서 여기서는
 * `AuthService.authenticate` 호출 하나가 내는 SQL 문 수를 세고, 네 실패 갈래와 성공 갈래가
 * **전부 같은 수**임을 단언한다. 균일화를 걷어내면(실패 갈래의 더미 조회 제거) 이 정수가
 * 즉시 갈린다.
 *
 * ## 대상이 아닌 것 — 헤더가 없는 요청
 *
 * `Authorization` 헤더가 없으면 `AuthenticationInterceptor` 가 여기 닿기 전에 끊고, 계약이
 * **다른 문구**(`no_header`)를 주므로 바이트 축에서 이미 구분된다. 시간을 맞춰도 얻을 것이
 * 없고 맞추려면 인증 없는 트래픽 전부에 DB 부하를 얹게 된다. 계약이 한 줄에 묶은 것은
 * 토큰이 든 세 갈래(만료·위조·계정 삭제)이고 이 테스트의 범위도 그것이다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuthenticationWorkUniformityTest {
    private lateinit var database: DatabaseHandle
    private lateinit var jdbcClient: JdbcClient
    private lateinit var users: JdbcUserRepository
    private lateinit var counting: CountingDataSource
    private lateinit var countedAuth: AuthService
    private lateinit var tokens: JwtAccessTokens

    @BeforeAll
    fun prepare() {
        database = PostgresTestSupport.createEmptyDatabase("auth_work_uniformity")
        Flyway
            .configure()
            .dataSource(database.jdbcUrl, database.username, database.password)
            .locations("classpath:db/migration")
            .load()
            .migrate()

        jdbcClient = JdbcClient.create(dataSource())
        users = JdbcUserRepository(jdbcClient)
        tokens = JwtAccessTokens(Secret(SIGNING_KEY), LIFETIME, MIN_SECRET_BYTES, Clock.systemUTC())

        counting = CountingDataSource(dataSource())
        val countingJdbc = JdbcClient.create(counting)
        countedAuth =
            AuthService(
                users = JdbcUserRepository(countingJdbc),
                workspaces = JdbcWorkspaceRepository(countingJdbc),
                passwords = UnusedPasswordHasher,
                accessTokens = tokens,
                transaction = SpringTransactionRunner(TransactionTemplate(DataSourceTransactionManager(counting))),
            )
    }

    @Test
    @DisplayName("토큰이 든 401 갈래와 성공 갈래가 같은 수의 SQL 문을 낸다 — failure_uniformity 의 시간 문장")
    fun `인증 경계의 일하는 양이 실패 사유로 갈리지 않는다`() {
        val live = newUser()
        val deleted = newUser()
        deleteUserRow(deleted)

        val liveToken = tokens.issue(live).token
        val counts =
            linkedMapOf(
                "유효 토큰(성공)" to countAuthenticate(liveToken),
                "삭제 계정" to countAuthenticate(tokens.issue(deleted).token),
                "위조 서명" to countAuthenticate(withBrokenSignature(liveToken)),
                "만료 토큰" to countAuthenticate(expiredToken(live)),
                "JWT 형식 아님" to countAuthenticate("이건 토큰이 아니다"),
            )

        assertThat(counts.values)
            .withFailMessage(
                "인증 경계가 도는 SQL 문 수가 갈리거나 %d 가 아니다 — %s. " +
                    "실패 사유가 「일한 양」으로 새면 반복 측정으로 계정 삭제 여부를 가릴 수 있다 " +
                    "(계약 x-auth.failure_uniformity).",
                AUTHENTICATE_STATEMENTS,
                counts,
            ).containsOnly(AUTHENTICATE_STATEMENTS)
    }

    @Test
    @DisplayName("균일화가 결과를 바꾸지 않는다 — 실패 갈래는 여전히 InvalidCredentials 다")
    fun `더미 조회가 실패를 성공으로 만들지 않는다`() {
        // 비용을 맞추는 조회의 **결과를 쓰지 않는다**는 성질. 쓰기 시작하면 nil UUID 행이
        // 우연히 생기는 날 위조 토큰이 통과한다.
        val live = newUser()
        val liveToken = tokens.issue(live).token

        listOf(withBrokenSignature(liveToken), expiredToken(live), "이건 토큰이 아니다").forEach { token ->
            assertThat(runCatching { countedAuth.authenticate(token) }.exceptionOrNull())
                .withFailMessage("실패해야 할 토큰이 통과했거나 다른 예외로 끝났다")
                .isInstanceOf(InvalidCredentialsException::class.java)
        }
        assertThat(countedAuth.authenticate(liveToken)).isEqualTo(live)
    }

    // ---------------------------------------------------------------- 픽스처

    private fun countAuthenticate(token: String): Int =
        counting.countStatements { runCatching { countedAuth.authenticate(token) } }

    private fun newUser(): UUID = users.create(uniqueEmail(), FIXTURE_HASH).id

    private fun uniqueEmail(): String = "auth-uniformity${counter++}@example.test"

    private fun deleteUserRow(userId: UUID) {
        jdbcClient.sql("DELETE FROM users WHERE id = :id").param("id", userId).update()
    }

    /**
     * 서명 **첫 바이트의 1비트**만 뒤집는다. 헤더·페이로드가 그대로라 갈리는 것이 서명 검증
     * 하나다 — 다른 것을 함께 바꾸면 무엇 때문에 갈렸는지 알 수 없다.
     *
     * base64url 문자열의 **마지막 글자를 바꾸는 방식은 쓰면 안 된다.** HS256 서명 32바이트는
     * 43글자로 인코딩되는데 마지막 글자의 하위 2비트는 아무 바이트에도 실리지 않는다 —
     * 그 비트만 다른 글자로 바꾸면 디코딩 결과가 **같고 서명은 여전히 유효하다.** 실제로
     * 첫 판이 그렇게 짜여 있었고, 「위조 토큰」이 위조가 아닌 채로 통과했다.
     */
    private fun withBrokenSignature(token: String): String {
        val parts = token.split('.')
        require(parts.size == 3) { "JWT 가 세 조각이 아니다" }
        val flipped =
            Base64
                .getUrlDecoder()
                .decode(parts[2])
                .also { it[0] = (it[0].toInt() xor 0x01).toByte() }
        return "${parts[0]}.${parts[1]}.${Base64.getUrlEncoder().withoutPadding().encodeToString(flipped)}"
    }

    private fun expiredToken(userId: UUID): String {
        val past = Instant.now().minus(LIFETIME).minusSeconds(60)
        return JwtAccessTokens(Secret(SIGNING_KEY), LIFETIME, MIN_SECRET_BYTES, Clock.fixed(past, ZoneOffset.UTC))
            .issue(userId)
            .token
    }

    private fun dataSource(): DataSource =
        DriverManagerDataSource(database.jdbcUrl, database.username, database.password)

    /** 이 테스트는 비밀번호를 쓰지 않는다. 불리면 **시끄럽게** 깨져야 배선 실수가 드러난다. */
    private object UnusedPasswordHasher : PasswordHasher {
        override fun hash(rawPassword: String): PasswordHash = error("이 테스트는 해시를 쓰지 않는다")

        override fun verify(
            rawPassword: String,
            stored: PasswordHash,
        ): Boolean = error("이 테스트는 해시를 쓰지 않는다")

        override fun needsRehash(stored: PasswordHash): Boolean = error("이 테스트는 해시를 쓰지 않는다")

        override fun dummyHash(): PasswordHash = error("이 테스트는 해시를 쓰지 않는다")
    }

    private companion object {
        var counter = 0

        /** 형태만 맞으면 되는 더미 PHC — 이 파일은 비밀번호 검증을 재지 않는다. */
        val FIXTURE_HASH = PasswordHash("\$argon2id\$v=19\$m=1,t=1,p=1\$c2FsdA\$aGFzaA")

        const val SIGNING_KEY = "test-only-signing-key-0123456789-abcdef"
        const val MIN_SECRET_BYTES = 32
        val LIFETIME: Duration = Duration.ofMinutes(60)

        /**
         * 인증 경계 한 번이 도는 SQL 문 수.
         *
         * 성공 갈래는 `exists` 하나, 실패 갈래는 비용을 맞추는 더미 `exists` 하나다.
         * 이 수를 못박는 이유는 「셋이 같다」만으로는 **양쪽이 함께 늘어난 구현**도 통과하기
         * 때문이다 — 그러면 보호된 요청마다 질의가 조용히 는다.
         */
        const val AUTHENTICATE_STATEMENTS = 1
    }
}
