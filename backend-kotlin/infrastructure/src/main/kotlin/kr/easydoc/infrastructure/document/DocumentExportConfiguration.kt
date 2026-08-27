package kr.easydoc.infrastructure.document

import kr.easydoc.application.auth.TransactionRunner
import kr.easydoc.application.crypto.ContentCipher
import kr.easydoc.application.document.ConversionExportService
import kr.easydoc.application.document.ConversionRepository
import kr.easydoc.application.document.DocumentExporter
import kr.easydoc.application.document.ExportRendering
import kr.easydoc.application.document.MaskedItemReader
import kr.easydoc.application.document.OriginalReflection
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

    /** 파일을 만드는 두 갈래를 한 묶음으로 세운다 — 원본이 있으면 반영, 없으면 새 문서다. */
    @Bean
    fun exportRendering(
        reflection: OriginalReflection,
        exporter: DocumentExporter,
    ): ExportRendering = ExportRendering(reflection = reflection, exporter = exporter)

    /** 복호화와 복원은 유스케이스, 파일 조립은 [exportRendering] 이 한다. */
    @Bean
    fun conversionExportService(
        conversions: ConversionRepository,
        cipher: ContentCipher,
        maskedItems: MaskedItemReader,
        rendering: ExportRendering,
        transactionRunner: TransactionRunner,
    ): ConversionExportService =
        ConversionExportService(
            conversions = conversions,
            cipher = cipher,
            maskedItems = maskedItems,
            rendering = rendering,
            transaction = transactionRunner,
        )
}
