package kr.easydoc.infrastructure.admin

import kr.easydoc.application.admin.AdminCreditAdjustmentCommand
import kr.easydoc.application.admin.AdminCreditAdjustmentStore
import kr.easydoc.application.credit.CreditAccountRepository
import kr.easydoc.core.exceptions.ConflictException
import kr.easydoc.core.exceptions.NotFoundException
import org.springframework.jdbc.core.simple.JdbcClient
import java.math.BigDecimal
import java.sql.ResultSet
import java.util.UUID

class JdbcAdminCreditAdjustmentStore(
    private val jdbc: JdbcClient,
    private val accounts: CreditAccountRepository,
) : AdminCreditAdjustmentStore {
    override fun apply(command: AdminCreditAdjustmentCommand) {
        // Serialize the operation UUID globally, including accidental reuse on another workspace.
        jdbc
            .sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
            .param("key", command.operationId.toString())
            .query { _, _ -> true }
            .single()
        val owner = lockSubjects(command)
        accounts.ensureAccount(command.workspaceId)
        val state =
            jdbc
                .sql(
                    "SELECT balance,reserved,revision FROM workspace_credit_accounts " +
                        "WHERE workspace_id=:workspace FOR UPDATE",
                ).param("workspace", command.workspaceId)
                .query { rs, _ ->
                    Triple(rs.getBigDecimal("balance"), rs.getBigDecimal("reserved"), rs.getLong("revision"))
                }.single()
        val previous =
            jdbc
                .sql("SELECT * FROM admin_credit_adjustments WHERE operation_id=:id")
                .param("id", command.operationId)
                .query { rs, _ -> replayMatches(rs, command) }
                .optional()
        if (previous.isPresent) {
            if (!previous.get()) throw ConflictException("같은 요청 번호로 다른 조정을 요청할 수 없습니다")
            return
        }
        if (state.first.compareTo(command.expectedBalance) != 0 ||
            state.second.compareTo(command.expectedReserved) != 0 || state.third != command.expectedRevision
        ) {
            throw ConflictException("크레딧 상태가 변경되었습니다. 새로 조회한 뒤 확인하세요")
        }
        if (command.credits < BigDecimal.ZERO && -command.credits > state.first - state.second) {
            throw ConflictException("회수량이 사용 가능 크레딧을 초과합니다")
        }
        accounts.grant(command.workspaceId, owner, command.credits, command.reason, command.note, command.actorUserId)
        record(command)
    }

    /** Deletion holds user then workspace before accounts. Keep FK locks in that same order. */
    @Suppress("ThrowsCount") // Missing owner, actor and workspace are independently checked under locks.
    private fun lockSubjects(command: AdminCreditAdjustmentCommand): UUID {
        val owner =
            accounts.ownerOf(command.workspaceId)
                ?: throw NotFoundException("작업 공간을 찾을 수 없습니다")
        val users = setOf(owner, command.actorUserId)
        val locked =
            jdbc
                .sql("SELECT id FROM users WHERE id IN (:ids) ORDER BY id FOR KEY SHARE")
                .param("ids", users.toList())
                .query(UUID::class.java)
                .list()
        if (locked.size != users.size) throw NotFoundException("조정 대상 또는 관리자 계정을 찾을 수 없습니다")
        val workspace =
            jdbc
                .sql("SELECT id FROM workspaces WHERE id=:id AND user_id=:owner FOR KEY SHARE")
                .param("id", command.workspaceId)
                .param("owner", owner)
                .query(UUID::class.java)
                .optional()
        if (workspace.isEmpty) throw NotFoundException("작업 공간을 찾을 수 없습니다")
        return owner
    }

    private fun replayMatches(
        rs: ResultSet,
        command: AdminCreditAdjustmentCommand,
    ): Boolean =
        rs.getObject("workspace_id", UUID::class.java) == command.workspaceId &&
            rs.getObject("actor_user_id", UUID::class.java) == command.actorUserId &&
            rs.getBigDecimal("credits").compareTo(command.credits) == 0 &&
            rs.getString("reason") == command.reason.wireName && rs.getString("note") == command.note &&
            rs.getBigDecimal("expected_balance").compareTo(command.expectedBalance) == 0 &&
            rs.getBigDecimal("expected_reserved").compareTo(command.expectedReserved) == 0 &&
            rs.getLong("expected_revision") == command.expectedRevision

    private fun record(command: AdminCreditAdjustmentCommand) {
        jdbc
            .sql(
                """
                INSERT INTO admin_credit_adjustments(operation_id,workspace_id,actor_user_id,credits,reason,note,
                    expected_balance,expected_reserved,expected_revision)
                VALUES(:id,:workspace,:actor,:credits,:reason,:note,:balance,:reserved,:revision)
                """.trimIndent(),
            ).param("id", command.operationId)
            .param("workspace", command.workspaceId)
            .param(
                "actor",
                command.actorUserId,
            ).param("credits", command.credits)
            .param("reason", command.reason.wireName)
            .param(
                "note",
                command.note,
            ).param("balance", command.expectedBalance)
            .param("reserved", command.expectedReserved)
            .param("revision", command.expectedRevision)
            .update()
    }
}
