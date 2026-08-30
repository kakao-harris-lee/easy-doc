"""`tools/export_reference_contexts.py`의 로직 테스트.

**네트워크 호출 없음, 실제 `dist/`도 골든 코퍼스도 읽지 않는다.** 사전은
`build_prompt_context`/`find_all`만 흉내 내는 작은 대역으로, 골든 문서는
임시 디렉터리에 쓴 JSON으로 대신한다. `main()` 통합 경로만 실제
`EasyDict.from_index_json`을 쓰는데, 이때도 색인은 임시 파일에 쓴 최소
구조(엔트리 1건)라 `dist/`에 의존하지 않는다.
"""
from __future__ import annotations

import json
import sys
import tempfile
import unittest
from dataclasses import dataclass
from pathlib import Path

_TOOLS_DIR = Path(__file__).resolve().parent.parent
if str(_TOOLS_DIR) not in sys.path:
    sys.path.insert(0, str(_TOOLS_DIR))

import export_reference_contexts as erc  # noqa: E402


PARAMS = erc.ContextParams(
    max_terms=40,
    max_chars=4000,
    max_chars_ratio=1.0,
    min_substitute=5,
    max_examples=3,
    gloss_style="sentence",
)


@dataclass
class _FakeMatch:
    entry_id: int


class _FakeDict:
    """`PromptContextSource`를 만족하는 최소 대역.

    문서 원문 -> (컨텍스트, 매칭 entry_id 목록)을 미리 정해 두고 그대로
    돌려준다. 호출 kwargs는 전부 기록해 파라미터 전달을 검증한다.
    """

    def __init__(self, table: dict[str, tuple[str, list[int]]]) -> None:
        self._table = table
        self.calls: list[dict] = []

    def build_prompt_context(self, text: str, **kwargs) -> str:
        self.calls.append({"text": text, **kwargs})
        return self._table[text][0]

    def find_all(self, text: str):
        return [_FakeMatch(entry_id=i) for i in self._table[text][1]]


def _write_golden(dir_path: Path, doc_id: str, filename: str, source_text: str, **extra) -> None:
    payload = {"id": doc_id, "title": f"문서 {doc_id}", "source_text": source_text, **extra}
    (dir_path / filename).write_text(json.dumps(payload, ensure_ascii=False), encoding="utf-8")


class TestLoadGoldenDocuments(unittest.TestCase):
    def test_reads_source_text_and_sorts_by_id(self) -> None:
        with tempfile.TemporaryDirectory() as td:
            d = Path(td)
            _write_golden(d, "010", "010-나중.json", "열 번째 원문")
            _write_golden(d, "002", "002-먼저.json", "두 번째 원문")
            docs = erc.load_golden_documents(d)
            self.assertEqual([x.doc_id for x in docs], ["002", "010"])
            self.assertEqual(docs[0].source_text, "두 번째 원문")
            self.assertEqual(docs[0].source_file, "002-먼저.json")

    def test_ignores_non_json_files(self) -> None:
        with tempfile.TemporaryDirectory() as td:
            d = Path(td)
            _write_golden(d, "001", "001-문서.json", "원문")
            (d / "README.md").write_text("문서 아님", encoding="utf-8")
            self.assertEqual(len(erc.load_golden_documents(d)), 1)

    def test_missing_source_text_raises(self) -> None:
        # 조용히 건너뛰면 그 문서의 회귀가 영원히 안 잡힌다 — 반드시 실패해야 한다.
        with tempfile.TemporaryDirectory() as td:
            d = Path(td)
            (d / "001-x.json").write_text(json.dumps({"id": "001"}), encoding="utf-8")
            with self.assertRaises(ValueError):
                erc.load_golden_documents(d)

    def test_empty_source_text_raises(self) -> None:
        with tempfile.TemporaryDirectory() as td:
            d = Path(td)
            _write_golden(d, "001", "001-x.json", "")
            with self.assertRaises(ValueError):
                erc.load_golden_documents(d)

    def test_missing_id_raises(self) -> None:
        with tempfile.TemporaryDirectory() as td:
            d = Path(td)
            (d / "x.json").write_text(json.dumps({"source_text": "원문"}), encoding="utf-8")
            with self.assertRaises(ValueError):
                erc.load_golden_documents(d)

    def test_duplicate_id_raises(self) -> None:
        # id가 파일명이 되므로 중복이면 한쪽 픽스처가 다른 쪽을 덮어쓴다.
        with tempfile.TemporaryDirectory() as td:
            d = Path(td)
            _write_golden(d, "001", "001-a.json", "원문 A")
            _write_golden(d, "001", "001-b.json", "원문 B")
            with self.assertRaises(ValueError):
                erc.load_golden_documents(d)

    def test_empty_directory_raises(self) -> None:
        with tempfile.TemporaryDirectory() as td:
            with self.assertRaises(ValueError):
                erc.load_golden_documents(Path(td))


class TestRenderContexts(unittest.TestCase):
    def test_passes_all_parameters_through(self) -> None:
        fake = _FakeDict({"원문": ("컨텍스트", [1])})
        docs = [erc.GoldenDoc("001", "001-x.json", "원문")]
        erc.render_contexts(fake, docs, PARAMS)
        self.assertEqual(
            fake.calls,
            [
                {
                    "text": "원문",
                    "max_terms": 40,
                    "max_chars": 4000,
                    "max_chars_ratio": 1.0,
                    "min_substitute": 5,
                    "max_examples": 3,
                    "gloss_style": "sentence",
                }
            ],
        )

    def test_keeps_documents_with_empty_context(self) -> None:
        # "빈 것이 정답"인 케이스가 빠지면 Kotlin이 아무것도 안 실어도 통과한다.
        fake = _FakeDict({"원문 A": ("컨텍스트", [1]), "원문 B": ("", [])})
        docs = [
            erc.GoldenDoc("001", "001-a.json", "원문 A"),
            erc.GoldenDoc("002", "002-b.json", "원문 B"),
        ]
        results = erc.render_contexts(fake, docs, PARAMS)
        self.assertEqual([r.doc.doc_id for r in results], ["001", "002"])
        self.assertEqual(results[1].context, "")
        self.assertEqual(results[1].match_count, 0)

    def test_counts_total_and_unique_matches(self) -> None:
        fake = _FakeDict({"원문": ("컨텍스트", [7, 7, 9])})
        docs = [erc.GoldenDoc("001", "001-x.json", "원문")]
        result = erc.render_contexts(fake, docs, PARAMS)[0]
        self.assertEqual(result.match_count, 3)
        self.assertEqual(result.unique_entry_count, 2)


class TestManifest(unittest.TestCase):
    def _manifest(self) -> dict:
        results = [
            erc.ContextResult(erc.GoldenDoc("001", "001-a.json", "원문 A"), "컨텍스트", 3, 2),
            erc.ContextResult(erc.GoldenDoc("002", "002-b.json", "원문 B"), "", 0, 0),
        ]
        return erc.build_manifest(
            index_path=Path("/somewhere/easy_dict.index.json"),
            index_sha256="a" * 64,
            schema_version="1.0.0",
            params=PARAMS,
            results=results,
        )

    def test_records_parameters(self) -> None:
        self.assertEqual(self._manifest()["parameters"], PARAMS.as_kwargs())

    def test_records_index_identity_without_absolute_path(self) -> None:
        index = self._manifest()["dictionary_index"]
        self.assertEqual(index["schema_version"], "1.0.0")
        self.assertEqual(index["sha256"], "a" * 64)
        # 절대 경로를 넣으면 체크아웃 위치가 다른 사람에게서 diff가 난다.
        self.assertEqual(index["path"], "easy_dict.index.json")

    def test_records_input_hash_per_document(self) -> None:
        doc = self._manifest()["documents"][0]
        self.assertEqual(doc["source_text_sha256"], erc.sha256_text("원문 A"))
        self.assertEqual(doc["context_sha256"], erc.sha256_text("컨텍스트"))
        self.assertEqual(doc["context_file"], "001.txt")
        self.assertEqual(doc["match_count"], 3)
        self.assertEqual(doc["unique_entry_count"], 2)

    def test_empty_context_is_recorded_explicitly(self) -> None:
        doc = self._manifest()["documents"][1]
        self.assertEqual(doc["context_chars"], 0)
        self.assertEqual(doc["context_sha256"], erc.sha256_text(""))

    def test_has_no_volatile_fields(self) -> None:
        # 생성 일시처럼 매번 바뀌는 값이 들어가면 재생성 diff가 통째로 뒤집혀
        # "참조 출력이 달라졌다"는 신호가 잡음에 묻힌다.
        rendered = erc.render_manifest_json(self._manifest())
        for banned in ("generated_at", "timestamp", "created", "date", "version_of_run"):
            self.assertNotIn(banned, rendered)

    def test_render_is_stable_and_newline_terminated(self) -> None:
        rendered = erc.render_manifest_json(self._manifest())
        self.assertEqual(rendered, erc.render_manifest_json(self._manifest()))
        self.assertTrue(rendered.endswith("\n"))
        self.assertIn("이식본", rendered)  # ensure_ascii=False — 한글이 이스케이프되지 않는다


class TestWriteOutputs(unittest.TestCase):
    def _results(self) -> list[erc.ContextResult]:
        return [
            erc.ContextResult(erc.GoldenDoc("001", "001-a.json", "원문 A"), "컨텍스트 본문", 3, 2),
            erc.ContextResult(erc.GoldenDoc("002", "002-b.json", "원문 B"), "", 0, 0),
        ]

    def test_writes_context_bytes_verbatim(self) -> None:
        # 끝에 개행을 덧붙이면 Kotlin 쪽이 매번 걷어내야 하는 가짜 차이가 된다.
        with tempfile.TemporaryDirectory() as td:
            out = Path(td) / "reference"
            erc.write_outputs(out, self._results(), {"documents": []})
            self.assertEqual((out / "001.txt").read_bytes(), "컨텍스트 본문".encode("utf-8"))

    def test_empty_context_becomes_zero_byte_file(self) -> None:
        with tempfile.TemporaryDirectory() as td:
            out = Path(td) / "reference"
            erc.write_outputs(out, self._results(), {"documents": []})
            self.assertTrue((out / "002.txt").exists())
            self.assertEqual((out / "002.txt").read_bytes(), b"")

    def test_prunes_stale_context_files(self) -> None:
        # 골든 문서가 빠졌는데 옛 픽스처가 남으면 없는 문서의 기대 출력이 계속 통과한다.
        with tempfile.TemporaryDirectory() as td:
            out = Path(td) / "reference"
            out.mkdir(parents=True)
            (out / "999.txt").write_text("낡은 픽스처", encoding="utf-8")
            report = erc.write_outputs(out, self._results(), {"documents": []})
            self.assertEqual(report.pruned, ["999.txt"])
            self.assertFalse((out / "999.txt").exists())
            self.assertEqual(sorted(report.written), ["001.txt", "002.txt"])

    def test_leaves_non_txt_files_alone(self) -> None:
        with tempfile.TemporaryDirectory() as td:
            out = Path(td) / "reference"
            out.mkdir(parents=True)
            (out / "README.md").write_text("설명", encoding="utf-8")
            erc.write_outputs(out, self._results(), {"documents": []})
            self.assertTrue((out / "README.md").exists())

    def test_total_bytes_includes_manifest(self) -> None:
        with tempfile.TemporaryDirectory() as td:
            out = Path(td) / "reference"
            manifest = {"documents": []}
            report = erc.write_outputs(out, self._results(), manifest)
            expected = (
                len("컨텍스트 본문".encode("utf-8"))
                + 0
                + len(erc.render_manifest_json(manifest).encode("utf-8"))
            )
            self.assertEqual(report.total_bytes, expected)

    def test_rerun_is_byte_identical(self) -> None:
        with tempfile.TemporaryDirectory() as td:
            out = Path(td) / "reference"
            manifest = {"documents": []}
            erc.write_outputs(out, self._results(), manifest)
            first = {p.name: p.read_bytes() for p in sorted(out.iterdir())}
            erc.write_outputs(out, self._results(), manifest)
            second = {p.name: p.read_bytes() for p in sorted(out.iterdir())}
            self.assertEqual(first, second)


_MINIMAL_INDEX = {
    "schema_version": "1.0.0",
    "josa": ["을", "를", "이", "가", "은", "는"],
    "entries": {
        "1": {
            "t": "내방",
            "e": "방문",
            "d": None,
            "s": "substitute",
            "r": "none",
            "p": 100,
        }
    },
    "surface_index": {"내방": [1]},
}


# `max_chars_ratio=1.0`은 컨텍스트를 원문 길이 이하로 묶으므로, 원문이 너무
# 짧으면 3개 섹션 제목만으로 예산을 넘겨 항목이 전부 잘린다. 실제 골든 문서는
# 최단 522자라 이 경계에 걸리지 않으므로, 통합 테스트 원문도 그 정도로 채운다.
_PADDING = "안내드립니다. 자세한 내용은 담당 부서로 문의하여 주시기 바랍니다. " * 12


class TestMain(unittest.TestCase):
    """실제 `EasyDict.from_index_json`까지 태우는 통합 경로.

    색인은 엔트리 1건짜리 임시 파일이라 `dist/`에 의존하지 않는다.
    """

    def _run(self, td: str, extra: list[str] | None = None) -> tuple[int, Path]:
        root = Path(td)
        index = root / "easy_dict.index.json"
        index.write_text(json.dumps(_MINIMAL_INDEX, ensure_ascii=False), encoding="utf-8")
        golden = root / "golden"
        golden.mkdir(exist_ok=True)
        _write_golden(golden, "001", "001-매칭있음.json", "내방을 하실 때 서류를 가져오세요. " + _PADDING)
        _write_golden(golden, "002", "002-매칭없음.json", "쉬운 문장만 있는 문서입니다. " + _PADDING)
        out = root / "reference"
        argv = ["--index", str(index), "--golden-dir", str(golden), "--output-dir", str(out)]
        return erc.main(argv + (extra or [])), out

    def test_end_to_end_writes_fixtures_and_manifest(self) -> None:
        with tempfile.TemporaryDirectory() as td:
            rc, out = self._run(td)
            self.assertEqual(rc, 0)
            self.assertEqual(sorted(p.name for p in out.iterdir()), ["001.txt", "002.txt", "manifest.json"])
            manifest = json.loads((out / "manifest.json").read_text(encoding="utf-8"))
            self.assertEqual([d["id"] for d in manifest["documents"]], ["001", "002"])
            self.assertIn("- 내방 → 방문", (out / "001.txt").read_text(encoding="utf-8"))

    def test_document_without_matches_still_gets_a_fixture(self) -> None:
        """매칭 0건 문서도 픽스처를 남긴다.

        `build_prompt_context`는 매칭이 없어도 빈 문자열이 아니라 3개 섹션
        **제목만 있는 골격**을 돌려준다(참조 구현 실측). 그러니 "빈 것이
        정답"인 케이스의 정답은 `""`가 아니라 이 골격이며, 그걸 그대로 기록해야
        Kotlin이 항목을 하나도 못 실었을 때 대조로 잡힌다.
        """
        with tempfile.TemporaryDirectory() as td:
            _, out = self._run(td)
            body = (out / "002.txt").read_text(encoding="utf-8")
            self.assertIn("### 바꿔 쓰세요", body)
            self.assertNotIn("- ", body)  # 항목은 하나도 없다
            manifest = json.loads((out / "manifest.json").read_text(encoding="utf-8"))
            self.assertEqual(manifest["documents"][1]["match_count"], 0)
            self.assertEqual(manifest["documents"][1]["context_chars"], len(body))

    def test_rerun_produces_identical_bytes(self) -> None:
        with tempfile.TemporaryDirectory() as td:
            _, out = self._run(td)
            first = {p.name: p.read_bytes() for p in sorted(out.iterdir())}
            self._run(td)
            second = {p.name: p.read_bytes() for p in sorted(out.iterdir())}
            self.assertEqual(first, second)

    def test_changed_parameters_are_recorded(self) -> None:
        with tempfile.TemporaryDirectory() as td:
            _, out = self._run(td, ["--max-terms", "5", "--gloss-style", "paren"])
            manifest = json.loads((out / "manifest.json").read_text(encoding="utf-8"))
            self.assertEqual(manifest["parameters"]["max_terms"], 5)
            self.assertEqual(manifest["parameters"]["gloss_style"], "paren")

    def test_missing_index_returns_2(self) -> None:
        with tempfile.TemporaryDirectory() as td:
            rc = erc.main([
                "--index", str(Path(td) / "없음.json"),
                "--golden-dir", td,
                "--output-dir", str(Path(td) / "out"),
            ])
            self.assertEqual(rc, 2)

    def test_missing_golden_dir_returns_2(self) -> None:
        with tempfile.TemporaryDirectory() as td:
            index = Path(td) / "easy_dict.index.json"
            index.write_text(json.dumps(_MINIMAL_INDEX, ensure_ascii=False), encoding="utf-8")
            rc = erc.main([
                "--index", str(index),
                "--golden-dir", str(Path(td) / "없음"),
                "--output-dir", str(Path(td) / "out"),
            ])
            self.assertEqual(rc, 2)


if __name__ == "__main__":
    unittest.main()
