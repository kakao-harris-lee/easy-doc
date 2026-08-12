"""압축 예산 방어(I-10 검증 3)의 **판별 가능한** 실행 증거.

`ZipEntry.getSize()`(Python 은 `ZipInfo.file_size`)를 믿으면 뚫린다. fixture
`doc-spike/forged_size.zip` 은 81KB 파일이면서 선언 크기가 1,024B 인데, 경계 없이
읽으면 **190MB 를 소모한다.**

**판정을 예외 발생으로 하면 안 된다.** 무제한 읽기도 결국 `BadZipFile` 로 끝나므로
결과만 보면 방어가 있으나 없으나 같아 보인다 — 실제로 이 저장소에서 그 착각으로
네 번 판정이 뒤집혔다(`doc-spike/README.md`). 차이는 **최대 메모리**에만 나타난다.

그래서 이 파일은 `tracemalloc` 으로 소모량을 잰다:

- `test_예산_방어가_메모리_소모를_막는다` — 실제 추출 경로가 상한 안에 머문다.
  방어(`_ensure_zip_within_budget` 의 경계 있는 읽기)를 걷어내면 이 테스트가 깨진다.
- `test_경계_없는_읽기는_실제로_메모리를_소모한다` — 음성 대조. 방어가 없는 구현이
  같은 입력에서 상한을 넘는 것을 보인다. 이 테스트가 통과해야 위 테스트의 통과가
  "입력이 애초에 무해해서"가 아님이 증명된다.

지금 어디서 도는가: `tests/` 아래라 `uv run pytest`(CI `quality` 잡)가 수집한다.
LLM 도 네트워크도 쓰지 않는다.
"""

from __future__ import annotations

import contextlib
import io
import tracemalloc
import zipfile
from pathlib import Path
from typing import Final

import pytest

from app.exceptions import DocumentExtractionError
from app.ingest.extractors import extract_text

_FIXTURE: Final = Path(__file__).parent / "fixtures" / "doc-spike" / "forged_size.zip"

#: 방어가 있으면 이 아래에 머물고, 없으면 190MB 를 쓴다. 두 값의 간격이 커서
#: 측정 잡음에 흔들리지 않는다.
_PEAK_LIMIT_BYTES: Final = 32 * 1024 * 1024


def _peak_bytes_of(fn: object) -> int:
    """fn 을 실행하며 최대 할당량을 잰다. 예외는 삼키되 소모량은 그대로 돌려준다."""
    tracemalloc.start()
    # 예외를 삼키는 것이 의도다 — 여기서 재는 것은 결과가 아니라 소모량이다.
    with contextlib.suppress(Exception):
        fn()  # type: ignore[operator]
    peak = tracemalloc.get_traced_memory()[1]
    tracemalloc.stop()
    return peak


def _forged_bytes() -> bytes:
    return _FIXTURE.read_bytes()


def test_fixture_가_선언_크기를_위조하고_있다() -> None:
    """전제 확인 — 이 파일이 위조 zip 이 아니면 아래 두 테스트가 무의미해진다."""
    with zipfile.ZipFile(io.BytesIO(_forged_bytes())) as archive:
        info = archive.infolist()[0]
    assert info.file_size == 1024, "선언 크기가 1,024B 여야 한다(위조값)"
    assert info.compress_size > 50_000, "압축 크기가 선언 크기보다 훨씬 커야 한다"


def test_예산_방어가_메모리_소모를_막는다() -> None:
    """실제 추출 경로가 상한 안에 머문다. 방어를 걷어내면 깨진다."""
    peak = _peak_bytes_of(lambda: extract_text("f.hwpx", _forged_bytes()))
    assert peak < _PEAK_LIMIT_BYTES, (
        f"위조 zip 추출이 {peak / 1024 / 1024:.1f}MB 를 소모했다 "
        f"(상한 {_PEAK_LIMIT_BYTES / 1024 / 1024:.0f}MB). "
        "`_ensure_zip_within_budget` 의 경계 있는 읽기가 사라졌는지 확인하라 — "
        "예외가 나는지만 보면 이 회귀는 보이지 않는다"
    )


def test_추출은_위조_zip을_거부한다() -> None:
    """메모리와 별개로 거부 자체도 요구사항이다."""
    with pytest.raises(DocumentExtractionError):
        extract_text("f.hwpx", _forged_bytes())


def test_경계_없는_읽기는_실제로_메모리를_소모한다() -> None:
    """음성 대조 — 방어가 없으면 같은 입력이 상한을 넘는다.

    이 테스트가 통과해야 위 테스트의 통과가 "입력이 애초에 무해해서"가 아니라
    "방어가 일해서"임이 증명된다. 프로브가 방어를 갖고 있으면(청크 읽기) 소모량이
    2MB 로 떨어져 아무것도 재지 못한다 — 그 착각이 이 저장소에서 실제로 났다.
    """

    def naive() -> None:
        with zipfile.ZipFile(io.BytesIO(_forged_bytes())) as archive:
            archive.read(archive.infolist()[0].filename)  # 경계 없는 읽기

    peak = _peak_bytes_of(naive)
    assert peak > _PEAK_LIMIT_BYTES, (
        f"경계 없는 읽기가 {peak / 1024 / 1024:.1f}MB 만 썼다 — fixture 가 더는 "
        "취약점을 재현하지 못한다는 뜻이므로 fixture 를 다시 만들어야 한다"
    )
