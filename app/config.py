"""애플리케이션 설정. 비밀키는 .env/환경변수로만 주입한다."""

from pydantic import SecretStr
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """환경변수 기반 설정."""

    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8", extra="ignore")

    # SecretStr: repr/model_dump에서 자동 마스킹되어 로그로 키가 유출되는 경로를 원천 차단한다.
    anthropic_api_key: SecretStr | None = None
    openai_api_key: SecretStr | None = None
