# G-β 1차 독립 리뷰 (Claude) — C4 `GET /documents` · C5 `DELETE /documents/{id}` + R-4~R-10

**회차:** 1차(독립). **codex 산출물을 읽지 않았다** — 같은 시점에 codex 레인이 독립 리뷰를 쓰는 중이고,
교차 종합은 3단계 재호출에서 한다. 이 파일은 교차 대조표를 담지 않는다.

**심판 대상:** `git log --oneline 81ba9fa..19062cc` 전 범위. HEAD = `19062cc`
(브랜치 `feat/kotlin-migration-harness`, 원격 푸시됨).

**참조한 정본:**
`docs/plans/2026-08-11-kotlin-react-migration.md` §2.2 · §2.3 · §3.1 · §3.2 · §4.4 · §4.5 · §4.6 · §5 · §6 ·
§9.1 · §9.2-ter / `contracts/easy-doc-v1.yaml` (v1.4.0, 이 배치에서 **무변경** — 실측 `git diff --stat 81ba9fa..19062cc -- contracts/` 산출 0) /
`.claude/skills/kotlin-migration/SKILL.md` 「선언한 범위와 실제 도달을 대조한다」 /
`.claude/skills/migration-safety-gate` I-4 · I-7 · I-8 · I-9 · I-10 /
`docs/migration/_workspace/00_progress.md` L-⑳ ~ L-㉖ ·
`04_kotlin-implementer_documents.md`(C4-R1~R9) · `..._delete.md`(C5·R-10) · `..._delete-plan.md` ·
`..._improvement-backlog.md`(B-10~B-23) · `04_contract-keeper_documents-test-spec.md`(DL-1~DL-11 · DD-1~DD-7 정본).

---

## 0. 이 회차가 실제로 실행한 것 / 하지 않은 것

「돌리지 않은 것을 돌린 것처럼 적지 않는다」는 이 저장소의 규율이라 먼저 적는다.

| 실행한 것 | 결과 |
|---|---|
| `uv run ruff check .` | **All checks passed** (exit 0) |
| `uv run ruff format --check .` | **1 file would be reformatted** — `tests/test_kotlin_gate_reach.py` (→ Cβ-1) |
| `uv run mypy . .claude` | Success: 139 source files |
| `uv run python .claude/skills/migration-safety-gate/scripts/scan_privacy_invariants.py` | exit 0 (BLOCK 0) |
| `uv run pytest -q` | **1480 passed**, 68 skipped, 5 deselected, 5 xfailed (79초) |
| `gh run view` (CI 실측, 3개 런) | HEAD `19062cc` 런 32455246832 — **quality = failure** · kotlin/frontend/e2e = success · llm-lane = in_progress. 앞 런 32451895280(`a559678`) — **llm-lane = failure**(차단축), frontend = failure(플레이크, 앞뒤 런 3회 success 실측) |
| `git rev-list HEAD -- <경로>` 3자리 | 이름 바꾼 파일 1 / 옛 이름 2 / 안 바꾼 핀 파일 5 (→ Cβ-5) |
| 라쳇 정규식 파서 대 실제 선언 대조(읽기 전용 스크립트) | 정규식 28키 == 실제 28키, 값 불일치 0, 스칼라 3개 일치 (→ Cβ-9) |
| 스키마 FK 전수 확인 (`V1`·`V5`) | `documents` 를 참조하는 FK 는 `conversions` **하나**이고 `ON DELETE CASCADE`; `conversion_jobs → conversions` 도 CASCADE — C5 의 연쇄 선언이 스키마와 일치 |

| 실행하지 않은 것 | 사유 |
|---|---|
| Gradle 빌드·ktlint·detekt·Testcontainers 스위트 | 이 회차에서 돌리지 않았다. Kotlin 쪽 초록의 근거는 **CI `kotlin` 잡 success(런 32455246832) 하나**이고, 그 잡이 `--rerun-tasks` 없이 돈다는 사실은 Cβ-2 의 대상이다 |
| Cβ-2·Cβ-3·Cβ-5 의 **종단 재현** | 코드 수정 금지 지시. 기제는 실측했고 종단은 **추정**으로 표시한다 |
| Cβ-7 의 multipart 팔 실행 | 코드 독해 추정 |
| AEAD round-trip·변조 거부·키 회전·Argon2·JWT 재감사 | 이 배치가 그 경로를 건드리지 않았다 → **미검토(범위 밖)** |
| parity 리포트·도메인별 mismatch | 이 배치에 parity 도메인 변경 0 → **미검토(범위 밖)** |
| CORS 노출 헤더 재검증 | `CorsContractTest` 변경이 주석뿐 → **미검토(범위 밖)** |

---

## 1. 판정 요약

| ID | 축 | 심각도 | 마감 |
|---|---|---|---|
| **Cβ-1** | 도달 범위(테스트 적정성) | **차단 ② 장치** | 즉시 |
| **Cβ-2** | 도달 범위(테스트 적정성) | **판정 필요**(하한 수정 필요, 차단 상향 후보) | C6 착수 전 |
| **Cβ-3** | 보안 불변식 / 도달 범위 | 수정 필요 | 즉시(C5 마감 조건) |
| **Cβ-4** | 테스트 적정성 / 도달 범위 | 수정 필요 | C6 착수 전 |
| **Cβ-5** | 도달 범위(테스트 적정성) | 수정 필요 | C6 착수 전 |
| **Cβ-6** | 계약 준수 | 수정 필요 | C6 착수 전 |
| **Cβ-7** | 계약 준수 / parity 위험 | 판정 필요 | C6 착수 전 |
| **Cβ-8** | 도달 범위(계약 준수) | 수정 필요 | C6 착수 전 |
| **Cβ-9** | 테스트 적정성 | 권고 | 다음 하네스 회차 |
| **Cβ-10** | 테스트 적정성 | 권고 | — |
| **Cβ-11** | Kotlin/Spring 관용성 / 도달 범위 | 권고 | C7 |
| **Cβ-12** | Kotlin/Spring 관용성 | 권고 | — |
| **Cβ-13** | 계약 준수 | 권고 | — |
| **Cβ-14** | 계약 준수 | 권고 | P2(`app/**` 삭제) 전 |
| **Cβ-15** | 문서·원장 | 권고 | — |
| **Cβ-16** | 게이트 신호 정합 | **판정 필요** | G-β 종료 보고 |

리더 판정 오류 넷의 독립 평가는 §7 에 따로 있다.

---

## 2. 계약 준수

### Cβ-6 (수정 필요) — `/health` 가 계약 미선언 **500** 을 낼 수 있고, 주석은 「항상 200」이라고 전칭으로 적는다

- 근거 ①: `backend-kotlin/application/src/main/kotlin/kr/easydoc/application/health/HealthDiagnosis.kt`
  `diagnose()` 의 `require(names.size == names.distinct().size)` — 이름이 겹치면 `IllegalArgumentException`.
  도메인 예외가 아니므로 `GlobalExceptionHandler` 의 `Exception` 백스톱으로 떨어져 **500** 이다.
- 근거 ②: `backend-kotlin/application/src/test/kotlin/.../HealthDiagnosisTest.kt`
  `이름이 겹치면 끊는다` 가 그 던짐을 **단언**한다 — 「일어날 수 없다」가 아니라 「일어나게 설계했다」다.
- 근거 ③: 실측 — 계약 `paths./health.get.responses` 의 키 집합이 **`['200']` 하나**다
  (다른 13개 오퍼레이션은 전부 `500`·`503` 을 선언한다).
- 근거 ④: 전칭 선언 두 자리. `HealthController` KDoc *"## 항상 200 이다 … `degraded` 여도 503 을 내지 않는다"*,
  `DependencyProbe` KDoc *"예외가 여기서 올라오면 `/health` 자신이 5xx 가 되고 … 「항상 200」이 깨진다"*.
  둘 다 그 상태를 인정하면서 전칭을 유지한다.

**이것이 왜 이 배치의 지적인가.** L-㉓ ⒞ 가 「계약이 선언하지 않은 상태 코드가 어느 엔드포인트에서든
나가는 것」을 **인스턴스는 닫고 종류는 열어 둔** 채 게이트 28 로 올렸다. P-8 이 그 종류의 **새 인스턴스를
만들었다** — 종전 `/health` 는 상수 응답이라 500 경로가 원리적으로 없었다.

**오늘 도달은 0이다**(두 probe 이름이 `database`·`queue` 로 다르다). 그래서 사건이 아니라 선언 결함이다.

**처방 판정 요청** — ⑴ 이름 중복을 **기동 실패**로 옮긴다(배선 버그이므로 요청 시점 500 보다 기동 실패가
옳고, 그러면 「항상 200」이 참이 된다), 또는 ⑵ 계약에 500 을 선언하고 두 KDoc 의 전칭을 사실로 고친다.
⑴ 이 「항상 200」의 근거(오케스트레이터가 멀쩡한 프로세스를 재시작하지 않게 한다)와 일관된다.

### Cβ-7 (판정 필요) — 같은 이름 `workspace_id` 의 빈 값 처리가 **세 갈래**이고 계약은 하나만 적었다

| 자리 | 입력 | 관측 | 계약 |
|---|---|---|---|
| multipart `workspace_id` | `""` | `parseWorkspaceId` 의 `value.isNullOrEmpty()` → `null` → 기본 공간 → **202** | *"빈 문자열은 미지정과 같게 다룬다"* — **명시** |
| multipart `workspace_id` | `" "` | `UUID.fromString(" ")` 실패 → `InvalidInputException` → 422 **문자열** | 미선언 |
| 쿼리 `workspace_id` | `""` · `" "` | R-6 이후 **422 배열**(`uuid_parsing`) | `anyOf[uuid, null]` 뿐 — **빈 값 조항 없음** |

- 근거: `DocumentController.parseWorkspaceId` (`if (value.isNullOrEmpty()) return null`) ·
  `TypedValueSlotInterceptor.rejectBlank` · 실측한 계약 노드 `DocumentFileRequest.properties.workspace_id.description`
  대 `paths./documents.get.parameters[?workspace_id]`.
- **multipart 팔은 실행하지 않았다 — 코드 독해 추정이다.**

**R-6 이 이것을 계약 레인에 올렸고(⑴ 「빈 값」 조항 부재 ⑵ 같은 이름의 비대칭) 계약은 한 줄도 바뀌지
않았다.** 그래서 지금 상태는 「제품이 계약을 위반했다」가 아니라 **「같은 이름에 세 동작이 있고 계약이
그중 하나만 정했다」**다. R-6 의 불변식(「반영할 것이 없으면 성공하지 못한다」)의 **반례가 제품 코드에
살아 있고 그것을 계약이 축복한다** — 그 사실이 불변식 문면에 없다.

**판정 요청**: ⓐ 쿼리를 multipart 와 같게(빈 문자열 = 미지정), ⓑ multipart 를 쿼리와 같게(빈 문자열 거절),
ⓒ 비대칭을 계약에 명시. 마감을 C6 앞으로 두는 이유는 그 커밋이 `PUT /conversions/{id}` 로 **세 번째
자리**를 만들기 때문이다.

### Cβ-13 (권고) — `DELETE /documents/{document_id}` 204 가 헤더를 선언하지 않는다

실측: `204: {description: "파기됨. 본문 길이 0."}` — `headers` 없음. 실제로는 전역 부착으로 두 헤더가
나가고 DD-1 이 그것을 단언한다. `GET /documents` 200 은 인라인으로 선언한다. 같은 사실이 두 오퍼레이션에서
다르게 표현되므로 계약에서 생성한 클라이언트·문서는 204 의 헤더를 모른다. 「계약이 '모든 응답'을 표현하지
못한다」(규칙 3 근거 2)의 **자리를 C5 가 하나 늘렸다**. 새 결함이 아니라 누적이다.

### Cβ-14 (권고, P2 연동) — 계약이 폐기 대상 Python 파일:줄을 앵커로 든다

실측한 자리: `x-request-field-constraints.fields[].measured_on` 이 `app/services/auth.py:192-194` ·
`app/services/documents.py:399-402` · `app/services/workspaces.py:136-140` 을 든다. `x-contrast-case` 는
`Annotated[int, Query(ge=1, le=100)]` 를 근거로 든다. `app/**` 은 폐기 대상이므로 P2 이후 앵커가 전부
죽는다. `NamedReferenceGuardTest` 는 계약 산문을 덮지 않고(선언된 한계 · B-22) 분모도 `backend-kotlin`
아래뿐이다. **처방**: 삭제 전에 `measured_on` 을 성질 서술(정규화 후 / 원시)만 남기고 파일:줄을 걷어낸다.

### 계약 준수 — 검토함, 지적 없음

- **Jackson snake_case**: 전역 전략 없이 `@get:JsonProperty`·`@param:JsonProperty` 필드별 명시.
  `DocumentContractNodeTest.목록 DTO 의 키가 계약 required 와 같다`(P-33) 가 두 스키마와 정확 대조.
- **`{"detail": ...}` 오류 형식 / `ProblemDetail` 누출**: 인터셉터가 던지는
  `MethodArgumentTypeMismatchException` 이 기존 `handleTypeMismatch` 를 타 배열 `detail` 로 나가는 것이
  DL-5·DD-6·ValueSlot 부정 케이스로 실측돼 있다. `createResponseEntity` 오버라이드는 이 배치에서 무변경.
- **소유권 404(403 아님)**: DELETE·GET 모두 404. DD-2 가 `isNotEqualTo(FORBIDDEN)` 로 명시 단언,
  DL-9 가 「빈 목록 아님」을 `items` 키 부재로 단언.
- **`Cache-Control: no-store` · `X-Content-Type-Options`**: GET 은 개별+전역 겹침을 **개수까지**
  (`containsExactly`) 단언, DELETE 204 는 전역만으로 나가는 것을 DD-1 이 단언.
  `DocumentListHeaderFloorTest` 가 전역 장치를 뺀 컨텍스트에서 개별 부착을 재고, **전제 자체를**
  (`/health` 에 헤더가 없음) 단언한다 — 이 배치에서 가장 잘 만든 장치다.
- **입력 상한 초과 상태 코드**: `limit`/`offset` 은 422 배열, `x-input-limits` ↔ 인라인 `parameters` ↔
  코드 상수 **삼중 대조**(P-25 두 케이스). `list_offset` 에 상한이 없다는 사실을 `isNull()` 로 고정한 것이
  「없는 것」과 「사라진 것」을 가른다.
- **`Location` / RFC 5987 / CORS 노출 헤더**: 이 배치에서 변경 없음 → 재검증 대상 아님.

---

## 3. parity 위험

이 배치에는 정규식·유니코드·한글·POI·프롬프트 문자열 변경이 **없다**(실측: `core` 모듈 diff 0,
`parity/` diff 0). 그래서 이 축의 대상은 「계약과 코드가 값으로 갈리는 자리」뿐이다.

- **Cβ-7** 이 이 축에도 든다 — 같은 이름의 세 동작.
- **`created_at` 동률 타이브레이크**: `listSql` 이 `ORDER BY d.created_at DESC, d.id DESC` 를 든다.
  `DocumentListReachTest.페이지 경계에서 중복과 누락이 없다` 는 서로 다른 트랜잭션에서 만든 3건을 쓰므로
  `created_at` 이 동률이 아니고 **타이브레이크 절을 지워도 통과한다**(B-11 이 이미 그렇게 신고). 이 회차의
  새 지적은 아니다 — 상태 **미해소**로만 갱신한다.
- **`x-open-asymmetry`**(`text` 는 제어문자를 걷지 않고 `edited_text` 는 걷는다): C7 몫으로 그대로 열려 있다.
  이 배치가 `text` 경로를 건드렸으므로(길이 판정 위치 무변경) 상태 확인만 한다 — **미해소**.
- **검토함 — 지적 없음**: 목록 응답의 시각 표현이 `Instant.toString()` 문자열로 고정돼 Jackson 설정 변경에
  흔들리지 않는다. `source_format`·`status` 가 enum 이 아니라 `wireName` 문자열이다.

---

## 4. 보안 불변식

> `privacy-gate` 판정이 이 회차에 새로 나오지 않았다. 아래는 **이 배치가 새로 들어온 코드가 그 감사 목록의
> 어느 항목에 닿는지**만 지목한다. 판정이 갈리면 `privacy-gate` 가 우선한다.

### Cβ-3 (수정 필요) — `WebMvcConfig` 의 X-A3 선언에 **강제자 0**

선언(`WebMvcConfig.addInterceptors` 주석):
> **인증 뒤**에 등재한다 — 계약이 인증을 입력 검증보다 먼저로 못박았고(X-A3), 인터셉터는 등재 순서대로 돈다.

실측한 도달:

| 공백 값 자리를 쓰는 케이스 전수 | 토큰 |
|---|---|
| `ValueSlotInvariantReachTest.kt:167`(경로 변수, `/workspaces/{id}`) | **있음**(`newAccount()`) |
| `ValueSlotInvariantReachTest.kt:201`(쿼리 표본 `공백뿐`) | **있음** |
| `DocumentDeleteReachTest.kt:292`(경로 변수, `/documents/{id}`) | **있음** |

저장소 전체에서 `%20`/`BLANK_SEGMENT` 를 쓰는 자리가 이 셋뿐이고(실측 grep) **셋 다 유효 토큰을 붙인다.**
기존 X-A3 케이스(`DocumentDeleteReachTest.인증이 경로 변수 변환보다 먼저다`,
`DocumentListReachTest.인증이 쿼리 파라미터 검증보다 먼저다`)는 `not-a-uuid` / 범위 밖 정수를 쓰는데,
그 둘은 **인자 해석 단계**에서 나므로 인터셉터 등재 순서와 무관하게 401 이 유지된다.

**결과: 두 `registry.addInterceptor` 줄의 순서를 바꾸면 `DELETE /documents/%20` 이 토큰 없이 422 를 내고
전 게이트가 초록이다.** R-6 의 음성 대조 표(`C4-R6-6`)에도 「순서 뒤집기」 칸이 없다 —
가드 미등재 / 부분 건너뜀 / 전체 등재 세 상태만 쟀다.

악용 비용: **두 줄 순서 교환**, 자동 신호 전부 초록. 잃는 것: 토큰 없이 API 파라미터 형태 탐색(정보 노출).
「타 사용자 데이터 노출」은 아니므로 §5 Phase 7 즉시 중단 목록의 ① 은 아니다.
처방: 토큰 없는 공백 값 자리 요청이 **401** 인지 재는 케이스 2건(쿼리 1 · 경로 1). 두 줄이면 닫힌다.

### 즉시 파기가 「표시」가 아니라 파기인가 — 검토함, 지적 없음

- 앱: `deleteOwned` 가 `DELETE FROM documents WHERE id = :id AND user_id = :ownerId` 한 문장.
  `RETURNING` 없음(지운 제목·암호문을 힙에 올리지 않는다는 사유가 KDoc 에 있다).
  변환 삭제 메서드는 `ConversionRepository` 에 **존재하지 않는다**.
- 스키마(실측): `documents` 를 참조하는 FK 는 `conversions` 하나(`ON DELETE CASCADE`),
  `conversion_jobs → conversions` 도 CASCADE. 즉 KDoc 의 「참조 FK 는 하나」가 참이고 두 단 연쇄가 성립한다.
- 관측: `DocumentDeleteReachTest.삭제가 변환과 작업 행까지 파기한다` 가 **삭제 전** 세 테이블 1행 +
  `octet_length(source_text_encrypted) > 0` 을 재고 삭제 후 셋 다 0 을 잰다. **전/후 쌍이 있어야
  「삭제 후 0건」이 「애초에 0건」과 구분된다** — 이 배치가 그 함정을 스스로 피했다.
- 문장 수: `JdbcDocumentStoreTest.포트 경유 삭제가 한 문장으로 연쇄한다` 가 `CountingDataSource` 로 1을
  단언 → 「읽고 나서 지운다」와 「앱이 변환을 또 지운다」 둘 다 2가 되어 잡힌다.
- 잔여(범위 밖, 원장 ⑦): WAL·백업 잔여. 운영 정책이고 이 단위 밖이라는 처분에 동의한다.

### 소유권 은닉 404 의 세 축 — 검토함, 지적 없음

세 축이 실제로 서로 다른 것을 잰다는 것이 이 회차에서 처음 **증명됐다**:

| 축 | 장치 | 이 배치의 상태 |
|---|---|---|
| 바이트 | DD-3(상태·본문·헤더 이름 집합 동일, `date` 만 제외) | 성립 |
| 시간 | DD-3 인접 시간 축, 문턱 1.5 | **R-10-② 로 양성 대조 완료** — 주입 1.0ms 에서 빨강, 0.5ms 초록. 탐지 하한 0.5~1.0ms, 기준선 7회 최대 비 1.110 |
| 구조 | `OwnershipPredicateGuardTest` 정확 열거 핀(`DELETE [documents]` 항목 추가) + `JdbcDocumentStoreTest` 문장 수 | 성립 |

**시간 축의 처분이 이 배치에서 가장 값나가는 부분이다.** 「성립할 때 초록」과 「깨졌을 때도 초록」밖에
없던 축을 양성 대조로 종결하고, KDoc 범위를 **「1.5배 이상의 격차를 잡는다」**로 실측 표와 함께 적었으며,
밀리초가 아니라 **배수**로 적은 이유(절대 하한은 기준선과 함께 커진다)까지 남겼다. 그리고 그 축이
**잡지 못하는 것**(소유 조건이 SQL 을 떠난 변이 — 격차가 1.5배 미만)을 구조 축으로 넘긴 것도 옳다.

### 로그·평문 — 검토함, 지적 없음

- `DocumentListItemResponse.toString()` 을 손으로 재정의해 `title` 을 `CONTENT_MASK` + 길이로 줄인다.
  목록은 한 번에 20건이라 컴파일러 생성 `toString()` 이면 한 줄이 제목 20개를 남긴다 — 그 판단이 맞다.
- `SensitiveToStringReachTest.EXPECTED_SOURCE_DECLARATIONS` 50 → 53. `checks` 가 저장소 최초 `Map` 이라
  `slotFor` 가 「모르는 타입」으로 **끊겼고**(설계대로) `mapSlot` 을 더해 키·값 **양쪽**에 표본을 심었다.
  「조용히 검사 밖에 남는 대신 빨개진다」가 실물로 확인된 자리다.
- `handleTypeMismatch` 가 `msg` 를 `"Input should be a valid …"` 고정 문구로 만들고 **제출값을 담지 않는다**
  (코드 실측). 인터셉터가 예외 `value` 에 원값을 넣지만 렌더 경로가 그것을 읽지 않는다.
- `HealthDiagnosis.reachable` 이 예외를 **로그에도 남기지 않는다**(드라이버 예외에 접속 문자열·자격증명이
  실린다는 사유가 적혀 있다). `HealthProbeConfiguration` 도 같다. `/health` 가 무인증이라 호출 빈도가
  통제되지 않는다는 근거까지 붙었다 — 옳다.
- golden `log_progress` 가 남기는 것은 문서 id·순번·경과 초·사유 코드뿐(본문·팩트 리터럴 0). 확인.
- 개인정보 스캐너 exit 0 / BLOCK 0 (이 회차 직접 실행).

### 마스킹 선행 — 검토함, 지적 없음

이 배치는 LLM 경로를 건드리지 않았다. `list`·`delete` 어디에도 `LLMProvider` 호출이 없고
(`DocumentServiceTest.삭제가 변환을 따로 지우지 않는다` 가 변환 저장소·큐 **미호출**을 단언),
업로드 경로의 「암호화 선행」 불변식(포트가 `EncryptedContent` 만 받는다)도 무변경.

### `TypedValueSlotInterceptor` 를 인증 **뒤**에 등재한 것이 옳은가 — 옳다(단, Cβ-3)

옳다. 계약 `info.description` 이 인증을 입력 검증보다 먼저로 못박았고, `preHandle` 은 인자 해석보다
앞이며 핸들러를 찾은 뒤에만 돌아 계약 밖 경로가 404 로 남는다. 경로 패턴을 좁히지 않은 것도 옳다 —
대상이 「값 자리가 있으나 그 타입으로 해석되지 않는 입력」이라는 **종류**이고 그 종류는 특정 경로에
속하지 않는다. **문제는 판단이 아니라 그 판단에 강제자가 없다는 것**이다(Cβ-3).

---

## 5. Kotlin/Spring 관용성

### Cβ-11 (권고) — F3 L2 축(스프링 엔진)의 대상이 **하드코딩 3개**다

`RequestFieldRejectionReachTest.DTO_CLASSES` 가 `SignupRequest`·`DocumentTextRequest`·`WorkspaceNameRequest`
세 개를 컴파일 시점 참조로 열거하고, `contractDtoClasses()` 가 `mapNotNull` 로 **조용히 흘린다**
(주석: *"없는 것은 조용히 빠진다(핀은 슬라이스 축이 진다)"*).

이 층이 **프로그램적 `ConstraintMapping` 을 보는 유일한 층**이다(`ConstraintMetadata` KDoc 실측 표 —
`standalone` 은 못 본다). 새 계약 필드 DTO 가 생기면 슬라이스 축의 `PINNED_WITHOUT_DTO` 정확 열거 핀이
빨개져 사람을 부르지만, **그 실패 메시지는 `DTO_CLASSES` 를 언급하지 않는다**(메시지 전문 확인). 프로브만
배선하고 L2 대상을 빠뜨리는 편집이 조용하다. R-4·R-5 가 이름의 열거와 자리의 열거를 없앤 파일 옆에서
**클래스 목록의 열거**가 남은 자리다.

처방: L1 축이 이미 쓰는 트리 스캔(`ProductClasses.onTestRuntimeClasspath()` / `apiClasses()`)으로 대상을
유도하거나, 최소한 `PINNED_WITHOUT_DTO` 실패 메시지에 「`DTO_CLASSES` 도 함께 고쳐라」를 넣는다.
마감을 C7 로 두는 이유는 그 커밋이 `ConversionReviewRequest` 를 만들기 때문이다.

### Cβ-12 (권고) — 값 자리 가드의 제외 조건이 **최상위 타입 하나**다

`TypedValueSlotInterceptor.rejectBlank`:
```kotlin
if (CharSequence::class.java.isAssignableFrom(parameter.parameterType)) return
```
`@RequestParam List<String>` · `String[]` · `Optional<String>` 은 비-CharSequence 라 **공백 원소가
거절된다** — 문자열 값 자리인데 이 가드의 대상이 된다(과잉 거절). 오늘 그런 파라미터가 없어 도달 0이고,
B-17 은 반대 방향(과소 탐지)만 적었다. KDoc 의 「문자열 파라미터는 공백이 그대로 전달되므로 이 가드의
대상이 아니다」가 **원소 타입에는 거짓**이다. 처방: 한계를 KDoc 에 적거나 컨테이너 원소 타입까지 본다.

### Kotlin/Spring 관용성 — 검토함, 지적 없음

- **모듈 경계**: `core` 에 Spring·DB 의존 없음. `ListPageLimits` 를 `core` 가 아니라 `api` 에 둔 사유
  (「페이지네이션은 도메인 개념이 아니라 HTTP 표면」)가 §3.2 와 맞다. `DocumentRepository.listOwned` 포트가
  경계 없는 `Int` 둘을 받는 것이 그 판단의 관측 가능한 결과다.
- **트랜잭션 경계**: `delete` 가 `transaction.inTransaction` 안에서 돌고, 문장이 하나여도 경계를
  유스케이스가 여는 이유(다음 문장이 자동으로 같은 경계에 든다)가 적혀 있다.
  `DocumentServiceTest` 가 `depthWhenDeleted == [1]` 로 그것을 재고 `transaction.committed == 1` 을 잰다.
- **`JdbcClient` 사용 / 조립 SQL**: `listSql(filterWorkspace)` 가 문자열을 잇지만 붙이는 값이 컴파일 시점
  상수 두 개 중 하나이고 식별자·값이 전부 이름 붙은 파라미터다. 주입면 없음. 한 형태로 합치지 않은 사유
  (널 파라미터 JDBC 타입 힌트)도 타당.
- **`@Validated` 를 붙이지 않은 판단**: 붙이면 AOP 메서드 검증이 서고 `ConstraintViolationException` 이
  나가 매퍼가 옮기는 예외(`HandlerMethodValidationException`)와 갈린다 — 공식 문서 근거까지 붙었다.
- **Flyway**: 이 배치에 새 마이그레이션 0. `alembic_version` 미접촉. `HealthProbeConfiguration` 이
  `migrate` 프로필에서도 조립되는 사유(스키마 이관 잡이 `{}`·`ok` 로 "확인했고 정상"처럼 보이지 않게 한다)가
  옳다.
- **LLM SDK 누출**: 이 배치에 LLM 코드 없음.
- **`spring-boot-starter-validation` 도입**: `api` 에만, version catalog 주석이 **무엇이 함께 켜지는가**
  (F3 의 1차 방벽 소멸)와 그 대체 강제자를 같은 커밋에 세운 사실까지 적었다. 잠금 파일도 함께 갱신됐다.
- **detekt 신호 처리**: `LongParameterList` → 임계값을 올리지 않고 `DocumentStorage` 묶음을 만든 선례가
  이 배치에서도 유지된다(`JdbcDocumentStoreTest` 의 `LargeClass` 는 병합으로 통과, 단언 손실 0 — 다음
  단위가 더하면 분할이 필요하다는 사실을 원장 ⑥ 에 남겼다).

---

## 6. 테스트 적정성

### Cβ-1 (**차단 — ② 장치**) — CI `quality` 잡이 HEAD 에서 죽어 게이트 체인 전체가 skip 된다

**실측(런 32455246832, sha `19062cc`)**:

| 스텝 | 결과 |
|---|---|
| `uv run ruff check .` | success |
| `uv run ruff format --check .` | **failure** |
| `uv run mypy . .claude` | **skipped** |
| 데이터 보호 불변식 스캔 (BLOCK 후보 0건 유지) | **skipped** |
| Python 정본 스냅샷 재생성 diff 검사 | **skipped** |
| `uv run alembic upgrade head` | **skipped** |
| 하네스 도달 / 스캐너 회귀 / parity 게이트 / 스냅샷 가드 / 게이트 러너 / Kotlin 테스트 클래스 **실재 확인 6종** | **전부 skipped** |
| 원시 제어문자 전수 검사 | **skipped** |
| `uv run pytest` | **skipped** |

**원인 줄** — R-9 커밋 `6282394` 가 만든 `tests/test_kotlin_gate_reach.py` 의 `_git_revisions`·`_blob_at`:
```python
        cwd=REPO_ROOT, capture_output=True, text=True, check=False,
```
재현: `uv run ruff format --check .` → `1 file would be reformatted, 155 files already formatted`
(진단 위치 943~947행).

**가려진 실질 위반은 없다** — 이 회차에서 skip 된 것들을 직접 돌렸다: mypy 0 · 스캐너 exit 0 / BLOCK 0 ·
`pytest` **1480 passed**. 그러므로 이것은 「빨강이 진짜 결함을 덮는다」가 아니라
**「개인정보 BLOCK 게이트와 전체 pytest(라쳇·바닥·도달 검사 포함)가 HEAD 에서 CI 에 도달하지 않는다」**다.
`kotlin` 잡이 `pytest tests/test_kotlin_gate_reach.py` 를 요구 모드로 따로 돌려 라쳇·바닥·리포트 축은
살아 있다(실측 success) — 죽은 것은 스캐너·`tests/test_harness_scope_reach.py`·golden 비-llm·app 스위트다.

**보고와의 어긋남.** L-㉖ 은 「ruff·mypy 0」으로 전건 초록을 보고했다. 그 측정은 `ruff check` 만이다.
`CLAUDE.md` 의 필수 통과 체인은 `uv run ruff check --fix . && uv run ruff format .` 이고 CI 는
`ruff format --check` 로 강제한다 — 즉 **보고 라벨(「ruff 0」)이 두 명령을 구분하지 못한다.**
L-㉕ #4(자기 커밋 뒤 ruff 미재측정)와 **같은 형태의 두 번째**이고, 그때는 R-8 이 잡았고 이번엔 CI 가 잡았다.

처방: ⑴ 서식 고침(한 줄), ⑵ **전건 초록 템플릿에 `ruff format --check` 를 명령 단위로 넣는다**
(라벨이 명령을 가리지 않게). 마감: **즉시** — 이 상태로 G-β 를 종료하면 「게이트가 돌았다」를 근거로
쓸 수 없다.

### Cβ-2 (판정 필요 — 차단 상향 후보) — 소스를 훑는 가드들이 **Gradle 선언 입력 밖의 파일을 읽는다**

R-8 의 성과는 *"개수를 「선언된 것」이 아니라 **「실제로 돈 것」**(Gradle JUnit XML)으로 센다"* 다.
그 「돈 것」이 무엇을 증명하는지 물었다.

실측한 배선:

| 자리 | 값 |
|---|---|
| `backend-kotlin/gradle.properties` | `org.gradle.caching=true` |
| `.github/workflows/ci.yml:284` | `./gradlew build --no-daemon` — `--rerun-tasks` 없음, `--no-build-cache` 없음 |
| `.github/workflows/ci.yml:263` | `gradle/actions/setup-gradle@v4` (Gradle User Home 복원 = 로컬 빌드 캐시 복원) |
| `backend-kotlin/build.gradle.kts:119-163` `tasks.withType<Test>` | 계약 파일을 **`inputs.file(apiContractFile)`** 로 선언한다. 그 주석이 사유(런타임에 읽는 파일이므로 입력으로 걸어야 한다)와 `PathSensitivity.NONE` 의 이유까지 적는다 |
| 같은 블록 | `systemProperty("easydoc.kotlin.source.root", rootDir.absolutePath)` — 주석: *"**소스 전수를 훑는 탐지기**(허용목록 가드 등)가 쓰는 루트"*. **그 트리는 입력으로 선언되지 않는다** |

즉 **같은 블록 안에서 한 런타임 입력(계약 파일)은 선언되고 다른 런타임 입력(훑는 소스 트리)은 선언되지
않는다.** 그 트리를 실행 시점에 읽는 가드는 최소 다섯이다 — `NamedReferenceGuardTest`(축 A·B),
`OwnershipPredicateGuardTest`, `EnvelopeColumnWriteGuardTest`, `SensitiveToStringReachTest`,
`SourceScanFormsProbe`.

**귀결**: 선언 입력이 바뀌지 않은 커밋에서 `:api:test` 가 UP-TO-DATE / FROM-CACHE 가 되고, R-8 의 리포트
축은 **복원된 XML** 을 읽어 「돌았다」로 읽는다. 가장 날카로운 경우는 `NamedReferenceGuardTest` 다 —
P-9 가 겨누는 편집 종류가 **주석 전용 편집**이고, 줄 수를 바꾸지 않는 주석 편집은 바이트코드를 바꾸지
않으므로 그 가드를 건너뛴다.

- **실측한 것**: 입력 선언 비대칭 · 캐시 설정 · CI 명령 · setup-gradle 의 GUH 복원.
- **추정(실행하지 않았다)**: 종단 재현(줄 수를 유지한 주석 편집 → `:api:test` FROM-CACHE → 가드 미실행).
  Kotlin 이 줄 번호 테이블을 담으므로 **줄을 더하거나 지우는** 주석 편집은 입력을 바꾼다. 성립하는 것은
  「기존 주석 줄 안의 텍스트 교체」이고, 그것이 정확히 죽은 포인터 편집의 모양이다.

**이것이 L-㉖ ⑧(`--rerun-tasks` 를 빼면 UP-TO-DATE)의 구조적 원인이다** — 플래그 규율의 문제가 아니라
**입력 선언의 문제**다. 그래서 사용자 지시로 미룬 「플래그 강제자」와 별개 항목으로 올린다. 처방 셋 중 하나:
⑴ 훑는 테스트에 `inputs.dir(rootDir)`(적절한 필터·PathSensitivity), ⑵ 스캐닝 테스트 전용 태스크 +
`outputs.upToDateWhen { false }`(이미 `parityHarness` 가 쓰는 형태), ⑶ CI 테스트 스텝에 `--no-build-cache`.
⑴ 이 근거에 맞다 — 문제가 「입력을 안 적었다」이므로.

### Cβ-4 (수정 필요) — R-7 의 처방이 **바닥 목록 밖에서 재발했다**

R-7 이 닫은 결함은 「핀 알갱이(클래스) > 보호 대상 알갱이(메서드)」이고, 처방(`MIN_TESTS_IN_FLOOR_CLASS`)의
도달은 **`FLOOR_TEST_CLASSES` 뿐**이다. 그런데 이 배치가 만든 `DocumentListContractTest` 는 제품 KDoc
**두 곳**이 유일한 강제자로 지목한다:

- `DocumentController.list` KDoc: *"실제로 애너테이션이 걸려 나가는지는 **DL-5·DL-6·DL-7** 이 잰다"*
- `ListPageLimits.kt`: *"⑶ `DocumentListContractTest` 의 DL-5·DL-6·DL-7 — 나간 바이트로 경계 양쪽과
  기본값을 잰다. **⑴⑵ 가 통과해도 애너테이션을 안 달았으면 여기서 깨진다**"*

그 클래스가 `FLOOR_TEST_CLASSES` 에도 `MIN_TESTS_IN_FLOOR_CLASS` 에도 **없다**(실측: 두 표 28항목 확인).

`@Min`/`@Max` 를 떼거나 DL-5·DL-6 메서드만 지웠을 때 다른 무엇이 잡는가를 전수 확인했다:

| 후보 장치 | 결과 |
|---|---|
| `RequestFieldConstraintLayerTest.엔진 질의가 제약을 지목한다` | `isNotEmpty()` 라 **둘 중 하나가 남으면 통과** |
| `RequestFieldRejectionReachTest.스프링 엔진 질의가 제약을 지목한다` | 같음 |
| `ValueSlotInvariantReachTest` 부정 표본 | 동치류가 「빈·공백·문법 아님·표현 범위 초과」 — **경계 밖 정수(0·101)가 없다** → 침묵 |
| `DocumentListReachTest` DL-11 | 위조 토큰으로 **401** 을 재므로 침묵 |
| `DocumentContractNodeTest` P-25 | 계약 ↔ **상수** 대조뿐 — 애너테이션 부착 여부를 보지 않는다(그 사실을 `ListPageLimits` 가 스스로 적었다) |
| 라쳇·바닥·개수표 | 클래스가 남으면 전부 초록 |

**즉 메서드만 지우면 조용하다.** L-㉔ 이 「종류」로 선언한 것(「바닥이 클래스 단위인데 보호 대상이 메서드
단위인 **모든 항목**」)보다 **실제 종류가 넓다**: 「**제품 주석이 이름으로 유일한 강제자로 지목한 모든
클래스**」. 처방 둘 — ⑴ 이 클래스를 바닥 + 개수표에 넣는다(현재 `@Test` **9개** — `grep -c "@Test"` 실측), 또는 ⑵ 「제품 주석이 이름으로
지목한 테스트가 바닥에 있는가」를 `NamedReferenceGuardTest` 축 A 의 **파생 축**으로 더한다(그 스캐너가
이미 참조를 뽑고 있으므로 분모가 공짜이고, 이름 열거가 아니라 모양 판정이다). ⑵ 가 종류만큼 넓힌다.

### Cβ-5 (수정 필요) — 라쳇 이력 대조가 **파일 이름 변경으로 초기화된다**

`_git_revisions` 는 `git rev-list HEAD -- <경로>` 이고 `--follow` 가 없다. 실측:

| 경로 | `rev-list` 개수 |
|---|---|
| `…/db/LiveSql.kt` (이 배치가 **새 이름**으로 바꾼 파일) | **1** |
| `…/db/SqlComments.kt` (옛 이름) | 2 |
| `…/document/JdbcDocumentStoreTest.kt` (이름 안 바뀐 핀 파일) | 5 |

그러므로 「이름 변경 + 값 내리기」를 **한 커밋에** 하면 `max(seen)` 이 그 낮춘 값이 되어 통과하고,
`unjudged` 도 뜨지 않는다(리비전 ≥1 이고 상수도 찾힌다). 실패 메시지의
*"이름이 바뀌었거나 파일이 옮겨졌다면 `RATCHET_SCALAR_PINS` 도 함께 고쳐라"* 는 **다른 상태**(상수 미발견)만
다룬다. 죽은 경로가 아니다 — 이 배치가 파일 이름 변경(`SqlComments`→`LiveSql`)과 클래스 추출
(`ValueSlotInvariantReachTest`)을 **둘 다** 했다.

처방: `git log --follow --format=%H -- <경로>` 로 갈거나, 리비전 수가 1이고 그 리비전이 rename 인 핀을
`unjudged` 로 올린다. **종단 재현은 실행하지 않았다**(코드 수정 금지) — `rev-list` 실측 + 판정 로직 독해에
근거한 추론이다.

### Cβ-9 (권고) — 라쳇 판정기가 **두 번째 파서**이고 실제 선언과 대조하지 않는다

`_scalar_in` · `_table_in` 이 정규식으로 같은 파일의 선언을 다시 읽는데, **읽은 값이 임포트된 실제
상수/딕셔너리와 같은지 단언하지 않는다.** 같은 파일이 `_declared_test_count` 에 대해서는
*"두 벌이 되면 한쪽만 고쳐지는 날 서로 다른 것을 세면서 둘 다 초록이 된다"* 고 적고 구간 정의를 공유시켰다 —
그 규율이 라쳇 판정기에 적용되지 않았다.

실측(읽기 전용 스크립트): 오늘은 일치한다 — 정규식 28키 == 실제 28키, 값 불일치 0, 스칼라 3개 일치.
그래서 **잠재**이지 현재 결함이 아니다. 다만 `_table_in` 의 항목 정규식이 **후행 쉼표를 요구**하므로
(`"…": \d+,`) 새 키를 후행 쉼표 없이 마지막 줄에 더하면 그 키가 `current`·`history` 양쪽에서 조용히
빠지고 이력 대조 밖에 남는다(키 집합 정확 일치 테스트는 실제 dict 를 쓰므로 통과한다). 처방 두 줄:
`assert _table_in(text, name) == MIN_TESTS_IN_FLOOR_CLASS` + 스칼라 동치 단언.

### Cβ-10 (권고) — B-21 에 「AssertJ 어휘 열거」가 필요 없는 처방이 있다

L-㉕ 는 B-21(Kotlin 라쳇 항목 제거 탐지)을 *"전수 분류하려면 AssertJ 비교 어휘를 읽어야 하고 그 열거는
R-4·R-5 에서 두 번 거부한 형태"* 로 미뤘다. 어휘를 읽지 않는 길이 있다: **후보를 트리에서 유도한다** —
`backend-kotlin/**/src/test/**` 의 `const val MIN_[A-Z_]* = \d+` 전수를 모아 `RATCHET_SCALAR_PINS` ∪
`NON_RATCHET_KOTLIN_PINS`(사유 문자열 표)와 **정확 분할**로 대조. 이 파일이 자기 상수에 이미 같은 형태를
쓴다(`test_이_파일의_수치_상수가_전부_분류돼_있다`). `MIN_*` 는 이 저장소의 명명 관용이라 이름 열거가
아니라 **모양**이다.

**판정 필요 지점**: 사유 문자열 표는 형식상 면제 목록(은폐형)이다. 그러나 `NON_RATCHET_PINS` 선례가 있고
빈 사유 실패 단언이 붙는다 — 규칙 4 ⑵ 의 거부권에 걸리는지 리더 판정이 필요하다.

### 테스트 적정성 — 검토함, 지적 없음

- **DL-1~DL-11 · DD-1~DD-7 전건 배치 확인**(정본 `04_contract-keeper_documents-test-spec.md` §2-2·§2-3 대조):
  DL-1·5·6·7 → `DocumentListContractTest`(C-M) / DL-2·3·4·8·9·10·11 → `DocumentListReachTest`(C-R·C-I) /
  DD-1~DD-7 → `DocumentDeleteReachTest`(실 소켓 + 실 PostgreSQL). **미배치 0건.**
- **DD-5 의 HTTP 팔을 닫지 않은 판단이 옳다.** `GET /conversions/{id}` 가 C6 인데 그 자리를 404 로 재면
  「핸들러가 없어서 404」가 「파기됐으니 404」로 둔갑한다. L-⑲ 에서 리더가 `202/NONE` 을 저장 도달의 증거로
  오독한 것과 정확히 같은 함정이고, 이번엔 레인이 먼저 봤다. 대체 축(저장 상태)이 응답보다 강한 근거라는
  판단에도 동의한다.
- **`DocumentListHeaderFloorTest` 가 자기 전제를 단언한다** — 「이 컨텍스트에 전역 부착 장치가 실제로
  없다」를 `/health` 헤더 부재로 관측. 「전제가 조용히 깨지는 경로」를 스스로 막은 드문 자리다.
- **`NamedReferenceGuardTest` 의 빈 분모 방어**: 축별 후보 수를 `isPositive()` 로 재고, `requireNonEmpty`
  를 합성 인자와 **실제 빈 루트** 양쪽으로 확인한다(디렉터리가 없는 상태와 훑을 자리는 있는데 0건인 상태를
  구분한 것까지). `점 참조 분모 0 이면 빨강` 이 R-10-① 처방의 도달을 지킨다.
- **`fakeExtensionPath()` 를 리터럴로 적지 않고 계약에서 찾는다** — 계약이 노드를 옮기면 리터럴이 우연히
  참이 되어 케이스가 조용히 무력해진다는 이유. 「검사의 기준이 검사 대상 자신에게서 나오지 않는가」의
  올바른 처리다.
- **`ConstraintMetadata` 의 엔진 생존 확인**: 두 층 각각에 ⑴ 제품 실물(`limit`·`offset`) ⑵ 합성 표본
  (클래스 수준) ⑶ 과잉 탐지 0 을 뒀고, L2 에 **두 검증기가 같은 인스턴스가 아님**을 전제 단언으로 고정했다.
  컨테이너 원소(type-use)를 **재현하지 못했으므로 단언하지 않고 관측값만 출력**한 처리도 옳다 —
  「위협을 재현하지 못했으면 방어도 증명되지 않았다」.
- **`LiveSql` 의 방향 분리**: 분모(문장 발견)는 원시 청크, 판정(방어 존재)은 걷어낸 사본. 「위 칸의 근거를
  아래 칸에 옮겨 적지 마라」를 KDoc 에 명시하고 그 옮겨 적기가 두 결함의 공통 기제였다고 적은 것이 정확하다.
  블록 주석 중첩 깊이 세기 · 지운 자리에 공백 남기기(`user_i` + 빈 주석 + `d = :x` 가 없던 방어를 만든다) ·
  `''`/`\'` 를 리터럴을 **길게** 보는 쪽으로 해석(fail-closed) — 세 판단 모두 방향이 맞다.
  세 음성/양성 대조 케이스(리터럴 안 / 리터럴 뒤 살아 있는 대입 / escaped quote)가 양방향을 고정한다.
- **`OwnershipPredicateGuardTest` 인구조사 핀에 `DELETE [documents]` 추가**, 그리고 그것이
  `EXPECTED_UNGUARDED` 에는 **없다**는 사실을 주석이 관측으로 적었다(두 목록이 다른 사건을 잡는다).
- **`test_리포트에_건너뛴_테스트가_없다`** 가 범위를 바닥이 아니라 리포트 전수로 둔 근거(실행 1,062 ·
  건너뜀 0 실측, `@Tag("llm")` 은 발견 단계에서 빠져 리포트에 없다)가 「범위는 근거를 넘지 않는다」에 맞다.

---

## 7. 리더 판정 오류 넷 — 독립 평가

### ⑴ 정직하게 신고된 잔여를 종결로 처분 (기준을 「정직성」 → 「악용 비용」으로 교체)

**교체 방향은 맞다. 그러나 진술이 한 인자로 줄어 있어 그대로 쓰면 틀린 답을 준다.**

L-㉕ 의 문면은 *"잔여 처분 기준을 여기서 명시한다: **정직성이 아니라 악용 비용이다**"* 다. 그런데 같은
절이 실제로 쓰는 판정은 **두 인자**다 — *"「단언 비우기」는 diff 가 단언 자리에 남아 pitest 를 기다릴 수
있지만 `@Disabled` 한 줄은 개수를 그대로 둔 채 강제자를 끈다"*, 그리고 R-9 의 근거도
*"한 줄 **+ 자동 신호 전부 초록**"* 이다. B-20 의 해소 절도 두 인자로 적는다. 즉 **작동하는 기준은
「악용 비용 × 자동 탐지 여부」이고, 선언된 기준은 「악용 비용」하나**다.

한 인자로 읽으면 한 줄짜리 편집이면 전부 차단으로 올라가고(계약 예시 값 한 줄 수정도 한 줄이다), 그러면
기준이 판별력을 잃는다. **처방: 기준 문장을 「악용 비용 × 자동 탐지 여부」로 다시 적어라.** 두 인자로
적으면 이 회차의 판정도 그 표에 그대로 들어간다 — Cβ-3(두 줄 · 탐지 0) · Cβ-5(rename + 한 줄 · 탐지 0) ·
L-㉖ ⑧(플래그 하나 · 탐지 0) 대 L-㉖ 미결 ②(한 줄이지만 **SQL 이 diff 에 남는다**).

**추가 관측**: 새 기준을 적용하면 **B-21 이 B-20 과 같은 칸**이다(한 줄 · 자동 탐지 0). L-㉕ 는 그것을
「가장 값싼 남은 경로」로 명시하고 *"그때까지 리뷰가 유일한 방벽이라는 사실을 숨기지 않는다"* 로 남겼는데,
남긴 사유는 **비용이 아니라 닫는 방법의 무게**다. 그렇다면 기준에는 세 번째 인자(닫는 비용)가 암묵으로
들어 있고, 그것을 적지 않으면 다음 회차가 무엇이든 「무겁다」로 미룰 수 있다. Cβ-10 이 그 세 번째 인자를
낮추는 구체안이다.

### ⑵ 바닥 핀 알갱이가 보호 대상보다 굵은데 「유일한 강제자」로 선언

**진단과 처방 모두 옳다. 처방의 도달이 자기 선언한 종류보다 좁다.** → **Cβ-4** 가 그 재발이고, 같은
배치가 만든 클래스에서 났다. 종류의 정확한 이름은 「바닥이 클래스 단위인데 보호 대상이 메서드 단위인
항목」이 아니라 **「제품 주석이 이름으로 유일한 강제자로 지목한 모든 클래스」**다 — 바닥은 그중 일부다.

부수 확인: `d816fb0` 의 「빈 선언 뿌리」 처방은 정확하다. 실측으로 재검증했다 —
`test_바닥_목록이_비지_않는다` 가 `MIN_FLOOR_CLASSES = 28` 을 걸고, 키 집합 정확 일치가 개수표를
바닥에 묶으므로 **바닥이 비지 못하면 개수표도 비지 못한다**는 논증이 성립한다(둘 다 28, 집합 동일 실측).
`test_이_파일의_수치_상수가_전부_분류돼_있다` 의 정확 분할도 이 파일에 대해 성립한다(실측: 선언 3 =
라쳇 2 + 비라쳇 1). **다만 그 정확 분할은 `path == THIS_TEST_PATH` 로 좁혀져 Kotlin 항목을 덮지 않는다** —
그것이 B-21 이고 Cβ-10 이다.

### ⑶ 개수를 `grep` 으로 센 대리 측정

**정확한 자기 지적이고 처방(판정 장치에 직접 묻기)도 옳다.** 값 넷을 조여 실제와 맞춘 것도 확인했다 —
`NamedReferenceGuardTest` 16 · `DocumentDeleteReachTest` 14 · `ValueSlotInvariantReachTest` 3 ·
`RequestFieldRejectionReachTest` 4 · `RequestFieldConstraintLayerTest` 7 · `DocumentListReachTest` 10 ·
`RequestFieldRejectionLayerTest` 5 를 이 회차에서 직접 세어 전건 일치했다.

**남은 같은 종류**: **Cβ-9** — 라쳇 판정기 자신이 정규식으로 선언을 다시 읽는 대리 측정이고, 그 값이
실제 선언과 같은지 묻지 않는다. ⑶ 과 정확히 같은 형태다.

### ⑷ 자기 커밋 뒤 ruff 미재측정 후 「전건 초록」 보고

**정정은 정확하다. 그리고 같은 형태가 이 배치에서 다시 났다 — Cβ-1.** 이번 것은 「자기 커밋 뒤
미재측정」이 아니라 **「측정한 명령이 필수 체인의 절반이었다」**다: `ruff check` 는 초록이고
`ruff format --check` 는 빨강이며 CI 는 후자로 강제한다. L-㉕ 가 남긴 부수 사실
(*"`--isolated` 로 돌리면 E501 이 보이지 않는다 — 프로젝트 설정 없이 린터를 돌리면 다른 답이 나온다"*)과
같은 종류의 다음 항목이다. **처방은 사람의 주의가 아니라 라벨이다** — 「ruff 0」을
「`ruff check` 0 · `ruff format --check` 0」으로 쪼개 적으면 빠진 절반이 문면에 보인다.

**리더를 봐주지 않은 결과 요약**: 넷 중 셋이 이 배치 안에서 **재발**했다(⑵→Cβ-4, ⑶→Cβ-9, ⑷→Cβ-1).
재발의 공통 형태는 「처방의 논리는 맞고 **처방의 도달이 선언보다 좁다**」이며, 이는 stop-time 게이트가
여섯 번 잡은 것과 같은 구조다.

---

## 8. 규칙 4 분류 — 새 장치들

| 장치 | 분류 | 판정 |
|---|---|---|
| `RequestFieldConstraintLayerTest`(엔진 메타데이터 질의) | **탐지형** | 옳다. R-4 가 범위 선언형(이름 열거) → 성질 검사, R-5 가 자리 열거 → 엔진 질의로 두 번 갈아탔다. 규칙 4 ⑵ 가 지시하는 갈아타기 |
| `RequestFieldRejectionLayerTest` / `…ReachTest`(나간 바이트) | **탐지형** | 옳다. 두 축의 관측 지점 차이가 실측 표로 고정됐다. 단 L2 대상 목록은 **범위 선언형이 남았다** → Cβ-11 |
| `PINNED_WITHOUT_DTO` 정확 열거 핀 | **탐지형**(인구조사) | 옳다. 「비우는 방향으로만 고쳐라」 + 정확 일치라 양방향으로 울린다 |
| `TypedValueSlotInterceptor` | **강제·표현형**(제품 가드) | 옳다. 이름 열거 없이 파라미터 선언에서 유도. 단 제외 조건이 최상위 타입 하나 → Cβ-12 |
| `ValueSlotInvariantReachTest` | **탐지형** | 옳다. 동치류로 덮고 모르는 타입은 `error()` 로 끊는다. 단 경로 팔의 선언이 도달보다 넓다 → Cβ-8 |
| `MIN_TESTS_IN_FLOOR_CLASS` | **범위 선언형** | 옳다. **빈 선언에서 실패한다** — `MIN_FLOOR_CLASSES` 하한 + 키 집합 정확 일치로 뿌리가 막혔다(실측 재검증) |
| 리포트 축(`건너뜀 0` · 하한) | **탐지형** | 분류는 옳다. **초록이 증명하는 것이 「이 실행에서 돌았다」가 아니다** → Cβ-2 |
| `RATCHET_SCALAR_PINS` / `RATCHET_TABLE_PIN` | **범위 선언형** | **빈 선언에서 실패한다**(`assert RATCHET_SCALAR_PINS` · `assert current`). 정확 분할이 이 파일에만 도달 → Cβ-10. 이력 기준점이 rename 에 초기화 → Cβ-5 |
| `NON_RATCHET_PINS` | **은폐형**(면제 목록) | **넓히지 않았고** 값이 사유 문자열이며 빈 사유가 실패한다. 규칙 4 ⑵ 의 형태를 최소로 쓴 처리 — 수용 가능 |
| `NamedReferenceGuardTest`(축 A·B) | **탐지형** | 옳다. 이름을 열거하지 않고 **모양**으로 뽑고, 오탐 98% 를 근거로 범위를 좁힌 것이 「면제 목록을 낳지 않는다」는 규칙 4 ⑵ 에 맞다. 「막지 못하는 것」 다섯 항목을 선언한 것도 옳다. 단 도달이 캐시에 걸린다 → Cβ-2 |
| `ConstraintMetadata` | **탐지형** 지원 클래스 | 옳다. `@Test` 가 없어 분모 밖인 것도 맞다 |
| `LiveSql` | **탐지형** 지원(정규화) | 옳다. 이름을 `SqlComments` 에서 바꾼 사유(종전 이름·선언이 범위를 「무시하는 것만」으로 적었고 리터럴은 무시되지 않는다)가 정확하며, 그 자체가 이 회차가 고치는 결함과 같은 형태라는 자기 진단도 맞다 |
| `fetch-depth: 0` | **강제·표현형** | 되돌리면 요구 모드가 **실패**한다(`_history_unavailable` 이 `.git/shallow` 를 본다 — actions/checkout 기본값이 얕은 클론이라 그 파일이 생긴다). 「조용히 사라지지 않는다」는 주석의 주장이 성립한다 |
| CI llm-lane 스텝 상한 55분 / 잡 상한 70분 | **강제·표현형** | 옳다. 잡 타임아웃이 후속 스텝을 skip 시킨 실측(run 32403598822)을 근거로 상한을 스텝으로 내린 판단이 정확하고, **이 배치에서 실제로 완주했다**(28m09s) |
| `--log-cli-level=INFO`(+ `-s` 폐기) | **강제·표현형** | 옳다. 「캡처를 켜면 진행 줄이 사라진다」는 자기 전제를 **비-tty 리다이렉트 + kill -9** 로 뒤집고, 판정을 부수효과(실패 리포트의 `Captured` 섹션 보존)로 넘긴 것이 방법론적으로 맞다 |
| `LLM_MODEL`/`LLM_EFFORT` 고정 | **강제·표현형** | 옳다. 주장(*"어긋나면 조용해지는 것이 아니라 '비교 불가'로 시끄러워진다"*)을 코드로 검증했다 — `tests/golden/baseline.py` 의 `Verdict.INCOMPARABLE` 은 `blocking=True`. 참이다 |

**은폐형을 넓힌 자리는 찾지 못했다.** `.gitignore` 무변경, `NON_RATCHET_PINS` 는 사유 강제 + 정확 분할,
`@Disabled` 는 억제 장치로 분류되어 탐지형(건너뜀 0)으로 갈아탔다. `EXPECTED_UNGUARDED`·`PINNED_WITHOUT_DTO`
는 면제 목록이 아니라 인구조사(정확 일치)로 유지된다.

---

## 9. 도달 범위 점검 (다섯 축을 가로지르는 필수 구획)

| 점검 항목 | 결과 |
|---|---|
| 「전역」·「모든」·「항상」 선언이 닿지 않는 경로 | **지적 3건** — Cβ-6(`/health` 「항상 200」이 거짓) · Cβ-8(값 자리 불변식의 「자동으로 덮는다」가 경로 팔에서 거짓) · Cβ-12(「문자열 파라미터는 대상이 아니다」가 원소 타입에서 거짓) |
| 그 게이트가 **지금 어디서 도는가** / 도달 0 | **지적 2건** — Cβ-1(quality 잡 게이트 체인이 HEAD 에서 **도달 0**) · Cβ-3(X-A3 인터셉터 순서 강제자 **0**). 그 밖에는 실측으로 확인: 라쳇·바닥·리포트 축은 `kotlin` 잡 요구 모드에서 돈다(success), `fetch-depth: 0` 이 두 잡에 있고 `llm-lane` 은 이미 갖고 있었다 |
| 측정이 **대리 경로**에서 이뤄지지 않았는가 | **지적 1건** — Cβ-2(리포트 축의 「돈 것」이 캐시 복원과 구별되지 않는다). 반대로 잘 처리된 자리: DD-5 를 HTTP 팔 대신 저장 상태로 잰 판단, R-6 이 후보 둘을 「고쳐질 것 같은 코드」가 아니라 실행으로 배제한 것 |
| 검사의 기준이 **검사 대상 자신**에게서 나오지 않는가 | **지적 1건(잠재)** — Cβ-9(라쳇 판정기의 두 번째 파서). 잘 처리된 자리: `fakeExtensionPath()`·`syntheticTestName()` 을 리터럴로 적지 않고 저장소/계약에서 찾아 「우연히 참이 되어 케이스가 무력해지는」 경로를 막았다 |
| 판정이 **대리 지표**로 이뤄지지 않는가 | **지적 1건** — Cβ-1(「ruff 0」이 두 명령을 가린다). 잘 처리된 자리: 「무언가 빨개졌다」로 판정하지 않고 기제별 칸으로 쟀다(R-6 3칸 × 4행, R-7 3줄, R-8 3행). ktlint 가 고아 KDoc 때문에 빨개진 것을 판정으로 쓰지 않은 처리가 특히 정확하다 |
| 규칙·패턴의 **범위가 근거보다 넓지 않은가** | **지적 없음.** `DocumentListHeaderFloorTest` 를 바닥에 넣지 않은 판정(레인이 범위를 「이 커밋이 만든 한 자리」로 좁혔으므로 바닥 편입은 재지 않은 범위의 선점) · `test_리포트에_건너뛴_테스트가_없다` 의 범위를 실측 근거(건너뜀 0)만큼 둔 것 · P-9 를 「목록」이 아니라 「모양」으로 좁힌 것(전폭이면 오탐 98%) — 셋 다 근거를 넘지 않았다 |
| **음성 대조**가 붙어 있는가 | **지적 2건** — Cβ-3(순서 뒤집기 칸이 없다) · Cβ-4(메서드 삭제 대조가 이 클래스에 없다). 붙은 자리: R-4 3칸 · R-5 3칸 · R-6 4행×3열 · R-7 3줄 + 대조군 · R-8 3행 · R-9 양방향 9대상×2 · R-10 축 B 전/후 + **시간 축 양성 대조 7단계**. 복원 sha256 전건 일치 기록 확인 |
| 판정하는 코드가 **자기 자신을 검사 대상에 넣었는가** | **부분.** `tests/test_kotlin_gate_reach.py` 는 mypy·ruff 범위 안이다(그것이 Cβ-1 을 드러낸 경로다). `NamedReferenceGuardTest` 는 자기 KDoc 을 실제로 짚었고(가짜 예시), `RequestFieldConstraintLayerTest` 의 상수 이름이 스캐너 규칙에 걸리는 것까지 기록했다. **닫히지 않은 자리**: `_scalar_in`/`_table_in` 이 자기 파일을 읽지만 그 읽기의 정확성을 자기가 검사하지 않는다(Cβ-9) |

---

## 10. Phase 종료 조건 대비 현황 (§5 Phase 4 · §6)

| 종료 조건 | 상태 | 근거 |
|---|---|---|
| C4 `GET /documents` 계약 케이스 DL-1~DL-11 | **충족** | 정본 표 전건 배치, 미실행 0. C-M 은 슬라이스, C-R·C-I 는 실 소켓 + 실 PostgreSQL |
| C5 `DELETE /documents/{id}` DD-1~DD-7 | **충족(DD-5 HTTP 팔은 C6)** | 전/후 쌍 관측 · 문장 수 1 · 세 축 소유권 은닉. HTTP 팔 유보 판단이 옳다 |
| §6 Contract 게이트 — status/body/header/error 가 v1 spec 과 일치 | **충족, 단 Cβ-6·Cβ-7·Cβ-13** | 계약 미선언 500(/health) · 같은 이름 세 동작 · 204 헤더 미선언 |
| §6 Security 게이트 — 소유권·로그·캐시 | **충족, 단 Cβ-3** | 소유권 3축 · 로그 마스킹 · 헤더 개수 단언. X-A3 인터셉터 순서만 강제자 0 |
| §6 DB 게이트 — 제약·잠금·cascade | **충족** | FK 연쇄를 실 PostgreSQL 에서 재고 스키마 전수와 대조(FK 1개·CASCADE) |
| §4.4 트랜잭션 경계 | **충족** | `delete` 가 유스케이스 트랜잭션 안, depth 단언 |
| §3.2 모듈 경계 | **충족** | `core` 무오염, `ListPageLimits` 위치 사유 타당 |
| §6 「각 테스트가 보장하던 행동을 재배치하고 누락 목록이 0인지 추적」 | **부분** | DL/DD 추적표는 0. **바닥·개수 핀의 도달이 「제품 주석이 지목한 강제자」 전체를 덮지 못한다**(Cβ-4) |
| 게이트가 **CI 에서 실제로 돌았다** | **불충족** | HEAD `19062cc` 의 `quality` 잡 failure → 스캐너·전체 pytest 미실행(Cβ-1). Kotlin 스위트의 「실제로 돌았다」도 캐시와 구별되지 않는다(Cβ-2) |
| CI 런 전체 초록 | **불충족** | `llm-lane` 차단축 빨강(예상된 상태이나 비차단 표시가 없다 — Cβ-16) |

### Cβ-16 (판정 필요) — 「예상된 빨강」이 CI 색을 죽인다

실측: 런 32451895280(`a559678`)에서 `llm-lane` 이 **처음 완주**(28m09s, 새 스텝 상한 55분 안)하고
`test_필수_정보가_보존된다` 가 **누락 28건 / 문서 17건(허용 0)** 으로 실패했다. 상대 하한선 축은 개선
(전체 33/56 → 35/56 · 실수집 17/36 → 19/36). 이 실패는 `04_goldenset-first-run.md` 가
*"첫 실행에서 필수 정보 보존 게이트가 실패하는 것은 여전히 예상된 상태"* 로 이미 적었으므로 **새 결함이
아니다.**

문제는 배선이다 — `llm-lane` 에 `continue-on-error` 가 없다(실측: 파일 전체에 그 키가 1자리이고 다른
스텝이다). 그래서 예상된 빨강이 **CI 런 전체를 빨강으로** 만든다. 이 축은 도달 0이었기 때문에 지금까지
색을 오염시키지 않았고, 이 배치의 타임아웃 수정이 그 도달을 열었다. 상시 빨강이 되면
「CI 가 초록인가」가 신호로서 죽고, 그 다음부터 Cβ-1 같은 진짜 빨강이 묻힌다.

**판정 요청**: ⓐ 축을 닫는 프롬프트 작업을 일정에 넣고 그때까지 잡을 **명시적으로 비차단**으로 표시할지,
ⓑ 차단을 유지하고 브랜치 CI 를 빨강으로 둘지. 어느 쪽이든 **문서에 적어야** 다음 회차가 색을 무시하지
않는다. 「예상된 빨강」은 적히지 않으면 「무시해도 되는 빨강」이 된다.

---

## 11. 미결의 처분이 옳은가 (프롬프트 #6)

| 미결 | 원장의 처분 | 이 회차 판정 |
|---|---|---|
| **L-㉖ ⑧** `--rerun-tasks` 를 빼면 UP-TO-DATE (자동 강제자 0, 이 세션에서 두 번 오측) | 사용자 결정으로 백로그, **「받아들일 만해서가 아니라 지시에 따라」** 명시 | **처분 형식은 옳다** — 「지시에 따라」와 「받아들일 만해서」를 구분해 적은 것이 이 원장의 규율이고 그것을 지켰다. **그러나 항목의 정체가 틀렸다**: 플래그 규율이 아니라 **Gradle 입력 선언 누락**이다(Cβ-2). 그래서 「B-19 와 함께 하네스 1순위」로 미루는 것과 별개로, 입력 선언 3줄은 **하네스 굳히기가 아니라 배선 수정**이라 중단 결정의 대상이 아니라고 본다. 판정 요청 |
| **L-㉖ ①** DD-5 HTTP 팔 → C6 | 유보 | **옳다.** 구현 없는 자리를 404 로 재면 둔갑한다 |
| **L-㉖ ②** 소유 술어 선언된 4갈래(한 줄 편집 / 남의 문서 노출) | 백로그 B-23, 「넷 다 SQL 안에 남아 diff 에 보인다」 | **옳다.** 두 인자 기준(비용 × 탐지)에서 「탐지: diff 가시」가 성립한다. 파서가 필요하다는 판단도 맞다 |
| **L-㉖ ③** `LiveSql` 이 조립 SQL 을 못 읽는다 | 백로그 | **옳다**, 단 이 배치가 조립 SQL 을 하나 더 만들었다(`listSql`). 그 문장에는 소유 술어가 리터럴로 있어 오늘 도달은 무해하다 |
| **L-㉖ ④** 축 B 의 「경로는 맞지만 뜻이 틀린」 인용 · 축 A 멤버 참조 | 백로그, 축 A 멤버는 오탐 22% 근거로 **넣지 않음** | **옳다.** 오탐 22% 로 시작하는 축이 면제 목록을 부른다는 판단이 규칙 4 ⑵ 와 일관되고, 「조용히 뺀 것이 아니라 판단한 것」으로 근거 셋을 적었다 |
| **L-㉖ ⑤** P-9 산문 인용 축 미덮음(B-22) | 백로그 | **옳다.** 앵커 규약 신설이 단위 밖. 단 Cβ-14(계약이 폐기 대상 파일:줄을 앵커로 든다)는 P2 전에 손이 필요하다 |
| **L-㉖ ⑥** `JdbcDocumentStoreTest` 가 detekt `LargeClass` 문턱에 닿았다 | 병합으로 통과, 다음 단위에서 분할 필요 | **옳다.** 임계값을 올리지 않은 것이 이 저장소의 선례와 일관 |
| **L-㉖ ⑦** WAL·백업 잔여 | 범위 밖(운영 정책) | **옳다** |
| **B-19** pitest | 하네스 1순위 | **옳다.** Cβ-4·Cβ-9 가 pitest 없이도 닫히는 자리라 그 앞에 둘 만하다 |
| **B-21** Kotlin 라쳇 항목 제거 탐지 | 백로그(닫는 방법이 무겁다) | **재검토 요청** — Cβ-10 의 처방(트리에서 후보 유도 + 정확 분할)은 AssertJ 어휘 열거가 필요 없고 이 파일이 이미 쓰는 형태다 |
| **B-10~B-18** | 백로그 | 상태 확인만. **전부 미해소**. B-11(타이브레이크 장치 0)은 이 배치의 목록 테스트가 여전히 재지 않는다(§3) |
| **B-16** `ContractErrorBodyReachTest` 간헐 실패 | 재발 0/3, 원인 미확정 유지, 감시 | **옳다.** 「3회 안에 보지 못했다」와 「없다」를 구분한 처리가 정확하다. 이 회차에 새 관측 없음 |
| **B-20** | 해소 | **옳다**, 단 문서에 항목이 **두 번** 있다(Cβ-15) |

**경계 판정 요약** — 백로그에 두어도 되는 것과 지금 닫아야 하는 것의 선을 두 인자(악용 비용 × 자동 탐지)로
다시 그으면: **지금 닫아야 하는 것** = Cβ-1(즉시) · Cβ-3(두 줄) · Cβ-2 의 입력 선언(세 줄) · Cβ-4 · Cβ-5.
**백로그로 옳은 것** = B-19 · B-22 · B-23 · B-10~B-18 · L-㉖ ①③④⑥⑦.
**재분류 요청** = B-21(Cβ-10) · L-㉖ ⑧ 의 정체(Cβ-2).

---

## 12. 미실행·확인 불가 항목 (요약 — 상세는 §0)

1. Gradle 빌드·ktlint·detekt·Testcontainers 스위트 — **이 회차 미실행**. Kotlin 초록의 근거는 CI
   `kotlin` 잡 success 하나이고, 그 잡의 「실제로 돌았다」는 Cβ-2 의 대상이다.
2. Cβ-2·Cβ-3·Cβ-5 의 **종단 재현** — 코드 수정 금지 지시로 미실행. 각 항목에 실측/추정 경계를 표시했다.
3. Cβ-7 의 multipart 팔 — 코드 독해 추정.
4. AEAD round-trip·변조 거부·키 회전(`encryption_scheme`/`key_version`)·Argon2 재해시·JWT clock skew —
   이 배치가 그 경로를 건드리지 않아 **미검토(범위 밖)**. `privacy-gate` 의 최신 감사 산출물도 이 회차에
   새로 나오지 않았다.
5. parity 리포트·도메인별 mismatch·coverage — 이 배치에 도메인 변경 0 → **미검토(범위 밖)**.
6. CORS 노출 헤더·`Location`·RFC 5987 `Content-Disposition` — 이 배치에서 변경 없음 → **미검토(범위 밖)**.
7. compose 기동 스모크 — **C2 이후 계속 미실행**(원장이 이미 신고).
8. `frontend` 잡의 `WorkspaceProvider.test.tsx` 실패(런 32451895280) — HEAD 런에서 success 이고 앞선 3개
   런에서도 success 이므로 **플레이크로 판단**했으나 원인은 확인하지 않았다. 프론트엔드는 이 배치에서
   무변경(diff 0).

---

## 13. 리더에게 (1차 산출물의 처분)

- 이 파일은 **Claude 단독 리뷰**다. `..._cross.md` 없이 Phase 종료 조건 충족을 보고하지 않는다.
- **교차 종합 재호출이 필요하다.** 3단계에서 `04_documents-c4c5_codex-reviewer.md` 와 이 파일을 함께 주면
  `04_documents-c4c5_cross.md` 를 쓴다. 어간은 지정값 그대로 쓴다.
- **먼저 처분이 필요한 것 하나**: Cβ-1 은 리뷰 결론과 무관하게 지금 HEAD 의 CI 상태다. 교차 종합을
  기다릴 이유가 없다.
