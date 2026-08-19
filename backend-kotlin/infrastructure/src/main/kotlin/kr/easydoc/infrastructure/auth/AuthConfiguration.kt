package kr.easydoc.infrastructure.auth

import kr.easydoc.application.auth.AccessTokens
import kr.easydoc.application.auth.AuthService
import kr.easydoc.application.auth.PasswordHasher
import kr.easydoc.application.auth.TransactionRunner
import kr.easydoc.application.auth.UserRepository
import kr.easydoc.application.auth.WorkspaceRepository
import kr.easydoc.core.security.Secret
import kr.easydoc.infrastructure.db.SpringTransactionRunner
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Duration

// 인증 조립 — **이 모듈이 소유한다.**
//
// `LlmProviderConfiguration` 과 같은 자리이고 이유도 같다: 설정값과 구현 클래스를 함께
// 볼 수 있는 모듈이 `infrastructure` 하나뿐이다. `api`·`worker` 는
// `runtimeOnly(project(":infrastructure"))` 라 `JwtAccessTokens` 타입을 컴파일 시점에
// 보지 못하고, `application` 은 `infrastructure` 를 아예 의존하지 않는다.
//
// 그래서 설정 바인딩도 여기 있다. 종전에 `api` 의 `EasyDocProperties.AuthProperties` 에
// 있던 `easydoc.auth.*` 는 **읽는 쪽과 쓰는 쪽이 서로를 볼 수 없어 아무도 조립할 수 없는
// 설정**이었고, 그 자리를 이 파일이 넘겨받는다(YAML 키와 환경변수 이름은 그대로다).
// 같은 접두사를 두 곳에 두면 소유자가 둘이 되므로 `EasyDocProperties` 쪽에서는 지운다.

/**
 * 인증 설정. 바인딩 접두사는 `easydoc.auth`.
 *
 * ## 기동은 막지 않는다
 *
 * [jwtSecret] 이 비어 있어도 앱은 뜬다. 계약 `components/responses/ServiceUnavailable`
 * 이 *"기동 자체는 막지 않는다 — 설정이 비어도 `/health` 로 배포 상태를 진단할 수 있어야
 * 하므로, 그 설정이 필요한 요청만 503 이 된다"* 고 정했다. 검사는 [JwtAccessTokens] 가
 * **호출 시점에** 한다.
 *
 * @property minSecretBytes 계약 `x-auth.min_secret_bytes`. 설정으로 여는 이유는 하나뿐
 *   이다 — 계약이 값을 바꾸면 코드 재배포 없이 따라갈 수 있어야 한다. **낮추는 것은
 *   불변식 축소**이므로 리더 승인 사항이다.
 * @property maxConcurrentHashes 동시에 도는 Argon2 계산 수의 상한. 1건당
 *   `argon2.memoryKib` 만큼을 계산이 끝날 때까지 붙들고 있어, 상한이 없으면 로그인
 *   폭주가 그대로 OOM 이 된다(I-8 검증 5).
 * @property maxHashWaitMillis 세마포어 **대기**의 상한. 무기한 대기는 요청 스레드를
 *   영원히 반납하지 않아 과부하가 다른 API 까지 멈추게 한다.
 *
 *   **기본값의 유도**(privacy-gate R-2 · Claude KTL-2). 대기 상한 `W` 는 곧 「대기 줄의
 *   길이」다 — 해시 1회가 `H`(실측 ~100ms), 자리가 `P` 개면 줄에서 통과할 수 있는 요청
 *   수는 `W × P / H` 다. 종전 기본값 5000ms 는 `5000 × 4 / 100 = 200` 건으로,
 *   **Tomcat 기본 스레드 수(200)와 같다.** 즉 상한에 닿기 전에 스레드가 먼저 마른다 —
 *   240 동시 실측에서 `/health` 가 최대 1244ms 까지 늘어난 원인이 permit 이 아니라 이
 *   대기 상한 자체였다. 250ms 는 같은 식으로 **약 10건**이고, 그 이상은 기다리게 하지
 *   않고 즉시 배압으로 돌린다(고정 문구 500 — 계약이 선언한 코드다).
 */
@ConfigurationProperties(prefix = "easydoc.auth")
data class AuthProperties(
    val jwtSecret: Secret = Secret.EMPTY,
    val jwtExpireMinutes: Long = 60,
    val minSecretBytes: Int = 32,
    val maxConcurrentHashes: Int = 4,
    val maxHashWaitMillis: Long = 250,
    val argon2: Argon2Properties = Argon2Properties(),
)

/**
 * Argon2id 파라미터. 기본값은 계약 `x-auth.password_hash` 가 적은 조합이다.
 *
 * **설정으로 여는 이유**: I-8 이 "파라미터는 명시 고정한다(라이브러리 기본값에 기대지
 * 않는다)"를 요구하고, 하드웨어가 바뀌면 올릴 수 있어야 한다. 값을 올리면 기존 해시는
 * 로그인 성공 시 자동으로 재해시된다([Argon2PasswordHasher.needsRehash]).
 *
 * `version` 을 열어 두지 않는 이유: 라이브러리가 v1.3 으로만 해시한다. 다른 값을 적으면
 * 모든 로그인이 매번 재해시 대상이 되므로 조립 시점에 끊는다.
 */
data class Argon2Properties(
    val memoryKib: Int = 65536,
    val iterations: Int = 3,
    val parallelism: Int = 4,
    val saltLength: Int = 16,
    val hashLength: Int = 32,
)

/**
 * 인증 빈 조립.
 *
 * `AuthService` 는 `application` 의 평범한 클래스다 — Spring 애너테이션이 하나도 없다.
 * 유스케이스가 프레임워크를 모르게 두는 대신 조립을 여기서 한다.
 */
@Configuration(proxyBeanMethods = false)
class AuthConfiguration {
    @Bean
    fun passwordHasher(properties: AuthProperties): PasswordHasher =
        Argon2PasswordHasher(
            policy =
                Argon2Policy(
                    variant = "argon2id",
                    version = ARGON2_VERSION_13,
                    memoryKib = properties.argon2.memoryKib,
                    iterations = properties.argon2.iterations,
                    parallelism = properties.argon2.parallelism,
                    saltLength = properties.argon2.saltLength,
                    hashLength = properties.argon2.hashLength,
                ),
            maxConcurrentHashes = properties.maxConcurrentHashes,
            maxWaitMillis = properties.maxHashWaitMillis,
        )

    /**
     * `Clock.systemUTC()` 를 쓴다. 만료 판정에 허용 오차가 없으므로(계약
     * `x-auth.clock_skew_seconds`) **서버 시계는 NTP 동기를 전제한다** — 계약이 그 전제를
     * 명시했다. 시계를 주입 가능하게 두는 것은 테스트가 만료 경계를 재기 위해서다.
     */
    @Bean
    fun accessTokens(properties: AuthProperties): AccessTokens =
        JwtAccessTokens(
            secret = properties.jwtSecret,
            lifetime = Duration.ofMinutes(properties.jwtExpireMinutes),
            minSecretBytes = properties.minSecretBytes,
            clock = Clock.systemUTC(),
        )

    @Bean
    fun userRepository(jdbcClient: JdbcClient): UserRepository = JdbcUserRepository(jdbcClient)

    @Bean
    fun workspaceRepository(jdbcClient: JdbcClient): WorkspaceRepository = JdbcWorkspaceRepository(jdbcClient)

    @Bean
    fun transactionRunner(transactionManager: PlatformTransactionManager): TransactionRunner =
        SpringTransactionRunner(TransactionTemplate(transactionManager))

    @Bean
    fun authService(
        users: UserRepository,
        workspaces: WorkspaceRepository,
        passwordHasher: PasswordHasher,
        accessTokens: AccessTokens,
        transactionRunner: TransactionRunner,
    ): AuthService =
        AuthService(
            users = users,
            workspaces = workspaces,
            passwords = passwordHasher,
            accessTokens = accessTokens,
            transaction = transactionRunner,
        )

    private companion object {
        /** `Argon2Parameters.ARGON2_VERSION_13`. 인코더가 만드는 PHC 의 `v=` 값이다. */
        const val ARGON2_VERSION_13 = 19
    }
}
