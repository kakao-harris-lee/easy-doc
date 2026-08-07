"""도메인 예외 정의. 라우터 레벨에서 HTTP 응답으로 변환한다."""


class EasyDocError(Exception):
    """서비스 공통 최상위 예외."""


class LLMProviderError(EasyDocError):
    """LLM 호출 실패."""


class LLMTruncatedError(LLMProviderError):
    """출력 토큰 한도에서 응답이 잘렸다.

    LLMProviderError 하위로 두어 기존 호출 계약(단일 except)을 유지하면서,
    벤치마크·평가 리포트가 실패 유형을 구분할 수 있게 한다.
    """


class LLMEmptyResultError(LLMProviderError):
    """응답이 비었거나 후처리 후 본문이 남지 않았다."""


class InvalidInputError(EasyDocError):
    """사용자 입력이 도메인 규칙을 위반했다 (형식·길이 등)."""


class EmailAlreadyRegisteredError(EasyDocError):
    """이미 가입된 이메일로 다시 가입을 시도했다."""


class InvalidCredentialsError(EasyDocError):
    """인증 실패.

    이메일 부재와 비밀번호 불일치, 토큰 만료와 위조를 구분하지 않는다 — 어느 쪽인지
    알려주면 가입 여부가 새어 나가 계정 열거(enumeration) 공격의 단서가 된다.
    """


class UnsupportedFormatError(EasyDocError):
    """지원하지 않는 파일 형식을 업로드했다 (구버전 hwp 등)."""


class DocumentExtractionError(EasyDocError):
    """업로드 문서에서 텍스트를 뽑지 못했다 (손상·암호화·텍스트 없는 스캔본).

    메시지에는 형식명과 안내만 담는다 — 파일명·본문 조각은 개인정보이므로 금지
    (app/ingest/extractors.py 참고).
    """


class UploadTooLargeError(EasyDocError):
    """업로드 파일이 크기 상한을 넘었다.

    형식·내용 문제(422)와 구분한다 — 사용자가 취할 조치가 "파일을 나눠 올리기"로
    다르기 때문이다. 상한값은 app/ingest/extractors.py의 MAX_UPLOAD_BYTES.
    """


class QueueUnavailableError(EasyDocError):
    """비동기 작업 큐에 작업을 등록하지 못했다 (Redis 장애 등).

    우리 잘못도 사용자 잘못도 아닌 하위 시스템 장애이므로 502로 매핑한다.
    """


class NotFoundError(EasyDocError):
    """요청한 리소스가 없다."""


class ConflictError(EasyDocError):
    """리소스가 지금 상태에서는 받을 수 없는 요청이다 (예: 완료 전 변환에 검수 수정본 저장).

    NotFoundError와 가르는 기준은 "존재를 알려도 되는가"다 — 소유자 확인을 이미 통과한
    뒤에 상태 때문에 거절하는 자리이므로, 있다는 사실을 숨길 이유가 없고 사용자가 취할
    조치도 다르다(기다렸다가 다시 시도). 409로 매핑한다.
    """


class StorageError(EasyDocError):
    """저장 계층에서 예상하지 못한 제약을 위반했다 — 입력 문제가 아니라 코드 버그다.

    5xx로 매핑한다. 4xx로 감싸면 서버 버그가 "사용자가 뭘 잘못했다"로 둔갑해 조용히
    묻힌다. 원본 DB 예외를 그대로 올리지 않는 이유는 repositories/users.py 참고
    (PostgreSQL이 제약 위반 DETAIL에 실패한 행 전체를 담는다).
    """


class GoldenCollectionError(EasyDocError):
    """골든셋 수집 초안을 만들지 못했다 (내려받기 실패·형식 미지원·빈 본문).

    사용자 요청 경로가 아니라 운영자용 스크립트(scripts/collect_golden.py) 전용이라
    HTTP 매핑 대상이 아니다. 메시지에는 문서 본문을 담지 않는다.
    """


class WelfareApiError(GoldenCollectionError):
    """복지서비스 Open API(data.go.kr)가 오류를 돌려줬거나 응답을 해석하지 못했다.

    GoldenCollectionError 하위로 두어 수집 스크립트의 기존 예외 처리(한 줄 오류 출력)를
    그대로 쓴다. **메시지에 인증키를 담지 않는다** — 서버가 돌려준 문구를 그대로 옮기지
    않고 코드에 대응하는 우리 설명만 붙인다(app/easyread/bokjiro.py 참고).
    """


class ConfigurationError(EasyDocError):
    """서버 설정이 비어 있어 기능을 제공할 수 없다 (예: JWT 비밀키 미설정).

    사용자 잘못이 아니라 운영 설정 문제이므로 5xx로 매핑한다.
    """
