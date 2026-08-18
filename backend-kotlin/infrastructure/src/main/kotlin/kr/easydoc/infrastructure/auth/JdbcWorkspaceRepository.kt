package kr.easydoc.infrastructure.auth

import kr.easydoc.application.auth.WorkspaceRepository
import kr.easydoc.core.exceptions.StorageException
import kr.easydoc.core.workspace.DEFAULT_WORKSPACE_NAME
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.simple.JdbcClient
import java.util.UUID

/**
 * `workspaces` 테이블 접근 — 지금은 가입이 쓰는 기본 작업 공간 생성 하나뿐이다.
 *
 * 목록·이름 변경·삭제는 다음 작업 단위에서 이 클래스에 붙는다. 지금 만드는 이유는
 * **가입이 계정과 기본 작업 공간을 같은 트랜잭션에서 만들어야 하기 때문**이고
 * (계약 `paths./auth/signup`), 그 요구를 뒤로 미루면 작업 공간 없는 계정이 생긴다.
 */
class JdbcWorkspaceRepository(private val jdbc: JdbcClient) : WorkspaceRepository {
    /**
     * 기본 작업 공간을 만든다.
     *
     * 제약 위반은 전부 [StorageException] → 500 이다. 방금 만든 사용자에게
     * `uq_workspaces_user_id_name` 이 걸릴 수 없고 FK 도 같은 트랜잭션의 행을 가리키므로,
     * 여기서 터지는 것은 **사용자 입력 문제가 아니라 코드·스키마 버그**다. 4xx 로 감싸면
     * 서버 버그가 "사용자가 뭘 잘못했다"로 둔갑해 조용히 묻힌다.
     */
    override fun createDefault(userId: UUID): UUID {
        val id = UUID.randomUUID()
        try {
            jdbc
                .sql("INSERT INTO workspaces (id, user_id, name) VALUES (:id, :userId, :name)")
                .param("id", id)
                .param("userId", userId)
                .param("name", DEFAULT_WORKSPACE_NAME)
                .update()
        } catch (_: DataIntegrityViolationException) {
            // 원인을 잇지 않는다 — PostgreSQL 이 제약 위반 DETAIL 에 행 전체를 담는다.
            throw StorageException(STORAGE_FAILURE_MESSAGE)
        }
        return id
    }

    private companion object {
        const val STORAGE_FAILURE_MESSAGE = "요청을 처리하지 못했습니다"
    }
}
