# 실제 복지 안내문 수집 방안 (골든셋 ①-b)

> master-plan 7장 검증 절차 ①-b: 합성 골든셋 20건을 실제 수집본으로 교체·보강해야 벤더 확정 근거가 완성된다.
> 목표: **25건** (기존 합성 20건 주제와 겹치게 수집해 교체, 5건은 신규 주제).

## 1. 수집 원칙

1. **저작권**: 공공저작물 자유이용(저작권법 제24조의2) 대상 또는 **공공누리 제1유형**(출처 표시) 자료만 수집한다. 문서마다 출처 URL·기관명·공공누리 유형을 기록한다. 유형 표시가 없는 자료는 담당 기관에 이용 문의 후 편입한다.
2. **개인정보**: 처음부터 개인정보가 없는 **공개 배포용 안내문**을 우선한다. 담당자 연락처(기관 대표번호·부서 메일)가 있는 문서는 편입 전 `mask_text` 파이프라인을 통과시키고, 마스킹 결과를 사람이 확인한 뒤 넣는다. 파일럿 기관이 제공하는 내부 문서는 **기관 측이 개인정보 제거를 확인**했다는 서면(메일) 기록을 남긴다.
3. **다양성**: 기관 유형(중앙부처/지자체/공단), 분야(현금성 급여/의료/주거/에너지/교육), 길이(500~4,000자), 문체(고시·공고체/안내문체/개조식)를 분산시킨다. 4,000자 초과 문서도 2~3건 수집해 두되(분할 변환 테스트용) 골든셋 편입은 보류한다.

## 2. 수집 소스 (우선순위순)

| # | 소스 | 대상 | 비고 |
|---|---|---|---|
| 1 | 복지로 (bokjiro.go.kr) | 복지서비스 상세 안내 페이지 | 기초연금·에너지바우처·긴급복지 등 기존 골든셋 주제와 1:1 매칭 가능 |
| 2 | 정부24 (gov.kr) | 서비스 신청 안내 | 신청 방법·구비서류 중심 — required_facts 추출 용이 |
| 3 | 보건복지부 (mohw.go.kr) | 보도자료·고시·공고 | 공공누리 유형 명시됨 |
| 4 | 지자체 홈페이지 고시·공고 게시판 | 재난지원금·문화누리 등 지역 사업 안내 | 시·군·구 2~3곳 분산 (문체 다양성) |
| 5 | 국민건강보험공단·국민연금공단 | 건강검진·연금 안내 | 공단 특유의 안내문체 |
| 6 | 파일럿 기관 제공 | 실제 업무 문서 | 계약·협의 후. 개인정보 제거 책임 주체 명시 필수 |

## 3. 편입 절차 (문서당)

1. 원문 확보(웹 텍스트 복사 또는 hwpx/pdf 다운로드 → `extract_text`)
2. 출처 메타 기록: URL, 기관, 수집일, 공공누리 유형 → JSON `source` 필드(`GoldenSource`). `synthetic: false` 문서는 `source`가 없으면 스키마 검증에서 거부된다
3. `mask_text` 통과 → 마스킹 항목 사람 확인
4. `required_facts` 큐레이션 3~6개 (마스킹 대상 패턴 금지, 재작성 취약 표기는 accept 변형 부여)
5. `synthetic: false`로 `tests/golden/documents/`에 편입 → `uv run pytest tests/golden` 스키마 테스트 통과 확인
6. 교체된 합성 문서는 삭제하지 않고 `tests/golden/synthetic-archive/`로 이동(회귀 비교용)

## 4. 역할 분담

- **사람(운영자)**: 소스 접근·다운로드, 공공누리 유형 판단, 마스킹 결과 확인, `required_facts` 큐레이션, 파일럿 기관 협의 — 법적 판단과 의미 판단이 필요한 단계는 자동화하지 않는다.
- **자동화**: `scripts/collect_golden.py` — URL/파일 입력 → 텍스트 추출 → 마스킹 → 골든셋 JSON 초안 생성. 위 3장 절차의 1·2·3단계(기계적인 부분)를 대신한다.

### 4.1 `scripts/collect_golden.py` 사용법

```bash
# 웹 페이지에서 (html 본문 추출)
uv run python scripts/collect_golden.py "https://example.go.kr/board/view.do?id=1" \
    --org "보건복지부" --license "공공누리 제1유형"

# 내려받은 파일에서 (docx·pdf·hwpx·txt)
uv run python scripts/collect_golden.py ./기초연금안내.hwpx \
    --org "○○구청" --license "공공누리 제1유형" \
    --title "기초연금 신청 안내" --category "복지 안내문"

# 저장하지 않고 글자 수·마스킹 건수만 확인
uv run python scripts/collect_golden.py ./안내문.pdf --org "○○시" --license "공공누리 제1유형" --dry-run
```

| 옵션 | 설명 |
|---|---|
| `--org` (필수) | 발행 기관명 → `source.organization` |
| `--license` (필수) | 이용 조건 → `source.license` (예: `"공공누리 제1유형"`, `"파일럿 기관 제공"`) |
| `--title` | 문서 제목. 생략하면 마스킹 후 본문 첫 줄 |
| `--category` | 주제 분류. 생략하면 `미분류(사람 확인)` — 통제 어휘 밖이라 고치지 않으면 스키마 테스트가 막는다 |
| `--output` | 초안 저장 디렉터리 (기본 `docs/golden-drafts/`) |
| `--dry-run` | 파일을 쓰지 않고 통계만 출력 |

동작:

- URL이면 httpx로 받아 온다(타임아웃 30초, 리다이렉트 허용, User-Agent 명시). `text/html`은 표준 라이브러리 파서로 본문만 걷고(script·style·nav·header·footer·head 제외, 블록 요소 단위 개행), pdf/docx/hwpx 첨부는 `app/ingest/extractors.py`의 `extract_text`로 넘긴다. URL에 확장자가 없으면 Content-Type으로 형식을 가른다.
- 본문은 **반드시 `mask_text`를 통과한 뒤** 초안에 담긴다. `source_text`는 마스킹된 텍스트다.
- `id`는 `tests/golden/documents/`와 초안 디렉터리를 통틀어 최대 번호 + 1로 자동 채번한다.
- 초안은 `docs/golden-drafts/NNN-슬러그.json`에 쓴다. **`tests/golden/documents/`에 직접 넣지 않는다** — 사람 검토를 거치지 않은 문서가 평가셋에 섞이면 통과율이 사람 손을 타지 않은 채로 움직인다.
- 표준출력에는 저장 경로·글자 수·마스킹 건수·다음 단계만 나온다. 문서 본문과 마스킹 원문은 출력하지 않는다(CLAUDE.md 보안·데이터 규칙).

초안이 나온 뒤 사람이 할 일(3장 4~6단계): `required_facts` 3~6개 큐레이션 → `category`를 통제 어휘로 수정 → 마스킹 결과 육안 검수 → `tests/golden/documents/`로 이동 → `uv run pytest tests/golden`.

## 5. 완료 기준

- [ ] 25건 편입 (`synthetic: false`), 주제·기관·길이 분포 표 작성
- [ ] 전 문서 출처·공공누리 유형 기록
- [ ] `uv run pytest tests/golden` 통과 (스키마 + 실제 문서 기준)
- [ ] 골든셋 LLM 평가 재실행 → 통과율·judge 점수를 합성셋 결과와 비교 보고
- [ ] 이 결과로 벤더 확정 (master-plan 3.1 기록 표 채움)
