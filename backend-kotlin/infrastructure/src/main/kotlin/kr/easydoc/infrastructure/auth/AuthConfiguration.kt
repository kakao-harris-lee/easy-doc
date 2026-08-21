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

/** 인증 설정. 바인딩 접두사는 `easydoc.auth`. */
@ConfigurationProperties(prefix = "easydoc.auth")
data class AuthProperties(
    val jwtSecret: Secret = Secret.EMPTY,
    val jwtExpireMinutes: Long = 60,
    val minSecretBytes: Int = 32,
    val maxConcurrentHashes: Int = 4,
    val maxHashWaitMillis: Long = 250,
    val argon2: Argon2Properties = Argon2Properties(),
)

/** Argon2id 파라미터. 기본값은 계약 `x-auth.password_hash` 가 적은 조합이다. */
data class Argon2Properties(
    val memoryKib: Int = 65536,
    val iterations: Int = 3,
    val parallelism: Int = 4,
    val saltLength: Int = 16,
    val hashLength: Int = 32,
)

/** 인증 빈 조립. */
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
