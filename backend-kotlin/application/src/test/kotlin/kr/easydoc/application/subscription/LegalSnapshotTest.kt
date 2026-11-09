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
    fun `방침 버전 이력은 게시 순서대로이고 현재 버전이 마지막이다`() {
        assertThat(LegalSnapshot.PRIVACY_VERSIONS).containsExactly("2026-11-01", "2026-11-10")
        assertThat(LegalSnapshot.PRIVACY_VERSIONS.last()).isEqualTo(LegalSnapshot.PRIVACY_VERSION)
        assertThat(LegalSnapshot.PRIVACY_VERSIONS).isSorted()
    }

    @Test
    fun `동의 시점의 방침 버전은 그 시점에 시행 중이던 방침이다`() {
        assertThat(LegalSnapshot.privacyVersionAt(Instant.parse("2026-10-31T15:00:00Z"))).isEqualTo("2026-11-01")
        assertThat(LegalSnapshot.privacyVersionAt(Instant.parse("2026-11-05T12:00:00+09:00"))).isEqualTo("2026-11-01")
        assertThat(LegalSnapshot.privacyVersionAt(Instant.parse("2026-11-10T00:00:00+09:00"))).isEqualTo("2026-11-10")
        assertThat(LegalSnapshot.privacyVersionAt(Instant.parse("2026-11-09T23:59:59+09:00"))).isEqualTo("2026-11-01")
    }

    @Test
    fun `첫 방침 시행 전 동의에는 가리킬 방침이 없다`() {
        assertThat(LegalSnapshot.privacyVersionAt(Instant.parse("2026-10-31T14:59:59Z"))).isNull()
    }

    @Test
    fun `첫 방침 시행 시각은 유료 약관 시행 시각과 같다`() {
        assertThat(LegalSnapshot.privacyVersionAt(LegalSnapshot.EFFECTIVE_AT)).isEqualTo("2026-11-01")
    }

    @Test
    fun `방침 개정은 유료 시행 시각을 옮기지 않는다`() {
        assertThat(LegalSnapshot.PRIVACY_VERSION).isNotEqualTo(LegalSnapshot.VERSION)
        assertThat(LegalSnapshot.VERSION).isEqualTo("2026-11-01")
        assertThat(LegalSnapshot.EFFECTIVE_AT).isEqualTo(Instant.parse("2026-11-01T00:00:00+09:00"))
    }
}
