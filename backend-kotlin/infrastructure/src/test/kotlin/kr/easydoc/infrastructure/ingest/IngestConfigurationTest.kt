package kr.easydoc.infrastructure.ingest

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class IngestConfigurationTest {
    private val context =
        ApplicationContextRunner().withUserConfiguration(
            IngestConfiguration::class.java,
        )

    @Test
    fun `기본 동시 추출 수는 4다`() {
        context.run { context ->
            assertThat(context.getBean(ConcurrencyLimitedTextExtractor::class.java).availablePermits).isEqualTo(4)
        }
    }

    @Test
    fun `설정한 동시 추출 수를 사용한다`() {
        context.withPropertyValues("easydoc.ingest.max-concurrent-extractions=2").run { context ->
            assertThat(context.getBean(ConcurrencyLimitedTextExtractor::class.java).availablePermits).isEqualTo(2)
        }
    }

    @Test
    fun `양수가 아닌 동시 추출 수는 거절한다`() {
        for (permits in listOf(0, -1)) {
            assertThatThrownBy { IngestProperties(maxConcurrentExtractions = permits) }
                .isInstanceOf(IllegalArgumentException::class.java)
        }
    }
}
