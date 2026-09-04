package kr.easydoc.infrastructure.document

import kr.easydoc.application.crypto.ContentCipher
import kr.easydoc.application.document.EnvelopeRotation
import kr.easydoc.application.document.KeyRotationBatch
import kr.easydoc.application.document.KeyRotationObserver
import kr.easydoc.application.document.KeyRotationPolicy
import kr.easydoc.application.document.LoggingKeyRotationObserver
import kr.easydoc.application.document.SealedStores
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile

/**
 * `rotate-keys` profile 이름. 낡은 세대로 봉인된 행을 현재 쓰기 세대로 재봉인하고 종료하는
 * 실행 모드다(backlog §1.1 「키 회전에 운영 진입점이 없음」).
 *
 * `MIGRATE_PROFILE`(`CryptoConfiguration.kt`)과 같은 이름을 `api` 쪽에도 따로 든다 — 이유도
 * 같다: `api` 는 `infrastructure` 를 `runtimeOnly` 로만 의존해 이 모듈의 상수를 컴파일
 * 시점에 보지 못한다(`ApiApplication.kt` 의 `ROTATE_KEYS_PROFILE`).
 *
 * `migrate` 와 달리 이 profile 은 `CryptoConfiguration`·`DocumentConfiguration` 을 **면제하지
 * 않는다**(둘 다 `@Profile("!$MIGRATE_PROFILE")` 이라 `rotate-keys` 에서도 조립된다) — 회전은
 * 본문 암호화 키 전체 세대를 쥐어야 하고, [EnvelopeRotation] 빈이 그대로 필요하기 때문이다.
 */
const val ROTATE_KEYS_PROFILE: String = "rotate-keys"

/**
 * 키 회전 배치 크기. 바인딩 접두사는 `easydoc.encryption.rotation`.
 *
 * 기본값 200 은 `easydoc.retention.batch-size`(기본 100, `RetentionProperties`)보다 크게
 * 잡았다 — 회전 대상 대부분(`documents`·`conversions`·`conversion_feedback`)은 4천~2만자
 * 텍스트라 한 트랜잭션에 200건을 묶어도 가볍지만, `document_originals` 는 파일 최대 10MB라
 * 운영자가 그 가족만 낮춰 재실행할 수 있게 **가족 공통 값 하나**로 둔다(가족별 값을 따로
 * 두면 손잡이가 넷으로 늘어 운영 복잡도가 배치 크기 튜닝의 값어치를 넘는다).
 */
@ConfigurationProperties(prefix = "easydoc.encryption.rotation")
data class KeyRotationProperties(val batchSize: Int = DEFAULT_BATCH_SIZE) {
    companion object {
        const val DEFAULT_BATCH_SIZE: Int = 200
    }
}

/**
 * `rotate-keys` profile 전용 조립. [KeyRotationBatch] 는 이미 있는 [EnvelopeRotation]·
 * [SealedStores]·[ContentCipher] 빈을 그대로 받는다 — 회전 로직을 여기서 새로 만들지 않는다.
 */
@Configuration(proxyBeanMethods = false)
@Profile(ROTATE_KEYS_PROFILE)
class KeyRotationConfiguration {
    @Bean
    fun keyRotationObserver(): KeyRotationObserver = LoggingKeyRotationObserver()

    @Bean
    fun keyRotationPolicy(properties: KeyRotationProperties): KeyRotationPolicy =
        KeyRotationPolicy(properties.batchSize)

    @Bean
    fun keyRotationBatch(
        stores: SealedStores,
        rotation: EnvelopeRotation,
        cipher: ContentCipher,
        policy: KeyRotationPolicy,
        observer: KeyRotationObserver,
    ): KeyRotationBatch =
        KeyRotationBatch(
            stores = stores,
            rotation = rotation,
            cipher = cipher,
            policy = policy,
            observer = observer,
        )
}
