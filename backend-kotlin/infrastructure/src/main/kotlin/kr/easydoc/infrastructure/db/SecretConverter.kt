package kr.easydoc.infrastructure.db

import kr.easydoc.core.security.Secret
import org.springframework.boot.context.properties.ConfigurationPropertiesBinding
import org.springframework.core.convert.converter.Converter
import org.springframework.stereotype.Component

/**
 * 설정 문자열을 [Secret] 으로 바인딩한다.
 *
 * 이 변환기가 없으면 `easydoc.auth.jwt-secret` 같은 키를 [Secret] 타입 필드에 바인딩할 수
 * 없어, 결국 타입을 `String` 으로 되돌리게 된다 — 그 순간 마스킹 보장이 사라진다.
 *
 * `api` 와 `worker` 둘 다 쓰므로 두 진입점이 공유하는 `infrastructure` 에 둔다.
 * 각 진입점에 복제하면 한쪽만 고쳐지는 날이 온다.
 */
@Component
@ConfigurationPropertiesBinding
class SecretConverter : Converter<String, Secret> {
    override fun convert(source: String): Secret = Secret(source)
}
