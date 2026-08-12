package kr.easydoc.core.exceptions

/**
 * 도메인 예외 정의. `app/exceptions.py` 를 그대로 옮긴 것이다.
 *
 * 계층 구조를 Python 그대로 유지한다 — `app/api/errors.py` 의 매핑이 상위 예외 하나에
 * 걸리면 하위 예외가 같은 응답을 받는 것에 의존하기 때문이다(예: `LLMTruncatedError` 가
 * `LLMProviderError` 매핑을 타고 502가 된다).
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

/**
 * 비동기 작업 큐에 작업을 등록하지 못했다.
 *
 * 우리 잘못도 사용자 잘못도 아닌 하위 시스템 장애이므로 502로 매핑한다.
 */
class QueueUnavailableException(message: String) : EasyDocException(message)

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
class StorageException(message: String) : EasyDocException(message)

/**
 * 서버 설정이 비어 있어 기능을 제공할 수 없다 (예: JWT 비밀키 미설정).
 *
 * 사용자 잘못이 아니라 운영 설정 문제이므로 5xx로 매핑한다.
 */
class ConfigurationException(message: String) : EasyDocException(message)
