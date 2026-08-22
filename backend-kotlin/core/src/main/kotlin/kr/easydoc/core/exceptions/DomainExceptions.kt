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

/** 저장된 암호문을 열지 못했다 — **원인도 자원 종류도 구분하지 않는 단 하나의 예외**. */
class DecryptionFailedException : StorageException(MESSAGE) {
    companion object {
        /** 응답 detail 로 그대로 나간다. 계약 `InternalError.examples.storage` 와 같아야 한다. */
        const val MESSAGE: String = "저장된 변환 결과를 읽을 수 없습니다"
    }
}

/** 서버 설정이 비어 있어 기능을 제공할 수 없다 (예: JWT 비밀키 미설정). */
class ConfigurationException(message: String) : EasyDocException(message)
