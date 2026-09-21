package kr.easydoc.application.document

import kr.easydoc.core.actionguide.ActionGuideCandidate
import kr.easydoc.core.actionguide.ActionGuideItem
import kr.easydoc.core.actionguide.ActionGuideSection
import kr.easydoc.core.actionguide.ActionGuideSectionKind
import kr.easydoc.core.actionguide.ActionGuideSectionStatus
import kr.easydoc.core.actionguide.ActionGuideSourceAnchor
import kr.easydoc.core.crypto.EncryptedContent
import kr.easydoc.core.exceptions.InvalidInputException
import kr.easydoc.core.exceptions.StorageException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID

class ReviewHistoryCodecTest {
    @Test
    fun `스냅샷 평문 64KiB까지 저장하고 초과하면 전체를 missing으로 남긴다`() {
        val exact = "a".repeat(ReviewHistorySnapshotCodec.MAX_PLAINTEXT_BYTES - 10)
        val value = ReviewHistorySnapshotValue(null, exact, null)

        assertThat(ReviewHistorySnapshotCodec.decode(ReviewHistorySnapshotCodec.encode(value)!!)).isEqualTo(value)
        assertThat(ReviewHistorySnapshotCodec.encode(value.copy(contentText = exact + "a"))).isNull()
    }

    @Test
    fun `커서는 변환과 cutoff를 보존하고 다른 변환 커서를 거절한다`() {
        val conversionId = UUID.randomUUID()
        val cursor =
            ReviewHistoryCursor(
                conversionId = conversionId,
                cutoff = Instant.parse("2026-09-21T00:00:00Z"),
                createdAt = Instant.parse("2026-09-20T23:59:00.123456789Z"),
                eventId = UUID.randomUUID(),
            )

        assertThat(ReviewHistoryCursorCodec.decode(ReviewHistoryCursorCodec.encode(cursor), conversionId))
            .isEqualTo(cursor)
        assertThatThrownBy {
            ReviewHistoryCursorCodec.decode(ReviewHistoryCursorCodec.encode(cursor), UUID.randomUUID())
        }.isInstanceOf(InvalidInputException::class.java)
    }

    @Test
    fun `커서는 저장소 범위 밖 timestamp와 역순 또는 비정규 nanos를 거절한다`() {
        val conversionId = UUID.randomUUID()
        val cursor =
            ReviewHistoryCursor(
                conversionId = conversionId,
                cutoff = Instant.parse("2026-09-21T00:00:00Z"),
                createdAt = Instant.parse("2026-09-20T23:59:00.123456789Z"),
                eventId = UUID.randomUUID(),
            )
        val encoded = ReviewHistoryCursorCodec.encode(cursor)

        val extremeCutoff =
            rewrite(encoded) {
                position(19)
                putLong(Instant.MAX.epochSecond)
            }
        val reversed =
            rewrite(encoded) {
                position(31)
                putLong(cursor.cutoff.epochSecond + 1)
            }
        val nonCanonicalNanos =
            rewrite(encoded) {
                position(27)
                putInt(1_000_000_000)
            }

        listOf(extremeCutoff, reversed, nonCanonicalNanos).forEach { malformed ->
            assertThatThrownBy { ReviewHistoryCursorCodec.decode(malformed, conversionId) }
                .isInstanceOf(InvalidInputException::class.java)
        }
    }

    @Test
    fun `인증된 payload가 깨져도 client 422가 아닌 storage failure다`() {
        val cipher = FakeContentCipher(writeKeyVersion = 1)
        val snapshotId = UUID.randomUUID()

        assertThatThrownBy {
            ReviewHistorySnapshotCodec.decrypt(
                EncryptedContent(byteArrayOf(1, 2, 3), cipher.writeScheme, cipher.writeKeyVersion),
                snapshotId,
                cipher,
            )
        }.isInstanceOf(StorageException::class.java)
            .hasMessage(ReviewHistorySnapshotCodec.CORRUPT_SNAPSHOT_MESSAGE)
    }

    @Test
    fun `artifact JSON은 사용자 텍스트를 JSON 문자열로 escape하고 지시문으로 해석하지 않는다`() {
        val candidate =
            ActionGuideCandidate(
                schemaVersion = 1,
                sections =
                    listOf(
                        ActionGuideSection(
                            ActionGuideSectionKind.CONTACT,
                            ActionGuideSectionStatus.AVAILABLE,
                            listOf(
                                ActionGuideItem(
                                    text = "주소 \"확인\"\\n 다음",
                                    cautions = listOf("주의\n문장"),
                                    sourceAnchors = listOf(ActionGuideSourceAnchor(listOf(0), "원문\"")),
                                ),
                            ),
                        ),
                    ),
            )

        val json = ReviewHistoryArtifactJson.actionGuide(candidate)
        assertThat(json).contains("\\\"확인\\\"").contains("\\n")
        assertThat(json).startsWith("{\"schema_version\":1")
    }

    @Test
    fun `appender가 클라이언트 시간이 아닌 서버 시각과 owner actor를 쓰고 큰 body는 snapshot을 생략한다`() {
        val repository = RecordingHistoryRepository()
        val now = Instant.parse("2026-09-21T03:04:05Z")
        val appender =
            DefaultReviewHistoryAppender(
                enabled = true,
                repository = repository,
                cipher = FakeContentCipher(writeKeyVersion = 1),
                clock = Clock.fixed(now, ZoneOffset.UTC),
            )
        val owner = UUID.randomUUID()
        val conversion = UUID.randomUUID()

        appender.appendInvalidatedByEdit(
            ownerId = owner,
            conversionId = conversion,
            contentRevision = 2,
            contentText = "본문",
        )
        appender.appendInvalidatedByEdit(
            ownerId = owner,
            conversionId = conversion,
            contentRevision = 3,
            contentText = "가".repeat(ReviewHistorySnapshotCodec.MAX_PLAINTEXT_BYTES),
        )

        assertThat(repository.events).hasSize(2)
        assertThat(repository.events[0].first.actorUserId).isEqualTo(owner)
        assertThat(repository.events[0].first.createdAt).isEqualTo(now)
        assertThat(repository.events[0].first.contentRevision).isEqualTo(2)
        assertThat(repository.events[0].second).isNotNull
        assertThat(repository.events[1].second).isNull()
    }

    @Test
    fun `같은 본문 버전의 서로 다른 검수 산출물도 각각 snapshot을 가진다`() {
        val repository = RecordingHistoryRepository()
        val appender =
            DefaultReviewHistoryAppender(
                enabled = true,
                repository = repository,
                cipher = FakeContentCipher(writeKeyVersion = 1),
                clock = Clock.systemUTC(),
            )
        val owner = UUID.randomUUID()
        val conversion = UUID.randomUUID()
        appender.appendItemEvent(
            owner,
            conversion,
            1,
            UUID.randomUUID(),
            UUID.randomUUID(),
            1,
            ReviewHistoryEventType.ITEM_CONFIRMED,
            "본문",
            "{\"assessment\":1}",
        )
        appender.appendItemEvent(
            owner,
            conversion,
            1,
            UUID.randomUUID(),
            UUID.randomUUID(),
            1,
            ReviewHistoryEventType.ITEM_REOPENED,
            "본문",
            "{\"assessment\":2}",
        )

        assertThat(repository.events).hasSize(2)
        assertThat(repository.events.mapNotNull { it.second?.snapshotId }).doesNotHaveDuplicates()
    }

    private class RecordingHistoryRepository : ReviewHistoryRepository {
        val events = mutableListOf<Pair<ReviewHistoryEventToStore, ReviewHistorySnapshotToStore?>>()

        override fun append(
            ownerId: UUID,
            event: ReviewHistoryEventToStore,
            snapshot: ReviewHistorySnapshotToStore?,
        ) {
            events += event to snapshot
        }

        override fun pageOwned(
            ownerId: UUID,
            conversionId: UUID,
            cutoff: Instant,
            after: ReviewHistoryCursor?,
            limit: Int,
        ): List<StoredReviewHistoryEvent> = emptyList()

        override fun lockSnapshot(snapshotId: UUID): StoredReviewHistorySnapshot? = null

        override fun rewriteSnapshotEnvelope(
            expected: StoredReviewHistorySnapshot,
            payload: EncryptedContent,
        ): Boolean = false

        override fun snapshotIdsOlderThan(
            keyVersion: Int,
            after: UUID,
            limit: Int,
        ): List<UUID> = emptyList()
    }

    private fun rewrite(
        encoded: String,
        block: ByteBuffer.() -> Unit,
    ): String {
        val bytes = Base64.getUrlDecoder().decode(encoded)
        ByteBuffer.wrap(bytes).apply(block)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
