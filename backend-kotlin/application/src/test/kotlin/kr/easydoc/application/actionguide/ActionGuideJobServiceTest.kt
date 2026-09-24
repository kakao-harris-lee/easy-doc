package kr.easydoc.application.actionguide

import kr.easydoc.core.actionguide.ActionGuideJobStatus
import kr.easydoc.core.exceptions.ConflictException
import kr.easydoc.core.exceptions.InsufficientCreditsException
import kr.easydoc.core.exceptions.NotFoundException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal
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
    @DisplayName("이미 진행 중인 작업이 있으면 409이고 이용량을 예약하지 않는다")
    fun `활성 작업이 있으면 예약하지 않는다`() {
        val world = World()
        // 같은 계정의 진행 중 작업 — 요청 키는 다르므로 멱등 반환이 아니라 활성 충돌 갈래다.
        world.jobs.rows[JOB] = storedJob(requestId = UUID.randomUUID())

        assertThatThrownBy { world.service.create(OWNER, CONVERSION, REQUEST, 3, null) }
            .isInstanceOf(ConflictException::class.java)
            .hasMessage(ACTIVE_JOB_CONFLICT_MESSAGE)
        assertThat(world.credits.reserveCalls).isZero()
        assertThat(world.jobs.rows).hasSize(1)
    }

    @Test
    @DisplayName("잠금 밖 경쟁이 같은 요청 키를 먼저 저장하면 409이고 이용량을 예약하지 않는다")
    fun `같은 요청 키 경쟁에 지면 예약하지 않는다`() {
        val world = World()
        world.jobs.beforeInsert = {
            world.jobs.rows[UUID.randomUUID()] = storedJob(jobId = UUID.randomUUID())
        }

        assertThatThrownBy { world.service.create(OWNER, CONVERSION, REQUEST, 3, null) }
            .isInstanceOf(ConflictException::class.java)
            .hasMessage(REQUEST_ID_CONFLICT_MESSAGE)
        assertThat(world.credits.reserveCalls).isZero()
        assertThat(world.jobs.rows).isEmpty()
    }

    @Test
    @DisplayName("잔액이 모자라면 402 예외이고 작업 행이 롤백돼 남지 않는다")
    fun `잔액이 모자라면 작업을 남기지 않는다`() {
        val world = World()
        world.credits.reservation = ActionGuideCreditReservation.Insufficient(BigDecimal("0.2"))

        assertThatThrownBy { world.service.create(OWNER, CONVERSION, REQUEST, 3, null) }
            .isInstanceOf(InsufficientCreditsException::class.java)
            .hasMessage(INSUFFICIENT_ACTION_GUIDE_CREDITS_MESSAGE)
        assertThat(world.jobs.rows).isEmpty()
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
                DirectTransaction(jobs),
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
