# Handoff: grade_3_4 원가 측정 1단계 (파일럿 서버)

**받는 쪽:** 공개 파일럿 서버에서 일하는 운영자 또는 에이전트

**목적:** 파일럿 DB의 비용 원장 `llm_calls`를 **읽기 전용으로** 조회한다. 그 집계 결과를 이 측정을 진행하는 세션에 돌려준다. 판정은 받는 쪽이 하지 않는다.

**배경:** `docs/plans/2026-10-22-grade-3-4-cost-measurement.md`의 1단계다. 「더 쉽게」(`grade_3_4`)는 크레딧을 1.2배 받는다. 이 조회로 실제 provider 원가가 그 안에 드는지 본다.

## 하지 않는 것

- **DB 쓰기 금지:** 쓰기 질의를 실행하지 않는다. SQL 파일은 `BEGIN READ ONLY`로 시작해 `ROLLBACK`으로 끝난다.
- **배포 상태 유지:** 배포된 작업 트리를 `git pull`·`checkout`으로 바꾸지 않는다. 컨테이너를 재시작하거나 재빌드하지 않는다.
- **비밀값 출력 금지:** `.env`의 비밀값(API 키·DB 비밀번호·암호화 키)을 출력하거나 옮기지 않는다. 아래 3번에 적은 키만 읽는다.
- **결과 저장 금지:** 결과를 저장소에 커밋하지 않는다. 응답으로만 돌려준다.

## 절차

배포 저장소 루트(`compose.yml`이 있는 곳)에서 실행한다.

**1. SQL 파일 받기.** 작업 트리를 바꾸지 않고 GitHub `main`에서 파일만 꺼낸다.

```bash
git fetch origin main
SQL_FILE="$(mktemp)"
git show origin/main:docs/plans/2026-10-22-grade-3-4-cost-measurement.sql > "$SQL_FILE"
git rev-parse --short origin/main
grep -n -i -E '\b(insert|update|delete|alter|drop|truncate|create|grant)\b' "$SQL_FILE" || echo "쓰기 구문 없음"
```

마지막 줄이 「쓰기 구문 없음」이 아니면 실행하지 말고 그 출력을 그대로 돌려준다.

**2. 조회 실행.**

```bash
docker compose -f compose.yml -f compose.pilot.yml exec -T postgres sh -ec \
  'psql -X -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v ON_ERROR_STOP=1' < "$SQL_FILE"
```

- 출력은 ①·②·③ 세 개의 표다.
- 오류가 나면 SQL을 고치지 말고 오류 메시지 원문을 돌려준다.
- 질의마다 60초 제한이 걸려 있다.

**3. 측정 조건 확인.** 비밀값이 아닌 키만 읽는다.

```bash
grep -E '^EASYDOC_(LLM_PROVIDER|LLM_MODEL|LLM_EFFORT|LLM_MAX_OUTPUT_TOKENS|LLM_READ_TIMEOUT|LLM_INPUT_USD_PER_MILLION_TOKENS|LLM_OUTPUT_USD_PER_MILLION_TOKENS|READING_LEVEL_EXTRA_EASY_ENABLED)=' .env
```

없는 키는 「미설정」으로 적는다. 미설정이면 제품 기본값이 적용된다.

## 돌려줄 것

아래 형식 그대로 돌려준다.

````markdown
## grade_3_4 원가 1단계 결과

- 실행 시각(KST):
- origin/main 커밋:
- 배포 중인 커밋(`git rev-parse --short HEAD`):

### 조회 출력
```text
(psql 출력 전체)
```

### 측정 조건
```text
(3번 grep 출력, 없는 키는 미설정)
```

### 특이 사항
- 운영자 시험 사용이 섞였는지 (아는 경우만)
- 오류나 경고
````

## 받은 뒤 세션이 할 일

받는 쪽은 아래를 하지 않는다. 결과를 받은 세션이 한다.

- 계획 2절의 판정표로 판정한다.
- 생략 조건을 확인한다. 길이 구간마다 수준별 완료 변환이 30건 이상이고 판정 구역이 같은지 본다.
- 2·3단계로 갈지 정한다.
- 가격 정본에 기록한다.
- ③은 재변환 1회를 시간 간격으로 묶은 근사다. 같은 문단을 3분 안에 다시 재변환한 경우 한 번으로 합쳐질 수 있으므로, 결과를 이 한계와 함께 읽는다.
