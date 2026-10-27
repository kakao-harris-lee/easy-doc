package kr.easydoc.application.subscription

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class LegalSnapshotTest {
    @Test
    fun `스냅샷 버전은 공개한 약관 파일 이름과 같다`() {
        assertThat(LegalSnapshot.VERSION).isEqualTo("2026-11-01")
    }

    @Test
    fun `시행 시각은 버전 날짜의 한국 시간 자정이다`() {
        assertThat(LegalSnapshot.EFFECTIVE_AT).isEqualTo(Instant.parse("2026-11-01T00:00:00+09:00"))
        assertThat(LegalSnapshot.ZONE.id).isEqualTo("Asia/Seoul")
    }
}
