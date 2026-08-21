package kr.easydoc.infrastructure.ingest

import kr.easydoc.application.document.DocumentTextExtractor
import kr.easydoc.application.document.ExtractedDocument
import kr.easydoc.core.document.SourceFormat
import org.apache.poi.openxml4j.util.ZipSecureFile
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.xml.XMLConstants
import javax.xml.stream.XMLInputFactory

/** 배선으로만 확인할 수 있는 방어 셋 — POI 전역 설정 · StAX 속성 · 동시 추출 제한. */
class IngestDefensesTest {
    private var savedMaxEntrySize: Long = 0
    private var savedMinInflateRatio: Double = 0.0
    private var savedMaxFileCount: Long = 0

    @BeforeEach
    fun rememberGlobalDefenses() {
        savedMaxEntrySize = ZipSecureFile.getMaxEntrySize()
        savedMinInflateRatio = ZipSecureFile.getMinInflateRatio()
        savedMaxFileCount = ZipSecureFile.getMaxFileCount()
    }

    @AfterEach
    fun restoreGlobalDefenses() {
        ZipSecureFile.setMaxEntrySize(savedMaxEntrySize)
        ZipSecureFile.setMinInflateRatio(savedMinInflateRatio)
        ZipSecureFile.setMaxFileCount(savedMaxFileCount)
    }

    @Test
    @DisplayName("**제품 조립**이 POI 전역 zip 방어값을 우리 예산으로 되돌린다 (계획 §5 D-8)")
    fun `조립이 POI 전역 zip 방어를 설치한다`() {
        ZipSecureFile.setMaxEntrySize(WRONG_MAX_ENTRY_SIZE)
        ZipSecureFile.setMinInflateRatio(WRONG_MIN_INFLATE_RATIO)
        ZipSecureFile.setMaxFileCount(WRONG_MAX_FILE_COUNT)

        IngestConfiguration().documentTextExtractor()

        assertThat(ZipSecureFile.getMaxEntrySize())
            .withFailMessage(
                "조립이 maxEntrySize 를 설치하지 않았다 — IngestConfiguration 의 " +
                    "PoiZipDefenses.apply() 호출이 사라졌는지 보라. 기본값은 항목 하나에 4GiB-1 이다.",
            ).isEqualTo(ZIP_UNCOMPRESSED_BUDGET_BYTES)
        assertThat(ZipSecureFile.getMinInflateRatio()).isEqualTo(PoiZipDefenses.MIN_INFLATE_RATIO)
        assertThat(ZipSecureFile.getMaxFileCount()).isEqualTo(PoiZipDefenses.MAX_FILE_COUNT)
    }

    @Test
    @DisplayName("이 설정은 backstop 이다 — 1차 방어는 ZipBudget 이고 순서가 그렇다")
    fun `POI 설정은 backstop 임을 명시한다`() {
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

        /**
         * 일부러 어긋뜨리는 값. 우리 값과 달라야 음성 대조가 성립한다 — 같은 값을 심으면
         * 조립이 아무것도 하지 않아도 통과한다.
         */
        const val WRONG_MAX_ENTRY_SIZE = 7L
        const val WRONG_MIN_INFLATE_RATIO = 0.99
        const val WRONG_MAX_FILE_COUNT = 3L
    }
}
