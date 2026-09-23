package kr.easydoc.application.actionguide

import kr.easydoc.core.actionguide.ActionGuideJobStatus
import kr.easydoc.core.exceptions.ConflictException
import kr.easydoc.core.exceptions.NotFoundException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.ZoneOffset
import java.util.UUID

class ActionGuideJobServiceTest {
    @Test
    fun `같은 요청 키와 입력은 기존 작업을 반환하고 다시 예약하지 않는다`() {
        val world = World()
        world.jobs.rows[JOB] = storedJob()

        val result = world.service.create(OWNER, CONVERSION, REQUEST, 3, null)

        assertThat(result.job.jobId).isEqualTo(JOB)
        assertThat(world.credits.reserveCalls).isZero()
    }

    @Test
    fun `같은 요청 키의 다른 입력은 충돌이고 다시 예약하지 않는다`() {
        val world = World()
        world.jobs.rows[JOB] = storedJob()

        assertThatThrownBy { world.service.create(OWNER, CONVERSION, REQUEST, 4, null) }
            .isInstanceOf(ConflictException::class.java)
            .hasMessage(REQUEST_ID_CONFLICT_MESSAGE)
        assertThat(world.credits.reserveCalls).isZero()
    }

    @Test
    fun `신규 요청은 원문 글자 수 크레딧을 예약하고 queued 작업을 만든다`() {
        val world = World()

        val result = world.service.create(OWNER, CONVERSION, REQUEST, 3, null)

        assertThat(result.job.status.wireName).isEqualTo("queued")
        assertThat(result.job.reservedCredits).isEqualByComparingTo("1.5")
        assertThat(world.credits.reserveCalls).isEqualTo(1)
    }

    @Test
    fun `collection은 활성 작업을 latest로도 복구하고 필요량과 가용량을 준다`() {
        val world = World()
        world.jobs.rows[JOB] = storedJob()

        val result = world.service.list(OWNER, CONVERSION)

        assertThat(result.activeJob?.jobId).isEqualTo(JOB)
        assertThat(result.latestJob?.jobId).isEqualTo(JOB)
        assertThat(result.requiredCredits).isEqualByComparingTo("1.5")
        assertThat(result.availableCredits).isEqualByComparingTo("7")
    }

    @Test
    fun `feature OFF는 저장소를 보지 않고 404 계약이다`() {
        val world = World(enabled = false)
        world.jobs.context = null

        assertThatThrownBy { world.service.get(OWNER, CONVERSION, UUID.randomUUID()) }
            .isInstanceOf(NotFoundException::class.java)
    }

    @Test
    fun `expected guide revision 0은 계약상 유효하다`() {
        val world = World()
        world.jobs.context = defaultContext().copy(guideRevision = 0)

        val result = world.service.create(OWNER, CONVERSION, REQUEST, 3, 0)

        assertThat(result.job.jobId).isNotNull()
    }

    @Test
    fun `provider 호출이 시작된 작업이 상한만큼 쌓이면 새 요청은 시도 상한 초과다`() {
        val world = World()
        world.seedTerminalJobs(MAX_PROVIDER_STARTED_ATTEMPTS, providerStarted = true)

        assertThatThrownBy { world.service.create(OWNER, CONVERSION, UUID.randomUUID(), 3, null) }
            .isInstanceOf(ActionGuideAttemptLimitExceededException::class.java)
            .hasMessage(ATTEMPT_LIMIT_MESSAGE)
        assertThat(world.jobs.rows).hasSize(MAX_PROVIDER_STARTED_ATTEMPTS)
    }

    @Test
    fun `provider 호출을 시작하지 못하고 끝난 작업은 시도로 세지 않는다`() {
        val world = World()
        world.seedTerminalJobs(MAX_PROVIDER_STARTED_ATTEMPTS, providerStarted = false)

        val result = world.service.create(OWNER, CONVERSION, UUID.randomUUID(), 3, null)

        assertThat(result.job.status.wireName).isEqualTo("queued")
        assertThat(world.jobs.rows).hasSize(MAX_PROVIDER_STARTED_ATTEMPTS + 1)
    }

    private class World(enabled: Boolean = true) {
        val jobs = FakeActionGuideJobs()
        val credits = RecordingActionGuideCredits()
        val service =
            ActionGuideJobService(
                enabled,
                jobs,
                credits,
                DirectTransaction(),
                Clock.fixed(NOW, ZoneOffset.UTC),
            )

        /** 이미 끝난 과거 시도를 쌓는다. [providerStarted] 가 시도로 셀지 여부를 가른다. */
        fun seedTerminalJobs(
            count: Int,
            providerStarted: Boolean,
        ) {
            repeat(count) {
                val jobId = UUID.randomUUID()
                jobs.rows[jobId] =
                    storedJob(
                        status = ActionGuideJobStatus.SUCCEEDED,
                        requestId = UUID.randomUUID(),
                        executionId = if (providerStarted) UUID.randomUUID() else null,
                        providerStartedAt = if (providerStarted) NOW else null,
                        jobId = jobId,
                    )
            }
        }
    }
}
