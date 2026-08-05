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

    # 인증. jwt_secret 미설정 시 인증 API를 쓸 수 없다(앱 기동 자체는 가능).
    jwt_secret: SecretStr | None = None
    jwt_expire_minutes: int = 60

    # 문서 본문 암호화(Fernet) 키. 미설정 시 문서 저장 불가.
    fernet_key: SecretStr | None = None
