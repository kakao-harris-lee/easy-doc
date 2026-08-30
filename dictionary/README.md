# easy-dictionary (쉬운 말 사전)

지적·발달장애인을 위한 공공/복지 문서 쉬운 글 변환 서비스([easy-doc](..))가 참고할 순화어 사전을 만드는 빌드 도구다. 국립국어원이 개방한 순화어·사전 데이터와 사람이 직접 검수한 복지 용어 시드(CSV)를 받아 검증·정규화·증강해서, 런타임에 바로 조회할 수 있는 SQLite 1개와 JSON 3종을 만든다. **원천은 형식이 맞다고 받지 않는다** — 목적이 "쉽게 하기"가 아닌 목록은 규모가 커도 탈락시킨다([DESIGN.md §5.7](./DESIGN.md#57-원천-평가-기준-순화-목록은-쉬운-말-목록이-아니다)). 외부 패키지 의존성이 없다(표준 라이브러리만 사용, Python 3.11+). 이 저장소는 산출물(`dist/`)까지만 책임지며, easy-doc 저장소는 건드리지 않는다.

설계 배경과 근거는 [DESIGN.md](./DESIGN.md)에 정리되어 있다. 이 문서는 "이걸로 뭘 어떻게 하는가"에 집중한다.

## 왜 단순 딕셔너리가 아닌가

`{"과태료": "벌금"}`처럼 단순 치환하면 사고가 난다 — 과태료는 행정질서벌, 벌금은 형벌이라 전과 여부가 갈리는 별개의 법적 개념이기 때문이다. 그래서 모든 표제어는 `replace_strategy`(`substitute`: 교체해도 안전 / `gloss`: 원어를 남기고 괄호로 설명 / `keep`: 바꾸지 않음)와 `risk_level`(`none`/`low`/`high`)을 필수로 가진다. `risk_level=high`인 표제어는 자동 치환에서 제외되고 사람 검수 큐(`status='review'`)로 넘어간다. 자세한 근거는 [DESIGN.md §2.1](./DESIGN.md#21-단순-termeasy_term-딕셔너리는-b2g에서-위험하다--가장-중요한-결정).

## 빠른 시작

```bash
# 1. 데이터 확보 (아래 "데이터 출처와 라이선스" 참고. 가상 샘플은 data/raw/sample/*.csv다)

# 2. 빌드: CSV -> SQLite + JSON 3종. 원천마다 --input과 --source-* 세트를 반복한다.
PYTHONPATH=src python3 -m easydict.build \
    --input data/raw/raw_terms.csv \
    --source-code data.go.kr:admin-terms \
    --source-name "행정용어 순화어 대조표" \
    --organization "행정안전부" \
    --license "공공누리 제1유형" \
    --db dist/easy_dict.sqlite3 \
    --export dist/ \
    --reset

# 3. 조회 (파이썬 예시는 아래 "파이썬에서 쓰기" 참고)
```

빌드가 끝나면 `dist/`에 `easy_dict.sqlite3`, `easy_dict.json`, `easy_dict.index.json`, `easy_dict.simple.jsonl` 4개가 생기고, 표제어 수·전략 분포·검수 큐 개수를 요약한 빌드 리포트가 콘솔에 출력된다. 여러 CSV를 누적하려면 `--input`/`--source-*` 세트를 반복하면 된다:

```bash
PYTHONPATH=src python3 -m easydict.build \
    --input data/raw/raw_terms.csv \
    --source-code data.go.kr:admin-terms --source-name "행정용어 순화어 대조표" \
    --organization "행정안전부" --license "공공누리 제1유형" \
    --input data/raw/raw_terms_law.csv \
    --source-code moleg.go.kr:law-terms --source-name "알기 쉬운 법령 용어" \
    --organization "법제처" --license "공공누리 제1유형" \
    --input data/raw/raw_terms_welfare_cp949.csv \
    --source-code data.go.kr:welfare-terms --source-name "복지용어 순화어 대조표" \
    --organization "보건복지부" --license "공공누리 제1유형" \
    --db dist/easy_dict.sqlite3 --export dist/ --reset
```
(`raw_terms_welfare_cp949.csv`는 CP949 인코딩 샘플이다 — 별도 옵션 없이 `build.py`가 인코딩을 자동 판별한다.)

## 산출물 4종 (`dist/`)

| 파일 | 용도 |
|---|---|
| `easy_dict.sqlite3` | 정본(source of truth). 전체 스키마+데이터, FTS5 검색 포함. 어드민/검수 도구가 읽는다. |
| `easy_dict.json` | 전체 덤프. 벡터화·재배포·사람이 훑어보는 용도. |
| `easy_dict.index.json` | 런타임 조회 최적화 색인. **easy-doc이 실제로 로드하는 파일.** |
| `easy_dict.simple.jsonl` | 기획서 호환 최소 형태(`{"term","easy_term","category"}`). 초기 프로토타입/수작업 검토용. |

자세한 스키마는 [DESIGN.md §4](./DESIGN.md#4-산출물-dist).

## 파이썬에서 쓰기

`EasyDict`는 `dist/easy_dict.index.json`(또는 `dist/easy_dict.sqlite3`)을 로드해 표면형 트라이를 구성하고, 문서 안에서 최장일치 + 조사 경계 인식으로 용어를 찾는다. 최종 목적은 LLM 프롬프트에 주입할 컨텍스트를 만드는 것이다 — 문서에 실제로 등장한 용어만 골라 넣는다.

```python
from easydict.lookup import EasyDict

d = EasyDict.from_index_json("dist/easy_dict.index.json")
text = "과태료 고지서를 받으시면 신청서에 이름과 주소를 명기하여 주십시오."

matches = d.find_all(text)              # 최장일치 + 조사 경계
context = d.build_prompt_context(text)  # LLM 프롬프트에 그대로 붙일 마크다운
```

위 예시를 실제로 돌리면 `build_prompt_context`가 다음을 출력한다 (`raw_terms.csv` + `raw_terms_law.csv`를 함께 빌드한 사전 기준, [DESIGN.md §7.2](./DESIGN.md#72-프롬프트-주입-형태-build_prompt_context-출력)와 동일한 3섹션 구조):

```markdown
## 이 문서에 나온 어려운 말 (반드시 아래 지침대로 처리하세요)

### 바꿔 쓰세요
- 명기하다 → 쓰다

### 원래 말을 남기고 괄호로 설명하세요 (지우면 안 됩니다)
- 과태료 → 과태료(정해진 법을 안 지켜서 내는 돈)

### 절대 바꾸지 마세요
```

`과태료`는 법적으로 벌금과 다른 개념이라 `gloss` 전략(원어 보존)으로 분류되고, `명기하다`는 활용형 `명기하여`가 변형형 색인을 통해 매칭되어 `substitute` 전략으로 바뀐다. `EasyDict.from_sqlite("dist/easy_dict.sqlite3")`로 SQLite를 직접 열면 FTS5 기반 `search()`도 쓸 수 있다.

## easy-doc 연동

이 저장소는 `dist/` 산출물만 만든다. easy-doc 저장소의 코드나 설정은 수정하지 않는다 — easy-doc의 `CLAUDE.md`가 "RAG 사전은 MVP 밖"이라고 명시하고 있으므로, 이 저장소는 easy-doc이 나중에 준비되었을 때 그대로 가져다 쓸 수 있는 산출물만 만든다. 연동은 다음과 같은 형태다.

```
dictionary/dist/easy_dict.index.json
        └→ (빌드 시 복사) → backend-kotlin/.../resources/dictionary/
```

easy-doc은 Kotlin 백엔드이므로 `kotlinx.serialization`으로 `index.json`을 읽어 동일한 표면형 트라이를 구성하면 된다. 스키마가 언어중립이라 이 저장소의 파이썬 `lookup.py` 구현을 참조 구현으로 삼으면 된다.

easy-doc의 골든셋은 `required_facts.canonical`이 변환 결과에 남아 있는지 검증한다. `keep`/`gloss` 전략은 원어를 보존하므로 이 검증을 통과시킨다 — 이 사전이 사실보존 검증의 사전 방어선 역할을 한다. 자세히는 [DESIGN.md §7](./DESIGN.md#7-easy-doc-연동).

## 새 데이터 원천 추가하기

기관마다 CSV 헤더가 다르다(예: `순화대상어` vs `원어` vs `용어`). `build.py`의 `COLUMN_ALIASES` 표에 헤더 별칭을 추가하면 새 원천을 붙일 수 있다. 헤더 해석에 실패하면 빈 사전을 조용히 만드는 대신 **빌드를 중단하고 발견된 헤더 목록을 출력한다**. 별칭 표는 [DESIGN.md §5.1](./DESIGN.md#51--컬럼-별칭--실무에서-가장-많이-깨지는-지점).

## 데이터 출처와 라이선스

`data/raw/`에는 이제 실제 데이터가 들어와 있다(2026-08-29 기준).

- `nikl_admin_terms_2018.csv` — 국립국어원 「알기 쉬운 행정용어」(2018) 대량 자동 변환
- `welfare_seed_1.csv` ~ `welfare_seed_5.csv` — 사람이 직접 검수한 복지 용어 시드 데이터(1·2차 일반, 3차 RAG 관점, 4차 행정 처분·혼동 쌍, 5차 절차·장애 정도)
- `krdict_advanced.csv` / `krdict_advanced_v2.csv` — 국립국어원 한국어기초사전 API 고급어휘
- `corpus_examples_golden57.csv` — easy-doc 골든 코퍼스(57건)에서 뽑은 예문 전용 원천(`--source-role examples`, DESIGN.md §5.5(7) — 엔트리를 만들지도 고치지도 않고 예문만 붙인다)
- `nikl_admin_terms_2018.known-errors.md` — 위 nikl 원천 원문 자체의 알려진 오류 기록(우리가 임의로 고치지 않고 여기 남긴다)

**`data/raw/sample/*.csv`만 인코딩/헤더 변형 테스트를 위한 가상의 샘플이다**(`raw_terms.csv`는 UTF-8, `raw_terms_welfare_cp949.csv`는 CP949, `raw_terms_law.csv`는 헤더가 다른 변형) — `tests/`가 재현성·인코딩 판별 테스트에 쓴다. 새 원천을 더 받으려면 다음에서 직접 데이터를 받는다.

- [공공데이터포털](https://www.data.go.kr) — 기관별 순화어 대조표
- [국립국어원](https://www.korean.go.kr) — 다듬은 말
- [법제처](https://www.moleg.go.kr) — 알기 쉬운 법령 만들기

받은 데이터가 전부 원천이 되는 것은 아니다. **국립국어원 다듬은말 18,340건과 법제처 법령용어 115,732건은 검토 후 적재하지 않기로 했다** — 앞의 것은 대치어가 우리 목표와 다른 기준으로 골라졌고, 뒤의 것은 대치어 컬럼 자체가 없다. 판단 근거와 4단계 평가 기준은 [DESIGN.md §5.7](./DESIGN.md#57-원천-평가-기준-순화-목록은-쉬운-말-목록이-아니다)에 있다.

**공공누리 유형에 따라 재배포 가능 여부가 다르다.** 산출물을 다른 시스템에 넘기기 전에 반드시 `sources` 테이블(또는 `easy_dict.json`의 `sources` 배열)에서 원천별 `license` 값을 확인하라. B2G 납품에서 실제로 문제가 되는 지점이다.

### `data/법제처_국가법령정보센터_법령용어_20240912.csv` — 원천이 아니다

이 파일은 저장소에 있지만 **빌드가 읽지 않고, 앞으로도 적재하지 않는다.** 대치어(순화어) 컬럼이 없어 `easy_term`을 채울 수 없기 때문이다. 남겨 둔 용도는 하나다 — 갭 리스트의 "잘린 조각"(`국민기초생활 보장법`이 `국민기초생활`/`보장법`으로 쪼개지는 문제, [tools/README.md](./tools/README.md))을 판정할 **법령용어 화이트리스트**다. 아직 그 판정을 하는 도구는 없다.

`data/raw/`가 아니라 `data/` 바로 아래 있는 것도 그래서다 — `data/raw/`는 빌드가 적재하는 원천의 자리라, 적재하지 않을 파일을 거기 두면 실수로 `--input`에 걸린다. 자세히는 [DESIGN.md §5.7](./DESIGN.md#57-원천-평가-기준-순화-목록은-쉬운-말-목록이-아니다).

## 빌드 검증

빌드 파이프라인 자체의 계약(`tests/`)과 보조 도구(`tools/tests/`), 현재 `dist/` 산출물이 스스로 모순되지 않는지(층위 1 불변식 — `substitute`+`review` 공존 금지, `readability` 범위, `deprecated` 유출, `simple.jsonl` 계약, 도달 가능성, 보호 엔트리 승리, 엔트리 귀속), 그리고 실제 문서를 통과시키면 무슨 일이 나는지(층위 2 — 경계 위반·원문 파괴·활용형 비문·상충 지침)를 명령 하나로 확인한다.

```bash
./scripts/check.sh
```

둘 다 읽기 전용이다 — `dist/`를 재빌드하거나 수정하지 않는다(먼저 빌드가 한 번 되어 있어야 층위 1 검사가 돈다). 무엇을 못 잡는지도 매번 `[미검사]`로 함께 출력한다 — 층위 3(의미 검증)은 아직 여기 없다. 계획은 [docs/inspection-plan.md](./docs/inspection-plan.md) 참고.

### 층위 2 — 코퍼스 통과 검사 (`tools/audit_corpus.py`)

easy-doc의 골든 코퍼스(기본 `../data/golden` — easy-doc 저장소 루트 기준)에 현재 `dist/`를 통과시켜, 경계 위반(`CCTV`에서 `CT`가 매칭되는 류)·원문 파괴·활용형 비문(`받음하실`류)·상충 지침을 센다. `check.sh` 4/4 단계로 묶여 있고 단독으로도 돌릴 수 있다.

```bash
PYTHONPATH=src python3 tools/audit_corpus.py --golden-dir ../data/golden
```

**골든 문서 디렉터리(`../data/golden`)를 못 찾으면 이 단계는 건너뛴다 — "통과"가 아니라 "검사 안 함"이다.** `check.sh`도 같은 이유로 이 단계를 실패시키지 않고 `[건너뜀]`으로만 표시한다.

지금 남은 알려진 사례(형태소 경계 등)를 0으로 만들 수 없어서, 0건을 요구하는 대신 **기준선 파일(`tools/audit_corpus.baseline.json`)로 증가만 막는다.** 갭 리스트(§5.6)와 반대로 **이 기준선은 커밋한다** — 재생성 대상이 아니라 비교 대상이라 고정돼야 의미가 있다.

- `--update-baseline`은 건수가 **줄어들 때만** 무비판적으로 써도 된다.
- 건수가 **늘어났을 때는 왜 늘었는지 먼저 확인**하고 나서 갱신한다 — 늘어난 채로 기준선을 덮으면 그 회귀를 영구히 통과시키게 된다.

## 프로젝트 구조

```
easy-dictionay/
├── DESIGN.md                 # 설계 문서 (근거·스키마·모듈 계약)
├── README.md                 # 이 문서
├── schema/
│   ├── schema.sql             # SQLite DDL (정본 스키마)
│   └── entry.schema.json      # easy_dict.json entries[] JSON Schema
├── data/
│   ├── 법제처_..._법령용어_20240912.csv  # 원천 아님. 적재 안 함(§데이터 출처와 라이선스)
│   └── raw/
│       ├── *.csv                 # 실제 원천 데이터(§데이터 출처와 라이선스 참고)
│       ├── *.known-errors.md     # 원천 자체의 알려진 오류 기록(우리가 임의로 안 고침)
│       └── sample/*.csv          # 가상 샘플 CSV (인코딩/헤더 변형 테스트용)
├── src/easydict/
│   ├── __init__.py             # 공개 심볼 재노출
│   ├── models.py                # Source/Variant/Example/Entry 데이터클래스, 태그 표준값
│   ├── normalize.py             # 한국어 정규화·변형형 생성 (NFC, 조사 경계, 활용형)
│   ├── build.py                  # CSV → SQLite 빌드 파이프라인
│   ├── export.py                 # SQLite → JSON 3종 익스포트
│   └── lookup.py                  # 조회/매칭/프롬프트 컨텍스트 생성
├── tools/                      # 보조 스크립트(갭 추출·예문 추출·불변식 검사 등) + tools/tests/
│   ├── check_invariants.py     # 층위 1 산출물 불변식 (dist/ 읽기 전용)
│   ├── audit_corpus.py         # 층위 2 코퍼스 통과 검사 (§빌드 검증)
│   └── audit_corpus.baseline.json  # 층위 2 기준선 — 커밋 대상(갭 리스트와 반대)
├── scripts/
│   └── check.sh                # 검사 진입점(§빌드 검증) — tests + tools/tests + 층위 1 불변식 + 층위 2 코퍼스 통과
├── docs/
│   └── inspection-plan.md      # 검사 도구 로드맵(층위 1~4, Phase 1~5)
└── tests/                      # 통합 테스트
```

## 개발

```bash
PYTHONPATH=src python3 -m unittest discover -s tests -v
```

전체 검증(테스트 + 산출물 불변식)은 [빌드 검증](#빌드-검증) 참고.
