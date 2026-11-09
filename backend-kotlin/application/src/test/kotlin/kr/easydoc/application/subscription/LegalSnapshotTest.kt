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

    @Test
    fun `개인정보처리방침 버전은 공개한 방침 파일 이름과 같다`() {
        assertThat(LegalSnapshot.PRIVACY_VERSION).isEqualTo("2026-11-10")
    }

    @Test
    fun `방침 개정은 유료 시행 시각을 옮기지 않는다`() {
        assertThat(LegalSnapshot.PRIVACY_VERSION).isNotEqualTo(LegalSnapshot.VERSION)
        assertThat(LegalSnapshot.VERSION).isEqualTo("2026-11-01")
        assertThat(LegalSnapshot.EFFECTIVE_AT).isEqualTo(Instant.parse("2026-11-01T00:00:00+09:00"))
    }
}
