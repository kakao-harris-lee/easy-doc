package kr.easydoc.application.document

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.UUID

/** 보존 만료 파기 유스케이스 — Spring 도 DB 도 없이 대역으로 돈다. */
class RetentionPurgeServiceTest {
    @Test
    @DisplayName("꺼져 있으면 저장소를 부르지 않는다")
    fun `비활성이면 저장소를 건너뛴다`() {
        val world = World(enabled = false)

        val result = world.purge.run()

        assertThat(result.enabled).isFalse()
        assertThat(world.store.calls).isZero()
        assertThat(world.observer.seen).containsExactly(result)
        assertThat(world.transaction.started).isZero()
    }

    @Test
    @DisplayName("dry-run 이면 저장소에 dryRun=true 로 넘기고 트랜잭션 안에서 돈다")
    fun `dry-run 은 미리보기만 한다`() {
        val world = World(dryRun = true)

        val result = world.purge.run()

        assertThat(result.dryRun).isTrue()
        assertThat(world.store.dryRuns).containsExactly(true)
        assertThat(world.transaction.committed).isEqualTo(1)
        assertThat(
            world.observer.seen
                .single()
                .documentIds,
        ).isEqualTo(result.documentIds)
    }

    @Test
    @DisplayName("활성 리스 건수는 결과로 남고, 감사 이벤트 문자열에 본문이 없다")
    fun `감사 이벤트에 본문이 없다`() {
        val body = "주민등록번호 900101-1234567 이 본문에 있다"
        val world = World()
        world.store.next =
            RetentionPurgeResult(
                dryRun = false,
                enabled = true,
                purgedDocuments = 1,
                purgedConversions = 1,
                skippedLeased = 2,
                documentIds = listOf(DOCUMENT),
            )

        val result = world.purge.run()

        assertThat(result.skippedLeased).isEqualTo(2)
        assertThat(result.toString()).doesNotContain(body)
        assertThat(
            world.observer.seen
                .single()
                .toString(),
        ).doesNotContain(body)
        assertThat(result.documentIds).containsExactly(DOCUMENT)
        assertThat(world.transaction.depth).isZero()
    }

    @Test
    @DisplayName("배치 크기 정책이 저장소 한도에 그대로 실린다")
    fun `배치 크기를 넘긴다`() {
        val world = World(batchSize = BATCH)

        world.purge.run()

        assertThat(world.store.limits).containsExactly(BATCH)
    }

    @Test
    @DisplayName("실제 삭제는 짧은 배치가 나올 때까지 트랜잭션마다 반복한다")
    fun `만료량이 배치를 넘으면 끝까지 지운다`() {
        val world = World(batchSize = BATCH)
        val first = UUID.fromString("00000000-0000-4000-8000-0000000000a1")
        val second = UUID.fromString("00000000-0000-4000-8000-0000000000a2")
        val third = UUID.fromString("00000000-0000-4000-8000-0000000000a3")
        world.store.enqueue(
            RetentionPurgeResult(
                dryRun = false,
                enabled = true,
                purgedDocuments = BATCH,
                purgedConversions = BATCH,
                skippedLeased = 1,
                documentIds = listOf(first, second),
            ),
            RetentionPurgeResult(
                dryRun = false,
                enabled = true,
                purgedDocuments = 1,
                purgedConversions = 1,
                skippedLeased = 1,
                documentIds = listOf(third),
            ),
        )

        val result = world.purge.run()

        assertThat(world.store.calls).isEqualTo(2)
        assertThat(world.transaction.committed).isEqualTo(2)
        assertThat(result.purgedDocuments).isEqualTo(3)
        assertThat(result.purgedConversions).isEqualTo(3)
        assertThat(result.skippedLeased).isEqualTo(1)
        assertThat(result.documentIds).containsExactly(first, second, third)
        assertThat(world.observer.seen).containsExactly(result)
    }

    @Test
    @DisplayName("dry-run 은 한 배치만 미리보고 반복하지 않는다")
    fun `dry-run 은 한 배치만 본다`() {
        val world = World(dryRun = true, batchSize = BATCH)
        world.store.enqueue(
            RetentionPurgeResult(
                dryRun = true,
                enabled = true,
                purgedDocuments = BATCH,
                purgedConversions = BATCH,
                skippedLeased = 0,
                documentIds = listOf(DOCUMENT, UUID.fromString("00000000-0000-4000-8000-0000000000d2")),
            ),
            RetentionPurgeResult(
                dryRun = true,
                enabled = true,
                purgedDocuments = 1,
                purgedConversions = 1,
                skippedLeased = 0,
                documentIds = listOf(UUID.fromString("00000000-0000-4000-8000-0000000000d3")),
            ),
        )

        val result = world.purge.run()

        assertThat(world.store.calls).isEqualTo(1)
        assertThat(world.transaction.committed).isEqualTo(1)
        assertThat(result.purgedDocuments).isEqualTo(BATCH)
    }

    private class World(
        enabled: Boolean = true,
        dryRun: Boolean = false,
        batchSize: Int = DEFAULT_BATCH,
    ) {
        val transaction = RecordingTransactionRunner()
        val store = FakeExpiredDocumentPurge()
        val observer = RecordingObserver()
        val purge =
            PurgeExpiredDocuments(
                store = store,
                transaction = transaction,
                observer = observer,
                policy = RetentionPurgePolicy(enabled = enabled, dryRun = dryRun, batchSize = batchSize),
            )
    }

    private class FakeExpiredDocumentPurge : ExpiredDocumentPurge {
        var calls: Int = 0
            private set
        val dryRuns = mutableListOf<Boolean>()
        val limits = mutableListOf<Int>()
        private val queued = ArrayDeque<RetentionPurgeResult>()
        var next: RetentionPurgeResult?
            get() = queued.firstOrNull()
            set(value) {
                queued.clear()
                if (value != null) {
                    queued.addLast(value)
                }
            }

        fun enqueue(vararg results: RetentionPurgeResult) {
            queued.clear()
            queued.addAll(results)
        }

        override fun purge(
            dryRun: Boolean,
            limit: Int,
        ): RetentionPurgeResult {
            calls++
            dryRuns += dryRun
            limits += limit
            return queued.removeFirstOrNull()
                ?: RetentionPurgeResult(
                    dryRun = dryRun,
                    enabled = true,
                    purgedDocuments = 0,
                    purgedConversions = 0,
                    skippedLeased = 0,
                    documentIds = emptyList(),
                )
        }
    }

    private class RecordingObserver : RetentionPurgeObserver {
        val seen = mutableListOf<RetentionPurgeResult>()

        override fun record(result: RetentionPurgeResult) {
            seen += result
        }
    }

    private companion object {
        val DOCUMENT: UUID = UUID.fromString("00000000-0000-4000-8000-0000000000d1")
        const val BATCH: Int = 2
        const val DEFAULT_BATCH: Int = 100
    }
}
