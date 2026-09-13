#!/usr/bin/env python3
"""국립국어원 '다듬은 말' 전체 목록/상세 수집기.

외부 패키지 없이 Python 3 표준 라이브러리만 사용한다. 수집 상태는 SQLite에
저장하므로 중단 후 같은 명령을 다시 실행하면 이어서 진행한다.
"""

from __future__ import annotations

import argparse
import csv
import html
import json
import random
import re
import sqlite3
import sys
import time
from datetime import datetime, timezone
from html.parser import HTMLParser
from http.cookiejar import CookieJar
from pathlib import Path
from typing import Iterable
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import HTTPCookieProcessor, Request, build_opener


BASE_URL = "https://www.korean.go.kr"
LIST_URL = f"{BASE_URL}/front/imprv/refineList.do"
DETAIL_URL = f"{BASE_URL}/front/imprv/refineView.do"
PAGE_UNIT = 30
USER_AGENT = (
    "Mozilla/5.0 (compatible; NIKLRefinedWordsCollector/1.0; "
    "+https://www.korean.go.kr/)"
)
FIELDS = [
    "display_no",
    "refine_seq",
    "source_term",
    "original_term",
    "refined_term",
    "meaning_examples",
    "notes",
    "detail_url",
    "collected_at",
]


def clean_text(value: str, preserve_lines: bool = False) -> str:
    value = html.unescape(value).replace("\xa0", " ")
    if preserve_lines:
        lines = [re.sub(r"[ \t\r\f\v]+", " ", line).strip() for line in value.split("\n")]
        return "\n".join(line for line in lines if line)
    return re.sub(r"\s+", " ", value).strip()


class ListParser(HTMLParser):
    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.total: int | None = None
        self.in_tbody = False
        self.in_row = False
        self.in_cell = False
        self.cell_parts: list[str] = []
        self.cells: list[str] = []
        self.refine_seq: str | None = None
        self.rows: list[dict[str, str | int]] = []

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        attributes = dict(attrs)
        if tag == "div" and "paging" in (attributes.get("class") or "").split():
            raw_total = attributes.get("data-totalcnt")
            if raw_total and raw_total.isdigit():
                self.total = int(raw_total)
        if tag == "tbody":
            self.in_tbody = True
        elif tag == "tr" and self.in_tbody:
            self.in_row = True
            self.cells = []
            self.refine_seq = None
        elif tag == "td" and self.in_row:
            self.in_cell = True
            self.cell_parts = []
        elif tag == "a" and self.in_cell:
            match = re.search(r"fnCmdEditView\(['\"](\d+)['\"]\)", attributes.get("href") or "")
            if match:
                self.refine_seq = match.group(1)

    def handle_data(self, data: str) -> None:
        if self.in_cell:
            self.cell_parts.append(data)

    def handle_endtag(self, tag: str) -> None:
        if tag == "td" and self.in_cell:
            self.cells.append(clean_text("".join(self.cell_parts)))
            self.in_cell = False
        elif tag == "tr" and self.in_row:
            if len(self.cells) == 4 and self.cells[0].isdigit() and self.refine_seq:
                self.rows.append(
                    {
                        "display_no": int(self.cells[0]),
                        "refine_seq": self.refine_seq,
                        "source_term": self.cells[1],
                        "original_term": self.cells[2],
                        "refined_term": self.cells[3],
                    }
                )
            self.in_row = False
        elif tag == "tbody":
            self.in_tbody = False


class DetailParser(HTMLParser):
    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.board_depth = 0
        self.in_board = False
        self.in_row = False
        self.in_header = False
        self.in_value = False
        self.header_parts: list[str] = []
        self.value_parts: list[str] = []
        self.rows: list[tuple[str, str]] = []

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        attributes = dict(attrs)
        classes = (attributes.get("class") or "").split()
        if tag == "div" and "boardView" in classes:
            self.in_board = True
            self.board_depth = 1
            return
        if self.in_board and tag == "div":
            self.board_depth += 1
        if not self.in_board:
            return
        if tag == "tr":
            self.in_row = True
            self.header_parts, self.value_parts = [], []
        elif tag == "th" and self.in_row:
            self.in_header = True
        elif tag == "td" and self.in_row:
            self.in_value = True
        elif tag == "br" and self.in_value:
            self.value_parts.append("\n")

    def handle_data(self, data: str) -> None:
        if self.in_header:
            self.header_parts.append(data)
        elif self.in_value:
            self.value_parts.append(data)

    def handle_endtag(self, tag: str) -> None:
        if not self.in_board:
            return
        if tag == "th":
            self.in_header = False
        elif tag == "td":
            self.in_value = False
        elif tag == "tr" and self.in_row:
            key = clean_text("".join(self.header_parts))
            value = clean_text("".join(self.value_parts), preserve_lines=True)
            if key:
                self.rows.append((key, value))
            self.in_row = False
        elif tag == "div":
            self.board_depth -= 1
            if self.board_depth == 0:
                self.in_board = False

    def result(self) -> dict[str, str]:
        if len(self.rows) < 5:
            raise ValueError(f"상세 표의 필드가 부족합니다: {self.rows!r}")
        # 사이트에서 첫 두 행의 제목이 모두 '다듬은 말'이므로 위치도 함께 사용한다.
        return {
            "source_term": self.rows[0][1],
            "refined_term": self.rows[1][1],
            "original_term": self.rows[2][1],
            "meaning_examples": self.rows[3][1],
            "notes": self.rows[4][1],
        }


class Collector:
    def __init__(self, timeout: float, delay: float, retries: int) -> None:
        self.timeout = timeout
        self.delay = delay
        self.retries = retries
        self.opener = build_opener(HTTPCookieProcessor(CookieJar()))
        self.last_request_at = 0.0

    def _wait(self) -> None:
        remaining = self.delay - (time.monotonic() - self.last_request_at)
        if remaining > 0:
            time.sleep(remaining + random.uniform(0, min(0.15, self.delay / 4)))

    def fetch(self, url: str, data: dict[str, str] | None = None) -> str:
        encoded = urlencode(data).encode("utf-8") if data is not None else None
        for attempt in range(self.retries + 1):
            self._wait()
            request = Request(
                url,
                data=encoded,
                headers={"User-Agent": USER_AGENT, "Accept": "text/html", "Referer": LIST_URL},
            )
            try:
                with self.opener.open(request, timeout=self.timeout) as response:
                    raw = response.read()
                    charset = response.headers.get_content_charset() or "utf-8"
                    self.last_request_at = time.monotonic()
                    return raw.decode(charset, errors="replace")
            except HTTPError as exc:
                self.last_request_at = time.monotonic()
                if exc.code not in {429, 500, 502, 503, 504} or attempt == self.retries:
                    raise
                retry_after = exc.headers.get("Retry-After")
                wait = float(retry_after) if retry_after and retry_after.isdigit() else 2**attempt
            except (URLError, TimeoutError):
                self.last_request_at = time.monotonic()
                if attempt == self.retries:
                    raise
                wait = 2**attempt
            time.sleep(wait + random.uniform(0, 0.5))
        raise RuntimeError("도달할 수 없는 코드")


def connect_db(path: Path) -> sqlite3.Connection:
    db = sqlite3.connect(path)
    db.row_factory = sqlite3.Row
    db.executescript(
        """
        PRAGMA journal_mode=WAL;
        CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL);
        CREATE TABLE IF NOT EXISTS pages (
            generation TEXT NOT NULL,
            page_no INTEGER NOT NULL,
            row_count INTEGER NOT NULL,
            fetched_at TEXT NOT NULL,
            PRIMARY KEY (generation, page_no)
        );
        CREATE TABLE IF NOT EXISTS words (
            refine_seq TEXT PRIMARY KEY,
            display_no INTEGER NOT NULL,
            source_term TEXT NOT NULL,
            original_term TEXT NOT NULL,
            refined_term TEXT NOT NULL,
            meaning_examples TEXT NOT NULL DEFAULT '',
            notes TEXT NOT NULL DEFAULT '',
            detail_fetched INTEGER NOT NULL DEFAULT 0,
            detail_url TEXT NOT NULL,
            collected_at TEXT NOT NULL,
            generation TEXT NOT NULL
        );
        """
    )
    return db


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def get_meta(db: sqlite3.Connection, key: str) -> str | None:
    row = db.execute("SELECT value FROM meta WHERE key = ?", (key,)).fetchone()
    return row[0] if row else None


def set_meta(db: sqlite3.Connection, key: str, value: str) -> None:
    db.execute(
        "INSERT INTO meta(key, value) VALUES (?, ?) "
        "ON CONFLICT(key) DO UPDATE SET value = excluded.value",
        (key, value),
    )


def parse_list(page_html: str) -> tuple[int, list[dict[str, str | int]]]:
    parser = ListParser()
    parser.feed(page_html)
    if parser.total is None:
        raise ValueError("목록 HTML에서 전체 건수를 찾지 못했습니다.")
    if not parser.rows:
        raise ValueError("목록 HTML에서 데이터 행을 찾지 못했습니다.")
    return parser.total, parser.rows


def collect_list(
    db: sqlite3.Connection,
    client: Collector,
    max_pages: int | None,
) -> tuple[int, bool]:
    first_html = client.fetch(
        f"{LIST_URL}?mn_id=&pageIndex=1",
        {"pageUnit": str(PAGE_UNIT), "searchCondition": "all", "searchKeyword": ""},
    )
    total, first_rows = parse_list(first_html)
    page_count = (total + PAGE_UNIT - 1) // PAGE_UNIT
    old_total = get_meta(db, "snapshot_total")
    generation = get_meta(db, "active_generation")
    if old_total != str(total) or generation is None:
        generation = f"{total}-{int(time.time())}"
        set_meta(db, "snapshot_total", str(total))
        set_meta(db, "active_generation", generation)
        set_meta(db, "list_complete", "0")
    limit = min(page_count, max_pages) if max_pages else page_count
    print(f"사이트 총계 {total:,}건, 목록 {page_count:,}페이지 (페이지당 {PAGE_UNIT}건)")

    for page_no in range(1, limit + 1):
        done = db.execute(
            "SELECT 1 FROM pages WHERE generation = ? AND page_no = ?",
            (generation, page_no),
        ).fetchone()
        if done:
            continue
        if page_no == 1:
            rows = first_rows
        else:
            page_html = client.fetch(
                f"{LIST_URL}?mn_id=&pageIndex={page_no}",
                {"pageUnit": str(PAGE_UNIT), "searchCondition": "all", "searchKeyword": ""},
            )
            page_total, rows = parse_list(page_html)
            if page_total != total:
                raise RuntimeError(f"수집 중 총계가 {total}에서 {page_total}(으)로 변경되었습니다. 다시 실행하세요.")
        now = utc_now()
        with db:
            for row in rows:
                seq = str(row["refine_seq"])
                db.execute(
                    """
                    INSERT INTO words(
                        refine_seq, display_no, source_term, original_term, refined_term,
                        detail_url, collected_at, generation
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(refine_seq) DO UPDATE SET
                        display_no=excluded.display_no,
                        source_term=excluded.source_term,
                        original_term=excluded.original_term,
                        refined_term=excluded.refined_term,
                        detail_url=excluded.detail_url,
                        collected_at=excluded.collected_at,
                        generation=excluded.generation
                    """,
                    (
                        seq,
                        row["display_no"],
                        row["source_term"],
                        row["original_term"],
                        row["refined_term"],
                        f"{DETAIL_URL}?mn_id=&imprv_refine_seq={seq}&pageIndex=1",
                        now,
                        generation,
                    ),
                )
            db.execute(
                "INSERT INTO pages(generation, page_no, row_count, fetched_at) VALUES (?, ?, ?, ?)",
                (generation, page_no, len(rows), now),
            )
        if page_no == 1 or page_no % 20 == 0 or page_no == limit:
            print(f"목록: {page_no:,}/{limit:,} 페이지")

    completed_pages = db.execute(
        "SELECT COUNT(*) FROM pages WHERE generation = ?", (generation,)
    ).fetchone()[0]
    complete = completed_pages == page_count
    if complete:
        with db:
            db.execute("DELETE FROM words WHERE generation <> ?", (generation,))
            actual = db.execute("SELECT COUNT(*) FROM words").fetchone()[0]
            if actual != total:
                raise RuntimeError(f"검증 실패: 사이트 총계 {total}, 고유 수집 건수 {actual}")
            set_meta(db, "list_complete", "1")
    return total, complete


def collect_details(
    db: sqlite3.Connection,
    client: Collector,
    max_details: int | None,
) -> None:
    sql = "SELECT refine_seq, detail_url FROM words WHERE detail_fetched = 0 ORDER BY display_no DESC"
    params: tuple[int, ...] = ()
    if max_details is not None:
        sql += " LIMIT ?"
        params = (max_details,)
    pending = db.execute(sql, params).fetchall()
    print(f"상세: 이번 실행에서 {len(pending):,}건 수집")
    for index, row in enumerate(pending, 1):
        page_html = client.fetch(row["detail_url"])
        parser = DetailParser()
        parser.feed(page_html)
        detail = parser.result()
        with db:
            db.execute(
                """
                UPDATE words SET source_term=?, refined_term=?, original_term=?,
                    meaning_examples=?, notes=?, detail_fetched=1, collected_at=?
                WHERE refine_seq=?
                """,
                (
                    detail["source_term"],
                    detail["refined_term"],
                    detail["original_term"],
                    detail["meaning_examples"],
                    detail["notes"],
                    utc_now(),
                    row["refine_seq"],
                ),
            )
        if index == 1 or index % 100 == 0 or index == len(pending):
            print(f"상세: {index:,}/{len(pending):,}건")


def rows_for_export(db: sqlite3.Connection) -> Iterable[dict[str, object]]:
    for row in db.execute("SELECT * FROM words ORDER BY display_no DESC"):
        yield {field: row[field] for field in FIELDS}


def export_files(db: sqlite3.Connection, output_dir: Path) -> tuple[Path, Path, int]:
    csv_path = output_dir / "korean_refined_words.csv"
    jsonl_path = output_dir / "korean_refined_words.jsonl"
    rows = list(rows_for_export(db))
    with csv_path.open("w", encoding="utf-8-sig", newline="") as file:
        writer = csv.DictWriter(file, fieldnames=FIELDS)
        writer.writeheader()
        writer.writerows(rows)
    with jsonl_path.open("w", encoding="utf-8", newline="\n") as file:
        for row in rows:
            file.write(json.dumps(row, ensure_ascii=False) + "\n")
    return csv_path, jsonl_path, len(rows)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="국립국어원 다듬은 말 전체 데이터 수집")
    parser.add_argument("--output-dir", type=Path, default=Path.cwd(), help="결과 저장 폴더")
    parser.add_argument("--details", action="store_true", help="각 항목의 의미/용례와 참고 사항도 수집")
    parser.add_argument("--delay", type=float, default=0.8, help="요청 사이 최소 대기 시간(초)")
    parser.add_argument("--timeout", type=float, default=30.0, help="요청 제한 시간(초)")
    parser.add_argument("--retries", type=int, default=5, help="일시 오류 재시도 횟수")
    parser.add_argument("--max-pages", type=int, help="시험 실행용 목록 최대 페이지 수")
    parser.add_argument("--max-details", type=int, help="시험 실행용 상세 최대 건수")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if args.delay < 0 or args.timeout <= 0 or args.retries < 0:
        print("delay/retries는 0 이상, timeout은 0보다 커야 합니다.", file=sys.stderr)
        return 2
    args.output_dir.mkdir(parents=True, exist_ok=True)
    db_path = args.output_dir / "korean_refined_words.sqlite3"
    db = connect_db(db_path)
    client = Collector(timeout=args.timeout, delay=args.delay, retries=args.retries)
    try:
        total, list_complete = collect_list(db, client, args.max_pages)
        if args.details:
            if not list_complete:
                raise RuntimeError("상세 수집 전에 전체 목록 수집을 완료해야 합니다.")
            collect_details(db, client, args.max_details)
        csv_path, jsonl_path, count = export_files(db, args.output_dir)
        detail_count = db.execute("SELECT COUNT(*) FROM words WHERE detail_fetched = 1").fetchone()[0]
        print(f"완료: {count:,}/{total:,}건 (상세 {detail_count:,}건)")
        print(f"CSV:   {csv_path}")
        print(f"JSONL: {jsonl_path}")
        print(f"DB:    {db_path}")
        return 0 if list_complete else 3
    except KeyboardInterrupt:
        print("\n중단됨: 같은 명령을 다시 실행하면 이어서 수집합니다.", file=sys.stderr)
        return 130
    except Exception as exc:
        print(f"오류: {exc}", file=sys.stderr)
        print("같은 명령을 다시 실행하면 완료된 지점부터 이어서 수집합니다.", file=sys.stderr)
        return 1
    finally:
        db.close()


if __name__ == "__main__":
    raise SystemExit(main())
