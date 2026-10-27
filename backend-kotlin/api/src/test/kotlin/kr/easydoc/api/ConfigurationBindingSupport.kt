package kr.easydoc.api

import kr.easydoc.core.security.Secret
import org.springframework.boot.context.properties.bind.BindResult
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.ConfigurationPropertySource
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource
import org.springframework.core.convert.ConversionService
import org.springframework.core.convert.support.DefaultConversionService

// 설정 바인딩 테스트가 공유하는 조립기다. `ConfigurationPropertiesBindingTest` 가 커져
// 주제별로 나뉘면서 세 클래스가 같은 Binder 설정을 쓴다.

/** 기본값(빈 값)과 다르기만 하면 된다. 실제 키가 아니다. */
internal const val BINDING_SECRET_VALUE = "binding-test-only-value-0123456789"

internal fun <T : Any> bindProperties(
    prefix: String,
    type: Class<T>,
    values: Map<String, String>,
): T {
    val conversion = DefaultConversionService()

    conversion.addConverter(String::class.java, Secret::class.java) { Secret(it) }
    val sources: List<ConfigurationPropertySource> = listOf(MapConfigurationPropertySource(values))
    val binder = Binder(sources, null, conversion as ConversionService)
    val result: BindResult<T> = binder.bind(prefix, Bindable.of(type))
    check(result.isBound) { "$prefix 바인딩이 아무 값도 싣지 못했다" }
    return result.get()
}
