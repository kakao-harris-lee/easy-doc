"""애플리케이션 설정. 비밀키는 .env/환경변수로만 주입한다."""

from pydantic import SecretStr
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """환경변수 기반 설정."""

    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8", extra="ignore")

    # SecretStr: repr/model_dump에서 자동 마스킹되어 로그로 키가 유출되는 경로를 원천 차단한다.
    anthropic_api_key: SecretStr | None = None
    openai_api_key: SecretStr | None = None

    # 인프라 접속 정보. 기본값은 docker-compose.yml의 로컬 개발 환경 기준이다.
    database_url: str = "postgresql+asyncpg://postgres:postgres@localhost:5432/easydoc"
    redis_url: str = "redis://localhost:6379/0"

    # 브라우저에서 API를 부를 수 있는 오리진 목록.
    # 파일럿 배포는 nginx가 프론트와 API를 같은 호스트로 서빙하므로 cross-origin이
    # 아니다 — 다른 오리진이 되는 것은 개발용 Vite 서버(5173)뿐이라 기본값이 그것 하나다.
    # 환경변수로 덮어쓸 때는 JSON 배열로 준다: CORS_ORIGINS='["https://example.kr"]'
    cors_origins: list[str] = ["http://localhost:5173"]

    # 인증. jwt_secret 미설정 시 인증 API를 쓸 수 없다(앱 기동 자체는 가능).
    jwt_secret: SecretStr | None = None
    jwt_expire_minutes: int = 60

    # 문서 본문 암호화(Fernet) 키. 미설정 시 문서 저장 불가.
    fernet_key: SecretStr | None = None

    # 변환 워커가 쓸 LLM 벤더 이름 (app/llm/factory.py가 아는 이름).
    # 기본값이 anthropic인 것은 선택이 아니라 미확정 상태를 그대로 둔 것이다 —
    # 벤더는 골든셋 벤치마크로 확정하며(master-plan 3.1), 확정되면 그 기록 표를
    # 채우면서 이 기본값도 함께 갱신한다. 그때까지는 .env의 LLM_PROVIDER로 고른다.
    # 비교 도구(scripts/benchmark.py·골든셋 평가)는 이 값을 쓰지 않는다 — 대상 벤더를
    # 명시 지정하는 것이 비교의 전제라 각자 --providers·GOLDEN_PROVIDER를 유지한다.
    llm_provider: str = "anthropic"

    # llm_provider로 고른 벤더의 모델명 덮어쓰기. None이면 provider 구현체의 기본 모델을 쓴다.
    # 모델 교체·롤백을 코드 수정 없이 .env만으로 할 수 있게 두는 것이 목적이다 —
    # 기본 모델 자체는 실측 근거와 함께 provider 구현체에 남긴다(docs/quality 보고서 인용).
    llm_model: str | None = None

    # Anthropic 전용 사고 깊이(output_config.effort): low|medium|high|xhigh|max.
    # None이면 파라미터를 보내지 않는다 = API 기본값(high). 낮출수록 사고 토큰·지연·원가가
    # 줄고 품질이 떨어질 수 있어, 값을 바꾸면 벤치마크로 확인한 뒤 반영한다.
    # llm_model과 달리 llm_provider와 짝지어 게이팅하지 않는다 — effort는 Anthropic만
    # 받는 파라미터라 다른 벤더로 새어 나갈 경로 자체가 없다(app/llm/factory.py).
    llm_effort: str | None = None
