package kr.easydoc.core.exceptions

/**
 * 도메인 예외 정의. `app/exceptions.py` 를 그대로 옮긴 것이다.
 *
 * 계층 구조를 유지한다 — `GlobalExceptionHandler` 의 매핑이 상위 예외 하나에 걸리면 하위
 * 예외가 같은 응답을 받는 것에 의존하기 때문이다(예: `DecryptionFailedException` 이
 * `StorageException` 매핑을 타고 500 이 된다).
 *
 * **매핑 표에 없는 예외는 500 `unmapped_domain` 이다.** `LlmProviderException` 계열이
 * 그렇다 — 계약 v1.3.0 이 502 를 폐기해(`x-retired-responses`) 매핑이 없어졌고, LLM 실패는
 * HTTP 상태가 아니라 `ConversionResponse.failure_code` 로 나간다(워커의 일이다).
 * **다시 매핑을 세우지 마라** — 502 를 선언하는 오퍼레이션이 계약에 0개다.
 *
 * **의도적으로 옮기지 않은 것**: `GoldenCollectionError`·`WelfareApiError`.
 * 둘은 운영자용 오프라인 스크립트(`scripts/collect_golden.py`, `app/easyread/bokjiro.py`)
 * 전용이고 HTTP 매핑 대상이 아니다. §9-1 결정으로 그 도구들은 Python 독립 검증 oracle로
 * 존치하므로 Kotlin 런타임에 대응물이 필요 없다.
 *
 * 메시지 규약: 예외 메시지에 **입력값을 넣지 않는다**. 이 규약이 `app/api/errors.py` 가
 * `str(exc)` 를 그대로 응답 detail 에 담아도 안전한 근거이고, 로그에 트레이스백을
 * 남겨도 되는 근거다.
 */
open class EasyDocException(message: String) : RuntimeException(message)

/** LLM 호출 실패. */
open class LlmProviderException(message: String) : EasyDocException(message)

/**
 * 출력 토큰 한도에서 응답이 잘렸다.
 *
 * `LlmProviderException` 하위로 두어 기존 호출 계약(단일 catch)을 유지하면서,
 * 벤치마크·평가 리포트가 실패 유형을 구분할 수 있게 한다.
 */
class LlmTruncatedException(message: String) : LlmProviderException(message)

/** 응답이 비었거나 후처리 후 본문이 남지 않았다. */
class LlmEmptyResultException(message: String) : LlmProviderException(message)

/** 사용자 입력이 도메인 규칙을 위반했다 (형식·길이 등). */
class InvalidInputException(message: String) : EasyDocException(message)

/** 이미 가입된 이메일로 다시 가입을 시도했다. */
class EmailAlreadyRegisteredException(message: String) : EasyDocException(message)

/**
 * 인증 실패.
 *
 * 이메일 부재와 비밀번호 불일치, 토큰 만료와 위조를 구분하지 않는다 — 어느 쪽인지
 * 알려주면 가입 여부가 새어 나가 계정 열거(enumeration) 공격의 단서가 된다.
 */
class InvalidCredentialsException(message: String) : EasyDocException(message)

/** 지원하지 않는 파일 형식을 업로드했다 (구버전 hwp 등). */
class UnsupportedFormatException(message: String) : EasyDocException(message)

/**
 * 업로드 문서에서 텍스트를 뽑지 못했다 (손상·암호화·텍스트 없는 스캔본).
 *
 * 메시지에는 형식명과 안내만 담는다 — 파일명·본문 조각은 개인정보이므로 금지
 * (`app/ingest/extractors.py` 참고).
 */
class DocumentExtractionException(message: String) : EasyDocException(message)

/**
 * 업로드 파일이 크기 상한을 넘었다.
 *
 * 형식·내용 문제(422)와 구분한다 — 사용자가 취할 조치가 "파일을 나눠 올리기"로
 * 다르기 때문이다.
 */
class UploadTooLargeException(message: String) : EasyDocException(message)

/** 요청한 리소스가 없다. */
class NotFoundException(message: String) : EasyDocException(message)

/**
 * 리소스가 지금 상태에서는 받을 수 없는 요청이다 (예: 완료 전 변환에 검수 수정본 저장).
 *
 * `NotFoundException` 과 가르는 기준은 "존재를 알려도 되는가"다 — 소유자 확인을 이미
 * 통과한 뒤에 상태 때문에 거절하는 자리이므로, 있다는 사실을 숨길 이유가 없고 사용자가
 * 취할 조치도 다르다(기다렸다가 다시 시도). 409로 매핑한다.
 */
class ConflictException(message: String) : EasyDocException(message)

/**
 * 저장 계층에서 예상하지 못한 제약을 위반했다 — 입력 문제가 아니라 코드 버그다.
 *
 * 5xx로 매핑한다. 4xx로 감싸면 서버 버그가 "사용자가 뭘 잘못했다"로 둔갑해 조용히 묻힌다.
 * 원본 DB 예외를 그대로 올리지 않는다 — PostgreSQL이 제약 위반 DETAIL에 실패한 행 전체를
 * 담기 때문이다(`app/repositories/users.py` 참고).
 */
open class StorageException(message: String) : EasyDocException(message)

/**
 * 저장된 암호문을 열지 못했다 — **실패 원인을 구분하지 않는 단 하나의 예외**.
 *
 * ## 왜 갈래를 만들지 않는가 (`migration-safety-gate` I-7 검증 3)
 *
 * 태그 불일치·키 세대 부재·길이 부족·형식 오류·associated data 불일치가 전부 이 예외
 * 하나다. 원인이 응답·로그·지연 어느 축으로든 구분돼 나가면 그것이 **복호화 oracle** 이고,
 * 공격자는 바이트를 조금씩 바꿔 가며 그 신호만으로 암호를 푼다. 그래서
 *
 * - 메시지를 **고정 상수 하나**로 둔다(호출자가 상황별 문구를 넣지 못하게 인자를 받지 않는다),
 * - **원인 예외를 잇지 않는다** — `GeneralSecurityException` 을 `cause` 로 달면 스택
 *   트레이스에 어느 단계에서 깨졌는지가 그대로 남고, 그것이 로그로 나가면 같은 oracle 이다.
 *
 * ## 왜 [StorageException] 하위인가
 *
 * 사용자가 고칠 수 있는 입력 문제가 아니라 **저장된 데이터가 열리지 않는 상태**다.
 * `app/privacy/crypto.py` 도 `InvalidToken` 을 `StorageError` 로 올렸고, HTTP 매핑은
 * 500 이다(`app/api/errors.py`). 4xx 로 감싸면 서버 사고가 "사용자가 뭘 잘못했다"로
 * 둔갑해 조용히 묻힌다.
 */
class DecryptionFailedException : StorageException(MESSAGE) {
    companion object {
        /**
         * 응답 detail 로 그대로 나가는 문구. 어떤 실패든 **같은 문자열**이어야 한다.
         *
         * 입력값·파일명·바이트 수를 넣지 않는다 — 예외 메시지에 입력을 넣지 않는다는
         * 이 파일의 규약이자, 여기서는 oracle 금지 조건이기도 하다.
         */
        const val MESSAGE: String = "저장된 문서를 읽을 수 없습니다"
    }
}

/**
 * 서버 설정이 비어 있어 기능을 제공할 수 없다 (예: JWT 비밀키 미설정).
 *
 * 사용자 잘못이 아니라 운영 설정 문제이므로 5xx로 매핑한다.
 */
class ConfigurationException(message: String) : EasyDocException(message)
