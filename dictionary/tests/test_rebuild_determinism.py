"""같은 입력을 같은 순서로 두 번 빌드해도 산출물이 의미적으로 동일한지
확인한다 (docs/inspection-plan.md Phase 3 작업 3 — "재빌드 diff").

`tests/test_reproducibility.py`는 --input **순서를 일부러 바꿔서** 비교한다
(파일 간 순서, 파일 내부 행 순서). 이 파일은 그 반대다 — 아무것도 안 바꾸고
**똑같은 명령을 그대로 두 번** 돌렸을 때도 산출물이 같은지만 본다. 매번
새 SQLite에 `--reset`으로 적재하므로 `entries.id`(AUTOINCREMENT)는 실전에서
1부터 다시 매겨져 사실상 항상 일치하지만, 그 사실에 기대지 않고 id를
정규화해서 비교한다(§5.4) — 언젠가 id 할당 순서가 비결정적으로 바뀌어도
이 테스트가 "문제 없음"으로 잘못 판정하지 않게 하기 위해서다.

**두 빌드를 서로 다른 `python3 -m easydict.build` 서브프로세스로 띄운다.**
`build.main()`을 한 프로세스 안에서 두 번 호출하기만 하면 잡을 수 없는
비결정성까지 잡으려는 것이다 — 예를 들어 어딘가 `set()`/미정렬 `dict` 순회
순서에 기대는 코드가 있다면, 각 프로세스가 서로 다른 `PYTHONHASHSEED`를
받는 실제 운영 환경(빌드할 때마다 새 프로세스)에서만 드러난다. 같은
인터프리터 안에서 두 번 호출하면 해시 시드가 같아 이런 종류의 버그를
놓친다.

**check.sh에 그냥 끼워 넣는다(따로 안 뺐다).** 이유: 실측(2026-08-29)으로
실데이터 6종 전체(nikl 1,076행 포함)를 서브프로세스로 두 번 빌드해도
빌드당 ~0.66초, 합쳐서 1.3초 남짓이었다 — "두 번 빌드는 느릴 수 있다"는
우려와 달리 부담스러운 비용이 아니었다. 여기서는 다른 재현성 테스트와
같은 이유로 더 작은 통제 샘플(`SAMPLE_CSV_DIR`)을 써서 더 빠르게 돈다.
이 파일은 `tests/`에 있으므로 check.sh 1단계(`unittest discover -s tests`)가
별도 배선 없이 자동으로 같이 돈다 — 그래서 check.sh를 따로 손대지 않았다.

**발견**: `easy_dict.json`(전체 덤프)에는 빌드 시각을 담는 `generated_at`
필드가 있어, 같은 입력이라도 두 번 빌드하면 값이 달라진다. 이건 결함이
아니라 의도된 메타데이터이므로 비교에서 제외한다. `easy_dict.index.json`/
`easy_dict.simple.jsonl`에는 그런 시각 필드가 없다.

`dist/`·`data/raw/`는 건드리지 않는다 — 전부 임시 디렉터리에서 돈다.
"""

from __future__ import annotations

import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
SAMPLE_CSV_DIR = REPO_ROOT / "data" / "raw" / "sample"
SCHEMA_SQL_PATH = REPO_ROOT / "schema" / "schema.sql"

# (csv 파일, source-code, source-name, organization, license) — tests/test_reproducibility.py
# 의 _INPUTS와 같은 근거로 같은 샘플을 쓴다(통제된 소형 데이터가 재현성
# 검증 목적에 실데이터보다 적합하다).
_INPUTS: tuple[tuple[str, str, str, str, str], ...] = (
    ("raw_terms.csv", "data.go.kr:admin-terms", "행정용어 순화어 대조표", "행정안전부", "공공누리 제1유형"),
    ("raw_terms_welfare_cp949.csv", "mohw.go.kr:welfare-terms", "복지용어 순화어 대조표", "보건복지부", "공공누리 제1유형"),
    ("raw_terms_law.csv", "moleg.go.kr:law-terms", "법률용어 순화어 대조표", "법제처", "공공누리 제1유형"),
)


def _run_build_subprocess(workdir: Path) -> tuple[Path, Path]:
    """`python3 -m easydict.build`를 별도 프로세스로 실행한다(§docstring 참고
    — 같은 인터프리터 안에서 두 번 호출하면 못 잡는 비결정성까지 잡는다)."""
    db_path = workdir / "easy_dict.sqlite3"
    export_dir = workdir / "dist"
    argv = [sys.executable, "-m", "easydict.build"]
    for csv_name, code, name, org, lic in _INPUTS:
        argv += [
            "--input", str(SAMPLE_CSV_DIR / csv_name),
            "--source-code", code,
            "--source-name", name,
            "--organization", org,
            "--license", lic,
        ]
    argv += [
        "--db", str(db_path),
        "--export", str(export_dir),
        "--reset",
        "--schema", str(SCHEMA_SQL_PATH),
    ]
    env = {**os.environ, "PYTHONPATH": str(REPO_ROOT / "src")}
    result = subprocess.run(argv, cwd=str(REPO_ROOT), env=env, capture_output=True, text=True)
    if result.returncode != 0:
        raise AssertionError(
            f"build 서브프로세스 실패 (rc={result.returncode}):\n"
            f"stdout:\n{result.stdout}\nstderr:\n{result.stderr}"
        )
    return db_path, export_dir


def _normalize_index_doc(doc: dict) -> tuple:
    """`easy_dict.index.json`을 id 대신 내용값으로 정규화한다 —
    `TestBuildReproducibility.test_index_json_semantically_identical_ignoring_ids`
    (tests/test_reproducibility.py)와 같은 방식."""
    id_to_key = {
        eid: (info["t"], info["e"], info["d"], info["s"], info["r"], info["p"], tuple(info["g"]))
        for eid, info in doc["entries"].items()
    }
    surface_map = {
        surface: tuple(id_to_key[str(eid)] for eid in ids)
        for surface, ids in doc["surface_index"].items()
    }
    return doc["schema_version"], doc["josa"], surface_map


def _normalize_full_doc(doc: dict) -> dict:
    """`easy_dict.json`(전체 덤프)에서 `generated_at`(빌드 시각)을 빼고,
    `entries`(id 오름차순 리스트, export.py `_ALL_ENTRIES_SQL`)를 id 대신
    (term, easy_term) 키로 다시 묶는다."""
    out = {k: v for k, v in doc.items() if k not in ("generated_at", "entries")}
    entries_by_key = {}
    for info in doc["entries"]:
        key = (info["term"], info["easy_term"])
        entries_by_key[key] = {k: v for k, v in info.items() if k != "id"}
    out["entries_by_key"] = entries_by_key
    return out


@unittest.skipUnless(SCHEMA_SQL_PATH.is_file(), f"schema.sql 없음: {SCHEMA_SQL_PATH}")
class TestRebuildDeterminism(unittest.TestCase):
    """같은 --input(순서까지 동일)으로 두 번 빌드한 산출물이 의미적으로
    동일한지 확인한다. `tests/test_reproducibility.py`와 달리 아무 순서도
    바꾸지 않는다 — 이게 바로 이 테스트의 요점이다."""

    @classmethod
    def setUpClass(cls) -> None:
        for csv_name, *_ in _INPUTS:
            path = SAMPLE_CSV_DIR / csv_name
            if not path.is_file():
                raise unittest.SkipTest(f"샘플 CSV 없음: {path}")

        cls._tmp = tempfile.TemporaryDirectory(prefix="easydict_rebuild_determinism_test_")
        tmp_root = Path(cls._tmp.name)
        run1_dir = tmp_root / "run1"
        run2_dir = tmp_root / "run2"
        run1_dir.mkdir()
        run2_dir.mkdir()

        cls.db1, cls.export1 = _run_build_subprocess(run1_dir)
        cls.db2, cls.export2 = _run_build_subprocess(run2_dir)

    @classmethod
    def tearDownClass(cls) -> None:
        cls._tmp.cleanup()

    def test_index_json_semantically_identical(self) -> None:
        doc1 = json.loads((self.export1 / "easy_dict.index.json").read_text(encoding="utf-8"))
        doc2 = json.loads((self.export2 / "easy_dict.index.json").read_text(encoding="utf-8"))
        self.assertEqual(
            _normalize_index_doc(doc1), _normalize_index_doc(doc2),
            "같은 입력을 같은 순서로 두 번 빌드했는데 easy_dict.index.json이 달라졌다",
        )

    def test_full_export_semantically_identical_ignoring_generated_at(self) -> None:
        doc1 = json.loads((self.export1 / "easy_dict.json").read_text(encoding="utf-8"))
        doc2 = json.loads((self.export2 / "easy_dict.json").read_text(encoding="utf-8"))
        self.assertEqual(
            _normalize_full_doc(doc1), _normalize_full_doc(doc2),
            "같은 입력을 같은 순서로 두 번 빌드했는데 easy_dict.json이 "
            "(generated_at 제외하고도) 달라졌다",
        )

    def test_simple_jsonl_identical(self) -> None:
        def _rows(export_dir: Path) -> list[str]:
            text = (export_dir / "easy_dict.simple.jsonl").read_text(encoding="utf-8")
            return sorted(
                json.dumps(json.loads(line), sort_keys=True, ensure_ascii=False)
                for line in text.splitlines() if line
            )

        self.assertEqual(
            _rows(self.export1), _rows(self.export2),
            "같은 입력을 같은 순서로 두 번 빌드했는데 easy_dict.simple.jsonl이 달라졌다",
        )


if __name__ == "__main__":
    unittest.main()
