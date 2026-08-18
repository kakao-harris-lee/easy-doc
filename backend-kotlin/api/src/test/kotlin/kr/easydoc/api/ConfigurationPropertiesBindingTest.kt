package kr.easydoc.api

import kr.easydoc.api.config.EasyDocProperties
import kr.easydoc.core.security.Secret
import kr.easydoc.infrastructure.auth.AuthProperties
import kr.easydoc.infrastructure.llm.LlmProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.BindResult
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.ConfigurationPropertySource
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource
import org.springframework.core.convert.ConversionService
import org.springframework.core.convert.support.DefaultConversionService

/**
 * **설정 바인딩이 실제로 값을 싣는지** — 2026-08-19 실측으로 드러난 결함의 회귀 고정판.
 *
 * ## 무엇이 있었나
 *
 * Kotlin 은 주 생성자의 모든 파라미터에 기본값이 있으면 **public 무인자 생성자를 하나 더**
 * 만든다. 그러면 non-synthetic 생성자가 둘이라 Spring 이 바인딩 생성자를 추론하지 못하고,
 * 남은 경로(Kotlin 주 생성자 조회)는 `kotlin-reflect` 를 요구하는데 실행 클래스패스에
 * 없었다. 결과는 JavaBean 바인딩이었고 setter 가 없어 기동이 깨진다.
 *
 * ## 왜 「기본값과 다른 값」이어야 하는가
 *
 * `JavaBeanBinder` 는 바인딩한 값이 **기존 값과 같으면 예외를 던지지 않는다.** 그래서
 * `easydoc.cors-origins`·`easydoc.auth.jwt-expire-minutes` 가 기본값과 같은 채로 오래
 * 통과했고 아무도 몰랐다. 이 테스트가 기본값을 그대로 넣으면 **결함이 있어도 초록**이다.
 * 그것이 이 파일에서 값을 일부러 어긋나게 고르는 이유다.
 *
 * ## 도달 범위
 *
 * 세 설정 클래스 전부를 건다. 결함은 클래스 하나의 문제가 아니라 **Kotlin data class +
 * 전 파라미터 기본값**이라는 형태 전체의 문제이므로, 한 곳만 걸면 나머지가 같은 함정에
 * 그대로 남는다.
 */
class ConfigurationPropertiesBindingTest {
    @Test
    @DisplayName("세 설정 클래스가 기본값과 **다른** 값을 실제로 바인딩한다")
    fun `설정이 기본값과 다른 값을 싣는다`() {
        val auth =
            bind(
                "easydoc.auth",
                AuthProperties::class.java,
                mapOf(
                    "easydoc.auth.jwt-secret" to SECRET_VALUE,
                    "easydoc.auth.jwt-expire-minutes" to "15",
                    "easydoc.auth.argon2.iterations" to "7",
                ),
            )
        assertThat(auth.jwtSecret.reveal()).isEqualTo(SECRET_VALUE)
        assertThat(auth.jwtExpireMinutes).isEqualTo(15)
        // 중첩 value object 도 같은 함정에 걸린다 — 함께 건다.
        assertThat(auth.argon2.iterations).isEqualTo(7)

        val llm = bind("easydoc.llm", LlmProperties::class.java, mapOf("easydoc.llm.effort" to "high"))
        assertThat(llm.effort).isEqualTo("high")

        val easyDoc =
            bind(
                "easydoc",
                EasyDocProperties::class.java,
                mapOf("easydoc.cors-origins[0]" to "https://example.test"),
            )
        assertThat(easyDoc.corsOrigins).containsExactly("https://example.test")
    }

    private fun <T : Any> bind(
        prefix: String,
        type: Class<T>,
        values: Map<String, String>,
    ): T {
        val conversion = DefaultConversionService()
        // 실행 환경의 SecretConverter 와 같은 자리. 없으면 Secret 필드에서 먼저 깨져
        // 이 테스트가 재려는 것(생성자 선택)에 닿지 못한다.
        conversion.addConverter(String::class.java, Secret::class.java) { Secret(it) }
        val sources: List<ConfigurationPropertySource> = listOf(MapConfigurationPropertySource(values))
        val binder = Binder(sources, null, conversion as ConversionService)
        val result: BindResult<T> = binder.bind(prefix, Bindable.of(type))
        check(result.isBound) { "$prefix 바인딩이 아무 값도 싣지 못했다" }
        return result.get()
    }

    private companion object {
        /** 기본값(빈 값)과 다르기만 하면 된다. 실제 키가 아니다. */
        const val SECRET_VALUE = "binding-test-only-value-0123456789"
    }
}
