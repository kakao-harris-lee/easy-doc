package kr.easydoc.infrastructure.ingest

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "easydoc.ingest")
data class IngestProperties(val maxConcurrentExtractions: Int = 4) {
    init {
        require(maxConcurrentExtractions > 0) { "easydoc.ingest.max-concurrent-extractions must be positive" }
    }
}
