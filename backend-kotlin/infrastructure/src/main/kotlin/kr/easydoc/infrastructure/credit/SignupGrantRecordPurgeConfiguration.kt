package kr.easydoc.infrastructure.credit

import kr.easydoc.application.auth.TransactionRunner
import kr.easydoc.application.credit.LoggingSignupGrantRecordPurgeObserver
import kr.easydoc.application.credit.PurgeSignupGrantRecords
import kr.easydoc.application.credit.SignupGrantRecordPurge
import kr.easydoc.application.credit.SignupGrantRecordPurgeObserver
import kr.easydoc.application.credit.SignupGrantRecordPurgePolicy
import kr.easydoc.core.exceptions.ConfigurationException
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Clock
import java.time.Period
import java.time.format.DateTimeParseException

/**
 * 무료 체험 중복 방지 원장 — 가입 크레딧용 `signup_grant_records`(V20)와 휴대폰 인증
 * 체험용 `phone_trial_grant_records`(V26) — 파기 설정(로드맵 5-1c, 개인정보처리방침
 * §3.1 「무료 체험 중복 방지 해시 — 부여 시점부터 2년」이 두 원장을 모두 가리킨다).
 * 두 원장은 같은 TTL 정책·같은 스케줄 슬롯을 공유한다. worker 만 지운다 —
 * `UnverifiedAccountPurgeConfiguration`(`infrastructure.auth`)과 같은 판단
 * (`CreditAccountConfiguration` 은 api·worker 양쪽이 쓰는 가입 부여 조립이라 건드리지
 * 않는다 — 파기는 그 조립과 변경 이유가 다른, worker 전용 관심사다).
 */
@Configuration(proxyBeanMethods = false)
@Profile("worker")
class SignupGrantRecordPurgeConfiguration {
    @Bean
    fun signupGrantRecordPurge(jdbcClient: JdbcClient): SignupGrantRecordPurge = JdbcSignupGrantRecordPurge(jdbcClient)

    @Bean
    fun signupGrantRecordPurgeObserver(): SignupGrantRecordPurgeObserver = LoggingSignupGrantRecordPurgeObserver()

    @Bean
    fun signupGrantRecordPurgePolicy(properties: CreditsProperties): SignupGrantRecordPurgePolicy =
        SignupGrantRecordPurgePolicy(
            enabled = properties.signupGrantRecordPurgeEnabled,
            ttl = parseTtl(properties),
            batchSize = properties.signupGrantRecordPurgeBatchSize,
        )

    /** `Clock.systemUTC()` — `UnverifiedAccountPurgeConfiguration.purgeUnverifiedAccounts` 와 같은 판단. */
    @Bean
    fun purgeSignupGrantRecords(
        store: SignupGrantRecordPurge,
        transactionRunner: TransactionRunner,
        observer: SignupGrantRecordPurgeObserver,
        policy: SignupGrantRecordPurgePolicy,
    ): PurgeSignupGrantRecords =
        PurgeSignupGrantRecords(
            store = store,
            transaction = transactionRunner,
            observer = observer,
            policy = policy,
            clock = Clock.systemUTC(),
        )

    /**
     * `easydoc.credits.signup-grant-record-ttl`(기본 `P2Y`)를 [Period] 로 읽는다 —
     * `CreditAccountConfiguration.parseSignupGrantValidity` 와 같은 판단(형식이 잘못되면
     * 프레임워크 예외 대신 도메인 예외로 옮겨 앱을 띄우지 않는다).
     */
    private fun parseTtl(properties: CreditsProperties): Period =
        try {
            Period.parse(properties.signupGrantRecordTtl)
        } catch (failure: DateTimeParseException) {
            throw invalidTtl(properties, failure)
        }

    private fun invalidTtl(
        properties: CreditsProperties,
        failure: DateTimeParseException,
    ): ConfigurationException =
        ConfigurationException(
            "easydoc.credits.signup-grant-record-ttl(가입 크레딧 원장 보유기간) 값이 " +
                "ISO-8601 Period 형식이 아니다(예: P2Y): ${properties.signupGrantRecordTtl} " +
                "(${failure.message})",
        )
}
