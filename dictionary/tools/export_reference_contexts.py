#!/usr/bin/env python3
"""Kotlin 이식본 대조용 참조 출력(`build_prompt_context`)을 픽스처로 뽑는다.

## 배경 — 왜 이 도구가 필요한가

`src/easydict/lookup.py`가 참조 구현이고, easy-doc의 Kotlin `core`가 이를
이식하는 중이다. 이식 과정에서 경계 규칙 하나만 빠져도 문서가 조용히
훼손된다(`DESIGN.md` §6.7의 실측 결함: `CCTV`에서 `CT`가 매칭돼
`C전류 변성기V`가 되는 종류). 사람이 눈으로 볼 수 있는 결함이 아니므로,
**참조 구현의 실제 출력과 바이트 단위로 대조하는 것**이 유일한 기계적
안전장치다.

이 도구는 골든 코퍼스(`data/golden/documents/*.json`, 읽기 전용) 56건의
원문(`source_text`)마다 `EasyDict.build_prompt_context()`를 호출해 그 출력을
`<id>.txt` 한 건씩 쓰고, 입력이 무엇이었는지 `manifest.json`에 해시로 남긴다.
Kotlin 쪽 테스트는 같은 원문을 자기 구현에 넣고 이 파일과 문자열이 같은지만
보면 된다.

**읽기만 한다.** `dist/`도 골든 코퍼스도 고치지 않고, 사전을 재빌드하지도
않는다 — 이미 빌드된 `dist/easy_dict.index.json`을 그대로 읽는다.

## 재현성 — 이 도구의 핵심 성질

같은 입력(색인 + 코퍼스 + 파라미터)이면 **몇 번을 돌려도 바이트가 같아야
한다.** 그래서 산출물에 생성 일시처럼 매번 바뀌는 값을 절대 넣지 않는다 —
넣으면 재생성할 때마다 diff가 전부 바뀌어, 정작 봐야 할 "참조 출력이
달라졌다"는 신호가 잡음에 묻힌다. 매니페스트가 담는 것은 **무엇으로
뽑았는지**(파라미터·색인 `schema_version`·입력 해시)뿐이다.

## 파라미터는 왜 매니페스트에 남기나

`build_prompt_context`의 출력은 `max_terms`/`max_chars`/... 값에 따라 통째로
달라진다. 파라미터를 기록하지 않으면 픽스처는 "참조 구현의 출력"이 아니라
"어떤 알 수 없는 설정에서의 출력"이 되고, 나중에 제품이 다른 값을 쓰기
시작하면 픽스처가 조용히 거짓말을 하게 된다. 기본값은
`docs/easy-doc-integration.md` §4·§5의 권장값(제품이 실제로 쓸 값)이며,
CLI로 바꿀 수 있고 바꾼 값은 그대로 매니페스트에 기록된다.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Protocol

# tools/ 는 패키지가 아니라 스크립트 모음이라, `easydict`를 import 하려면
# 저장소의 src/ 를 경로에 얹어야 한다(`extract_gaps.py`와 같은 방식).
DICT_ROOT = Path(__file__).resolve().parent.parent
if str(DICT_ROOT / "src") not in sys.path:
    sys.path.insert(0, str(DICT_ROOT / "src"))

# easy-doc 저장소 루트. 이 사전 저장소가 easy-doc 안에 중첩돼 있다는 사실에
# 의존한다 — 절대 경로를 박아 두면 다른 체크아웃에서 못 쓰므로 파일 위치에서
# 유도한다.
CONSUMER_ROOT = DICT_ROOT.parent

DEFAULT_INDEX_PATH = DICT_ROOT / "dist" / "easy_dict.index.json"
DEFAULT_GOLDEN_DIR = CONSUMER_ROOT / "data" / "golden" / "documents"
DEFAULT_OUTPUT_DIR = (
    CONSUMER_ROOT
    / "backend-kotlin"
    / "infrastructure"
    / "src"
    / "test"
    / "resources"
    / "dictionary"
    / "reference"
)

MANIFEST_FILENAME = "manifest.json"
CONTEXT_SUFFIX = ".txt"


class PromptContextSource(Protocol):
    """이 도구가 사전에게 요구하는 것 전부 (`EasyDict`가 이미 만족한다).

    테스트가 실제 `dist/`를 읽지 않고 작은 대역으로 대체할 수 있도록
    구조적 타입으로만 의존한다.
    """

    def build_prompt_context(self, text: str, **kwargs: Any) -> str: ...

    def find_all(self, text: str) -> list[Any]: ...


@dataclass(frozen=True, slots=True)
class ContextParams:
    """`build_prompt_context` 호출 파라미터 한 벌.

    출력이 이 값에 통째로 의존하므로 매니페스트에 그대로 기록된다.
    """

    max_terms: int
    max_chars: int | None
    max_chars_ratio: float | None
    min_substitute: int
    max_examples: int
    gloss_style: str

    def as_kwargs(self) -> dict[str, Any]:
        return {
            "max_terms": self.max_terms,
            "max_chars": self.max_chars,
            "max_chars_ratio": self.max_chars_ratio,
            "min_substitute": self.min_substitute,
            "max_examples": self.max_examples,
            "gloss_style": self.gloss_style,
        }


@dataclass(frozen=True, slots=True)
class GoldenDoc:
    doc_id: str
    source_file: str
    source_text: str


@dataclass(frozen=True, slots=True)
class ContextResult:
    doc: GoldenDoc
    context: str
    match_count: int
    unique_entry_count: int

    @property
    def context_filename(self) -> str:
        return f"{self.doc.doc_id}{CONTEXT_SUFFIX}"


@dataclass(frozen=True, slots=True)
class WriteReport:
    written: list[str]
    pruned: list[str]
    total_bytes: int


def sha256_text(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def load_golden_documents(golden_dir: Path) -> list[GoldenDoc]:
    """골든 문서를 id 오름차순으로 읽는다.

    원문 필드는 `source_text`다(`data/golden/documents/001-*.json`으로 확인).
    필수 필드가 없거나 id가 겹치면 조용히 넘기지 않고 실패시킨다 — 픽스처가
    한 건 빠진 채로 만들어지면 그 문서의 회귀는 영원히 안 잡힌다.
    """
    files = sorted(golden_dir.glob("*.json"))
    if not files:
        raise ValueError(f"골든 문서를 찾지 못했다: {golden_dir}")

    docs: list[GoldenDoc] = []
    seen: dict[str, str] = {}
    for path in files:
        with open(path, encoding="utf-8") as f:
            raw = json.load(f)
        doc_id = raw.get("id")
        if not doc_id:
            raise ValueError(f"`id`가 없는 골든 문서: {path.name}")
        source_text = raw.get("source_text")
        if not isinstance(source_text, str) or not source_text:
            raise ValueError(f"`source_text`가 없는 골든 문서: {path.name}")
        if doc_id in seen:
            raise ValueError(f"골든 문서 id 중복: {doc_id} ({seen[doc_id]}, {path.name})")
        seen[doc_id] = path.name
        docs.append(GoldenDoc(doc_id=doc_id, source_file=path.name, source_text=source_text))

    docs.sort(key=lambda d: d.doc_id)
    return docs


def render_contexts(
    dictionary: PromptContextSource, docs: list[GoldenDoc], params: ContextParams
) -> list[ContextResult]:
    """문서마다 참조 컨텍스트를 만든다.

    매칭이 0건인 문서도 결과에서 빼지 않는다 — "아무것도 안 실리는 게
    정답"인 케이스가 픽스처에서 빠지면 Kotlin이 그 문서에 아무것도 싣지
    않아도 대조를 통과해 버린다.

    **실측(참조 구현)**: `build_prompt_context`는 매칭이 0건이어도 빈
    문자열이 아니라 **3개 섹션 제목만 있는 골격**을 돌려준다. 그러니 그
    케이스의 기대값은 `""`가 아니라 그 골격이고, 그대로 기록하면 Kotlin이
    항목을 하나도 못 실었을 때 바로 잡힌다. (빈 문자열 처리는
    `write_outputs`에 그대로 남겨 둔다 — 지금 안 나온다고 해서 다루지
    않으면, 나중에 참조 구현이 바뀔 때 조용히 틀린다.)
    """
    kwargs = params.as_kwargs()
    results: list[ContextResult] = []
    for doc in docs:
        context = dictionary.build_prompt_context(doc.source_text, **kwargs)
        matches = dictionary.find_all(doc.source_text)
        results.append(
            ContextResult(
                doc=doc,
                context=context,
                match_count=len(matches),
                unique_entry_count=len({m.entry_id for m in matches}),
            )
        )
    return results


def build_manifest(
    *,
    index_path: Path,
    index_sha256: str,
    schema_version: str,
    params: ContextParams,
    results: list[ContextResult],
) -> dict[str, Any]:
    """재생성해도 값이 안 바뀌는 매니페스트를 만든다(생성 일시 없음)."""
    return {
        "generator": "dictionary/tools/export_reference_contexts.py",
        "purpose": (
            "Kotlin 이식본과 파이썬 참조 구현(easydict.lookup.EasyDict."
            "build_prompt_context)의 출력을 바이트 단위로 대조하기 위한 참조 픽스처."
        ),
        "dictionary_index": {
            "path": index_path.name,
            "schema_version": schema_version,
            "sha256": index_sha256,
        },
        "parameters": params.as_kwargs(),
        "documents": [
            {
                "id": r.doc.doc_id,
                "source_file": r.doc.source_file,
                "source_text_sha256": sha256_text(r.doc.source_text),
                "source_text_chars": len(r.doc.source_text),
                "context_file": r.context_filename,
                "context_sha256": sha256_text(r.context),
                "context_chars": len(r.context),
                "match_count": r.match_count,
                "unique_entry_count": r.unique_entry_count,
            }
            for r in results
        ],
    }


def render_manifest_json(manifest: dict[str, Any]) -> str:
    """매니페스트 직렬화. 개행·들여쓰기를 고정해 diff가 안정적이게 한다."""
    return json.dumps(manifest, ensure_ascii=False, indent=2, sort_keys=False) + "\n"


def write_outputs(
    output_dir: Path, results: list[ContextResult], manifest: dict[str, Any]
) -> WriteReport:
    """컨텍스트 파일과 매니페스트를 쓰고, 매니페스트에 없는 낡은 `.txt`는 지운다.

    컨텍스트는 `build_prompt_context`가 돌려준 문자열 **그대로** 쓴다 —
    끝에 개행을 덧붙이지 않는다. 이 파일은 사람이 읽는 문서가 아니라
    바이트 비교 대상이고, 덧붙인 개행은 Kotlin 쪽이 매번 걷어내야 하는
    가짜 차이가 된다. 그래서 컨텍스트가 비면 0바이트 파일이 된다(파일이
    없는 것과 다르다 — 매니페스트의 `context_chars: 0`이 "빈 것이 정답"임을
    명시한다).

    낡은 파일 정리는 픽스처가 거짓말하는 것을 막는다: 골든 문서가 빠졌는데
    옛 `.txt`가 남아 있으면 존재하지 않는 문서의 기대 출력이 계속 통과한다.
    """
    output_dir.mkdir(parents=True, exist_ok=True)

    expected = {r.context_filename for r in results}
    written: list[str] = []
    total_bytes = 0
    for r in results:
        path = output_dir / r.context_filename
        data = r.context.encode("utf-8")
        path.write_bytes(data)
        written.append(r.context_filename)
        total_bytes += len(data)

    pruned = sorted(
        p.name for p in output_dir.glob(f"*{CONTEXT_SUFFIX}") if p.name not in expected
    )
    for name in pruned:
        (output_dir / name).unlink()

    manifest_bytes = render_manifest_json(manifest).encode("utf-8")
    (output_dir / MANIFEST_FILENAME).write_bytes(manifest_bytes)
    total_bytes += len(manifest_bytes)

    return WriteReport(written=written, pruned=pruned, total_bytes=total_bytes)


def read_schema_version(index_path: Path) -> str:
    with open(index_path, encoding="utf-8") as f:
        doc = json.load(f)
    version = doc.get("schema_version")
    if version is None:
        raise ValueError(f"색인에 `schema_version`이 없다: {index_path}")
    return str(version)


def build_arg_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    p.add_argument(
        "--index",
        type=Path,
        default=DEFAULT_INDEX_PATH,
        help="사전 색인 경로(기본: dist/easy_dict.index.json). 재빌드하지 않고 그대로 읽는다",
    )
    p.add_argument(
        "--golden-dir",
        type=Path,
        default=DEFAULT_GOLDEN_DIR,
        help="골든 문서 디렉터리(기본: easy-doc의 data/golden/documents, 읽기 전용)",
    )
    p.add_argument(
        "--output-dir",
        type=Path,
        default=DEFAULT_OUTPUT_DIR,
        help="픽스처 출력 디렉터리(기본: backend-kotlin 쪽 테스트 리소스)",
    )
    # 아래 6개 기본값은 docs/easy-doc-integration.md §4·§5의 권장값 —
    # 제품(easy-doc)이 실제로 쓸 호출 파라미터다. 바꾸면 그 값이 그대로
    # manifest.json에 기록되므로 픽스처가 어떤 설정의 것인지 항상 남는다.
    p.add_argument("--max-terms", type=int, default=40, help="컨텍스트에 실을 최대 용어 수(기본 40)")
    p.add_argument("--max-chars", type=int, default=4000, help="컨텍스트 길이 상한(기본 4000)")
    p.add_argument(
        "--max-chars-ratio",
        type=float,
        default=1.0,
        help="원문 길이 대비 컨텍스트 길이 상한 비율(기본 1.0)",
    )
    p.add_argument(
        "--min-substitute",
        type=int,
        default=5,
        help="잘림에서 보호할 substitute 항목 최소 개수(기본 5)",
    )
    p.add_argument("--max-examples", type=int, default=3, help="참고 예문 최대 개수(기본 3)")
    p.add_argument(
        "--gloss-style",
        choices=("sentence", "paren"),
        default="sentence",
        help="gloss 섹션 형식(기본 sentence)",
    )
    return p


def main(argv: list[str] | None = None) -> int:
    args = build_arg_parser().parse_args(argv)

    if not args.index.exists():
        print(f"[오류] 사전 색인이 없다: {args.index}", file=sys.stderr)
        return 2
    if not args.golden_dir.is_dir():
        print(f"[오류] 골든 문서 디렉터리가 없다: {args.golden_dir}", file=sys.stderr)
        return 2

    from easydict.lookup import EasyDict  # sys.path 조작 이후여야 한다

    params = ContextParams(
        max_terms=args.max_terms,
        max_chars=args.max_chars,
        max_chars_ratio=args.max_chars_ratio,
        min_substitute=args.min_substitute,
        max_examples=args.max_examples,
        gloss_style=args.gloss_style,
    )

    docs = load_golden_documents(args.golden_dir)
    dictionary = EasyDict.from_index_json(args.index)
    results = render_contexts(dictionary, docs, params)
    manifest = build_manifest(
        index_path=args.index,
        index_sha256=sha256_file(args.index),
        schema_version=read_schema_version(args.index),
        params=params,
        results=results,
    )
    report = write_outputs(args.output_dir, results, manifest)

    no_match = [r.doc.doc_id for r in results if r.match_count == 0]
    empty = [r.doc.doc_id for r in results if not r.context]
    print(f"문서 {len(results)}건 -> {args.output_dir}", file=sys.stderr)
    print(f"총 {report.total_bytes:,} bytes (manifest 포함)", file=sys.stderr)
    print(
        f"매칭 0건 문서: {len(no_match)}건" + (f" ({', '.join(no_match)})" if no_match else ""),
        file=sys.stderr,
    )
    print(f"빈(0바이트) 컨텍스트: {len(empty)}건", file=sys.stderr)
    if report.pruned:
        print(f"낡은 픽스처 삭제: {', '.join(report.pruned)}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
