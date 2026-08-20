# Phase 4 · M-3 처방 A + D 구현 계획 — 소유 술어 탐지기와 문면 정정

- **역할**: `kotlin-implementer`
- **작성 시점 HEAD**: `66f008bc3203a0091c123cac6fb36f8ab0ebf947` (`feat/kotlin-migration-harness`)
- **입력 정본**: `docs/migration/_workspace/reviews/04_security-documents_privacy-gate.md` (§4.2 후보 A · §4.3 · §4.4 · §5.1 · §5.2)
- **범위**: 리더 판정이 확정한 **A(탐지형) + D(문면 정정) 둘뿐**이다.
  - 후보 **B**(타입 분리) — 기각. 구현하지 않는다.
  - 후보 **C**(소유자 인자 필수 포트 신설) — C6 과 같은 단위로 미룸. **이 커밋에서 새 포트를 만들지 않는다.**
  - 해제 조건 **⒜**(올바른 포트)는 이 커밋의 범위가 아니다. 이 커밋이 닫는 것은 **⒝ 와 ⒞** 다.
- **건드리지 않는 것**: `contracts/**` · `docs/migration/_workspace/reviews/**` · `00_progress.md` · C3 본체.

---

## 1. 기구현 확인 (`CLAUDE.md` 「구현 전 리서치·계획」 ②) — **이 작업의 핵심**

### 1.1 `privacy-gate` 의 실측을 스스로 재현했다

`OWNERSHIP-403` 이 이 자리를 덮지 않는다는 §5.1 의 실측을 그대로 다시 돌렸다.

```
$ .claude/skills/kotlin-migration/scripts/run_gate.sh \
    "uv run python .claude/skills/migration-safety-gate/scripts/scan_privacy_invariants.py --rule OWNERSHIP-403 --no-fail"
[run_gate] cmd: …
검사 범위: 전수. 검사 파일 310개.
2차 판정으로 제외한 적중 … - `OWNERSHIP-403` — 불변식을 집행·명명하는 형태(403 을 만들어 낼 수 없는 자리) 6건
[run_gate] exit: 0
```

**출력에 `DocumentPorts.kt` · `JdbcConversionRepository.kt` · `JdbcDocumentRepository.kt` 가 한 줄도 없다.** 규칙이 찾는 것은 **403 토큰**이지 소유 술어의 부재가 아니다 — 이름이 `OWNERSHIP-` 으로 시작해 덮는 것처럼 보이지만 덮지 않는다. §5.1 의 판정을 독립으로 확인했다.

ArchUnit 도 없다.

```
$ grep -rn "archunit\|ArchUnit" backend-kotlin/ gradle/ --include=*.kt --include=*.kts --include=*.toml   → 0건
```

대조군(§5.1 이 지목한 것)도 확인했다 — `listOwned` 쪽에는 장치가 **있다**(`JdbcDocumentStoreTest.kt:352-383`, `DocumentServiceTest.kt:324-325`). 즉 이 저장소는 이 유형을 세울 줄 알고, **두 포트 자리만 비어 있다.**

### 1.2 `EnvelopeColumnWriteGuardTest` 를 확장할 것인가, 새 클래스를 세울 것인가

`privacy-gate` §4.3 근거 3 이 재사용 후보로 명시 지목한 장치다. 전문을 읽고 판단했다.

| | `EnvelopeColumnWriteGuardTest` (기존) | 이번 장치 |
|---|---|---|
| 겨누는 성질 | **무결성** — 암호문 열을 SET 하면 봉투 두 값도 같은 문장에서 SET 하는가 | **접근 통제** — 행에 닿는 문장에 소유 매개변수가 걸려 있는가 |
| 분모 단위 | `UPDATE <표>` 뒤 **SET 절** | **문장 전체**(소유 술어는 `WHERE` 에 있고, 상관 하위질의는 바깥 `WHERE` 가 묶는다) |
| 대상 문장 종류 | UPDATE 만 | SELECT·INSERT·UPDATE·DELETE 전부 |
| 분모 범위 | **전수**(테스트 포함) | **제품 소스**(§2.2 에 사유와 그 제외를 검사하는 방법) |
| 실측 분모 | 5 문장 | 9 문장 (그중 소유 술어 없음 7) |

> **판정: 새 테스트 클래스를 세운다. 기존 장치를 확장하지도, 공통 골격을 뽑아 공유하지도 않는다.**

근거 셋.

1. **두 장치가 겨누는 것이 다르고 분모 단위가 다르다.** 하나로 합치면 한쪽 분모를 다른 쪽에 강요하게 된다 — UPDATE 만 보는 분모를 이번 성질에 쓰면 §1.5 가 지적한 표의 절반(읽기 두 포트)이 처음부터 탐지 밖이고, 반대로 문장 전체 분모를 기존 장치에 쓰면 그 장치의 SET 절 판정이 조건절과 섞인다(그 파일 `setClauseOf` 주석이 그 오탐을 실측으로 적어 두었다).
2. **공통 골격이 크지 않고, 뽑는 대가가 이 커밋의 범위를 넘는다.** 실제로 겹치는 것은 소스 걷기 + 루트 해석 ~25줄이다. 그런데 그것을 뽑으려면 **현재 초록이고 게이트 핀에 등재된 장치**(`tests/test_kotlin_gate_reach.py` `FLOOR_TEST_CLASSES`)를 고쳐야 한다. 리더가 확정한 이 커밋의 범위는 A + D 이고, 통과 중인 장치의 재작성은 그 검증 상태를 무효화한다(에이전트 재호출 지침).
3. **`ProductClasses.sourceRoots()` 재사용은 모듈 경계가 막는다.** 그 유틸은 `api/src/test` 에 살고 `private` 다. 이번 장치는 SQL 과 형제 장치가 사는 `infrastructure/src/test` 에 있어야 하며, `infrastructure` 는 `api` 의 테스트 소스를 볼 수 없다.

**대신 실제로 재사용하는 것**(중복 구현으로 취급되지 않도록 명시한다):

- **형태 전부** — 분모 파생 · 빈 분모 실패 · 합성 probe 음성 대조 · 정확 열거 핀. §4.4 가 요구한 「정확 열거 핀」의 기존 표현(`EXPECTED_FILES`/`EXPECTED_STATEMENTS`)을 그대로 따른다.
- **테이블 이름의 근거** — `EncryptedField.wireName`(`테이블.컬럼`). 형제 장치와 **같은 enum 에서 파생**한다. 이름을 리터럴로 다시 적지 않으므로 값이 공유된다.
- **probe 규율** — probe 마다 독립 임시 디렉터리(형제 장치가 실측으로 밟은 오염), probe SQL 의 테이블 이름을 조립해 실제 스캔 분모를 오염시키지 않기.
- **시스템 속성** `easydoc.kotlin.source.root` (`build.gradle.kts:139`·`:190` 이 모든 테스트 태스크에 넣어 준다). 새 배선을 만들지 않는다.

---

## 2. 라이브러리·프레임워크 리서치 (`CLAUDE.md` ①)

### 2.1 SQL 파서를 새로 들이지 않는다

후보: **JSqlParser** (`/jsqlparser/jsqlparser`, 최신 문서 4.9 — context7 조회). 채택하지 않는다.

| 사유 | 근거 |
|---|---|
| **어려운 부분을 대신해 주지 않는다** | 이 작업의 실제 어려움은 SQL 파싱이 아니라 **Kotlin 소스에서 SQL 조각을 잘라 내는 일**이다. 파서를 들여도 그 층은 우리가 그대로 짜야 한다 |
| **실패가 조용하다** | context7 확인: `withUnsupportedStatements()` 를 켜면 파싱 못 한 문장이 `UnsupportedStatement` 로 돌아오고 **오류가 기록되지 않는다**(공식 usage 문서 예제 — `getParseErrors().size() == 0`). 끄면 예외다. 어느 쪽이든 「해석 못 한 문장」이라는 **새 무성 표면**이 생기고, fail-closed 로 감싸려면 결국 우리 코드가 판정을 다시 한다 |
| **입력이 순수 SQL 이 아니다** | 테스트 SQL 에 Kotlin 문자열 템플릿(`'$id'`)이 섞여 있다(`EncryptionSchemeSchemaTest:234` 실측). 파서에게 이것은 SQL 이 아니다 |
| **의존성 정책** | `CLAUDE.md` — 「기본은 저장소에 이미 있는 방식의 재사용」. 이 저장소는 같은 축의 탐지기 넷(`EnvelopeColumnWriteGuardTest`·`ProvenanceCreationSitesTest`·`ProductClasses`·`AuthenticationCoverageContractTest`)을 전부 소스 훑개로 세웠다 |

**확인하지 않은 것 — 정직하게 적는다.** JSqlParser 가 PostgreSQL `FOR NO KEY UPDATE` 를 파싱하는지는 **확인하지 못했다**(context7 조회에서 해당 문법의 지원 여부가 나오지 않았다). 채택하지 않는 결론은 위 네 사유로 이미 닫히므로 이 항목을 근거로 쓰지 않는다.

### 2.2 분모 범위 — **제품 소스**로 좁히고, 그 좁힘을 검사받게 한다

실측으로 두 범위의 대가를 재고 골랐다(측정 스크립트는 scratchpad 일회용, 커밋하지 않는다).

| 범위 | 파일 | 문장 | 소유 술어 없음(= 핀 크기) |
|---|---|---|---|
| **제품 소스**(`src/main`) | 96 | **9** | **7** |
| 전수(테스트 포함) | 205 | 29 | **26** (그중 19 가 테스트 fixture) |

제품 소스를 고른 이유:

1. **불변식이 요청 경로의 성질이다.** 소유 술어가 지키는 것은 「남의 행이 응답으로 나가지 않는다」이고, 테스트 소스는 요청을 처리할 수 없다. `src/test` 는 이름 접두나 정규식이 아니라 **Gradle 소스셋 경계**이며, 이 저장소가 제품/비제품을 가르는 데 이미 쓰는 축이다(`ProductClasses` 가 같은 경계를 쓴다).
2. **핀의 신호 대 잡음.** 전수 핀 26 개 중 19 개가 테스트 fixture 라, C3~C7 이 테스트를 더할 때마다 핀이 깨진다. 그러면 핀 갱신이 **기계적 습관**이 되고 그 순간 §4.4 가 지키려던 성질(「목록이 커지는 것이 리뷰에 올라온다」)이 죽는다. 제품 소스 핀 7 개는 **전부 열어 볼 값어치가 있는 문장**이다.
3. **이 제외는 검사받는다.** `ProductClasses` KDoc 이 적은 결함(*"제외한 이유가 검사받지 않는다"*)을 되풀이하지 않는다 — §3.3 의 모듈 대조가 그것이다.

### 2.3 codex **C-8** 이 지적한 형태를 물려받지 않는다

`ProductClasses.sourceRoots()` 는 Gradle 루트의 **직계 자식**만 훑어 중첩 모듈을 놓친다(교차 종합 행 8). 새 장치는 그 형태를 복제하지 않는다.

- 경로에 `src/main` 이 있는 `.kt` 를 **깊이 제한 없이** 걷는다 → 중첩 모듈이 저절로 들어온다.
- `settings.gradle.kts` 의 `include(...)` 에서 모듈 이름을 **파생**해, 선언된 모듈이 전부 스캔에 기여했는지 대조한다. 모듈이 통째로 빠지면(소스셋 이름 변경·모듈 이동) **빨개진다.**

---

## 3. 처방 A — 설계

**위치**: `backend-kotlin/infrastructure/src/test/kotlin/kr/easydoc/infrastructure/db/OwnershipPredicateGuardTest.kt`
(형제 장치와 같은 패키지. 대상 SQL 이 `infrastructure` 에 산다)

### 3.1 분모 파생

- **테이블**: `EncryptedField.entries` 의 `wireName` 앞부분 → `documents` · `conversions`. **열거하지 않는다.**
- **문장 자르기**: 형제 장치와 같은 훑개 방식. 파일 텍스트를 `"""` · `"` · `;` 로 잘라 조각을 만들고, 조각 안에 `FROM|JOIN|UPDATE|INTO <감시 테이블>` 이 있으면 **문장 하나**로 센다.
  - 문자열 리터럴만 골라내는 렉서를 두지 않는 이유는 형제 장치와 같다 — 주석 안의 SQL 도 잡히는 **과잉 탐지 방향이라 fail-closed** 이고, 렉서 자신이 조용히 놓치는 표면이 된다.
  - 조각에서 SQL 동사(`SELECT|INSERT|UPDATE|DELETE|WITH`)를 못 찾으면 **끊는다.** 해석 못 한 문장을 조용히 넘기지 않는다(형제 장치의 `SET 절을 찾지 못했다` 와 같은 규율).

### 3.2 소유 술어 판정

문장 안에 `user_id` 를 **매개변수**와 `=` 로 묶는 자리가 있으면 소유 술어가 있다고 본다: `user_id = :이름` 또는 `user_id = ?`.

- 컬럼끼리의 비교(`d.user_id = w.user_id`)는 매개변수가 아니므로 **소유 술어가 아니다** — fail-closed.
- `IN`·`ANY` 등 `=` 아닌 형태는 **소유 술어 없음**으로 읽는다 — 과잉 탐지 방향.

### 3.3 단언 (테스트 케이스)

| # | 케이스 | 무엇을 고정하나 |
|---|---|---|
| 1 | `소유 술어 없는 질의는 정확 열거 핀 안에만 있다` | 소유 술어 없는 문장 목록 == `EXPECTED_UNGUARDED` (**정확 일치**, 중복 포함 순서 있는 목록). 새 문장이 늘거나, 소유 술어가 빠져 항목이 옮겨 오면 빨강 |
| 2 | `빈 분모는 통과가 아니다` | 전체 문장 인구조사 == `EXPECTED_STATEMENTS`; `requireNonEmpty(emptyList())` 가 실제로 끊는지; **빈 디렉터리를 훑으면 끊는지** |
| 3 | `선언된 모듈이 전부 분모에 들어 있다` | `settings.gradle.kts` 의 `include` 모듈 전부가 스캔에 기여했는가 (§2.3) |
| 4 | `스캐너가 소유 술어의 유무를 가른다` | **N-1 · N-2** 합성 probe |
| 5 | `대소문자와 대상 아닌 테이블을 가른다` | 소문자 SQL · 감시 대상 아닌 테이블 → 과잉 탐지 0 |
| 6 | `SQL 동사를 찾지 못하면 끊는다` | 해석 불가 문장을 조용히 넘기지 않는다 |

### 3.4 §4.4 의 경계선 — 지키는 방법

> **패턴 예외(이름 접두·경로 표식·정규식) = 은폐형. 정확 열거 핀 = 탐지형.**

- **`lock` 접두 제외 규칙을 만들지 않는다.** `lockSourceText`/`lockEnvelope` 와 `rewriteEnvelope` 는 **이름이 아니라 `EXPECTED_UNGUARDED` 의 항목**으로 다룬다.
- **`privacy-allow:` 계열 표기를 쓰지 않는다.** 스캐너 자신이 *"그 규칙에서 「오탐이니 눌러 달라」가 나오면 그것은 표기가 아니라 판정 요청"* 이라 적었고, `privacy-gate` §4.4 가 이 자리를 명시 배제했다.
- 핀은 **면제 목록이 아니라 인구조사**다. 항목이 늘거나 줄면 그 diff 가 리뷰에 올라온다.

핀에 들어갈 7 문장(실측):

| 파일 | 문장 | 왜 오늘 소유 술어가 없나 |
|---|---|---|
| `JdbcWorkspaceRepository.kt` | `SELECT documents` (작업 공간의 문서 수) | 호출자가 같은 트랜잭션에서 소유 작업 공간을 이미 잠갔다 |
| `JdbcDocumentRepository.kt` | `SELECT documents` (`lockSourceText`) | **M-3 대상.** 해제 조건 ⒜ 는 C6 |
| `JdbcDocumentRepository.kt` | `UPDATE documents` (`rewriteEnvelope`) | 회전 배치. §1.5 가 표에 더한 쓰기 갈래 |
| `JdbcDocumentRepository.kt` | `INSERT documents` | 소유자를 **조건이 아니라 값으로** 적는다 |
| `JdbcConversionRepository.kt` | `SELECT conversions` (`lockEnvelope`) | **M-3 대상.** 해제 조건 ⒜ 는 C6 |
| `JdbcConversionRepository.kt` | `UPDATE conversions` (`rewriteEnvelope`) | 회전 배치 |
| `JdbcConversionRepository.kt` | `INSERT conversions` | 새 행 |

### 3.5 이 장치가 **막지 못하는 것** (KDoc 에 적는다 — 적지 않으면 이것이 다음 거짓 전칭이 된다)

1. **문자열을 조립한 SQL**(`"… FROM ${'$'}table"`). 테이블 이름을 못 읽는다. 형제 장치가 같은 한계를 이미 문서화했고, 이 파일의 probe 가 바로 그 형태라 **probe 가 실제 분모를 오염시키지 않는다.**
2. **소유 매개변수가 무엇에 결속되는지 증명하지 않는다.** 문장 안에 `user_id = :param` 이 있으면 통과다 — 그것이 목표 테이블을 실제로 좁히는지는 판정하지 않는다. `JdbcWorkspaceRepository.listOwned` 가 그 예다(작업 공간 소유로 문서를 간접 좁힌다).
3. **분모가 제품 소스다.** 테스트 SQL 은 세지 않는다(§2.2). 모듈이 통째로 빠지는 형태는 §3.3-3 이 잡는다.
4. **한 리터럴에 문장을 여럿 담으면 한 문장으로 읽는다.**
5. **이 파일 자신의 삭제.** 최종 방어선은 `tests/test_kotlin_gate_reach.py` 의 선언 대조다.

---

## 4. 처방 D — 문면 정정

| 자리 | 지금 | 고침 |
|---|---|---|
| `DocumentPorts.kt:57-60` (`DocumentRepository` 클래스 KDoc) | *"읽기 메서드가 **전부** `ownerId` 를 받고…"* — **거짓**(`lockSourceText` 는 받지 않는다). 같은 파일 `:98` 이 예외를 적어 **파일이 자기와 모순** | 실제 도달을 그대로 적는다: 사용자 경로 읽기는 `ownerId` 를 받아 `WHERE` 에 넣고, 유지보수 경로(`lockSourceText`)는 받지 않는다. **무엇이 이것을 지키는가**의 답으로 `OwnershipPredicateGuardTest` 를 가리킨다 |
| `DocumentPorts.kt:99-100` | *"호출자는 회전 유스케이스 하나로 제한한다"* — 강제자 0 | 제한을 **강제하는 장치가 없다**는 사실과, 사용자 경로 전용 포트가 C6 몫이라는 것을 한 문장으로 적는다 |
| `DocumentPorts.kt:155-156` (`ConversionRepository`) | 소유권 규약 **무선언** | 같은 규약을 명시하고 같은 탐지기를 가리킨다 |

**규율**: 「전부」·「모든」·「항상」을 **새로 쓰지 않는다.** 이 결함이 정확히 그 규칙(하네스 규칙 1) 위반이었다. 탐지기를 가리키는 문장만 쓴다 — 그 탐지기는 실행되고 실패할 수 있으므로 검사받는 선언이다.

---

## 5. 음성 대조 — 실행 계획

`privacy-gate` §5.2 의 넷 전부를 **실행하고 결과를 남긴다**(해제 조건 ⒝).

| # | 변이 | 기대 | 실행 방법 |
|---|---|---|---|
| **N-1** | 소유 술어 **없는** 합성 probe | 빨강 | 테스트 케이스 4 안에서 — probe 가 소유 술어 없음으로 분류되는지 |
| **N-2** | 소유 술어 **있는** 합성 probe(`conversions` 조인 + `d.user_id = :ownerId`) | 초록 | 같은 케이스 |
| **N-3** | 분모를 0 으로 | 빨강 | ⒜ 빈 디렉터리 스캔 → `requireNonEmpty` 가 끊는지(케이스 2), ⒝ **worktree 에서 `build.gradle.kts` 의 `easydoc.kotlin.source.root` 를 빈 디렉터리로 돌려** 실제 실행이 빨개지는지 |
| **N-4** | **제품 코드** `JdbcDocumentRepository.listSql` 의 `WHERE d.user_id = :ownerId` 제거 | **빨강 — 새 탐지기에서** | worktree 에서 그 줄만 지우고 새 테스트 클래스만 실행. **판정 기준은 「빨개지는가」가 아니라 「실패 메시지가 그 파일·문장을 지목하는가」**다(기존 `JdbcDocumentStoreTest:356-363` 이 이미 빨개지므로, 그 빨강을 새 장치의 증거로 오독하지 않는다) |

**복원 규율**(하네스 규칙 5): N-3 ⒝ 와 N-4 는 **일회용 `git worktree`** 에서 한다 — 본 저장소를 건드리지 않으므로 복원이 필요 없다. 그래도 실험 뒤 본 저장소의 해당 파일 **sha256 을 대조해 보고**한다. `cp` 로 되돌리지 않고, 공유 작업 트리에서 `git stash` 를 쓰지 않는다.

---

## 6. 순서

1. 계획(이 문서) — **완료 후 코드**
2. `OwnershipPredicateGuardTest.kt` 신설 → 실측 핀 채우기
3. `tests/test_kotlin_gate_reach.py` 의 `TEST_CLASSES` + `TEST_CLASS_COUNT` 갱신 (89 → 90)
4. 처방 D — `DocumentPorts.kt` 문면 세 자리
5. **D 편집 뒤 스캐너 재실행** — KDoc 이 새 분모를 만들지 않았는지 확인(주석도 분모다)
6. 음성 대조 N-1~N-4 실행, 결과 기록
7. 검사 표 전부 실행 — Gradle `--rerun-tasks` · ktlint · detekt · `pytest` · **개인정보 스캐너** · ruff · mypy
8. 커밋 하나

## 7. 검사 표 (실행하고 결과를 그대로 적는다)

| 검사 | 명령 |
|---|---|
| Kotlin 빌드·테스트 | `./gradlew build --rerun-tasks` (up-to-date 는 재측정이 아니다) |
| ktlint · detekt | 위 `build` 에 포함 |
| Python 게이트 | `uv run ruff check .` · `uv run mypy . .claude` · `uv run pytest` |
| **개인정보 스캐너** | `scan_privacy_invariants.py` 전수 — **빠뜨리지 않는다** |
| 게이트 도달 | `uv run pytest tests/test_kotlin_gate_reach.py` |

골든셋(`uv run pytest tests/golden`)은 해당 없음 — 프롬프트·스타일 규칙·LLM 설정을 건드리지 않는다.
