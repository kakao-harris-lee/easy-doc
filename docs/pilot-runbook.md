# Kotlin Lean MVP 파일럿 실행서

## 전제

- Docker Engine과 Docker Compose v2
- `.env.example`을 복사한 `.env`
- `EASYDOC_AUTH_JWT_SECRET`, `EASYDOC_ENCRYPTION_KEY_V1`, `EASYDOC_ENCRYPTION_KCV_V1`
- 실제 변환을 확인할 때만 선택한 provider API key. 유료 호출 없이 상태만 보려면 `EASYDOC_LLM_PROVIDER=fake`(Compose가 `local` 프로필을 켠다)

worker는 lease를 집어 마스킹 → LLM → 결과 저장까지 실행한다. 내보내기는
`GET /conversions/{conversion_id}/export?format=docx|txt|hwpx`다. `pdf`는 계약상 422다.

## 전체 스택

```bash
cp .env.example .env
docker compose -f compose.yml config
docker compose -f compose.yml up -d --build --wait
docker compose -f compose.yml ps
```

- 화면: `http://127.0.0.1:8080`
- API: `http://127.0.0.1:8100`
- health: `http://127.0.0.1:8100/health`

서비스 흐름은 다음과 같다.

```text
browser -> frontend/nginx -> backend-api -> PostgreSQL
                                |
                                +-> lease queue -> backend-worker
```

`backend-api`가 기동하면서 Flyway로 스키마를 적용한다. `backend-worker`는 API가 healthy 한 뒤 시작한다.

## 확인 순서

1. `/health`가 200인지 확인한다.
2. 가입·로그인·작업 공간 생성이 되는지 확인한다.
3. 텍스트와 지원 파일(DOCX/PDF/HWPX)의 등록 응답이 202인지 확인한다.
4. 변환 상태가 `pending`을 지나 `done` 또는 `failed`로 끝나는지 확인한다. fake provider면 본문은 고정 문장이다.
5. 로그에 문서 본문, 개인정보, API key가 없는지 확인한다.

## 로그와 종료

```bash
docker compose -f compose.yml logs -f backend-api backend-worker
docker compose -f compose.yml down
```

`down -v`는 로컬 DB 볼륨을 삭제하므로 데이터 폐기가 명시적으로 필요할 때만 사용한다.

## CI와 같은 독립 프로젝트 검증

```bash
docker compose -f compose.yml -f compose.ci.yml run --rm backend-check
docker compose -f compose.yml -f compose.ci.yml run --rm frontend-check
```

실제 LLM 호출, 외부 배포, 기관 데이터 사용은 별도 승인과 비밀값 관리 없이 실행하지 않는다.

---

## 게이트 ① 판정

`docs/master-plan.md` §9의 **게이트 ①**(파일럿 실무자 검증 → 단계 2 진행)을 판정하는 절차다.
성공 기준의 정본은 §4.0의 두 문장이다 — 파일럿 실무자가 실제 문서 10건을 처리했을 때
① 외주 대비 시간이 유의미하게 단축되고 ② "이 결과물을 다듬어 실제로 배포하겠다"고 답하는 것.

위 「확인 순서」는 스택이 도는지 보는 스모크 테스트이고, 이 절은 **제품이 통과했는지**를 본다.
둘은 다른 판정이다.

### 대상과 규모

- 파일럿 기관 1~2곳(§7 KPI의 「파일럿 기관 1~2곳 확보」와 같은 규모).
- 실무자가 **실제 업무 문서 10건**을 처리한다.
- **10건은 기관 합산이다.** 한 사람이 10건을 채워야 하는 것이 아니고, 기관이 둘이면
  합쳐서 10건이면 된다. 다만 한 사람이 10건을 전부 채우면 표본이 한 사람의 취향이 되므로,
  가능하면 실무자 2인 이상으로 나눈다.

### 사전 조건

- **실제 LLM provider 키와 유료 호출 승인.** `EASYDOC_LLM_PROVIDER=fake`는 고정 문장을
  돌려주므로 품질 판단이 성립하지 않는다 — fake로 채운 10건은 이 게이트의 표본이 아니다.
  비용과 범위는 사용자가 승인한 뒤에 켠다(프로젝트 `CLAUDE.md` 모델·비용 정책).
- **개인정보가 든 문서는 지양하도록 안내한다.** 마스킹 범주는 주민등록번호·카드번호
  2종뿐이고(master-plan §3.2), 전화·이메일·계좌는 가려지지 않은 채 provider로 나간다.
  §8 리스크 표의 「축소한 범주의 개인정보가 그대로 전송」이 이 자리다.
- 기관의 **기존 방식 소요**를 착수 전에 인터뷰로 받아 아래 「기존 방식 소요」 칸에 적는다.
  이 값이 없으면 기준 ③을 판정할 대조군이 없다(기준 ①은 배포 의향 건수라 대조군이 필요 없다).

### 기록 방법

수기 입력은 **검수 화면 하단의 피드백 폼 제출 한 번**이 전부다
(배포 의향 · 품질 만족도 1~5 · 이번 건 소요 시간(분) · 자유 의견(선택)).
나머지는 시스템이 남긴다 — 처리 건수·상태·토큰은 `conversions`가, 소요 지표와 수정률은
피드백 저장 시점에 계산돼 `conversion_feedback`에 평문 숫자로 들어간다.

피드백 표는 **문서 30일 보존 파기와 분리돼 있다**(FK 없음). 문서가 파기돼도 판정 근거는 남는다.
자유 의견만 AEAD로 봉인되므로 집계 스크립트는 읽지 않는다 — 열람이 필요하면
소유자 토큰으로 화면에서 본다.

### 통과 기준 — **제안값이며 착수 전 승인이 필요하다**

아래 숫자는 아직 승인되지 않았다. 파일럿을 시작하기 전에 사용자가 확정하고 이 절을 고친다.

| # | 기준 | 제안값 | 판정 |
|---|---|---|---|
| 1 | 배포 의향이 `as_is`(그대로 쓸 수 있다) 또는 `with_edits`(조금 고쳐서 쓰겠다)인 건수 | 10건 중 **8건 이상** | 스크립트 |
| 2 | 품질 만족도 평균 | **3.5 이상** | 스크립트 |
| 3 | 문서 1건 소요 시간의 중앙값이 기존 방식 소요 대비 유의미하게 짧을 것 | — | **사람** |

- 기준 3은 자동 판정하지 않는다. 기존 방식 소요는 기관마다 다르고(외주 발주 리드타임이
  주 단위인 곳과 내부 인력이 하루에 끝내는 곳이 같은 임계값을 쓸 수 없다) 그 값은
  인터뷰로만 들어온다. 스크립트는 **중앙값을 내고, 판단은 사람이 한다.**
- **표본 10건은 통계적으로 작다.** §7의 경고("경계값에서의 재실행·표본 확대 판단은 사람이
  하며, 자동 재시도로 가리지 않는다")를 그대로 적용한다. 8/10과 7/10의 차이는 한 사람의
  그날 기분만큼도 안정적이지 않다 — 경계값이면 표본을 늘리거나 다시 돌린다.

**기존 방식 소요(인터뷰 기록)**

| 기관 | 문서 종류 | 기존 방식 | 1건 소요 | 출처·일자 |
|---|---|---|---|---|
| _(파일럿 착수 시 채운다)_ | | | | |

### 집계

문서 보존 만료(기본 30일) **전에** 실행한다. 지표 표는 파기 대상이 아니지만, 판정 중
원문·변환 결과를 대조해야 할 때 그쪽은 이미 사라져 있다.

```bash
docker compose -f compose.yml exec -T postgres \
  psql -U postgres -d easydoc -f - < scripts/pilot-report.sql
```

### 파일럿 종료 정리 — `conversion_feedback`

**순서는 집계 → 판정 기록 → 이 정리다.** 아래 「판정 기록」을 남긴 **직후**에 실행한다.
집계 전이나 판정 기록 전에 실행하면 판정 근거를 스스로 지우는 것이 된다.

판정이 끝나면 `conversion_feedback`을 정리한다. 이 표는 **문서 30일 파기의 사슬 밖**이다 —
파기는 `documents` → `conversions` → `conversion_jobs`로만 이어지고 피드백 표에는 FK가 없다
(판정 근거를 남기려고 의도한 설계다). 그 결과 이 표에는 TTL도 purge도 없어서
**아무도 지우지 않으면 영구히 남는다.**

자유 의견 칸이 문제다. AEAD로 봉해 두었지만 **봉인은 기밀성이지 삭제가 아니다** — 키는
운영 마스터 키라 계속 열린다. 그리고 그 칸에는 검수자가 문서 본문 조각을 그대로 붙여 넣는
일이 실제로 일어나고(`V2__conversion_feedback.sql`의 주석 — "○○동 ○○○님께 안내드립니다
부분이 어색합니다"), 마스킹 범주는 주민등록번호·카드번호 2종뿐이라(master-plan §3.2)
그 조각의 **이름·주소·전화번호는 어디서도 가려지지 않는다.**

**선택지는 둘이다.**

**⒜ 자유 의견만 비운다 (기본 권고)**

`comment_encrypted`·`encryption_scheme`·`key_version` 세 열을 `NULL`로 만든다.
개인정보가 들어오는 칸이 사라지고 **척도 숫자(배포 의향·품질 만족도·소요 시간)와
수정률 지표는 남는다.** 나중에 판정을 되짚거나 영업·계약 자료로 쓸 수 있다 —
없앨 이유가 있는 것은 본문 조각이지 본문에 대한 척도가 아니다. 그래서 이쪽이 기본이다.

```bash
# 세 열을 반드시 함께 NULL 로 만든다. 스키마에 「셋이 함께 있거나 함께 없다」 CHECK 가
# 걸려 있어(ck_conversion_feedback_comment_scheme_paired,
# ck_conversion_feedback_comment_key_version_paired) 하나만 비우면 거절된다.
docker compose -f compose.yml exec -T postgres \
  psql -U postgres -d easydoc -c \
  "UPDATE conversion_feedback SET comment_encrypted = NULL, encryption_scheme = NULL, key_version = NULL;"
```

**⒝ 표를 통째로 지운다**

판정 기록 문서(`docs/plans/`)에 집계 출력과 결론을 이미 남긴 뒤라면 이쪽이 깔끔하다.
표가 사라지므로 되짚을 근거는 그 문서에만 남는다.

```bash
docker compose -f compose.yml exec -T postgres \
  psql -U postgres -d easydoc -c "DELETE FROM conversion_feedback;"
```

**이것이 수기 절차인 이유**: 제품에 삭제 요청을 처리하는 경로가 아직 없다 — 계정 삭제
기능 자체가 없다(계약 `contracts/easy-doc-v1.yaml`의 오퍼레이션에 없다). master-plan §3.2가
약속한 "삭제 요청 시 즉시 파기"를 이 표에 대해서는 사람이 대신 실행하는 것이다.
자동화는 그 경로가 제품에 생길 때 함께 선다(`docs/kotlin-redevelopment-backlog.md`
「1.1 추후 개선 항목」).

### 판정 기록

- 판정 근거와 결론을 `docs/plans/`에 판정 기록 문서로 남긴다(집계 출력·인터뷰 값·사람 판단).
- `docs/master-plan.md` §9의 게이트 ① 줄에 결과를 반영한다.
