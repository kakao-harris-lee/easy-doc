package kr.easydoc.application.actionguide

import kr.easydoc.core.actionguide.GuideActionPresence
import kr.easydoc.core.actionguide.GuideAnalysisResult
import kr.easydoc.core.actionguide.GuideCoverageStatus
import kr.easydoc.core.actionguide.GuideSuitability
import kr.easydoc.core.actionguide.GuideUnitAssessment
import kr.easydoc.core.exceptions.ConflictException
import kr.easydoc.core.exceptions.NotFoundException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

class ActionGuideAnalysisServiceTest {
    private val owner = UUID.randomUUID()
    private val conversion = UUID.randomUUID()
    private val rows = Rows(owner, conversion)
    private var calls = 0
    private val analyzer =
        GuideAnalyzer { _, units ->
            calls++
            GuideAnalysisResult(
                GuideSuitability.UNCERTAIN,
                GuideActionPresence.UNCERTAIN,
                "검토 필요",
                emptyList(),
                emptyList(),
                units.map { GuideUnitAssessment(it.id, GuideCoverageStatus.NEEDS_REVIEW, emptyList()) },
                listOf("unreviewed"),
                false,
            )
        }
    private val service = ActionGuideAnalysisService(true, rows, analyzer, DirectTransaction())

    @Test
    fun `all source units are server fixed and current snapshot is reused with bound request aliases`() {
        val request = UUID.randomUUID()
        val first = service.create(owner, conversion, request, 1)
        val alias = UUID.randomUUID()
        val repeated = service.create(owner, conversion, alias, 1)
        assertThat(repeated.snapshot.analysisId).isEqualTo(first.snapshot.analysisId)
        assertThat(first.snapshot.sourceUnits.map { it.text }).containsExactly("소개", "", "문의")
        assertThat(first.snapshot.readingLevel).isEqualTo("grade_3_4")
        assertThat(calls).isEqualTo(1)
        rows.revision = 2
        assertThat(service.create(owner, conversion, request, 1).state).isEqualTo("stale")
        assertThat(service.get(owner, conversion, first.snapshot.analysisId).allowedModes).isEmpty()
        assertThatThrownBy { service.create(owner, conversion, alias, 2) }.isInstanceOf(ConflictException::class.java)
        assertThatThrownBy {
            service.create(owner, conversion, UUID.randomUUID(), 1)
        }.isInstanceOf(ConflictException::class.java)
    }

    @Test
    fun `disabled expired and wrong owner or document reads never analyze`() {
        val snapshot = service.create(owner, conversion, UUID.randomUUID(), 1).snapshot
        assertThatThrownBy {
            service.get(UUID.randomUUID(), conversion, snapshot.analysisId)
        }.isInstanceOf(NotFoundException::class.java)
        assertThatThrownBy {
            service.get(owner, UUID.randomUUID(), snapshot.analysisId)
        }.isInstanceOf(NotFoundException::class.java)
        rows.expired = true
        assertThatThrownBy {
            service.get(
                owner,
                conversion,
                snapshot.analysisId,
            )
        }.isInstanceOf(NotFoundException::class.java)
        val disabled = ActionGuideAnalysisService(false, rows, analyzer, DirectTransaction())
        assertThatThrownBy {
            disabled.create(owner, conversion, UUID.randomUUID(), 1)
        }.isInstanceOf(NotFoundException::class.java)
        assertThat(calls).isEqualTo(1)
    }

    private class Rows(
        private val owner: UUID,
        private val conversion: UUID,
    ) : GuideAnalysisRepository {
        var revision = 1L
        var expired = false
        private val snapshots = mutableMapOf<UUID, GuideAnalysisSnapshot>()
        private val requests = mutableMapOf<UUID, UUID>()

        override fun lockInput(
            ownerId: UUID,
            conversionId: UUID,
        ): GuideAnalysisInput? =
            if (ownerId == owner && conversionId == conversion &&
                !expired
            ) {
                GuideAnalysisInput(revision, "소개\n\n문의", "쉬운 소개", "grade_3_4", true)
            } else {
                null
            }

        override fun findRequest(
            ownerId: UUID,
            conversionId: UUID,
            requestId: UUID,
        ) = requests[requestId]?.let(snapshots::get)

        override fun findRevision(
            ownerId: UUID,
            conversionId: UUID,
            revision: Long,
        ) = snapshots.values.find {
            it.basedOnContentRevision ==
                revision
        }

        override fun find(
            ownerId: UUID,
            conversionId: UUID,
            analysisId: UUID,
        ) = snapshots[analysisId]

        override fun latest(
            ownerId: UUID,
            conversionId: UUID,
        ) = snapshots.values.maxByOrNull { it.basedOnContentRevision }

        override fun insert(
            ownerId: UUID,
            conversionId: UUID,
            snapshot: GuideAnalysisSnapshot,
        ) {
            snapshots[snapshot.analysisId] =
                snapshot
        }

        override fun bindRequest(
            ownerId: UUID,
            conversionId: UUID,
            requestId: UUID,
            analysisId: UUID,
        ) {
            requests[requestId] =
                analysisId
        }
    }
}
