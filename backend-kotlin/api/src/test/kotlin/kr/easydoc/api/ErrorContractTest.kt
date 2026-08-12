package kr.easydoc.api

import kr.easydoc.api.error.GlobalExceptionHandler
import kr.easydoc.core.exceptions.ConfigurationException
import kr.easydoc.core.exceptions.ConflictException
import kr.easydoc.core.exceptions.DocumentExtractionException
import kr.easydoc.core.exceptions.EasyDocException
import kr.easydoc.core.exceptions.EmailAlreadyRegisteredException
import kr.easydoc.core.exceptions.InvalidCredentialsException
import kr.easydoc.core.exceptions.InvalidInputException
import kr.easydoc.core.exceptions.LlmTruncatedException
import kr.easydoc.core.exceptions.NotFoundException
import kr.easydoc.core.exceptions.QueueUnavailableException
import kr.easydoc.core.exceptions.StorageException
import kr.easydoc.core.exceptions.UnsupportedFormatException
import kr.easydoc.core.exceptions.UploadTooLargeException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType

/**
 * 오류 응답 계약. `app/api/errors.py` 의 `_MAPPINGS` 를 그대로 옮겼는지 확인한다.
 *
 * 핸들러를 직접 호출한다 — Phase 1에는 도메인 예외를 던지는 엔드포인트가 하나도 없어
 * HTTP 경계에서 재현할 대상이 없기 때문이다. HTTP 경계 검증은 엔드포인트가 생기는
 * Phase 3의 contract test 가 맡는다(`00_contract-keeper_test-plan.md`).
 */
class ErrorContractTest {
    private val handler = GlobalExceptionHandler()

    @Test
    @DisplayName("입력 오류·지원하지 않는 형식·추출 실패 → 422")
    fun `입력 계열 예외는 422 다`() {
        assertStatus(InvalidInputException("제목이 너무 깁니다"), HttpStatus.UNPROCESSABLE_ENTITY)
        assertStatus(UnsupportedFormatException("hwp 는 지원하지 않습니다"), HttpStatus.UNPROCESSABLE_ENTITY)
        assertStatus(DocumentExtractionException("pdf 파일을 읽을 수 없습니다"), HttpStatus.UNPROCESSABLE_ENTITY)
    }

    @Test
    @DisplayName("업로드 크기 초과 → 413")
    fun `크기 초과는 413 이다`() {
        // 계획 §2.2에 없던 상태 코드다. contract-keeper 3자 대조에서 실재가 확인됐다.
        assertStatus(UploadTooLargeException("파일이 너무 큽니다"), HttpStatus.PAYLOAD_TOO_LARGE)
    }

    @Test
    @DisplayName("이메일 중복·상태 충돌 → 409")
    fun `충돌 계열 예외는 409 다`() {
        assertStatus(EmailAlreadyRegisteredException("이미 가입된 이메일입니다"), HttpStatus.CONFLICT)
        assertStatus(ConflictException("아직 완료되지 않았습니다"), HttpStatus.CONFLICT)
    }

    @Test
    @DisplayName("자격증명 오류 → 401 + WWW-Authenticate: Bearer")
    fun `자격증명 오류는 401 이고 표준 헤더가 붙는다`() {
        val response = handler.handleDomainException(InvalidCredentialsException("인증에 실패했습니다"))

        assertThat(response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
        assertThat(response.headers.getFirst(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo("Bearer")
    }

    @Test
    @DisplayName("소유권/부재 → 404")
    fun `부재는 404 다`() {
        assertStatus(NotFoundException("문서를 찾을 수 없습니다"), HttpStatus.NOT_FOUND)
    }

    @Test
    @DisplayName("LLM·큐 장애 → 502")
    fun `하위 시스템 장애는 502 다`() {
        assertStatus(QueueUnavailableException("작업을 등록하지 못했습니다"), HttpStatus.BAD_GATEWAY)
        // 하위 예외도 상위 매핑을 탄다 — Python 의 MRO 탐색과 같은 결과여야 한다.
        assertStatus(LlmTruncatedException("응답이 잘렸습니다"), HttpStatus.BAD_GATEWAY)
    }

    @Test
    @DisplayName("설정 오류 → 503, 저장소 오류 → 500")
    fun `설정과 저장소 오류`() {
        assertStatus(ConfigurationException("JWT 비밀키가 설정되지 않았습니다"), HttpStatus.SERVICE_UNAVAILABLE)
        assertStatus(StorageException("저장에 실패했습니다"), HttpStatus.INTERNAL_SERVER_ERROR)
    }

    @Test
    @DisplayName("매핑되지 않은 도메인 예외 → 500 + 고정 문자열")
    fun `매핑 누락은 고정 문자열로 500 이다`() {
        // 예외 메시지를 그대로 노출하지 않는다 — 무엇이 담길지 이 지점에서는 알 수 없다.
        val response = handler.handleDomainException(EasyDocException("입력값 홍길동 이 섞인 메시지"))

        assertThat(response.statusCode).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR)
        assertThat(response.body?.detail).isEqualTo(GlobalExceptionHandler.UNMAPPED_DOMAIN_MESSAGE)
        assertThat(response.body?.detail).doesNotContain("홍길동")
    }

    @Test
    @DisplayName("도메인 밖 예외 → 500 + 고정 문자열")
    fun `예상하지 못한 예외는 고정 문자열로 500 이다`() {
        val response = handler.handleUnexpected(IllegalStateException("주민등록번호 900101-1234567"))

        assertThat(response.statusCode).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR)
        assertThat(response.body?.detail).isEqualTo(GlobalExceptionHandler.UNEXPECTED_MESSAGE)
        assertThat(response.body?.detail).doesNotContain("900101")
    }

    @Test
    @DisplayName("본문은 application/json 이고 detail 키 하나뿐이다")
    fun `ProblemDetail 이 아니라 detail 한 키다`() {
        val response = handler.handleDomainException(NotFoundException("문서를 찾을 수 없습니다"))

        // application/problem+json 이면 계약 위반이다.
        assertThat(response.headers.contentType).isEqualTo(MediaType.APPLICATION_JSON)
        // ErrorResponse 는 detail 필드 하나만 갖는다 — type·title·instance 가 섞이지 않는다.
        val body = response.body ?: error("오류 응답에 본문이 없다")
        assertThat(body::class.java.declaredFields.map { it.name }).containsExactly("detail")
        assertThat(body.detail).isEqualTo("문서를 찾을 수 없습니다")
    }

    private fun assertStatus(
        exception: EasyDocException,
        expected: HttpStatus,
    ) {
        val response = handler.handleDomainException(exception)
        assertThat(response.statusCode)
            .withFailMessage(
                "%s 는 %s 여야 한다 (app/api/errors.py 의 _MAPPINGS)",
                exception::class.java.simpleName,
                expected,
            ).isEqualTo(expected)
        // detail 은 도메인 예외가 만든 메시지 그대로다.
        assertThat(response.body?.detail).isEqualTo(exception.message)
    }
}
