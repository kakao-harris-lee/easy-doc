# Phase 4 / 작업 단위 `crypto` — 저장 암호화 AEAD

**작성 주체**: `kotlin-implementer`
**기준 문서**: 계획 §5 Phase 4(`:411-420`) · §4.3(2차 개정) / `migration-safety-gate` **I-7**
(암호 정본) / privacy-gate `reviews/03_security-phase3-close_privacy-gate.md` **§5-4**
(잠정 위반과 해제 조건) / 원장 「Phase 3 종료 판정」 §1 ③ · §2 9(첫 커밋 조건)

**커밋** (착수 시 HEAD `b66fa46` → 이 단위 종료 `858347d`)

| 커밋 | 내용 |
|---|---|
| `74ec2b0` | core/application — 도메인 타입과 포트, 단일 복호화 실패 예외 |
| `fcf584b` | infrastructure — AES-256-GCM 어댑터·키 설정, I-7 전건 테스트 |
| `e891a08` | `V3` 스키마 정정(I-8 해제), `V2` 주석 정정, 마이그레이션 기대값 유도 |
| `858347d` | 401 3갈래 비율 회귀(X24-2 첫 커밋 조건) |

> `70d4122`(다른 레인, `SensitiveToStringReachTest` 계열)이 `74ec2b0` 와 `fcf584b` 사이에
> 들어왔다. 그 커밋 뒤 `SensitiveToStringReachTest` 4건을 다시 돌려 **새 value class
> `PlainBody` 가 그 게이트를 통과**하는 것을 확인했다(아래 §6).

---

## 1. 무엇을 만들었나

| 모듈 | 파일 | 역할 |
|---|---|---|
| `core` | `crypto/StoredContent.kt` | `PlainBody`(평문 래퍼) · `EncryptedContent`(암호문 + `scheme` + `key_version`) · `EncryptedField`(암호문 컬럼 4종) · `EncryptionScheme.AES_256_GCM_V1` |
| `core` | `exceptions/DomainExceptions.kt` | `DecryptionFailedException` 추가, `StorageException` 을 `open` 으로 |
| `application` | `crypto/ContentCipher.kt` | 포트. `writeScheme` · `writeKeyVersion` · `encrypt` · `decrypt` |
| `infrastructure` | `crypto/AesGcmContentCipher.kt` | JCA `AES/GCM/NoPadding` 구현 |
| `infrastructure` | `crypto/CryptoConfiguration.kt` | `easydoc.encryption.*` 바인딩과 빈 조립 |
| `infrastructure` | `resources/db/migration/V3__encryption_scheme_aead.sql` | CHECK 도메인 이전 + DEFAULT 제거 |
| `infrastructure` | `testFixtures/MigrationCatalog.kt` | 적용 버전 기대값을 디스크에서 유도 |

**Python 원본 대응**: 없다. `app/privacy/crypto.py` 는 계획 §4.3 2차 개정과
`kotlin-spring-conventions` 매핑표가 **참고 자료**로만 지정한 파일이고, 이 단위는
Fernet 포팅이 아니라 표준 AEAD **신규 구현**이다. Python 코드를 열지 않았다.

---

## 2. 저장 형식과 키 관리 규약

### 2.1 바이트

```
bytea = nonce(12) || AES-256-GCM 출력(암호문 || 태그 16)
```

방식 이름과 키 세대를 바이트에 **넣지 않는다**. `encryption_scheme`·`key_version`
컬럼이 이미 들고 있고, 그 둘이 associated data 에 실려 결속되므로 컬럼을 고치면 태그
검증부터 실패한다. 같은 사실을 두 곳에 적으면 어긋날 자리가 생긴다.

### 2.2 associated data

```
"easydoc-aead|{scheme}|{keyVersion}|{테이블.컬럼}|{행 UUID}"   (UTF-8)
```

**모호하지 않은 이유**: `scheme` 은 CHECK 가 좁힌 소문자·숫자·하이픈, `keyVersion` 은
숫자, 컬럼 이름은 `EncryptedField` 의 고정 상수, UUID 는 정규 표기 — 어느 조각에도
`|` 가 들어갈 수 없으므로 서로 다른 네 값이 같은 문자열을 만들 수 없다.

**막는 것 둘**(테스트로 확인):

- 같은 행의 다른 컬럼 — `easy_text_encrypted`(AI 초안)를 `edited_text_encrypted`
  (검수본) 자리로 옮기면 수정률 KPI 기준선이 조작된다.
- 다른 행의 같은 컬럼 — 남의 문서 암호문을 내 행에 넣으면 소유권 검사를 **통과한**
  경로로 남의 본문이 복호화된다(§5 Phase 7 즉시 중단 기준).

### 2.3 키

| 항목 | 값 |
|---|---|
| 알고리즘 | AES-256-GCM (`javax.crypto`, JCA 표준) |
| 키 길이 | 32바이트, base64 인코딩으로 설정에서 받는다 |
| nonce | 96비트, **매 암호화마다** `SecureRandom` |
| 태그 | 128비트 |
| 설정 접두사 | `easydoc.encryption` (`write-key-version`, `keys[].version`, `keys[].value`) |
| 환경변수 | `EASYDOC_ENCRYPTION_WRITE_KEY_VERSION`, `EASYDOC_ENCRYPTION_KEY_V1` (`api`·`worker` **같은 이름**) |
| 소유 모듈 | `infrastructure` — `auth`·`llm` 과 같은 자리. `api` 는 `infrastructure` 를 `runtimeOnly` 로만 보므로 그쪽에 두면 아무도 조립할 수 없다 |

**회전 절차**: 새 세대를 `keys` 에 **더하고** `write-key-version` 을 올린다. 옛 세대를
목록에 남겨 두는 한 그 세대로 쓴 행은 계속 읽힌다(`key_version` 컬럼이 가리킨다).
옛 세대를 목록에서 빼면 그 행들은 **영원히 열리지 않는다** — 회전은 재암호화가 아니다.

**기동은 막지 않는다**(`AuthProperties` 와 같은 규약, 계약 `ServiceUnavailable`):

| 상태 | 결과 |
|---|---|
| 키 미설정(값이 빈 문자열) | 그 세대를 적재하지 않는다. **경고 없음** — `application.yml` 이 환경변수 자리표시자를 미리 적어 두므로 개발 기동마다 빈 값이 들어오고, 그것을 경고로 찍으면 진짜 오설정이 소음에 묻힌다 |
| 키 오설정(base64 아님 / 32바이트 아님) | 그 세대만 빼고 **WARN 한 줄**(세대 번호와 기대 길이만. 값 없음) |
| 쓰기 세대 키 없음 → `encrypt` | `ConfigurationException` → **503** |
| 어떤 이유로든 `decrypt` 실패 | `DecryptionFailedException` → **500**, 고정 문구 하나 |

`encrypt` 만 갈래를 가르는 이유: 키 미설정은 **배포 상태**에 대한 사실이라 운영자에게
503 으로 알려야 하고, 암호문에 대해서는 아무것도 말하지 않는다. `decrypt` 는 반대로
어떤 사실도 흘리면 안 되므로 갈래가 없다(oracle 금지).

### 2.4 로그

`AesGcmContentCipher` 가 남기는 것은 두 줄뿐이다 — 기동 시 **적재한 세대 수 + 쓰기 세대
번호**, 오설정 시 **세대 번호 + 기대 길이**. 평문·암호문·키 재료는 한 조각도 없고,
회귀가 그것을 실제 로그 캡처로 확인한다(§3 마지막 줄).

---

## 3. I-7 항목 ↔ 테스트 대응표

전부 `infrastructure/src/test/.../crypto/AesGcmContentCipherTest.kt` (17건) 와
`.../crypto/EncryptionSchemeSchemaTest.kt` (3건).

| I-7 | 요구 | 테스트 | 확인한 것 |
|---|---|---|---|
| 1 | round-trip (한글·ASCII·빈 값·긴 값·개행/탭) | `round-trip 이 성립한다` | 6종(유니코드 경계 포함) 왕복 + 암호문에 평문 조각 없음 |
| 2 | 변조 1바이트 flip | `변조된 바이트를 거부한다` | nonce 첫 · 암호문 첫 · 태그 마지막 3자리 전건 거부 |
| 2 | 다른 키 | `다른 키를 거부한다` | 같은 세대 번호, 다른 재료 |
| 2 | 암호문 형식이 아닌 바이트 | `형식이 아닌 바이트를 거부한다` | 빈 값 · nonce+태그 미만 · 그냥 평문 바이트 |
| 3 | 단일 예외·같은 메시지·원인 미노출 | `실패 갈래가 서로 구분되지 않는다` | **7갈래**(태그·모르는 세대·길이 미달·모르는 방식·다른 컬럼·다른 행·다른 키)의 (타입, 메시지, cause) 가 정확히 1종이고 cause 가 `null` |
| 4 | nonce 재사용 금지 | `같은 평문을 두 번 암호화하면 결과가 다르다` | 같은 평문 2회 → nonce·암호문 모두 다름, 둘 다 열림 |
| 4 | (음성 대조 상설) | `난수원을 고정하면 nonce 가 반복된다` | 난수원을 고정하면 nonce·암호문이 **같아진다** — 위 케이스가 무엇 때문에 초록인지 고정 |
| 5 | `key_version` 회전 | `키를 회전해도 옛 세대를 읽는다` | v1 로 쓴 행을 v2 배선 뒤에 읽고, 새 쓰기는 v2 |
| 5 | 모르는 세대 | `모르는 키 세대를 거부한다` | 설정에 없는 번호 |
| 5 | 두 컬럼이 실제로 쓰인다 | `결속값이 어긋나면 거부한다` | 행·컬럼·`key_version`·`scheme` **4축** 전건 거부 |
| 5 | 코드 상수 ↔ 스키마 | `CHECK 도메인이 코드 상수와 같다` / `방식 이름이 제약을 지난다` | 실제 DB 의 `pg_get_constraintdef` 가 상수를 허용하고 `fernet` 을 **불허** · 두 테이블 모두 |
| 6 | 즉흥 암호 금지 | `표준 AES-256-GCM 으로 열린다` | **제품 코드를 부르지 않고** 테스트가 JCA 로 형식·AAD 를 재조립해 복호화 |
| — | 봉투가 행 컬럼 둘을 싣는다 | `봉투가 방식과 키 세대를 싣는다` | |
| — | 오설정이 기동을 막지 않는다 | `잘못된 키 재료는 그 세대만 뺀다` | 살아 있는 세대는 정상, 버려진 세대는 단일 예외 |
| — | 쓰기 키 미설정 | `쓰기 키가 없으면 설정 오류다` | `ConfigurationException`(503) |
| — | 로그 유출 0 | `암호화 경로가 로그로 새지 않는다` | 양성 대조 표식 확인 후 평문·키 base64·암호문 base64·깨진 키 재료 4종 0건 |
| — | 래퍼 `toString` | `래퍼가 값을 찍지 않는다` | `PlainBody` 는 길이만, `EncryptedContent` 는 바이트 수·방식·세대만 |
| — | 봉투 동등성 | `봉투 동등성이 값 기준이다` | `ByteArray` 참조 동일성 함정 |

**테스트 키는 소스에 없다.** 실행 시점 `SecureRandom` 으로 만든다 — 스캐너
`SECRET-LITERAL` 의 기준이 경로가 아니라 값의 모양이고, 무엇보다 키를 소스에 적는 습관
자체가 금지 대상이다.

---

## 4. 스키마 변경 — `V3__encryption_scheme_aead.sql`

### 4.1 왜 바꿨나

privacy-gate 03 §5-4 가 **I-8 잠정 위반**으로 적고 해제 조건을 *"Phase 4 에서 저장
암호화를 배선하기 **전에**"* 로 못박았다. V2 의 `DEFAULT 'fernet-v1'` +
`CHECK IN ('fernet-v1')` 이 선 근거 셋은 2026-08-12 결정으로 전부 무효다.

| V2 주석의 근거 | 현재 |
|---|---|
| Python 런타임이 이 DB 를 계속 읽고 쓴다 | Python 폐기 (master-plan 6.2) |
| Phase 7 관찰 기간에 Python 으로 롤백 | 롤백 포기 (§9 결정 2·3) |
| 값은 관찰 기간 내내 `fernet-v1` 로 고정 | **규칙 자체가 삭제** — I-7 검증 5 는 정반대를 요구한다 |

### 4.2 무엇을 바꿨나

| 변경 | 선택한 쪽 | 근거 |
|---|---|---|
| CHECK 도메인 | `('aes256gcm-v1')` — `fernet-v1` 을 **남기지 않는다** | 남기면 그 값으로 쓰는 경로가 열려 있다. 옛 이름을 읽어야 할 행이 존재하지 않는다 |
| 이름 | `aes256gcm-v1` (I-7 예시는 `aes-gcm-v1`) | 리더 지시값. `aes-gcm` 만으로는 AES-128/256 이 구분되지 않고, 이 컬럼의 존재 이유가 "어떤 행이 어떤 방식인가"다. `varchar(16)` 에 12자 |
| `encryption_scheme` DEFAULT | **제거** (새 이름으로 바꾸지 않음) | 이름만 바꾸면 "쓰는 쪽이 방식을 적지 않아도 된다"는 **구조**가 남아 다음 방식에서 같은 사고가 난다. 없애면 빠뜨린 INSERT 가 NOT NULL 위반으로 즉시 시끄럽게 실패한다 |
| `key_version` DEFAULT | **제거**, NOT NULL 유지 | V1 의 `DEFAULT 1` 은 Python `CURRENT_KEY_VERSION = 1` 시절 값이다. 회전 뒤 쓰는 쪽이 세대를 빠뜨리면 **v1 로 적힌 v2 암호문**이 생기고 그 행은 영원히 열리지 않는다(AAD 에 세대가 실린다). NOT NULL 을 푸는 대신 유지한 이유: 암호문이 아직 없는 행도 앞으로 쓸 세대를 적어 두는 편이, 나중에 NULL 해석 규칙을 만드는 것보다 낫다 |
| 「행 0」 전제 | **DO 블록이 실측** | 선언으로 두지 않는다. 행이 있으면 읽을 수 있는 메시지로 끊는다(CHECK 위반 메시지에는 무엇을 해야 하는지가 없다) |

### 4.3 `V2` 파일을 고쳤다 — 체크섬이 바뀐다

privacy-gate 해제 조건 ⑵ 가 *"V2 주석의 무효 근거 3개를 정정"* 이었다. 주석만 고쳐도
**Flyway 체크섬이 바뀌고**, 이미 V2 를 적용한 DB 는 다음 기동에서
`Migration checksum mismatch` 로 멈춘다.

**감수한 근거는 하나다 — 적용된 DB 가 일회용 테스트 컨테이너 말고는 없다**(§9 결정 2:
보존할 운영·파일럿 DB 없음). 로컬 개발 DB 를 오래 쓰고 있었다면 다시 만들어야 한다.
**배포 이후에는 이 방식을 쓸 수 없고, 그때는 정정도 새 마이그레이션으로 한다** —
그 문장을 V3 헤더에 함께 적어 두었다.

### 4.4 가드·기대값

`FlywayBaselineGuard` **본체는 손대지 않았다.** 지문(`python-schema-fingerprint.txt`)은
**V1 만 적용한 상태**의 것이라 V3 추가와 무관하고, `EXPECTED_ALEMBIC_HEAD = "0006"` 도
Alembic 쪽 축이라 바뀌지 않는다. 실제로 손댄 것은 **기대값이 하드코딩돼 있던 자리**다.

`containsExactly("1", "2")` 가 아홉 자리에 박혀 있었다(`FlywayBaselineGuardTest` 5 +
`ApiStartupWithDatabaseTest` 2 + `migrationTypes` 2). 마이그레이션 하나를 더할 때마다
그 아홉을 기계적으로 고치게 되고, 그 손질은 「테스트가 빨개졌으니 숫자를 늘린다」로
지나가 **원래 지키려던 것**(*"V1 은 재적용되지 않고 나머지가 그 위에 얹힌다"*)을 지운다.

그래서 `MigrationCatalog`(testFixtures)가 **소스 디렉터리를 읽어** 기대값을 유도한다.
클래스패스가 아니라 소스인 이유: 리소스 복사가 어긋나 V3 가 jar 에 들어가지 않으면
클래스패스 기준 기대값도 함께 줄어 단언이 통과한다 — 기대와 실제가 같은 곳에서 오면
그 대조는 아무것도 확인하지 않는다.

**이 카탈로그가 막지 못하는 것**(§7 잔여): 마이그레이션 파일을 **지우면** 기대값도 함께
줄어 가드 테스트는 통과한다. 그 축은 내용 테스트(`EncryptionSchemeSchemaTest`)가 지고,
음성 대조 E 가 그것을 실측했다.

---

## 5. 401 3갈래 비율 회귀 (X24-2 — Phase 4 첫 커밋 조건)

`AuthEndpointReachTest` 에 **M-3b** 를 더했다.

| 항목 | 값 |
|---|---|
| 대상 | 삭제 계정 · 위조 서명 · 만료 (`GET /auth/me`, HTTP 경계) |
| 판정 | 세 중앙값의 최대/최소 비 **≤ 1.5** |
| 표본 | 경로당 **101**(홀수라 중앙값이 표본 하나로 정해진다) |
| 워밍업 | **20 라운드** 폐기 |
| 순서 | 고정 시드 교차(`Random(TIMING_SEED)`) |
| 실측 | 삭제 1.64ms / 위조 1.62ms / 만료 1.60ms → **비 1.027** |

표본·워밍업 값은 privacy-gate 재실측 방식과 같다(원장 §1 ③ 이 *"이 저장소는 시간 축
게이트가 흔들려 꺼진 선례를 갖고 있으므로"* 방법을 맞추라고 지정했다).

**무헤더는 대상이 아니다** — 계약 `x-auth.failure_uniformity` 가 한 줄에 묶은 것은
토큰이 제시된 세 갈래이고, 무헤더는 다른 문구(`no_header`)라 바이트 축에서 이미
구분된다(게이트 24 에서 3관점 수렴).

**곁들여 고친 것**: `medianOf` 가 중앙 index 를 `TIMING_SAMPLES / 2` 로 상수에 묶고
있었다. 표본 수가 다른 측정을 붙이면 조용히 중앙이 아닌 값을 읽는다 — 그룹 크기에서
유도하도록 바꾸고 홀수 여부를 `check` 로 못박았다.

**이 케이스가 막지 못하는 것**: 세 갈래가 **함께** 느려지거나 빨라지는 변경은 비가 1 에
가까워 통과한다. 그 축은 M-3(구조)과 `AuthenticationWorkUniformityTest`(DB 왕복 계수)가
지고, 이 케이스는 그것들을 대체하지 않고 **더한다**.

---

## 6. 검증 (실행한 것만)

| 검사 | 명령 | 결과 |
|---|---|---|
| Kotlin 전체 | `./gradlew ktlintCheck detekt build --continue --rerun-tasks` | **exit 0**, `FAILED` 0줄 |
| 모듈 건수 | 위 실행의 test-results | core 359 / application 44 / **infrastructure 135** / **api 188** / worker 3 = **729** (skip 0 · fail 0 · err 0). Phase 3 종료 기준선 699 대비 **+30**(infra +20, api +10) |
| 모듈 경계 | `./gradlew moduleBoundaryCheck` | api·worker 양쪽 통과 |
| 스캐너 | `uv run python .claude/skills/migration-safety-gate/scripts/scan_privacy_invariants.py` | **exit 0**. 새 `crypto/` 파일은 리포트에 **0건** |
| Python 무변경 | `uv run ruff check . && uv run ruff format --check . && uv run mypy . .claude && uv run pytest` | **exit 0** — 1281 passed · 68 skipped · 5 deselected · 5 xfailed. `app/**` 무접촉 |
| toString 게이트 재확인 | 다른 레인 `70d4122` 커밋 후 `SensitiveToStringReachTest` | 4건 통과 — `PlainBody` 가 「값을 감싸는 타입」 판정에 들어가 재정의를 검사받는다 |

### 음성 대조 (일회용 git worktree, `cp` 금지)

worktree `scratchpad/neg`(detached `858347d`)에서 돌리고, 변조 복원은 **`git checkout --`**
로 한 뒤 sha256 을 본 저장소와 대조해 일치를 확인했다. 감사 종료 시
`git worktree remove --force` 로 제거했고 `git worktree list` 로 잔여 0 을 확인했다.

| # | 주입 | 빨개진 케이스 |
|---|---|---|
| A | `updateAAD` 두 줄 제거 | `결속값이 어긋나면 거부한다` · `실패 갈래가 서로 구분되지 않는다` · `표준 AES-256-GCM 으로 열린다` (**3건**) |
| B | `random.nextBytes(nonce)` 제거(nonce 고정) | `같은 평문을 두 번 암호화하면 결과가 다르다` (**1건**, 메시지 *"nonce 가 같다"*) |
| C | 실패 원인별로 다른 예외·다른 메시지 | **7건** — `실패 갈래가 서로 구분되지 않는다` 포함 전 거부 케이스 |
| D | `AuthService` 의 `users.exists(ABSENT_USER_PROBE_ID)` 제거 | `M-3b` — 삭제 2.54ms / 위조 1.08ms / 만료 1.06ms → **비 2.399** (privacy-gate 가 고치기 전 잰 2.18배와 같은 자리) |
| E | `V3` 파일 삭제 | `CHECK 도메인이 코드 상수와 같다` · `방식 이름이 제약을 지난다` · `방식과 키 세대를 적지 않은 INSERT 는 실패한다` (**3건**). `FlywayBaselineGuardTest` 는 **빨개지지 않는다** — 카탈로그가 디스크에서 기대값을 유도하므로 파일이 사라지면 기대도 준다(§4.4 마지막 문단) |

---

## 7. 갈림·잔여 — **다음 단위와 리더에게**

### ⓐ `conversions` 는 암호문 컬럼이 셋인데 `key_version`·`encryption_scheme` 은 행당 하나다 — **결정 필요**

스키마(V1 baseline)가 그렇게 생겼다. `easy_text_encrypted` · `masked_items_encrypted` ·
`edited_text_encrypted` **셋**이 `key_version` 하나를 공유한다.

문제가 되는 경로는 **검수 저장**이다. 변환 완료 시점 T 에 v1 로 두 컬럼을 쓰고, 키를
v2 로 회전한 뒤 시점 T+1 에 `edited_text_encrypted` 를 쓰면:

- 행의 `key_version` 을 2 로 올리면 → **앞의 두 암호문이 영원히 안 열린다**(AAD 에 세대가 실린다)
- 그대로 1 로 두면 → 새 암호문을 **옛 키로** 써야 한다(회전의 의미가 준다)

지금 코드는 어느 쪽도 강제하지 않는다. `ContentCipher` 는 세대를 인자로 받지 않고
`writeKeyVersion` 하나만 쓰므로, **저장 계층이 규율을 져야 한다.** 후보 둘:

1. **행 세대 고정** — 행 생성 시점의 세대를 그 행의 모든 후속 쓰기에 쓴다. 포트에
   "이 세대로 암호화" 오버로드가 필요하다.
2. **행 단위 재암호화** — 검수 저장 시 세 컬럼을 최신 세대로 다시 쓴다. 읽기-복호-재암호가
   한 트랜잭션에 들어간다.

**이 단위에서 고르지 않았다.** 문서 저장 경로가 아직 없어 어느 쪽도 검증할 수 없고,
잘못 고르면 되돌리는 비용이 데이터에 남는다. **다음 작업 단위의 착수 조건으로 올린다.**

### ⓑ `key_version` 은 `smallint`, Kotlin 은 `Int`

컬럼 범위는 −32768..32767, 도메인 타입은 그보다 넓다. 32767 을 넘는 세대는 INSERT 에서
깨진다. 실무상 도달하지 않지만 **타입이 스키마보다 넓다**는 사실을 적어 둔다 — 좁히는
쪽(`Short`)은 산술마다 변환이 붙어 읽기 나빠지고, 검증을 넣는 쪽이 낫다면 그 자리는
저장 계층이다.

### ⓒ 암호문 컬럼의 NULL 허용

`documents.source_text_encrypted` 는 `NOT NULL`, `conversions` 의 셋은 **nullable** 이다
(대기 중 변환에는 결과가 없다). `ContentCipher` 에는 「없음」 개념이 없고 `EncryptedContent`
자체가 non-null 이다 — 저장 계층이 `null` 을 다룬다. 갈림이 아니라 **경계 확인**이다.

### ⓓ I-7 예시 이름과 실제 값이 다르다

`migration-safety-gate` I-7 검증 5 의 예시는 `aes-gcm-v1`, 실제 값은 `aes256gcm-v1`
(리더 지시). 스킬이 *"예"* 로 적은 자리라 위반이 아니지만, 감사 때 문자열이 갈려 보이므로
여기 적어 둔다. 스킬 문구를 고칠지는 `privacy-gate` 판단.

### ⓔ 아직 안 한 것 (이 단위 범위 밖 — 명시)

- `documents`·`conversions` **INSERT/SELECT 경로와 문서 API** — 다음 단위
- 문서 파서(DOCX/PDF/HWPX), 내보내기, 보존 파기 — Phase 4·5 뒤 단위
- **실제 업로드→변환→내보내기를 돌린 뒤 로그 전문 grep**(I-3 검증 5 / Phase 4 종료 조건의
  "평문이 DB·로그에 없음") — 그 경로가 아직 없어 **미실행**이다. 이 단위가 확인한 것은
  암호 서비스 자신의 로그뿐이다
- `worker` 프로필의 암호화 빈이 실제로 조립되는지 — `worker` 는 `@ConfigurationPropertiesScan`
  과 `scanBasePackages` 가 `kr.easydoc` 라 조립되지만, **그것을 확인하는 테스트가 없다**
  (`WorkerStartupTest` 3건은 기동만 본다). Phase 5 에서 worker 가 암호화를 처음 쓸 때 함께 건다

---

## 8. 개선 후보 — **적용하지 않았다**

리더 승인 전까지 코드에 넣지 않는다.

| # | 후보 | 왜 지금 안 하나 |
|---|---|---|
| 1 | `EncryptionProperties.keys` 를 `Map<Int, Secret>` 으로 | 자연스러운 모양은 지도인데, `SensitiveToStringReachTest` 의 표본 생성기(`GeneratedToStringProbes.slotFor`)에 **지도 갈래가 없어** 만나면 `error` 로 끊는다. 게이트를 넓히는 판단은 그 게이트 소유 레인의 몫이라, 이 단위는 게이트가 이미 다루는 형태(목록 + 제품 타입)로 적었다 |
| 2 | 키 재료를 `Secret` 이 아니라 전용 타입으로 | `Secret` 이 이미 `toString` 마스킹·상수 시간 비교를 진다. 타입을 하나 더 만들 이유가 아직 없다 |
| 3 | 복호화 실패에 rate limit / 메트릭 | oracle 은 닫았지만 **반복 시도 자체**는 막지 않는다. 관측·차단은 Phase 5 메트릭 배선과 함께 보는 편이 맞다 |
| 4 | `AuthPorts.kt` 처럼 포트를 한 파일에 모으기 vs 도메인별 분리 | `crypto` 는 새 패키지로 갈랐다. `auth` 쪽 재배치는 이미 개선 후보로 미뤄져 있고 여기서 건드리지 않는다 |
| 5 | `MigrationCatalog` 에 "V3 가 존재한다" 축 추가 | §4.4 마지막 문단의 빈자리. 지금은 내용 테스트가 덮는다. 열거로 메우면 다시 하드코딩이 되므로, 넓히려면 **탐지 형태**를 먼저 정해야 한다 |
