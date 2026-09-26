package kr.easydoc.infrastructure.actionguide

import kr.easydoc.application.actionguide.GuideAnalysisSnapshot
import kr.easydoc.core.actionguide.GuideActionPresence
import kr.easydoc.core.actionguide.GuideAnalysisResult
import kr.easydoc.core.actionguide.GuideCoverageStatus
import kr.easydoc.core.actionguide.GuideSourceUnit
import kr.easydoc.core.actionguide.GuideSuitability
import kr.easydoc.core.actionguide.GuideUnitAssessment
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class GuideAnalysisSnapshotCodecTest {
    @Test
    fun `private snapshot round trip preserves source denominator saved edits level and instant`() {
        val snapshot =
            GuideAnalysisSnapshot(
                UUID.randomUUID(),
                3,
                1,
                listOf(GuideSourceUnit(0, "비밀 원문"), GuideSourceUnit(1, "")),
                "사용자 편집",
                "grade_3_4",
                GuideAnalysisResult(
                    GuideSuitability.UNCERTAIN,
                    GuideActionPresence.UNCERTAIN,
                    "검토 필요",
                    emptyList(),
                    emptyList(),
                    listOf(
                        GuideUnitAssessment(0, GuideCoverageStatus.NEEDS_REVIEW, emptyList()),
                        GuideUnitAssessment(1, GuideCoverageStatus.CONTEXT, emptyList()),
                    ),
                    listOf("unreviewed"),
                    false,
                ),
                Instant.now(),
            )
        assertThat(GuideAnalysisSnapshotCodec.decode(GuideAnalysisSnapshotCodec.encode(snapshot))).isEqualTo(snapshot)
        assertThat(snapshot.toString()).doesNotContain("비밀", "사용자 편집")
    }
}
