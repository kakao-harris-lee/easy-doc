package kr.easydoc.infrastructure.ingest

import kr.easydoc.application.document.DocumentTextExtractor
import kr.easydoc.application.document.ExtractedDocument
import java.util.concurrent.Semaphore

/** 공정한 세마포어로 동시 추출 수를 제한한다. */
class ConcurrencyLimitedTextExtractor(
    private val delegate: DocumentTextExtractor,
    permits: Int,
) : DocumentTextExtractor {
    init {
        require(permits > 0) { "Extraction permits must be positive" }
    }

    private val gate = Semaphore(permits, true)

    /** 지금 이 순간 자리 수. 회귀 테스트가 배선을 확인할 때만 읽는다. */
    val availablePermits: Int get() = gate.availablePermits()

    override fun extract(
        filename: String?,
        bytes: ByteArray,
    ): ExtractedDocument {
        gate.acquire()
        try {
            return delegate.extract(filename, bytes)
        } finally {
            gate.release()
        }
    }
}
