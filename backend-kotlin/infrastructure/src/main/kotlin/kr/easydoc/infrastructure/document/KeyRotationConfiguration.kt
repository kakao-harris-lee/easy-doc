package kr.easydoc.infrastructure.document

import kr.easydoc.application.crypto.ContentCipher
import kr.easydoc.application.document.EnvelopeRotation
import kr.easydoc.application.document.KeyRotationBatch
import kr.easydoc.application.document.KeyRotationObserver
import kr.easydoc.application.document.KeyRotationPolicy
import kr.easydoc.application.document.LoggingKeyRotationObserver
import kr.easydoc.application.document.SealedStores
import kr.easydoc.infrastructure.actionguide.ActionGuideContentKeyRotation
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/** 저장 데이터를 현재 쓰기 키로 재봉인하는 운영 프로필. */
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

/** `rotate-keys` 프로필에서 키 회전 배치를 조립한다. */
@Configuration(proxyBeanMethods = false)
@Profile(ROTATE_KEYS_PROFILE)
class KeyRotationConfiguration {
    @Bean
    fun tableStructureKeyRotation(
        jdbcClient: JdbcClient,
        cipher: ContentCipher,
        transactionManager: PlatformTransactionManager,
        properties: KeyRotationProperties,
    ): TableStructureKeyRotation =
        TableStructureKeyRotation(jdbcClient, cipher, TransactionTemplate(transactionManager), properties.batchSize)

    @Bean
    fun reviewHistoryKeyRotation(
        jdbcClient: JdbcClient,
        cipher: ContentCipher,
        transactionManager: PlatformTransactionManager,
        properties: KeyRotationProperties,
    ): ReviewHistoryKeyRotation =
        ReviewHistoryKeyRotation(jdbcClient, cipher, TransactionTemplate(transactionManager), properties.batchSize)

    @Bean
    fun actionGuideContentKeyRotation(
        jdbcClient: JdbcClient,
        cipher: ContentCipher,
        transactionManager: PlatformTransactionManager,
        properties: KeyRotationProperties,
    ): ActionGuideContentKeyRotation =
        ActionGuideContentKeyRotation(jdbcClient, cipher, TransactionTemplate(transactionManager), properties.batchSize)

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
