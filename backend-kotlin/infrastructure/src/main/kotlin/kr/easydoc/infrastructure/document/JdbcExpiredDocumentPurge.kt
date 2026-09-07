package kr.easydoc.infrastructure.document

import kr.easydoc.application.credit.CreditAccountService
import kr.easydoc.application.document.ExpiredDocumentPurge
import kr.easydoc.application.document.RetentionPurgeResult
import kr.easydoc.core.credit.Credits
import kr.easydoc.infrastructure.queue.JdbcConversionQueue
import org.springframework.jdbc.core.simple.JdbcClient
import java.sql.ResultSet
import java.util.UUID

/**
 * 만료 문서를 DB 시계 기준으로 고른다. 활성 리스(`leased` 이고 `lease_until` 이 미래)가 있는
 * 행은 건너뛰어, 변환 worker 가 붙잡고 있는 문서를 밑에서 지우지 않는다.
 *
 * **삭제 전에 끝나지 않은 크레딧 예약을 해제한다**(2026-09-07 리뷰 HIGH-1) —
 * `DocumentService.delete` 와 같은 이유다: 예약(`reserved`)만 하고 아직 소비·해제되지
 * 않은 채로 문서가 지워지면 그 크레딧이 영원히 묶인다. 이 경로는 사용자 요청이 아니라
 * worker 의 보존 만료 배치라 소유자를 인자로 받을 자리가 없다 — [lockPendingReservations]
 * 의 SQL 이 소유 술어 없이 `ids` 로만 좁힌다(`OwnershipPredicateGuardTest` 미방어 목록).
 */
class JdbcExpiredDocumentPurge(
    private val jdbc: JdbcClient,
    private val credits: CreditAccountService,
) : ExpiredDocumentPurge {
    override fun purge(
        dryRun: Boolean,
        limit: Int,
    ): RetentionPurgeResult {
        val skippedLeased = countSkippedLeased()
        val ids = lockExpiredWithoutLease(limit)
        val conversions = countConversions(ids)
        if (!dryRun && ids.isNotEmpty()) {
            releasePendingReservations(ids)
            deleteDocuments(ids)
        }
        return RetentionPurgeResult(
            dryRun = dryRun,
            enabled = true,
            purgedDocuments = ids.size,
            purgedConversions = conversions,
            skippedLeased = skippedLeased,
            documentIds = ids,
        )
    }

    /**
     * [ids] 중 아직 끝나지 않은(`pending`/`processing`) 채로 크레딧이 예약된 변환을 찾아
     * 해제한다 — `DocumentService.delete` 의 `lockPendingReservation` 과 같은 조건이지만
     * 배치라 `IN (:ids)` 로 한 번에 찾는다.
     */
    private fun releasePendingReservations(ids: List<UUID>) {
        val statement =
            ids.foldIndexed(jdbc.sql(pendingReservationsSql(ids.size))) { index, spec, id ->
                spec.param(idParam(index), id)
            }
        val reservations = statement.query { rs, _ -> rs.toPendingReservation() }.list()
        reservations.forEach { reservation ->
            credits.release(
                workspaceId = reservation.workspaceId,
                ownerId = reservation.ownerId,
                documentId = reservation.documentId,
                conversionId = reservation.conversionId,
                amount = Credits(reservation.creditsReserved),
            )
        }
    }

    private fun ResultSet.toPendingReservation() =
        PurgePendingReservation(
            conversionId = getObject("conversion_id", UUID::class.java),
            documentId = getObject("document_id", UUID::class.java),
            workspaceId = getObject("workspace_id", UUID::class.java),
            ownerId = getObject("owner_id", UUID::class.java),
            creditsReserved = getInt("credits_reserved"),
        )

    private fun countSkippedLeased(): Int =
        jdbc
            .sql(SKIPPED_LEASED_SQL)
            .query { rs, _ -> rs.getInt(1) }
            .single()

    private fun lockExpiredWithoutLease(limit: Int): List<UUID> =
        jdbc
            .sql(LOCK_EXPIRED_SQL)
            .param("limit", limit)
            .param("leased", JdbcConversionQueue.LEASED_STATE)
            .query { rs, _ -> rs.getObject("id", UUID::class.java) }
            .list()

    private fun countConversions(ids: List<UUID>): Int {
        if (ids.isEmpty()) return 0
        val statement =
            ids.foldIndexed(jdbc.sql(countConversionsSql(ids.size))) { index, spec, id ->
                spec.param(idParam(index), id)
            }
        return statement.query { rs, _ -> rs.getInt(1) }.single()
    }

    private fun deleteDocuments(ids: List<UUID>) {
        val statement =
            ids.foldIndexed(jdbc.sql(deleteSql(ids.size))) { index, spec, id ->
                spec.param(idParam(index), id)
            }
        statement.update()
    }

    /** [releasePendingReservations] 이 읽어 온 예약 한 건 — 배치판 `PendingCreditsReservation`. */
    private class PurgePendingReservation(
        val conversionId: UUID,
        val documentId: UUID,
        val workspaceId: UUID,
        val ownerId: UUID,
        val creditsReserved: Int,
    )

    private companion object {
        fun idParam(index: Int): String = "id$index"

        fun placeholders(size: Int): String = (0 until size).joinToString { ":${idParam(it)}" }

        fun countConversionsSql(size: Int): String =
            "SELECT count(*) FROM conversions WHERE document_id IN (${placeholders(size)})"

        fun deleteSql(size: Int): String = "DELETE FROM documents WHERE id IN (${placeholders(size)})"

        /**
         * 파기 대상 [ids] 중 아직 끝나지 않은 채로 예약된 변환을 잠그고 읽는다 — worker 의
         * `settleCreditsReserved`(CAS UPDATE)와 이 트랜잭션이 같은 행을 동시에 건드리지
         * 않도록 `FOR NO KEY UPDATE OF c` 로 먼저 잠근다(`JdbcConversionRepository.
         * lockPendingReservation` 과 같은 방어). 소유 술어가 없다 — worker 배치라
         * 「내 것」이 없다(`OwnershipPredicateGuardTest` 미방어 목록).
         */
        fun pendingReservationsSql(size: Int): String =
            """
            SELECT c.id AS conversion_id, c.document_id, d.workspace_id, d.user_id AS owner_id,
                   c.credits_reserved
            FROM conversions c
            JOIN documents d ON d.id = c.document_id
            WHERE c.document_id IN (${placeholders(size)})
              AND c.status IN ('pending', 'processing')
              AND c.credits_reserved > 0
            FOR NO KEY UPDATE OF c
            """.trimIndent()

        val SKIPPED_LEASED_SQL =
            """
            SELECT count(*)
            FROM documents d
            WHERE d.retention_expires_at <= now()
              AND EXISTS (
                  SELECT 1
                  FROM conversions c
                  INNER JOIN conversion_jobs j ON j.conversion_id = c.id
                  WHERE c.document_id = d.id
                    AND j.state = '${JdbcConversionQueue.LEASED_STATE}'
                    AND j.lease_until > now()
              )
            """.trimIndent()

        val LOCK_EXPIRED_SQL =
            """
            SELECT d.id
            FROM documents d
            WHERE d.retention_expires_at <= now()
              AND NOT EXISTS (
                  SELECT 1
                  FROM conversions c
                  INNER JOIN conversion_jobs j ON j.conversion_id = c.id
                  WHERE c.document_id = d.id
                    AND j.state = :leased
                    AND j.lease_until > now()
              )
            ORDER BY d.retention_expires_at ASC, d.id ASC
            LIMIT :limit
            FOR UPDATE OF d SKIP LOCKED
            """.trimIndent()
    }
}
