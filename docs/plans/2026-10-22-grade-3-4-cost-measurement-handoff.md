# 운영 서버 작업 지침: grade_3_4 원가 측정

**받는 쪽:** 공개 파일럿 서버에서 일하는 운영자 또는 에이전트

**목적:** 이 문서 하나로 [측정 계획](2026-10-22-grade-3-4-cost-measurement.md)의 1~4단계를 운영 서버에서 끝낸다. 판정 기준과 이유는 계획 1·2절이 정본이다. 이 문서는 실행 순서와 명령을 다룬다.

**물을 질문:** 「더 쉽게」(`grade_3_4`)는 크레딧을 1.2배 받는다. 실제 provider 원가가 그 안에 드는가?

## 0. 공통 규칙

**배포본과 운영 서비스**

- 배포 저장소(아래 `DEPLOY`)는 읽기만 한다. `git pull`·`checkout`·파일 수정을 하지 않는다.
- 파일럿 컨테이너를 재시작하거나 재빌드하지 않는다.
- 작업은 모두 별도 clone(아래 `WORK`)에서 한다.
- 시험·빌드용 Docker 명령에는 항상 `-p easy-doc-lane`을 붙인다. 파일럿 컴포즈 프로젝트와 섞이지 않게 하기 위해서다.

**비밀값**

- `DEPLOY/.env`에서 필요한 키만 셸 변수로 읽는다.
- 값을 출력하거나, 파일로 옮기거나, 커밋하지 않는다.
- 결과에는 집계값만 쓴다. 원장에는 본문이 없고, 레인 transcript는 저장소 밖 `OUT`에만 둔다.

**자원**

- 빌드나 레인을 돌리기 전에 `free -m`을 확인한다. 가용 메모리가 4GB 미만이면 미룬다.
- 호스트 earlyoom은 메모리가 바닥나면 빌드 JVM을 파일럿 JVM보다 먼저 정리한다. 파일럿 JVM은 `oom_score_adj -500`이다(`compose.pilot.yml`). 그래도 이용이 적은 시간대에 돌린다.

**비용**

- 유료 LLM 호출은 4절(3단계)에서만 한다.
- 사용자가 범위와 상한을 확인하고 승인한 뒤에만 시작한다.

**저장소 반영**

- 기록 문서(`docs/**`)는 `main`에 직접 커밋·푸시한다. 2026-10-22 사용자 지시다.
- 코드는 브랜치와 PR로 반영한다.
- 푸시 전에 `git rev-list --left-right --count origin/main...HEAD`로 남의 커밋이 섞이지 않았는지 본다.

**저장소 규칙**

저장소의 `CLAUDE.md`·`AGENTS.md`를 따른다. Claude Code는 자동으로 읽는다. 그 밖의 에이전트는 먼저 읽는다. 특히 다음 세 가지다.

- 구현은 Sonnet 실행 에이전트가 하고, 실패하는 테스트를 먼저 쓴다.
- 리뷰는 저자와 다른 패스가 한다.
- 주석은 최소로 단다.

## 1. 준비 (한 번)

```bash
DEPLOY=/srv/easy-doc                    # 예시. compose.yml과 .env가 있는 배포 저장소 루트로 바꾼다
WORK="$HOME/easy-doc-work"
OUT="$HOME/easydoc-lane/2026-10-grade34"

[ -d "$WORK/.git" ] || git clone "$(git -C "$DEPLOY" remote get-url origin)" "$WORK"
git -C "$WORK" fetch origin && git -C "$WORK" switch main && git -C "$WORK" pull --ff-only
mkdir -p "$OUT"

docker compose version
gh auth status                          # PR 작성용
git -C "$WORK" push --dry-run origin main   # 푸시 권한 확인. "Everything up-to-date"면 된다
```

푸시 권한이나 `gh` 인증이 없으면 여기서 멈추고 사용자에게 알린다.

## 2. 1단계: 파일럿 원장 조회 (무료)

```bash
cd "$DEPLOY"
SQL="$WORK/docs/plans/2026-10-22-grade-3-4-cost-measurement.sql"
grep -n -i -E '\b(insert|update|delete|alter|drop|truncate|create|grant)\b' "$SQL" || echo "쓰기 구문 없음"
docker compose -f compose.yml -f compose.pilot.yml exec -T postgres sh -ec \
  'psql -X -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v ON_ERROR_STOP=1' < "$SQL" | tee "$OUT/stage1-ledger.txt"
grep -E '^EASYDOC_(LLM_PROVIDER|LLM_MODEL|LLM_EFFORT|LLM_MAX_OUTPUT_TOKENS|LLM_READ_TIMEOUT|LLM_INPUT_USD_PER_MILLION_TOKENS|LLM_OUTPUT_USD_PER_MILLION_TOKENS|READING_LEVEL_EXTRA_EASY_ENABLED)=' .env \
  | tee "$OUT/stage1-conditions.txt"
```

**실행 규칙**

- 「쓰기 구문 없음」이 나오지 않으면 실행하지 않는다.
- SQL은 `BEGIN READ ONLY … ROLLBACK`으로 돌고, 질의당 60초 제한이 걸려 있다.
- 오류가 나면 SQL을 고치지 말고 오류 원문과 함께 멈춘다.

**판정 순서**

1. 질의 ②의 운영 모델 행만 쓴다.
2. `0: 전체` 행의 `usd_per_charged_credit`로 수준별 크레딧당 원가 c56(`grade_5_6`)과 c34(`grade_3_4`)를 읽는다.
3. `R = c34 ÷ c56`을 계산한다.
4. `R_max,Pro = 0.25 × 544,545 ÷ (1,000 × c56 × 1,500)`을 계산한다. 단위는 원이고, c56은 달러다.
5. 계획 2절 판정표에 넣는다.
   - R ≤ 1.0: 1.2배 유지
   - 1.0 < R ≤ R_max,Pro: 1.2배 유지 + 기록 + 30일 재확인
   - R_max,Pro < R: 사용자 결정
6. 질의 ③(재변환)의 수준별 크레딧당 원가와 p95는 판정과 별도로 함께 적는다.

**2·3단계 생략 조건**

다음을 모두 만족하면 3·4절을 건너뛰고 5절로 간다.

- 길이 구간마다 두 수준 모두 `conversions` ≥ 30
- 구간별 R이 모두 판정표의 같은 칸에 든다

하나라도 아니면 3절로 간다.

**기록**

계획 문서 끝에 `## 6. 실행 기록`을 만들고(있으면 이어 쓰고) 다음을 적는다.

- 날짜, `WORK`의 커밋
- 측정 조건(`stage1-conditions.txt` 내용)
- 질의 ②의 `0: 전체` 행과 구간별 R
- R_max,Pro, 판정, 다음 단계

그다음 `main`에 커밋·푸시한다. 커밋 메시지는 `docs: record grade_3_4 ledger check`다.

## 3. 2단계: 유료 레인에 수준 노브 추가 (코드, PR)

```bash
cd "$WORK" && git fetch origin && git switch -c feat/lane-reading-level origin/main
git rev-list --left-right --count origin/main...HEAD    # 0 0이어야 한다
```

**바꿀 것**

변경은 평가 레인 코드에 한정한다. 대상 경로는 두 곳이다.

- `backend-kotlin/infrastructure/src/test/kotlin/kr/easydoc/infrastructure/quality/`
- `backend-kotlin/core/src/main/kotlin/kr/easydoc/core/quality/JudgeLane.kt`

1. **노브 `EASYDOC_LANE_READING_LEVEL`**
   - 값은 `grade_5_6`·`grade_3_4`다. 계약의 wire 이름을 쓴다.
   - `middle_school`은 받지 않는다. judge 프롬프트(`LlmPrompt.forJudge`)에 중학교 기준이 없어, 변환만 중학교 수준으로 가고 채점은 5~6학년 기준으로 남기 때문이다(PR #183 리뷰).
   - 미설정이면 `grade_5_6`이다.
   - 그 밖의 값이면 유료 호출 전에 실패시킨다.
2. **변환에 전달:** `GoldenCorpusLlmEvaluationTest`의 변환 호출에 수준을 넘긴다. `ConvertDocumentUseCase.convert`는 이미 `readingLevel` 인자를 받는다(`ConvertDocumentUseCase.kt:116`).
3. **judge에 전달:** `GoldenJudge`(`core/.../quality/JudgeLane.kt:40`)는 지금 `LlmPrompt.forJudge`를 수준 없이 부른다(`:48`).
   - `forJudge`는 이미 `readingLevel`을 받으므로(`core/.../llm/LlmPrompt.kt:113`), `GoldenJudge`에 수준 인자를 기본값 `grade_5_6`으로 더해 그대로 넘긴다.
   - `GoldenJudge`는 품질 평가 패키지(`core/.../quality`)와 그 시험에서만 쓰인다. 제품 변환 경로에서는 쓰이지 않는다(2026-10-22 사용처 확인).
   - 기존 호출은 바꾸지 않는다.
4. **조건 줄 표시:** 조건 줄(`conditions.txt`와 리포트)에 `readingLevel=<값>`을 찍는다.
5. **문서별 항목:** 리포트의 문서·반복별 줄에 아래 항목이 없으면 더한다. 이미 있는 항목은 그대로 쓴다.
   - 원문 글자 수. 제품 과금과 같은 코드포인트 기준이다.
   - 출력 글자 수, 보정 여부
   - 변환+보정 비용과 judge 비용(분리)
   - 생성 지연, 시간 초과·절단 여부

**바꾸지 않을 것:**

- 제품 변환·과금 경로. `core/.../quality` 밖의 `src/main`이다.
- 계약
- 과금

**테스트** (먼저 실패시키고 통과시킨다, 유료 호출 없음)

- 노브 해석: 미설정, 세 값, 잘못된 값
- fake provider로 `grade_3_4`일 때 두 가지를 확인한다.
  - 변환 프롬프트가 더 쉬운 단계 역할 문구(`Prompts.kt`의 `EXTRA_EASY_ROLE`)를 쓰는지
  - judge 시스템 프롬프트가 「초등학교 3~4학년」인지

**검증** (CI와 같은 컨테이너, `free -m` 먼저)

```bash
cd "$WORK"
docker compose -p easy-doc-lane -f compose.yml -f compose.ci.yml --profile ci run --rm --build backend-check
```

**PR과 리뷰**

1. `gh pr create --base main`으로 PR을 연다.
2. 저자와 다른 패스의 리뷰어(Claude면 `oh-my-claudecode:code-reviewer`를 `model: sonnet`으로)가 diff를 본다.
3. 판정을 PR 코멘트로 남긴다. 지적이 0건이어도 남긴다. 형식은 아래와 같다.

```markdown
## 리뷰 판정 — code-reviewer (sonnet, 저자와 다른 패스)

**BLOCKER n · HIGH n · MEDIUM n · LOW n — 머지 가능 / 머지 불가**

### 리뷰어가 코드로 확인한 것
- <사실과 근거 `파일:줄`>

### 지적
- **HIGH** `파일:줄` — <어떤 입력에서 무엇이 깨지는가>

### 확인 불가
- <보지 못한 것과 사유>
```

4. 지적을 고친 뒤에는 조치 코멘트를 새로 단다. 기존 코멘트를 덮어쓰지 않는다.
5. 머지 조건: 판정이 「머지 가능」이고 CI가 초록이다. 둘 다 만족하면 `gh pr merge --merge`로 머지한다.
6. `gh pr view --json state`가 `MERGED`인 것을 확인한 다음에 브랜치를 지운다.

## 4. 3단계: 짝지은 유료 측정 (사용자 승인 필요)

**승인 요청**

아래 문구로 묻고, 명시적인 승인을 받은 뒤에만 진행한다.

> 3단계 유료 측정을 진행할까요? 골든 문서 8건(070·087·023·072·077·088·003·105, 약 17,800자)을 `grade_5_6`과 `grade_3_4`로 각 2회 변환합니다. provider·model·effort는 파일럿과 같습니다. 예상 비용은 약 US$12.5, 상한은 US$18입니다(`grade_5_6` 7달러 + `grade_3_4` 11달러, 레인이 상한에서 멈춤).

**준비**

1. 3절 PR이 머지된 `main`으로 맞춘다.
2. 필요한 키만 읽는다.
3. 단가가 비어 있으면 레인이 시작하지 않으므로 먼저 확인한다.

```bash
cd "$WORK" && git switch main && git pull --ff-only
set -a
. <(grep -E '^(OPENAI_API_KEY|ANTHROPIC_API_KEY|EASYDOC_LLM_(PROVIDER|MODEL|EFFORT|MAX_OUTPUT_TOKENS|READ_TIMEOUT|INPUT_USD_PER_MILLION_TOKENS|OUTPUT_USD_PER_MILLION_TOKENS))=' "$DEPLOY/.env")
set +a
[ -n "$EASYDOC_LLM_INPUT_USD_PER_MILLION_TOKENS" ] && [ -n "$EASYDOC_LLM_OUTPUT_USD_PER_MILLION_TOKENS" ] \
  && echo "단가 설정됨" || echo "단가 미설정 — 중단하고 사용자에게 알린다"
```

**실행** (같은 날 `grade_5_6` 다음 `grade_3_4` 순서, `free -m` 먼저, 수준당 수십 분)

```bash
run_level() {
  docker compose -p easy-doc-lane -f compose.yml -f compose.ci.yml --profile ci run --rm --build \
    -v "$OUT":/lane-out \
    -e OPENAI_API_KEY -e ANTHROPIC_API_KEY \
    -e EASYDOC_LLM_PROVIDER -e EASYDOC_LLM_MODEL -e EASYDOC_LLM_EFFORT \
    -e EASYDOC_LLM_MAX_OUTPUT_TOKENS -e EASYDOC_LLM_READ_TIMEOUT \
    -e EASYDOC_LLM_INPUT_USD_PER_MILLION_TOKENS -e EASYDOC_LLM_OUTPUT_USD_PER_MILLION_TOKENS \
    -e EASYDOC_LANE_DOCUMENTS=070,087,023,072,077,088,003,105 \
    -e EASYDOC_LANE_RUNS=2 -e EASYDOC_LANE_DICT_PRODUCT=1 \
    -e EASYDOC_LANE_READING_LEVEL="$1" -e EASYDOC_LANE_MAX_USD="$2" \
    -e EASYDOC_LANE_TRANSCRIPT_DIR="/lane-out/transcript-$1" \
    backend-check ./gradlew --no-daemon :infrastructure:testLlm --tests '*GoldenCorpusLlmEvaluationTest' \
    > "$OUT/lane-$1.log" 2>&1
}
cd "$WORK"
run_level grade_5_6 7
run_level grade_3_4 11
```

**환경변수 전달:** `-e 키`처럼 값을 적지 않으면 셸의 값이 컨테이너로 넘어간다. 2026-10-22에 로컬에서 확인했다. 그래서 키가 명령줄에 남지 않는다.

**읽는 법**

- 레인은 판정 실패가 있으면 AssertionError로 끝나서 종료 코드가 1이다. 이는 정상이다.
- 리포트는 stdout과 AssertionError 메시지에 두 번 실린다. 문서별 줄을 셀 때는 중복을 걷어 낸다(`sort -u`).
  - 문서별 줄은 `070#run1`처럼 회차가 붙어 있어 `sort -u`로 걸러도 회차가 합쳐지지 않는다. 그 아래 「사라진 사실」 보조 줄에는 회차가 없으므로 `sort -u` 결과로 세지 않는다.
  - 변환문 파일명은 `070-run1.txt` 형식이다. 리포트 줄의 `#run1`과 회차 번호는 같다.
  - judge 실패 줄의 재시도 수에는 같은 회차의 변환 재시도도 섞여 있다.
- 실패한 시도(타임아웃·429)의 토큰 비용은 레인 비용 집계에 들어가지 않는다. 시간 초과·재시도 건수를 함께 적어 c34가 과소 추정될 수 있음을 기록에 남긴다.
- 상한에 걸려 멈추면 그때까지의 결과를 그대로 기록하고 사용자에게 알린다.
- 105에서 읽기 타임아웃(120초)이 나면 실패로 지우지 말고 그대로 기록한다. D12 상한을 정할 근거다.

## 5. 4단계: 계산과 기록

**계산** (변환+보정 비용만 쓴다. judge 비용은 따로 적는다)

- **문서별 청구 크레딧**
  - `grade_5_6`: `ceil(글자 수 / 100) / 10`
  - `grade_3_4`: `ceil(ceil(글자 수 / 100) × 1.2) / 10`
- **c56, c34:** 수준별 `Σ비용 ÷ Σ청구 크레딧`이다. 문서 8건 × 2회를 모두 더한다.
- **R:** `c34 ÷ c56`이다. 문서별 R의 최소·최대도 함께 적는다.
- **M:** `Σ비용(grade_3_4) ÷ Σ비용(grade_5_6)`이다. 같은 문서·같은 반복끼리의 원가 배율이다.
- **출력 팽창:** 수준별 `출력 글자 수 ÷ 원문 글자 수`의 중앙값과 최대값이다. 보정률, 지연 중앙값과 최대값, 시간 초과 건수도 함께 낸다.
- **R_max,Pro:** 이번에 잰 c56으로 다시 계산한다.
- **판정:** 계획 2절의 판정표로 정한다.

**기록**

1. **계획 문서:** `## 6. 실행 기록`에 다음을 적는다.
   - 조건: 커밋, provider·model·effort, 문서·반복 수, 실제 지출
   - 문서×반복 표
   - c56·c34·R·M·R_max,Pro, 판정
2. **가격 정본:** `docs/plans/2026-09-13-service-pricing.md`의 「`grade_3_4`의 실제 원가는 아직 측정하지 않았다」 문장을 측정값과 판정으로 바꾼다.
   - 판정이 판정표 둘째 칸 이상이면 수익성 표에 `grade_3_4` 전량 사용 가정의 행을 더한다.
   - 산식은 표 아래 산식과 같고, 크레딧당 원가만 c34로 바꾼다.
3. **읽기 수준 계획:** `docs/plans/2026-09-24-reading-level-focused-review.md`의 D12 근거에 수준별 출력 팽창·지연·시간 초과 수치를 더한다. 상한은 정하지 않는다.
4. **커밋:** 세 문서를 `main`에 직접 커밋·푸시한다. 커밋 메시지는 `docs: record grade_3_4 cost measurement`다.

**사용자에게 보고**

- 첫 줄에 판정을 쓴다. 1.2배가 원가를 덮는지다.
- 그다음 R, M, 표본 크기, 실제 지출, 남은 결정을 쓴다. R이 R_max,Pro를 넘으면 배율·모델/effort·Pro 제공량 중 무엇을 바꿀지가 남은 결정이다.

## 6. 막혔을 때

| 상황 | 할 일 |
|---|---|
| 푸시 권한·`gh` 인증 없음 | 멈추고 사용자에게 알린다 |
| psql 오류 | SQL을 고치지 않고 오류 원문과 함께 멈춘다 |
| 레인이 유료 호출 전에 설정 오류로 끝남 | 비용이 들지 않았다. 원인(키·단가·노브)을 고치고 다시 돌린다 |
| 달러 상한 도달 | 부분 결과를 기록하고 추가 승인 없이 다시 돌리지 않는다 |
| 메모리 부족·earlyoom 종료 | 멈추고 이용이 적은 시간에 다시 한다. 파일럿 컨테이너 상태(`docker compose -f compose.yml -f compose.pilot.yml ps`)를 확인해 사용자에게 알린다 |
| 계획과 다른 판단이 필요함 | 진행하지 말고 근거와 함께 사용자에게 묻는다 |
