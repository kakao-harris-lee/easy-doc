package kr.easydoc.infrastructure.ingest

import kr.easydoc.application.document.DocumentTextExtractor
import kr.easydoc.application.document.ExtractedDocument
import kr.easydoc.core.document.SourceFormat
import org.apache.poi.openxml4j.util.ZipSecureFile
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.xml.XMLConstants
import javax.xml.stream.XMLInputFactory

/**
 * 배선으로만 확인할 수 있는 방어 셋 — POI 전역 설정 · StAX 속성 · 동시 추출 제한.
 *
 * 셋 다 **행동 음성 대조가 성립하지 않거나 비싸다.** 그 사실을 숨기지 않고, 각 케이스가
 * 무엇을 재고 무엇을 못 재는지 함께 적는다.
 */
class IngestDefensesTest {
    @Test
    @DisplayName("POI 전역 zip 방어값이 우리 예산으로 낮춰져 있다 (계획 §5 D-8)")
    fun `POI 전역 zip 방어가 걸려 있다`() {
        PoiZipDefenses.apply()

        // 기본값은 항목 하나에 4GiB-1 을 허용한다. 그 상태를 그대로 두지 않는다.
        assertThat(ZipSecureFile.getMaxEntrySize()).isEqualTo(ZIP_UNCOMPRESSED_BUDGET_BYTES)
        assertThat(ZipSecureFile.getMinInflateRatio()).isEqualTo(PoiZipDefenses.MIN_INFLATE_RATIO)
        assertThat(ZipSecureFile.getMaxFileCount()).isEqualTo(PoiZipDefenses.MAX_FILE_COUNT)
    }

    @Test
    @DisplayName("이 설정은 backstop 이다 — 1차 방어는 ZipBudget 이고 순서가 그렇다")
    fun `POI 설정은 backstop 임을 명시한다`() {
        // **이 케이스가 재지 못하는 것**: `PoiZipDefenses.apply()` 를 지워도 압축 폭탄은
        // 여전히 거부된다(ZipBudget 이 POI 를 부르기 전에 끊는다). 그래서 위 케이스는
        // 행동이 아니라 **구조**를 재는 단언이고, 이 주석이 그 한계의 기록이다.
        assertThat(PoiZipDefenses.MAX_ENTRY_SIZE).isEqualTo(ZIP_UNCOMPRESSED_BUDGET_BYTES)
    }

    @Test
    @DisplayName("StAX 팩터리가 DTD·외부 엔터티 세 속성을 명시한다 (spike S-4)")
    fun `StAX 속성이 명시돼 있다`() {
        val factory = SecureXml.newInputFactory()

        assertThat(factory.getProperty(XMLInputFactory.SUPPORT_DTD)).isEqualTo(false)
        assertThat(factory.getProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES)).isEqualTo(false)
        assertThat(factory.getProperty(XMLConstants.ACCESS_EXTERNAL_DTD)).isEqualTo("")
    }

    @Test
    @DisplayName("팩터리를 매번 새로 만든다 — 스레드 사이에 공유하지 않는다")
    fun `팩터리를 공유하지 않는다`() {
        assertThat(SecureXml.newInputFactory()).isNotSameAs(SecureXml.newInputFactory())
    }

    @Test
    @DisplayName("동시 추출이 상한을 넘지 않는다 (계획 §5 D-14)")
    fun `동시 진입 최대치가 상한을 넘지 않는다`() {
        val inFlight = AtomicInteger()
        val peak = AtomicInteger()
        val release = CountDownLatch(1)
        val entered = CountDownLatch(ConcurrencyLimitedTextExtractor.MAX_CONCURRENT_EXTRACTIONS)

        val limited =
            ConcurrencyLimitedTextExtractor(
                DocumentTextExtractor { _, _ ->
                    val now = inFlight.incrementAndGet()
                    peak.accumulateAndGet(now, ::maxOf)
                    entered.countDown()
                    // 자리를 붙잡고 있어야 상한이 실제로 발화한다.
                    release.await(AWAIT_SECONDS, TimeUnit.SECONDS)
                    inFlight.decrementAndGet()
                    ExtractedDocument(SourceFormat.DOCX, "")
                },
            )

        val pool = Executors.newFixedThreadPool(CALLERS)
        try {
            repeat(CALLERS) { pool.submit { limited.extract("안내문.docx", byteArrayOf()) } }
            assertThat(entered.await(AWAIT_SECONDS, TimeUnit.SECONDS))
                .withFailMessage("허용된 수만큼도 들어가지 못했다 — 제한이 아니라 직렬화가 됐는지 보라.")
                .isTrue()
            release.countDown()
        } finally {
            pool.shutdown()
            assertThat(pool.awaitTermination(AWAIT_SECONDS, TimeUnit.SECONDS)).isTrue()
        }

        assertThat(peak.get())
            .withFailMessage {
                "동시 진입이 ${peak.get()} 까지 올라갔다(상한 ${ConcurrencyLimitedTextExtractor.MAX_CONCURRENT_EXTRACTIONS}). " +
                    "건당 예산이 수십 MB 라 곱하면 OOM 이다."
            }.isLessThanOrEqualTo(ConcurrencyLimitedTextExtractor.MAX_CONCURRENT_EXTRACTIONS)
    }

    @Test
    @DisplayName("조립이 내는 포트가 제한을 두른 구현이다 — 제한 없는 추출기를 잡을 수 없다")
    fun `조립이 제한을 두른다`() {
        val bean = IngestConfiguration().documentTextExtractor()

        assertThat(bean)
            .withFailMessage(
                "조립이 제한 없는 추출기를 그대로 냈다 — 유스케이스가 무제한 경로를 잡게 된다.",
            ).isInstanceOf(ConcurrencyLimitedTextExtractor::class.java)
        assertThat((bean as ConcurrencyLimitedTextExtractor).availablePermits)
            .isEqualTo(ConcurrencyLimitedTextExtractor.MAX_CONCURRENT_EXTRACTIONS)
    }

    private companion object {
        const val CALLERS = 12
        const val AWAIT_SECONDS = 10L
    }
}
