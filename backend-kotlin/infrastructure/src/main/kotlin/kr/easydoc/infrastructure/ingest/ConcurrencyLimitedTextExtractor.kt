package kr.easydoc.infrastructure.ingest

import kr.easydoc.application.document.DocumentTextExtractor
import kr.easydoc.application.document.ExtractedDocument
import java.util.concurrent.Semaphore

/** 동시에 추출을 도는 요청 수를 제한한다 (계획 §5 D-14). */
class ConcurrencyLimitedTextExtractor(
    private val delegate: DocumentTextExtractor,
    permits: Int = MAX_CONCURRENT_EXTRACTIONS,
) : DocumentTextExtractor {
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

    companion object {
        /** 동시 추출 최대 수. */
        const val MAX_CONCURRENT_EXTRACTIONS: Int = 4
    }
}
