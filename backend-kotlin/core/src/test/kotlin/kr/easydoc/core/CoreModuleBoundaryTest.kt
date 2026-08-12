package kr.easydoc.core

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * `core` 가 Spring·DB·Jackson 을 모른다는 것을 실행으로 확인한다.
 *
 * 계획 §3.2가 "core 는 Spring 과 DB 의존성 없이 테스트 가능하게 둔다"고 지정했다.
 * 이 조건이 문서로만 있으면 어느 날 누군가 `implementation(libs.spring.boot.starter)`
 * 한 줄을 넣는 순간 조용히 깨지고, Phase 2의 "외부 API·DB 없이 실행하는 parity suite"
 * 종료 조건이 성립하지 않게 된다 — 불일치가 났을 때 도메인 로직 문제인지 배선 문제인지
 * 가릴 수 없다.
 *
 * **이 테스트가 증명하는 것과 못 하는 것**: 증명하는 것은 core 의 테스트 런타임
 * 클래스패스에 해당 클래스가 없다는 사실뿐이다. 빌드 스크립트가 의존성을 `implementation`
 * 이 아니라 `api` 로 바꾸는 식의 변경은 여기서 잡히지 않는다. 그 층은 모듈 빌드 스크립트
 * 리뷰가 맡는다.
 */
class CoreModuleBoundaryTest {
    @ParameterizedTest(name = "{0}")
    @ValueSource(
        strings = [
            "org.springframework.context.ApplicationContext",
            "org.springframework.boot.SpringApplication",
            "org.springframework.jdbc.core.simple.JdbcClient",
            "org.flywaydb.core.Flyway",
            "org.postgresql.Driver",
            // Jackson 은 2.x(com.fasterxml)와 3.x(tools.jackson) 패키지가 다르다. 둘 다 막는다.
            "com.fasterxml.jackson.databind.ObjectMapper",
            "tools.jackson.databind.ObjectMapper",
        ],
    )
    @DisplayName("core 클래스패스에 Spring·DB·Jackson 이 없다")
    fun `core 클래스패스에 프레임워크 의존성이 없다`(className: String) {
        val loaded = runCatching { Class.forName(className) }
        assertThat(loaded.isFailure)
            .withFailMessage(
                "core 클래스패스에서 %s 를 찾았다. core 는 Spring·DB·Jackson 을 몰라야 한다(계획 §3.2). " +
                    "core/build.gradle.kts 에 들어온 의존성을 되돌리고, 필요한 코드는 " +
                    "application 또는 infrastructure 로 옮겨라.",
                className,
            ).isTrue()
    }
}
