package kr.easydoc.infrastructure.auth

import kr.easydoc.application.auth.LoggingUnverifiedAccountPurgeObserver
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.time.Duration
import javax.sql.DataSource

/**
 * 미검증 계정 파기 설정 조립 — `MailConfigurationTest` 와 같이 Spring 컨텍스트 없이
 * 조립 메서드와 기본값을 직접 잰다.
 */
class UnverifiedAccountPurgeConfigurationTest {
    private val configuration = UnverifiedAccountPurgeConfiguration()

    @Test
    @DisplayName("기본값은 켜짐·24시간 TTL·배치 200이다")
    fun `기본값을 확인한다`() {
        val properties = UnverifiedAccountProperties()

        assertThat(properties.enabled).isTrue()
        assertThat(properties.ttlHours).isEqualTo(24)
        assertThat(properties.batchSize).isEqualTo(200)
    }

    @Test
    @DisplayName("properties 의 시간 단위 TTL 이 Duration 으로 변환돼 정책에 실린다")
    fun `TTL 을 Duration 으로 변환한다`() {
        val policy = configuration.unverifiedAccountPurgePolicy(UnverifiedAccountProperties(ttlHours = 48))

        assertThat(policy.ttl).isEqualTo(Duration.ofHours(48))
    }

    @Test
    @DisplayName("배치 크기가 그대로 정책에 실린다")
    fun `배치 크기를 넘긴다`() {
        val policy = configuration.unverifiedAccountPurgePolicy(UnverifiedAccountProperties(batchSize = 50))

        assertThat(policy.batchSize).isEqualTo(50)
    }

    @Test
    @DisplayName("enabled=false 는 정책에도 그대로 꺼진 채 전달된다")
    fun `비활성 설정이 정책에 실린다`() {
        val policy = configuration.unverifiedAccountPurgePolicy(UnverifiedAccountProperties(enabled = false))

        assertThat(policy.enabled).isFalse()
    }

    @Test
    @DisplayName("unverifiedAccountPurge 빈은 JdbcUnverifiedAccountPurge 를 조립한다")
    fun `JdbcUnverifiedAccountPurge 를 조립한다`() {
        val store = configuration.unverifiedAccountPurge(JdbcClient.create(fakeDataSource()))

        assertThat(store).isInstanceOf(JdbcUnverifiedAccountPurge::class.java)
    }

    @Test
    @DisplayName("observer 빈은 LoggingUnverifiedAccountPurgeObserver 를 조립한다")
    fun `LoggingUnverifiedAccountPurgeObserver 를 조립한다`() {
        val observer = configuration.unverifiedAccountPurgeObserver()

        assertThat(observer).isInstanceOf(LoggingUnverifiedAccountPurgeObserver::class.java)
    }

    /** 연결하지 않는다 — `JdbcClient.create` 가 `DataSource` 만 요구할 뿐 이 테스트는 쿼리를 돌리지 않는다. */
    private fun fakeDataSource(): DataSource =
        DriverManagerDataSource("jdbc:postgresql://localhost:5432/unused", "unused", "unused")
}
