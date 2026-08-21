package kr.easydoc.infrastructure.db

import kr.easydoc.core.security.Secret
import org.springframework.boot.context.properties.ConfigurationPropertiesBinding
import org.springframework.core.convert.converter.Converter
import org.springframework.stereotype.Component

/** 설정 문자열을 [Secret] 으로 바인딩한다. */
@Component
@ConfigurationPropertiesBinding
class SecretConverter : Converter<String, Secret> {
    override fun convert(source: String): Secret = Secret(source)
}
