package kr.easydoc.application.illustration.suggestion

import kr.easydoc.core.exceptions.ConfigurationException
import kr.easydoc.core.exceptions.ConflictException
import kr.easydoc.core.exceptions.InsufficientCreditsException
import kr.easydoc.core.exceptions.InvalidInputException
import kr.easydoc.core.exceptions.NotFoundException
import kr.easydoc.core.illustration.suggestion.IllustrationSuggestionJobStatus
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.ZoneOffset
import java.util.UUID

/** 접수·조회 계약(명세 §3·§6). 이용량 단가와 상한의 갈래를 이 계층에서 고정한다. */
class IllustrationSuggestionJobServiceTest {
    private val jobs = FakeSuggestionJobs()
    private val credits = RecordingSuggestionCredits()

    private fun service(
        enabled: Boolean = true,
        rate: BigDecimal? = BigDecimal("0.1"),
    ) = IllustrationSuggestionJobService(
        enabled,
        rate,
        jobs,
        credits,
        DirectSuggestionTransaction(),
        Clock.fixed(SUGGESTION_NOW, ZoneOffset.UTC),
    )

    @Test
    @DisplayName("원문 글자 수를 100자 단위로 올림해 단가를 곱한 만큼 예약한다")
    fun `원문 글자 수와 단가로 예약량을 정한다`() {
        // 1,500자 → 15단위 * 0.1 = 1.5
        val view = service().create(SUGGESTION_OWNER, SUGGESTION_CONVERSION, SUGGESTION_REQUEST, 3)

        assertThat(credits.reservedAmounts.single().amount).isEqualByComparingTo(BigDecimal("1.5"))
        assertThat(view.job.reservedCredits).isEqualByComparingTo(BigDecimal("1.5"))
        assertThat(view.job.status).isEqualTo(IllustrationSuggestionJobStatus.QUEUED)
    }

    @Test
    @DisplayName("같은 요청 식별자와 같은 입력은 기존 작업을 돌려주고 새 예약을 하지 않는다")
    fun `중복 요청은 같은 작업이다`() {
        val service = service()
        val first = service.create(SUGGESTION_OWNER, SUGGESTION_CONVERSION, SUGGESTION_REQUEST, 3)

        val second = service.create(SUGGESTION_OWNER, SUGGESTION_CONVERSION, SUGGESTION_REQUEST, 3)

        assertThat(second.job.jobId).isEqualTo(first.job.jobId)
        assertThat(credits.reserveCalls).isEqualTo(1)
    }

    @Test
    @DisplayName("같은 요청 식별자에 다른 본문 버전을 보내면 409다")
    fun `같은 요청 키에 다른 입력은 충돌이다`() {
        val service = service()
        service.create(SUGGESTION_OWNER, SUGGESTION_CONVERSION, SUGGESTION_REQUEST, 3)
        jobs.context = defaultSuggestionContext().copy(contentRevision = 4)

        assertThatThrownBy { service.create(SUGGESTION_OWNER, SUGGESTION_CONVERSION, SUGGESTION_REQUEST, 4) }
            .isInstanceOf(ConflictException::class.java)
            .hasMessage(SUGGESTION_REQUEST_ID_CONFLICT_MESSAGE)
    }

    @Test
    @DisplayName("본문 버전이 그 사이 바뀌었으면 409다 — 다른 요청 키여도 같다")
    fun `본문 버전이 다르면 충돌이다`() {
        assertThatThrownBy { service().create(SUGGESTION_OWNER, SUGGESTION_CONVERSION, UUID.randomUUID(), 2) }
            .isInstanceOf(ConflictException::class.java)
            .hasMessage(SUGGESTION_CONTENT_REVISION_CONFLICT_MESSAGE)
    }

    @Test
    @DisplayName("활성 작업이 있으면 409다")
    fun `활성 작업이 있으면 충돌이다`() {
        val service = service()
        service.create(SUGGESTION_OWNER, SUGGESTION_CONVERSION, SUGGESTION_REQUEST, 3)

        assertThatThrownBy { service.create(SUGGESTION_OWNER, SUGGESTION_CONVERSION, UUID.randomUUID(), 3) }
            .isInstanceOf(ConflictException::class.java)
            .hasMessage(SUGGESTION_ACTIVE_JOB_CONFLICT_MESSAGE)
    }

    @Test
    @DisplayName("provider 를 시작한 작업이 상한에 닿으면 429 예외다")
    fun `문서당 시도 상한을 넘기면 거절한다`() {
        repeat(MAX_SUGGESTION_PROVIDER_ATTEMPTS) { index ->
            val id = UUID.randomUUID()
            jobs.rows[id] =
                storedSuggestionJob(
                    status = IllustrationSuggestionJobStatus.SUCCEEDED,
                    requestId = UUID.randomUUID(),
                    executionId = UUID.randomUUID(),
                    providerStartedAt = SUGGESTION_NOW.plusSeconds(index.toLong()),
                    jobId = id,
                )
        }

        assertThatThrownBy { service().create(SUGGESTION_OWNER, SUGGESTION_CONVERSION, SUGGESTION_REQUEST, 3) }
            .isInstanceOf(IllustrationSuggestionAttemptLimitExceededException::class.java)
            .hasMessage(SUGGESTION_ATTEMPT_LIMIT_MESSAGE)
    }

    @Test
    @DisplayName("잔액이 모자라면 402 예외이고 작업 행을 만들지 않는다")
    fun `잔액이 모자라면 예약하지 않는다`() {
        credits.reservation = IllustrationSuggestionCreditReservation.Insufficient(BigDecimal("0.2"))

        assertThatThrownBy { service().create(SUGGESTION_OWNER, SUGGESTION_CONVERSION, SUGGESTION_REQUEST, 3) }
            .isInstanceOf(InsufficientCreditsException::class.java)
        assertThat(jobs.rows).isEmpty()
    }

    @Test
    @DisplayName("기능이 꺼져 있으면 접수·목록·단건이 모두 404다 — 존재를 알리지 않는다")
    fun `기능이 꺼져 있으면 404다`() {
        val disabled = service(enabled = false)

        assertThatThrownBy { disabled.create(SUGGESTION_OWNER, SUGGESTION_CONVERSION, SUGGESTION_REQUEST, 3) }
            .isInstanceOf(NotFoundException::class.java)
        assertThatThrownBy { disabled.list(SUGGESTION_OWNER, SUGGESTION_CONVERSION) }
            .isInstanceOf(NotFoundException::class.java)
        assertThatThrownBy { disabled.get(SUGGESTION_OWNER, SUGGESTION_CONVERSION, SUGGESTION_JOB) }
            .isInstanceOf(NotFoundException::class.java)
    }

    @Test
    @DisplayName("이용량 단가가 없으면 접수는 503이다 — 미설정은 0(무과금)과 다르다")
    fun `단가 미설정은 503이다`() {
        val unconfigured = service(rate = null)

        assertThatThrownBy { unconfigured.create(SUGGESTION_OWNER, SUGGESTION_CONVERSION, SUGGESTION_REQUEST, 3) }
            .isInstanceOf(ConfigurationException::class.java)
            .hasMessage(SUGGESTION_CREDITS_UNCONFIGURED_MESSAGE)
        // 조회는 열려 있고 필요 이용량만 「모른다」로 나온다 — capability 판정은 구성값이 진다.
        assertThat(unconfigured.list(SUGGESTION_OWNER, SUGGESTION_CONVERSION).requiredCredits).isNull()
        assertThat(credits.reserveCalls).isZero()
    }

    @Test
    @DisplayName("단가 0은 설정된 값이다 — 접수되고 예약량이 0이다(fake 모드)")
    fun `단가 0은 접수된다`() {
        val free = service(rate = BigDecimal.ZERO)

        val view = free.create(SUGGESTION_OWNER, SUGGESTION_CONVERSION, SUGGESTION_REQUEST, 3)

        assertThat(view.job.reservedCredits).isEqualByComparingTo(BigDecimal.ZERO)
    }

    @Test
    @DisplayName("유상 단가에서는 빈 원문도 최소 한 단위를 예약한다 — 무과금 작업과 섞이지 않는다")
    fun `유상 단가는 최소 한 단위를 부과한다`() {
        jobs.context = defaultSuggestionContext().copy(charCount = 0)

        val view =
            service(rate = BigDecimal("0.1"))
                .create(SUGGESTION_OWNER, SUGGESTION_CONVERSION, SUGGESTION_REQUEST, 3)

        assertThat(view.job.reservedCredits).isEqualByComparingTo(BigDecimal("0.1"))
    }

    @Test
    @DisplayName("fake 단가 0은 빈 원문에서도 0이다 — 최소 단위 규칙이 무과금을 뒤집지 않는다")
    fun `단가 0은 최소 단위를 만들지 않는다`() {
        jobs.context = defaultSuggestionContext().copy(charCount = 0)

        val view =
            service(rate = BigDecimal.ZERO)
                .create(SUGGESTION_OWNER, SUGGESTION_CONVERSION, SUGGESTION_REQUEST, 3)

        assertThat(view.job.reservedCredits).isEqualByComparingTo(BigDecimal.ZERO)
    }

    @Test
    @DisplayName("본문 버전이 0 이하면 422다")
    fun `본문 버전이 범위 밖이면 거절한다`() {
        assertThatThrownBy { service().create(SUGGESTION_OWNER, SUGGESTION_CONVERSION, SUGGESTION_REQUEST, 0) }
            .isInstanceOf(InvalidInputException::class.java)
    }

    @Test
    @DisplayName("변환이 완료되지 않았으면 409다")
    fun `완료되지 않은 변환은 충돌이다`() {
        jobs.context = defaultSuggestionContext().copy(completed = false)

        assertThatThrownBy { service().create(SUGGESTION_OWNER, SUGGESTION_CONVERSION, SUGGESTION_REQUEST, 3) }
            .isInstanceOf(ConflictException::class.java)
    }

    @Test
    @DisplayName("목록은 활성·최신 작업과 필요·가용 이용량을 함께 낸다")
    fun `목록이 두 이용량 값을 낸다`() {
        val service = service()
        val created = service.create(SUGGESTION_OWNER, SUGGESTION_CONVERSION, SUGGESTION_REQUEST, 3)

        val list = service.list(SUGGESTION_OWNER, SUGGESTION_CONVERSION)

        assertThat(list.activeJob?.jobId).isEqualTo(created.job.jobId)
        assertThat(list.latestJob?.jobId).isEqualTo(created.job.jobId)
        assertThat(list.requiredCredits).isEqualByComparingTo(BigDecimal("1.5"))
        assertThat(list.availableCredits).isEqualByComparingTo(credits.available)
    }

    @Test
    @DisplayName("남의 작업 id 는 404다 — 변환이 내 것이어도 작업 존재를 알리지 않는다")
    fun `모르는 작업 id 는 404다`() {
        assertThatThrownBy { service().get(SUGGESTION_OWNER, SUGGESTION_CONVERSION, UUID.randomUUID()) }
            .isInstanceOf(NotFoundException::class.java)
            .hasMessage(SUGGESTION_JOB_NOT_FOUND_MESSAGE)
    }

    @Test
    @DisplayName("변환을 찾지 못하면 404다 — 소유권 판정은 저장소가 같은 질의에서 한다")
    fun `변환이 없으면 404다`() {
        jobs.context = null

        assertThatThrownBy { service().list(SUGGESTION_OWNER, SUGGESTION_CONVERSION) }
            .isInstanceOf(NotFoundException::class.java)
    }
}
