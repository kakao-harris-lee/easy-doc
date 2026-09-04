package kr.easydoc.core.exceptions

/** 도메인 예외 정의. `app/exceptions.py` 를 그대로 옮긴 것이다. */
open class EasyDocException(message: String) : RuntimeException(message)

/** LLM 호출 실패. */
open class LlmProviderException(message: String) : EasyDocException(message)

/** 출력 토큰 한도에서 응답이 잘렸다. */
class LlmTruncatedException(message: String) : LlmProviderException(message)

/** 응답이 비었거나 후처리 후 본문이 남지 않았다. */
class LlmEmptyResultException(message: String) : LlmProviderException(message)

/** 사용자 입력이 도메인 규칙을 위반했다 (형식·길이 등). */
class InvalidInputException(message: String) : EasyDocException(message)

/** 이미 가입된 이메일로 다시 가입을 시도했다. */
class EmailAlreadyRegisteredException(message: String) : EasyDocException(message)

/** 인증 실패. */
class InvalidCredentialsException(message: String) : EasyDocException(message)

/** 지원하지 않는 파일 형식을 업로드했다 (구버전 hwp 등). */
class UnsupportedFormatException(message: String) : EasyDocException(message)

/** 업로드 문서에서 텍스트를 뽑지 못했다 (손상·암호화·텍스트 없는 스캔본). */
class DocumentExtractionException(message: String) : EasyDocException(message)

/** 업로드 파일이 크기 상한을 넘었다. */
class UploadTooLargeException(message: String) : EasyDocException(message)

/** 요청한 리소스가 없다. */
class NotFoundException(message: String) : EasyDocException(message)

/** 리소스가 지금 상태에서는 받을 수 없는 요청이다 (예: 완료 전 변환에 검수 수정본 저장). */
class ConflictException(message: String) : EasyDocException(message)

/** 저장 계층에서 예상하지 못한 제약을 위반했다 — 입력 문제가 아니라 코드 버그다. */
open class StorageException(message: String) : EasyDocException(message)

/**
 * 저장된 암호문을 열지 못했다 — **원인을 구분하지 않는 단 하나의 예외**(무엇이 실패했는지가
 * 문구로 새지 않게 한다).
 *
 * **문구는 자원을 특정한다.** 이 예외가 HTTP 경계에서 관측되는 자리가 변환 결과 조회·검수
 * 저장·내보내기뿐이기 때문이다. 변환 결과가 **아닌** 봉투의 복호화 실패가 동기 응답으로 나가게
 * 되면 이 문구를 다시 판정해야 한다 — 그때 조용히 고치지 않는다(계약 개정 사항).
 */
class DecryptionFailedException : StorageException(MESSAGE) {
    companion object {
        /**
         * 응답 detail 로 그대로 나간다. 계약은 이 갈래의 값을 저장소에 **위임했고** 규범은 값이
         * 아니라 성질 넷이다 — 500 · 문자열 · **고정** · 최상위 키 `detail` 하나.
         * `InternalError.examples.storage` 는 그 값의 **예시**라, 값을 바꾸면 계약 위반이 아니라
         * **예시가 낡는다**(그때 예시를 함께 갱신한다).
         */
        const val MESSAGE: String = "저장된 변환 결과를 읽을 수 없습니다"
    }
}

/** 서버 설정이 비어 있어 기능을 제공할 수 없다 (예: JWT 비밀키 미설정). */
class ConfigurationException(message: String) : EasyDocException(message)

/**
 * OAuth state·nonce 가 유효하지 않다 — 만료·이미 사용·`provider`/`redirect_uri` 바인딩 불일치.
 * `EmailAlreadyRegisteredException`·`ConflictException`(409)과 달리 **자원 상태 충돌이 아니라
 * 요청 자체가 무효**라 409가 아니고, 길이·형식 같은 입력 규칙 위반도 아니라 422도 아니다 —
 * 계약이 이 갈래를 400으로 못박았다(`POST /auth/oauth/{provider}/callback`).
 */
class InvalidOAuthStateException(message: String) : EasyDocException(message)

/**
 * 이메일 인증 코드가 유효하지 않다 — 오답·만료·무효화(5회 오답)를 구분하지 않는
 * **단 하나의 예외**다. 사유를 가르면 공격자가 "이 코드가 존재는 했다"는 정보를 얻는다
 * (`AuthService.login`이 계정 존재 여부를 감추는 것과 같은 이유).
 */
class InvalidVerificationCodeException(message: String) : EasyDocException(message)

/**
 * 이메일 인증 없이는 할 수 없는 동작을 요청했다 — 오늘은 `POST /documents` 하나뿐이다.
 * 401(자격증명 자체가 없음)과 다르다: 토큰은 유효하고 신원도 확실하지만 **그 신원으로
 * 아직 할 수 없는 일**이라 403이다.
 */
class EmailNotVerifiedException(message: String) : EasyDocException(message)

/**
 * 재시도 쿨다운 안에서 다시 요청했다 — 재발송 60초 쿨다운이 오늘의 유일한 발생 자리다.
 * [retryAfterSeconds]는 계약이 요구하는 `Retry-After` 헤더 값이다(정수 초, 최소 1 —
 * 0을 보내면 클라이언트가 즉시 재시도를 반복해 쿨다운을 사실상 무력화한다).
 */
class RateLimitedException(
    message: String,
    val retryAfterSeconds: Long,
) : EasyDocException(message) {
    init {
        require(retryAfterSeconds >= 1) { "retryAfterSeconds 는 1 이상이어야 한다: $retryAfterSeconds" }
    }
}

/**
 * 동기로 부른 하위 시스템(예: 소셜 로그인 제공자)에 닿지 못했다 — 타임아웃·연결 실패·5xx.
 * `LlmProviderException`(비동기 워커 경로, HTTP 상태로 나가지 않음)과 달리 이 예외는
 * **요청·응답 안에서 동기로** 관측되므로 502로 나간다. 계약 `x-retired-responses`가
 * BadGateway를 폐기하며 "동기로 하위 시스템을 부르는 오퍼레이션이 새로 생기면 다시 세운다"고
 * 예고한 자리이며, `POST /auth/oauth/{provider}/callback`이 그 자리다.
 */
class ExternalServiceUnavailableException(message: String) : EasyDocException(message)
