package kr.easydoc.infrastructure.document

import kr.easydoc.application.credit.CreditAccountService
import kr.easydoc.application.document.ExpiredDocumentPurge
import kr.easydoc.application.document.RetentionPurgeResult
import kr.easydoc.core.credit.Credits
import kr.easydoc.infrastructure.queue.JdbcConversionQueue
import org.springframework.jdbc.core.simple.JdbcClient
import java.math.BigDecimal
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
 *
 * **접수와의 잠금 순서(이슈 #219).** 이 배치는 문서 → 작업 → 변환 순으로 잡아 접수(변환 → 문서)와
 * 방향이 반대다. 그래도 사이클이 닫히지 않는 이유는 둘이다.
 *
 * ⑴ 접수의 문서 문장이 `SKIP LOCKED` 다(`…JobRepository.lockOwnedContext`). 접수는 이 배치가 쥔
 *    문서 행을 **기다리지 않고** 건너뛰어 `null` → 404 로 끝난다. 기다리는 변이 없으니 사이클도 없다.
 * ⑵ 애초에 겹칠 수 있는 구간이 보존 만료의 **그 순간 하나**다. 이 배치는
 *    `retention_expires_at <= now()` 인 문서만 고르고, 접수의 context 질의는
 *    `retention_expires_at > now()` 인 문서만 본다. 두 술어는 서로 배타적이라, 한 문서가 두 질의에
 *    동시에 걸리려면 두 문장 사이에서 만료 시각이 지나가야 한다.
 *
 * 그래서 이 파일의 잠금 순서는 그대로 둔다 — 바꿀 이유가 접수 쪽에서 오지 않는다.
 */
class JdbcExpiredDocumentPurge(
    private val jdbc: JdbcClient,
    private val credits: CreditAccountService,
    private val jobLocks: DocumentJobLocks,
) : ExpiredDocumentPurge {
    override fun purge(
        dryRun: Boolean,
        limit: Int,
    ): RetentionPurgeResult {
        val skippedLeased = countSkippedLeased()
        val ids = lockExpiredWithoutLease(limit)
        val conversions = countConversions(ids)
        if (!dryRun && ids.isNotEmpty()) {
            // **이용량 계정 갱신은 삭제 뒤다.** 문서 삭제 trigger 가 작업 표를 먼저 `FOR UPDATE`
            // 로 잠그고 나서 계정을 갱신하고(V29 `settle_action_guide_jobs_for_document`),
            // 작업 worker 도 작업 행 → 계정 순서다. 파기가 계정을 **먼저** 잡으면 그 둘과
            // 엇갈려 교착한다 — 파기는 계정을 든 채 trigger 의 작업 행 잠금을 기다리고,
            // 정산 중인 worker 는 그 계정을 기다린다. 교착에서 정산이 죽으면 이미 돈을 쓴
            // 호출의 결과가 사라지고, 파기가 죽으면 그 배치가 통째로 되돌아간다.
            //
            // 예약을 읽는 일(`lockPendingReservations`)은 문서가 아직 있어야 한다 —
            // `documents` 를 조인해 소유자·워크스페이스를 얻는다. 그래서 읽기는 삭제 앞,
            // 계정 갱신은 삭제 뒤로 나눈다. 해제 자체는 `workspace_credit_accounts` 와
            // `credit_transactions` 만 건드리고, 후자의 `document_id` 에는 일부러 FK 가
            // 없어(V15) 문서가 사라진 뒤에도 그대로 기록된다.
            //
            // 그리고 그 계정보다도 먼저, **배치의 활성 작업 행을 한 번에** 잠근다. 삭제 trigger 는
            // 행마다 돌아 문서 A 의 계정 갱신 뒤에 문서 B 의 작업 행을 잠그러 가므로, 한 문장
            // 안에서도 같은 엇갈림이 생긴다([DocumentJobLocks] 참고).
            jobLocks.lockActiveJobs(ids)
            val pending = lockPendingReservations(ids)
            deleteDocuments(ids)
            releaseReservations(pending)
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
     * [ids] 중 아직 끝나지 않은(`pending`/`processing`) 채로 크레딧이 예약된 변환을 잠그고
     * 읽는다 — `DocumentService.delete` 의 `lockPendingReservation` 과 같은 조건이지만
     * 배치라 `IN (:ids)` 로 한 번에 찾는다. 해제는 [releaseReservations] 가 삭제 뒤에 한다.
     */
    private fun lockPendingReservations(ids: List<UUID>): List<PurgePendingReservation> {
        val statement =
            ids.foldIndexed(jdbc.sql(pendingReservationsSql(ids.size))) { index, spec, id ->
                spec.param(idParam(index), id)
            }
        return statement.query { rs, _ -> rs.toPendingReservation() }.list()
    }

    /** [lockPendingReservations] 가 읽어 둔 예약을 되돌린다. 문서 행은 이미 없어도 된다. */
    private fun releaseReservations(reservations: List<PurgePendingReservation>) {
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
            creditsReserved = getBigDecimal("credits_reserved"),
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

    /** [lockPendingReservations] 가 읽어 온 예약 한 건 — 배치판 `PendingCreditsReservation`. */
    private class PurgePendingReservation(
        val conversionId: UUID,
        val documentId: UUID,
        val workspaceId: UUID,
        val ownerId: UUID,
        val creditsReserved: BigDecimal,
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

        /**
         * **제품 코드에서 `documents` 를 막는 잠금은 지금 이 문장 하나뿐이고, 그 사실에 기대는
         * 곳이 있다.** 세 가족의 접수(`…JobRepository.lockOwnedContext`)는 문서 행을
         * `FOR KEY SHARE … SKIP LOCKED` 로 집어, 건너뛴 행을 「지워지거나 파기되는 중」으로 읽고
         * 404 를 돌려준다. 그 해석이 맞는 것은 `FOR KEY SHARE` 와 충돌하는 잠금이 삭제와 이 배치
         * 말고 없기 때문이다.
         *
         * 그러니 `documents` 에 **막는 잠금**(`FOR UPDATE`·키 갱신)을 새로 들이면, 접수가 그것까지
         * 「삭제 중」으로 읽어 멀쩡한 문서에 404 를 준다. 그런 잠금을 추가할 때는 세
         * `lockOwnedContext` KDoc 을 함께 고친다.
         */
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
