package kr.easydoc.infrastructure.document

import kr.easydoc.application.auth.TransactionRunner
import kr.easydoc.application.crypto.ContentCipher
import kr.easydoc.application.document.ConversionExportService
import kr.easydoc.application.document.ConversionRepository
import kr.easydoc.application.document.DocumentExporter
import kr.easydoc.application.document.MaskedItemReader
import kr.easydoc.infrastructure.crypto.MIGRATE_PROFILE
import kr.easydoc.infrastructure.export.PackagedDocumentExporter
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile

/** 내보내기 조립 — 패키지 작성과 유스케이스를 문서 저장 조립과 가른다. */
@Configuration(proxyBeanMethods = false)
@Profile("!$MIGRATE_PROFILE")
class DocumentExportConfiguration {
    /** 형식별 파일 조립. zip·POI 는 이 모듈 안에 남는다. */
    @Bean
    fun documentExporter(): DocumentExporter = PackagedDocumentExporter()

    /** 복호화와 복원은 유스케이스, 패키지 조립은 [documentExporter] 가 한다. */
    @Bean
    fun conversionExportService(
        conversions: ConversionRepository,
        cipher: ContentCipher,
        maskedItems: MaskedItemReader,
        exporter: DocumentExporter,
        transactionRunner: TransactionRunner,
    ): ConversionExportService =
        ConversionExportService(
            conversions = conversions,
            cipher = cipher,
            maskedItems = maskedItems,
            exporter = exporter,
            transaction = transactionRunner,
        )
}
