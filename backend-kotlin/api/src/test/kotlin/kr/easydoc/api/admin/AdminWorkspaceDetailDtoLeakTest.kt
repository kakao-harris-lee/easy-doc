package kr.easydoc.api.admin

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.reflect.full.memberProperties

/**
 * 관리자 조회 응답에 본문·프롬프트 필드가 없다 — 범위 밖 「관리자도 본문을 보지 않는다
 * (개인정보 원칙)」(어드민 최소 계획 §2 결정 7). `DocumentDtoLeakTest`·`WorkspaceDtoLeakTest`
 * 와 같은 규약을 관리자 DTO로 넓힌다 — 여기는 **구조적 부재**(필드 자체가 없다)를 잰다,
 * `toString()` 마스킹(`SensitiveToStringReachTest`)과는 다른 축이다.
 */
class AdminWorkspaceDetailDtoLeakTest {
    @Test
    @DisplayName("AdminConversionItemResponse 는 id·title·status·failure_code·created_at 뿐이다")
    fun `최근 변환 항목에 본문·프롬프트 필드가 없다`() {
        val propertyNames = AdminConversionItemResponse::class.memberProperties.map { it.name }.toSet()

        assertThat(propertyNames).isEqualTo(setOf("id", "title", "status", "failureCode", "createdAt"))
        assertThat(propertyNames).noneMatch { name ->
            BODY_LIKE_TOKENS.any { token -> name.contains(token, ignoreCase = true) }
        }
    }

    @Test
    @DisplayName("AdminErrorItemResponse 는 id·workspace_id·created_at·failure_code 뿐이다")
    fun `오류 목록 항목에 본문 필드가 없다`() {
        val propertyNames = AdminErrorItemResponse::class.memberProperties.map { it.name }.toSet()

        assertThat(propertyNames).isEqualTo(setOf("id", "workspaceId", "createdAt", "failureCode"))
    }

    private companion object {
        /** `easyText`·`prompt`·`body` 류가 우연히도 조용히 섞여 들어오는 것을 막는다. */
        val BODY_LIKE_TOKENS = listOf("body", "text", "prompt", "draft")
    }
}
