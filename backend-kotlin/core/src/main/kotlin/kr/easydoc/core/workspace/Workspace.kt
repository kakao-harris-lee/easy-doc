package kr.easydoc.core.workspace

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
)

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
