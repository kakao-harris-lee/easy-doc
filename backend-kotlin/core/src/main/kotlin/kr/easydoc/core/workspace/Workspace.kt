package kr.easydoc.core.workspace

import kr.easydoc.core.privacy.CONTENT_MASK
import java.time.Instant
import java.util.UUID

/**
 * 가입 시 함께 만들어지는 기본 작업 공간의 이름.
 *
 * 계약(`paths./auth/signup`)이 값과 이유를 함께 적었다 — *"가입은 계정과 기본 작업 공간을
 * 같은 트랜잭션에서 만든다. 나눠 커밋하면 계정만 있고 작업 공간이 없는 사용자가 생겨 첫
 * 업로드가 갈 곳을 잃는다."*
 */
const val DEFAULT_WORKSPACE_NAME: String = "기본 작업 공간"

/**
 * 작업 공간 한 건. 계약 `components/schemas/WorkspaceResponse` 가 요구하는 세 값이다.
 *
 * **소유자를 담지 않는다.** 소유자 식별자는 조회 조건으로만 쓰이고(`WHERE user_id = ?`)
 * 응답에는 나가지 않는다 — 계약의 `required` 가 `id`·`name`·`created_at` 셋뿐이고,
 * 담아 두면 `toString()`·직렬화 어디로든 새는 경로가 생긴다. `User` 가 비밀번호 해시를
 * 담지 않는 것과 같은 규율이다.
 *
 * `name` 은 **정규화된 값**이다 — 제어문자를 걷어내고 앞뒤 공백을 턴 결과
 * (`application` 의 `normalizeWorkspaceName`). 저장 전에 정규화하므로 여기 담긴 값과
 * DB 의 값이 같다.
 */
data class Workspace(
    val id: UUID,
    val name: String,
    val createdAt: Instant,
) {
    /**
     * **이름을 찍지 않는다.**
     *
     * 계약 자신이 작업 공간 이름을 사적 응답 헤더 대상으로 분류했다
     * (`x-private-response-headers.applies_to` — *"작업 공간 이름도 사용자가 적은
     * 콘텐츠"*). 그런데 `data class` 의 기본 `toString()` 은 모든 필드를 찍는다 —
     * `Secret` 이 막는 것과 **같은 기본 동작**이고, 이 자리에서는 KDoc 이 소유자를
     * 담지 않는 근거로 *"담아 두면 `toString()`·직렬화 어디로든 새는 경로가 생긴다"* 를
     * 들면서 `name` 자신에는 같은 규율을 적용하지 않은 비대칭이 있었다(A-3).
     *
     * 오늘 이 경로에 로거가 0개라 **도달은 0**이다. 그래도 지금 막는 이유는, 막는 비용이
     * 한 줄인데 새는 순간은 **로깅이 처음 들어오는 커밋**이고 그때 아무도 이 클래스를
     * 다시 보지 않기 때문이다.
     *
     * 진단에 필요한 것은 남긴다 — 식별자와 생성 시각으로 행을 특정할 수 있다.
     */
    override fun toString(): String = "Workspace(id=$id, name=$NAME_MASK, createdAt=$createdAt)"

    companion object {
        /**
         * 이름 자리에 대신 찍히는 표식. 테스트가 이 값으로 「가려졌음」을 확인한다.
         *
         * 값은 [kr.easydoc.core.privacy.CONTENT_MASK] 하나에서 온다 — 타입마다 다른
         * 문자열을 쓰면 확인하는 쪽이 타입마다 다른 상수를 알아야 한다.
         */
        const val NAME_MASK: String = CONTENT_MASK
    }
}

/**
 * 목록 한 줄 — 작업 공간 + 그 안의 문서 수.
 *
 * [Workspace] 에 `documentCount` 를 넣지 않고 감싸는 이유는 계약이 그렇게 갈랐기
 * 때문이다: *"문서 수는 목록에서만 준다 — 방금 만든 공간은 늘 0이고, 이름만 바꾼 공간의
 * 문서 수는 화면이 이미 안다. 응답마다 COUNT 를 붙이면 쓰지 않는 값을 위해 질의가 는다"*
 * (`components/schemas/WorkspaceListItem`). 한 타입에 nullable 필드로 두면 그 구분이
 * 타입에서 사라지고, 생성·이름 변경 응답에 `null` 이 실려 나갈 여지가 생긴다.
 */
data class WorkspaceListing(
    val workspace: Workspace,
    val documentCount: Int,
)
