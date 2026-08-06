"""복지서비스 API 수집 CLI(scripts/collect_bokjiro.py)의 인증키 로딩 테스트.

API 호출·파싱은 tests/easyread/test_bokjiro.py가 맡는다. 여기서 보는 것은 **키가 어디서
오는가** 하나다 — "환경변수만 읽으면서 오류 메시지는 .env에 넣으라고 안내"하던 모순이
실제 사용자 실행에서 터진 자리라, 안내와 동작이 같은지 기계로 지킨다.

인증키 값은 전부 이 파일에서 만든 예시다.
"""

from pathlib import Path

import pytest

from app.easyread.collection import CollectionSettings

ENV_KEY = "DATA_GO_KR_API_KEY"


def _settings(env_file: Path) -> CollectionSettings:
    """`.env` 위치를 가리켜 설정을 읽는다 (기본값은 리포 루트의 실제 `.env`다)."""
    # 아래 ignore[call-arg] 사유: `_env_file`은 pydantic-settings 공식 초기화 인자인데,
    # pydantic mypy 플러그인이 __init__을 필드에서만 만들어 내 인식하지 못한다.
    # 이 인자 없이는 임시 .env를 가리킬 방법이 없어 로딩 자체를 테스트할 수 없다.
    return CollectionSettings(_env_file=env_file)  # type: ignore[call-arg]


def test_env_파일에_적은_키를_읽는다(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.delenv(ENV_KEY, raising=False)
    env_file = tmp_path / ".env"
    env_file.write_text(f"{ENV_KEY}=파일에적은키\n", encoding="utf-8")

    키 = _settings(env_file).data_go_kr_api_key
    assert 키 is not None
    assert 키.get_secret_value() == "파일에적은키"


def test_환경변수가_env_파일보다_세다(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    """일회성 실행에서 `DATA_GO_KR_API_KEY=... uv run ...`으로 덮어쓸 수 있어야 한다."""
    env_file = tmp_path / ".env"
    env_file.write_text(f"{ENV_KEY}=파일에적은키\n", encoding="utf-8")
    monkeypatch.setenv(ENV_KEY, "환경변수키")

    키 = _settings(env_file).data_go_kr_api_key
    assert 키 is not None
    assert 키.get_secret_value() == "환경변수키"


def test_어디에도_없으면_None이다(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.delenv(ENV_KEY, raising=False)
    assert _settings(tmp_path / "없는.env").data_go_kr_api_key is None


def test_키는_repr에_드러나지_않는다(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    """SecretStr이라 트레이스백·로그로 키가 새지 않는다."""
    monkeypatch.delenv(ENV_KEY, raising=False)
    env_file = tmp_path / ".env"
    env_file.write_text(f"{ENV_KEY}=절대노출금지\n", encoding="utf-8")

    settings = _settings(env_file)
    assert "절대노출금지" not in repr(settings)
    assert "절대노출금지" not in str(settings.model_dump())


def test_리포_루트의_env를_기본으로_본다() -> None:
    """상대 경로면 실행 위치에 따라 읽히다 말다 한다 — 절대 경로로 고정한다."""
    env_file = CollectionSettings.model_config["env_file"]
    assert isinstance(env_file, Path)
    assert env_file.is_absolute()
    assert env_file.name == ".env"
    assert (env_file.parent / "pyproject.toml").exists()
