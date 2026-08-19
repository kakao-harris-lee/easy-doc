package kr.easydoc.infrastructure.ingest

import kr.easydoc.application.document.DocumentTextExtractor
import kr.easydoc.application.document.ExtractedDocument
import java.util.concurrent.Semaphore

/**
 * 동시에 추출을 도는 요청 수를 제한한다 (계획 §5 D-14).
 *
 * 원본: `app/api/deps.py` 의 `CapacityLimiter(4)`.
 *
 * ## 왜 필요한가
 *
 * 건당 예산이 수십 MB(zip 해제 예산 50MiB · PDF 는 문서 전체를 메모리에 올린다)인데
 * 서블릿 컨테이너의 작업 스레드는 수백 개다. **곱하면 OOM 이다.** PDFBox 쪽에는 읽기
 * 메모리 상한 API 자체가 없으므로(계획 §1.2 Q-3) 이 제한이 실제 방어의 한 축이다.
 *
 * ## 왜 데코레이터인가
 *
 * 제한을 추출기 **안**에 두면 형식이 늘 때마다 따라 붙여야 하고 하나를 빠뜨리면 그 형식만
 * 무제한이 된다. 포트를 감싸면 **포트를 지나는 모든 호출**이 자동으로 제한을 받는다 —
 * 다음 형식이 늘어도 여기는 손대지 않는다.
 *
 * ## 대기한다 (거절하지 않는다)
 *
 * 자리가 없으면 **막아 세운다**. 원본의 `CapacityLimiter` 와 같은 동작이고, 이유는
 * 대기가 사용자에게 "조금 느림"인 반면 거절은 "실패"이기 때문이다. 대기 상한을 두는 갈래는
 * 요구가 아니므로 넣지 않고 개선 후보로 등재한다 — 그때는 503 이 나가야 하고 그것은
 * 계약 표면이라 `contract-keeper` 판정이 필요하다.
 */
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
        /**
         * 동시 추출 최대 수.
         *
         * 원본 `CapacityLimiter(4)` 와 같은 값이다. 코어 수에 비례시키지 않는 이유는 이 제한이
         * CPU 가 아니라 **메모리** 예산이기 때문이다 — 코어가 많은 기계일수록 힙이 큰 것은
         * 아니고, 예산은 힙에 걸린다.
         */
        const val MAX_CONCURRENT_EXTRACTIONS: Int = 4
    }
}
