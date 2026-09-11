package kr.easydoc.infrastructure.accesslog

import kr.easydoc.infrastructure.MigrationCatalog
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 인구조사 — **IP가 `personal_data_access_logs`(V22) 외의 어디에도 저장되지 않는다**
 * (계획 `docs/plans/2026-09-11-access-log-retention.md` 수용 기준 8, §3.4 「접속기록
 * 외의 어떤 표에도 IP를 저장하지 않는다」). 마이그레이션 SQL 원문을 직접 훑는다 — 이
 * 저장소에는 다른 표가 IP를 담기 시작했는지 재는 자리가 없었으므로 새로 만든다.
 *
 * **[MigrationCatalog]로 읽는다** — 클래스패스 URL(`getResource`)로 디렉터리를 열면
 * `classes/`로 컴파일된 자원이 아니라 실제 실행 환경에 따라 jar 안 자원을 가리킬 수
 * 있어 `File(url.toURI())`가 `IllegalArgumentException: URI is not hierarchical`로
 * 터진다 — `MigrationCatalog`가 이미 `easydoc.kotlin.source.root` 시스템 속성(테스트
 * 실행에 주입된다) 기준으로 소스 트리를 직접 읽는 안전한 경로를 두고 있다
 * (`EncryptionSchemeSchemaTest`와 같은 선례).
 *
 * `client_ip`·`inet` 열이 하나라도 V22 밖에서 나오면 이 테스트가 빨개진다.
 */
class PersonalDataAccessLogIpIsolationTest {
    @Test
    @DisplayName("V22 외의 마이그레이션에는 client_ip·inet 열이 없다")
    fun `IP 열은 V22 뿐이다`() {
        assertThat(MigrationCatalog.versions)
            .withFailMessage("마이그레이션 목록이 비었다 — 이 인구조사가 아무것도 재지 않는다")
            .isNotEmpty()

        val offenders =
            MigrationCatalog.versions
                .filterNot { it == V22_VERSION }
                .filter { version ->
                    val content = MigrationCatalog.sourceOf(version).lowercase()
                    IP_MARKERS.any { marker -> content.contains(marker) }
                }

        assertThat(offenders)
            .withFailMessage("V22 외의 마이그레이션이 IP 관련 열을 담고 있다 — 접속기록 예외가 새어 나갔다: %s", offenders)
            .isEmpty()
    }

    private companion object {
        const val V22_VERSION = "22"

        /** `client_ip` 열 이름, `inet` 타입 — 둘 중 하나라도 있으면 IP를 담는다는 신호다. */
        val IP_MARKERS = listOf("client_ip", " inet ", " inet,", " inet\n")
    }
}
