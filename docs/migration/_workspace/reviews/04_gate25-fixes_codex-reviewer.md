# 게이트 26 (`04_gate25-fixes`) — codex 독립 리뷰 원본

**작성:** codex-reviewer / **일자:** 2026-08-19 / **회차:** 1차 (게이트 26 의 1단계, 병렬·독립)
**어간:** `04_gate25-fixes` — **리더가 1단계 호출에서 지정한 값을 그대로 썼다.** 스킬 `{scope}` 정본 표에서 새로 고르지 않았다.
**대상:** `1e685dc..0ce88b4` (13커밋, 변경 파일 26개) + 미커밋 `CLAUDE.md` 1건
**성격:** 게이트 25(`04_crypto`) 리더 판정 9건에 대한 조치의 심판. 이 파일은 **codex 원문 보존이 목적**이고, 판정·종합은 `migration-reviewer` 와 리더의 몫이다.

> **이 파일의 규율.** §3 은 codex 원문 **무편집**이다. Claude 가 쓴 문장은 §1·§2·§4·§5 에만 있다.
> §4「정리」는 지적을 목록화한 것일 뿐 옳고 그름을 판정하지 않는다 — 심각도도 codex 가 붙인 라벨
> 그대로이고 `codex-review` 스킬 §5 척도로의 **환산은 하지 않았다**(환산은 2차 교차 종합의 몫이다).
> codex 가 사실과 다른 전제에 선 것으로 보이는 자리도 지우지 않고 §4 에 「전제 확인 필요」만 달았다.

---

## 1. 호출 메타데이터

### 1.1 공통

| 항목 | 값 |
|---|---|
| 스크립트 | `.claude/skills/codex-review/scripts/codex-review.sh` |
| 헬퍼 | `/Users/harris/.claude/plugins/cache/openai-codex/codex/1.0.6/scripts/codex-companion.mjs` |
| 헬퍼 출처 | plugins cache (버전 자동 선택 — 경로 하드코딩하지 않았다) |
| codex CLI | `codex-cli 0.147.0; advanced runtime available` |
| 세션 런타임 | shared session (`unix:/var/folders/.../cxc-XFsTL0/broker.sock`) |
| 모드 | 전 호출 `adversarial` — 저장 암호화·보안 불변식·게이트 장치가 걸린 변경이라 스킬 §3.2 가 요구한다 |
| HEAD | `0ce88b451629ef24c3beefaaa8e26e3b22952f29` |
| merge-base | `1e685dc77f55b4bcd9345c2631b999bdac715f54` |

**codex 는 이 저장소에 셸 접근을 가진 상태로 돌았다.** 헬퍼 stderr 에 `[codex] Running command: /bin/zsh -lc ...` 기록이 남아 있고, 그래서 일부 지적이 「실제로 돌려 봤다」(javap 확인, 요구 모드 실행, `git ls-files` 전수)를 근거로 든다. 그 실행 결과의 참거짓은 이 파일이 판정하지 않는다.

### 1.2 호출별

| 호출 | 대상 | 스크립트 인자 | 종료 코드 | 출력 바이트 | thread id |
|---|---|---|---|---|---|
| **A** (암호 코어) | branch diff vs `1e685dc` | `adversarial --base 1e685dc "<focus A>"` | **0** | 7,811 | `01a019f5-be37-7d52-a6dc-4b7a64098b10` |
| **B** (하네스 도달) | branch diff vs `1e685dc` | `adversarial --base 1e685dc "<focus B>"` | **0** | 6,732 | `01a019fc-79ba-7bd1-9553-1eb33791d66f` |
| **C** (`CLAUDE.md`) | working tree diff | `adversarial --scope working-tree "<focus C>"` | **0** | 4,228 | `01a01a04-89c7-7853-877d-7f591988c50b` |
| **D** (바이너리 재료 보충) | branch diff vs `1e685dc` | `adversarial --base 1e685dc "<focus D + 18KB diff>"` | **0** | 4,283 | `01a01a0a-f90d-77a2-b569-2dc792861cd4` |

네 호출 모두 **종료 코드 0** 이다 — 스킬 §3.1 표에서 리뷰 근거가 되는 유일한 값이다. 대상 판정도 넷 다 `non-empty` 였다(§1.3). 호출 D 는 21:41:59 에 시작해 **16분 27초** 만인 21:58:26 에 끝났다(A 7분 · B 8분 · C 4분). 프롬프트가 21,524바이트로 가장 크고 codex 가 저장소를 오래 탐색했다.

호출을 넷으로 쪼갠 이유: 리더가 든 확인 항목 8개를 한 프롬프트에 넣으면 스킬 §3.5 의 「한 번에 3~5개 축」 상한을 넘어 전부 얕게 본다. A=축1·2·3(암호), B=축1·6·7(하네스 도달), C=미커밋 `CLAUDE.md`, D=§5.2 의 재료 결함 보충이다.

### 1.3 스크립트가 stderr 에 찍은 대상 판정 (스킬 §6 필수 기록)

```
[A·B·D]
codex-review: 리뷰 대상 = branch diff vs 1e685dc
codex-review: 대상 판정 = non-empty (merge-base=1e685dc77f55, 변경 파일 26개 (branch 모드는 커밋된 변경만 센다))

[C]
codex-review: 리뷰 대상 = working tree diff
codex-review: 대상 판정 = non-empty (staged 0 / unstaged 1 / untracked 13)
```

C 의 untracked 13건은 `.playwright-mcp/**` 11건과 `docs/*.doc` 2건이다. **리뷰 본체가 아니며 focus C 가 그렇게 명시했다.** 사전 확인 결과 `.playwright-mcp/` 는 프런트엔드 접근성 트리 스냅숏이고 담긴 값은 합성 테스트 데이터뿐이다(`ws-verify@example.com`, 「민원 안내문입니다.」). `.doc` 2건은 바이너리라 헬퍼가 `(skipped: ...)` 로 대체한다.

### 1.4 제공한 맥락

- 배경 문서 4건을 **읽었다**: `04_kotlin-implementer_crypto-fixes.md`, `04_harness_gate25-actions.md`, `00_progress.md` 의 게이트 25 절, `reviews/04_crypto_cross.md`. **그 결론은 프롬프트에 옮기지 않았다** — 자기 보고가 이번 리뷰의 검증 대상이므로 codex 에 「이 조치는 완료됐다」를 심으면 심판이 무의미해진다. 프롬프트에는 **채점 기준**(무엇이 지켜져야 하는가)과 **사실**(어느 파일이 무엇을 하기로 돼 있는가)만 넣었다.
- 이번 회차의 다른 산출물(`04_gate25-fixes_migration-reviewer.md`, `04_security-crypto-fixes_privacy-gate.md`)은 **열지 않았다.**
- 민감 데이터 미포함 확인: `.env.example` 의 신규 키 3줄은 **값이 비어 있다**(`EASYDOC_ENCRYPTION_KEY_V1=`). 첨부한 18KB diff 에 base64 32바이트 이상 리터럴 0건.

### 1.5 재료 확인 — 바이너리 판정 파일을 실제로 읽었는가

**읽었다.** `backend-kotlin/infrastructure/src/test/kotlin/kr/easydoc/infrastructure/crypto/AesGcmContentCipherTest.kt` 를 `git show 1e685dc:<경로>` 와 `git show 0ce88b4:<경로>` 로 두 판 모두 떠서 NUL 을 제거한 뒤 `diff -u` 로 대조했다(**18,168바이트 / 329줄**). 그 텍스트 diff 전문을 호출 D 의 프롬프트에 첨부했다. 바이너리 판정의 원인과 범위는 §5.2 에 사실만 적었다.

### 1.6 본 저장소 무변조

Gradle 을 돌리지 않았다 — codex 호출은 읽기 전용이고, 이 회차에서 워크트리를 만들거나 지우지 않았다. **이 에이전트가 만든 파일은 이 산출물 하나뿐이고, 추적 파일은 한 개도 수정하지 않았다.** `HEAD` 는 리뷰 전후 `0ce88b451629ef24c3beefaaa8e26e3b22952f29` 로 같다.

리뷰 시작 시점의 `git status --porcelain --untracked-files=all` sha256 은 `9c74c2e3b0a40cd3f3a18a5137668337e73867544470a00cb6de41ae2ea881ed` 였다. **종료 시점에는 이 값이 달라져 있고, 그 차이는 전부 다른 레인이 만든 것이다** — 이 회차 중 `04_contract-keeper_documents-contract-plan.md`·`04_kotlin-implementer_documents-plan.md`·`04_gate25-fixes_migration-reviewer.md`·`04_security-crypto-fixes_privacy-gate.md` 가 생겼다. **뒤의 두 파일은 이번 회차의 다른 리뷰 산출물이고 독립성 규약에 따라 열지 않았다.** 워킹 트리의 `M CLAUDE.md` 는 리뷰 대상이지 이 에이전트의 편집이 아니다(§2.3·§4.3).

`git worktree list` 의 `wt-gate26`(및 리뷰 초반에 있던 `wt-audit`)는 **같은 시각 다른 레인의 것**이고 이 에이전트가 만들지도 지우지도 않았다.

---

## 2. 전달한 프롬프트 전문

배경 → 지켜야 하는 조건 → 대상 → 질문 순서를 네 호출 모두 지켰다.

### 2.1 호출 A — 암호 코어 5축

```
이 저장소는 Python/FastAPI 런타임을 Kotlin/Spring Boot 로 교체하는 중이다. 이 diff 는 저장 암호화(AES-256-GCM)에 대한 직전 보안 리뷰 지적 9건의 조치다. Python Fernet 과의 암호문 호환은 요구하지 않는다(2026-08-12 롤백 포기). 요구되는 성질은 round-trip, 변조 거부, nonce 재사용 금지, 복호화 oracle 금지, 키 회전이다. 다음 다섯 축만 본다. 각 축에서 조치가 목표를 실제로 달성했는지, 아니면 달성한 것처럼 보이기만 하는지 판정하라.

축1 (키 누설). backend-kotlin/infrastructure/src/main/kotlin/kr/easydoc/infrastructure/crypto/KeyCheckValue.kt 는 KCV(key) = hex(AES-256-GCM(key, nonce=0x00 x 12, aad="easydoc-kcv-v1", plaintext="")[0..5]) 를 정의하고, 같은 디렉터리 CryptoConfiguration.kt 의 기동 자기점검이 그 값을 실패 메시지에 넣는다. .env.example 은 이 값을 설정 파일에 적어도 된다고 안내한다. 이 6바이트가 256비트 키에 대해 무엇을 누설하는지 계산으로 판정하라 — 오프라인 무차별 대입 비용, 서로 다른 키가 같은 KCV 를 낼 확률과 그것이 대조의 의미에 주는 영향, 고정 nonce + 빈 평문 + 고정 AAD 조합에서 GCM 태그가 H = E_K(0^128) 및 E_K(J0) 와 갖는 대수적 관계가 키에 대한 정보를 주는지. 그리고 기동 경로 전체에서 키 재료 자체가 로그, 예외 메시지, 스택트레이스, Spring 프로퍼티 바인딩 실패 메시지, data class 자동 생성 toString, Actuator/env 엔드포인트로 새는 경로가 있는지 찾아라.

축2 (타이밍). backend-kotlin/infrastructure/.../crypto/AesGcmContentCipher.kt 의 decrypt 는 조기 반환을 없애고 없는 키 세대와 길이 미달 갈래에도 더미 키/더미 바이트로 AEAD 를 정확히 한 번 돌리도록 바뀌었다. 조기 분기가 실제로 전부 사라졌는지, 남은 관측 가능한 차이로 실패 원인이 구분되는 경로가 있는지 찾아라 — 예외 타입/메시지, 할당량, 본문 길이 비례 비용, 분기 예측, 맵 조회, 문자열 비교. 그리고 같은 배치가 추가한 비율 회귀 테스트가 무엇을 증명하고 무엇을 증명하지 못하는지 명시적으로 적어라: 단일 JVM 안 상대 비율 측정이 원격 공격자에 대한 상한이 되는가, 문턱 1.5 의 근거가 무엇인가, 이 테스트가 통과하면서도 oracle 이 남는 구성이 가능한가, 측정 표본과 워밍업이 JIT/GC 노이즈를 견디는가.

축3 (정의역). backend-kotlin/core/src/main/kotlin/kr/easydoc/core/crypto/StoredContent.kt 의 PlainBody 는 생성 시점에 짝 없는 서로게이트를 거부한다. 이 거부가 저장 경로 전체를 덮는지 판정하라 — PlainBody 를 만드는 모든 경로(주 생성자, copy(), Jackson 등 역직렬화, Java 상호운용, 리플렉션, value class 라면 init 이 돌지 않는 구성), 그리고 PlainBody 를 거치지 않고 암호화나 저장에 도달하는 우회 경로가 있는지. 거부가 아니라 치환이어야 하는 자리가 있는지도 본다.

축4 (스키마). backend-kotlin/infrastructure/src/main/resources/db/migration/V4__key_version_domain.sql 이 documents 와 conversions 의 key_version 에 CHECK (key_version > 0) 를 건다. 실제 PostgreSQL 의미론으로 판정하라 — NULL 에 대해 이 CHECK 가 무엇을 하는지(그리고 컬럼이 NOT NULL 인지), 기존 행 사전 검사 DO 블록과 ALTER 사이의 경합, smallint 상한과 Kotlin 쪽 EncryptedContent.KEY_VERSION_RANGE 가 어긋나는지, NOT VALID 없이 ALTER 하는 것의 잠금 영향. 그리고 이 CHECK 가 강제되는지 확인한다는 테스트(backend-kotlin/infrastructure/src/test/.../crypto/EncryptionSchemeSchemaTest.kt)가 실제 DB 에 INSERT 를 시도해 거부를 관측하는지, 아니면 SQL 텍스트만 읽는지 확인하라.

축5 (AAD 결속 격리). backend-kotlin/infrastructure/src/test/.../crypto/AesGcmContentCipherTest.kt 가 AAD 에 scheme 과 keyVersion 이 결속됨을 성질로 단언한다고 한다. 그 단언이 결속을 격리해 증명하는지 판정하라 — 다른 세대나 다른 방식의 봉투가 거부되는 이유가 AAD 불일치인가, 아니면 키 조회 실패나 방식 문자열 검사 같은 앞단 분기 때문인가. 두 원인을 구분하려면 같은 키 재료를 두 세대에 공유시키거나 표준 JCA 로 직접 여는 구성이 필요한데, 그런 구성이 실제로 있는지 코드에서 확인하고 없으면 그렇게 적어라.
```

### 2.2 호출 B — 하네스 도달 범위 5축

```
이 저장소의 하네스 규칙 하나가 이 리뷰의 채점 기준이다: 게이트/불변식/규칙을 세우거나 넓힐 때 선언한 범위와 실제 도달 범위를 실행으로 대조한다. 장치는 탐지형/은폐형/강제형/범위선언형으로 분류하고, 은폐형(무시 패턴, 억제, 면제 조항)은 넓히지 않는다. 범위 선언형 장치는 빈 선언에서 통과하면 안 된다. 그리고 어떤 장치든 그것을 떼었을 때 장치 밖에서 무언가 빨개져야 한다 — 자기 단언만 빨개지는 것은 미충족이다. 이 diff 는 그 규칙에 따라 만든 새 게이트 장치들이다. 다음 다섯 축만 본다.

축1. tests/test_kotlin_gate_reach.py 는 Kotlin 가드 테스트 클래스 23개의 선언 목록과 실제 소스 트리를 양방향 정확 일치로 대조하고, Gradle 리포트 XML 로 실행 여부를 대조하고, .github/workflows/ci.yml 이 자기 자신을 돌리는지도 확인한다. 이 파일 자신이 삭제되면 무엇이 빨개지는가? ci.yml 에서 이 파일을 돌리는 스텝이 지워지면? 이 파일이 ci.yml 을 읽어 자기 배선을 확인하는 구조가 순환(자기가 자기 배선을 증명)인지, 아니면 실제로 이 파일 밖의 무언가가 이 파일의 존재와 실행을 강제하는지 판정하라.

축2. 같은 파일의 실행 대조는 환경변수 KOTLIN_GATE_REACH_REQUIRE_REPORT 가 켜졌을 때만 강제되고 없으면 skip 한다. 이 조건부가 탐지형인지 은폐형인지 판정하라 — CI 에서 그 변수가 실제로 켜지는 것을 무엇이 보장하는가, 그 보장 자체가 같은 파일 안에 있는가, 변수가 조용히 빠지거나 잡 이름이 바뀌었을 때 결과가 초록인가 빨강인가, 그리고 리포트 XML 을 근거로 삼는 것이 실행의 증거로 충분한가(오래된 리포트, 부분 실행, 캐시 복원).

축3. .claude/skills/migration-safety-gate/scripts/scan_privacy_invariants.py 의 403 탐지가 이름 열거에서 종류(FORBIDDEN 을 품은 식별자 전체) + 응답 자리 제한으로 바뀌었다. 이 전환이 미탐과 오탐을 실제로 줄였는지, 아니면 옮겼을 뿐인지 판정하라 — 새 응답 자리 목록 밖에서 403 을 내는 실제 Kotlin/Spring 및 Python/FastAPI 관용구를 구체적으로 열거하고, 자리 제한이 새로 만든 미탐 종류를 짚어라. 숫자 갈래(\b403\b)만 자리를 묻지 않는 비대칭이 무엇을 삼키고 무엇을 과잉 적중시키는지도 본다.

축4. tests/test_privacy_scanner.py 에 xfail(strict=True) 로 선언한 미도달 2종(계산값 403, 응답 자리 밖 호출 인자)이 있다. 이것이 탐지형 선언인지 은폐형 면제인지 판정하라 — strict xfail 이 실제로 강제하는 것이 무엇인지, 그 선언이 있는 상태와 없는 상태에서 저장소에 그 형태의 실제 코드가 들어왔을 때 결과가 어떻게 다른지, 그리고 이 선언이 "닫지 않은 구멍"을 계속 보이게 하는지 아니면 실패를 예상된 것으로 바꿔 조용하게 만드는지.

축5. 이 diff 가 도입하거나 고친 장치들(위 넷 + backend-kotlin/api/src/test/.../SourceScanFormsProbe.kt, support/GeneratedToStringProbes.kt, support/ProductClasses.kt, SensitiveToStringReachTest.kt, backend-kotlin/infrastructure/src/test/.../MigrationCatalog.kt) 중, 떼어도 그 장치 자신의 단언 말고는 아무것도 빨개지지 않는 것이 있는지 지목하라. 특히 정확 일치 상수(가드 클래스 개수 23, 선언 수 46)가 사고를 잡는 트립와이어인지 아니면 편집할 때 반사적으로 함께 갱신되는 값인지 판정하고, 그 상수가 잘못된 값으로 커밋됐을 때 무엇이 알려 주는지 적어라.
```

### 2.3 호출 C — 미커밋 `CLAUDE.md` 3축

```
CLAUDE.md 는 이 저장소 에이전트 하네스의 최상위 지침 파일이고 그 안의 규칙은 게이트로 취급된다. 이 working tree 변경의 본체는 CLAUDE.md 에 신설된 「구현 전 리서치·계획 (필수)」 절 하나다 — 기능 착수 전에 ① 라이브러리·프레임워크 리서치(공식 문서/context7) ② 기구현 확인 ③ 계획 문서 작성을 순서대로 끝내야 하고, 마지막 문장이 「이 규칙은 Kotlin 마이그레이션에 한정되지 않는다 — 프론트엔드·도구 스크립트·하네스(.claude/**) 변경에도 똑같이 적용한다」로 범위를 전칭 선언한다. 나머지 untracked 파일(.playwright-mcp/**, docs/*.doc)은 이 리뷰의 대상이 아니다.

채점 기준은 같은 CLAUDE.md 안에 이미 있는 규칙이다: 「게이트·불변식·규칙을 세우거나 넓힐 때 선언한 범위와 실제 도달 범위를 실행으로 대조한다. 대조 없이 전역·모든·항상을 쓰지 않고, 도달 0(이 게이트가 지금 어디서 도는가)을 특히 의심하며, 범위는 근거를 넘지 않는다. 범위 선언형 장치는 빈 선언에서 통과하면 안 된다.」 규칙 전문은 .claude/skills/kotlin-migration/SKILL.md 의 해당 절에 있다.

세 축만 본다.

축1 (도달). 이 새 절이 선언한 범위(모든 기능 구현, 프론트엔드·도구 스크립트·.claude/** 포함)에 대해 실제 강제자가 무엇인가. 저장소에서 이 규칙의 위반을 탐지하는 것(테스트, 훅, CI 스텝, 스캐너, 에이전트 정의)이 하나라도 있는지 실제로 찾아보고, 없으면 도달 0 이라고 적어라. 있으면 그 강제자가 선언한 범위 전체에 닿는지 대조하라. 이 절 자신이 자기가 금지한 형태(대조 없는 전칭 선언)인지 판정하라.

축2 (규약 정합). 이 절이 지정한 계획 산출물 경로 docs/migration/_workspace/{phase}_{agent}_{scope}-plan.md 와 docs/plans/ 가 저장소의 기존 파일명 규약과 일치하는지 확인하라. {phase}·{scope} 값의 정본이 어디에 있는지 찾고(같은 저장소가 이 값을 여러 문서에 복제해 갈린 전례가 있다), 이 새 문장이 그 정본을 가리키는지 아니면 값을 또 복제했는지 판정하라. 실제 docs/migration/_workspace/ 안의 기존 파일명들과 이 패턴이 맞는지도 대조하라.

축3 (내부 모순). 이 절이 CLAUDE.md 안의 다른 조항(「하지 말 것」, 「Definition of Done」, 「하네스: Kotlin 마이그레이션」의 리뷰 게이트, 변경 이력 표)과 모순되거나 중복되는 자리가 있는지 찾아라. 특히 변경 이력 표의 2026-08-19 행이 대상으로 적은 항목(메모리 research-first-no-reinvent, skills/kotlin-migration, agents/kotlin-implementer — 하네스 반영은 다음 게이트)이 실제 파일 상태와 맞는지, 즉 표가 아직 하지 않은 일을 한 것처럼 적고 있는지 확인하라. 그리고 이 변경이 커밋되지 않은 채 남아 있는 것이 리뷰·게이트 관점에서 문제인지도 적어라.
```

### 2.4 호출 D — 바이너리 판정 파일 보충 4축

아래 머리글 뒤에 §1.5 의 18,168바이트 텍스트 diff 전문이 그대로 이어붙어 프롬프트로 나갔다(총 21,524바이트).

```
이 저장소는 Python/FastAPI 를 Kotlin/Spring Boot 로 교체하는 중이고, 이 회차는 저장 암호화(AES-256-GCM) 조치에 대한 독립 심판이다. 이 프롬프트는 재료 결함을 메우는 보충 호출이다.

관측된 사실(판정이 아니라 재료다):
backend-kotlin/infrastructure/src/test/kotlin/kr/easydoc/infrastructure/crypto/AesGcmContentCipherTest.kt 는 Kotlin 문자열 리터럴 안에 NUL 바이트(U+0000) 한 개를 담고 있다(약 75행, .ifEmpty { "[NUL]없는값" } 형태이며 [NUL] 자리에 실제 0x00 바이트가 그대로 들어 있다). 그래서 git 이 이 파일을 binary 로 분류하고, 이 파일을 포함하는 어떤 diff 도 내용 대신 "Bin 26778 -> 40684 bytes" 만 보여 준다. 이 NUL 은 리뷰 기준점 1e685dc 판에도 이미 있었다. 저장소 전체에서 NUL 을 담은 추적 파일은 9개이고 나머지 8개는 jar·zip·docx·hwpx 픽스처이며, 소스 파일로는 이 파일 하나뿐이다.

이 파일의 1e685dc → 0ce88b4 변경분을 NUL 을 제거한 텍스트로 복원해 이 프롬프트 끝에 통째로 붙인다. 저장소 작업 트리의 현재 판은 직접 읽을 수 있다. 다음 네 축만 본다.

축1 (AAD 결속 격리). 이 테스트가 AAD 에 scheme 과 keyVersion 이 결속됨을 격리해 증명하는가. 다른 세대·다른 방식 봉투가 거부되는 이유가 AAD 불일치인지, 아니면 키 조회 실패나 방식 문자열 검사 같은 앞단 분기인지를 이 테스트가 구분하는가. 같은 키 재료를 두 세대에 공유시키는 구성과 표준 JCA 로 직접 여는 구성이 실제로 그 구분을 만들어 내는지 코드로 확인하고, 못 만드는 자리가 있으면 짚어라.

축2 (타이밍 회귀). 복호화 실패 갈래의 소요 시간 비율을 재는 테스트가 무엇을 증명하고 무엇을 증명하지 못하는지 코드를 근거로 판정하라. 표본 구성·워밍업·중앙값·문턱이 어떤 회귀를 잡고 어떤 oracle 을 통과시키는지.

축3 (재료 가시성). 소스 파일이 diff 에 보이지 않는다는 사실의 결과를 판정하라. 이 저장소에서 diff 를 소비하거나 파일을 binary 로 판단해 건너뛰는 장치를 실제로 찾아 열거하라 — .claude/skills/migration-safety-gate/scripts/scan_privacy_invariants.py 의 파일 선별, ktlint/detekt 설정, .github/workflows/ci.yml, tests/ 아래 하네스 테스트, 그리고 코드 리뷰가 diff 기반일 때. 그중 이 파일을 조용히 건너뛰는 것이 있는지 확인하라. 아울러 이 NUL 리터럴이 의도된 것인지, 그것을 쓰는 단언이 실제로 무언가를 재는지, .gitattributes 로 text 로 강제하는 것과 리터럴을 유니코드 이스케이프로 바꾸는 것 중 무엇이 맞는지 적어라.

축4 (음성 통제). 이 diff 가 새로 넣은 테스트 케이스들이 실제 음성 통제인지 판정하라 — 대응하는 제품 동작을 제거하면 그 케이스가 빨개지는가, 아니면 구현을 그대로 복사했거나 항상 참인 자기 만족 단언인가. 특히 X1(서로게이트 거부)·X3(타이밍 균일화)·R-4(catch 범위)·wireName 결속을 겨눈 케이스를 하나씩 본다.

--- 아래는 1e685dc → 0ce88b4 의 이 파일 변경분(unified diff, NUL 제거본) ---
```

---

## 3. codex 원문 출력 (무편집)

아래 네 구획은 codex 가 낸 그대로다. 문장을 다듬거나 중복을 병합하거나 오탐으로 보이는 항목을 빼지 않았다.

### 3.1 호출 A — 암호 코어

```
# Codex Adversarial Review

Target: branch diff against 1e685dc
Verdict: needs-attention

NO SHIP. 축1은 원키 자체의 로그·toString·Actuator 누설은 찾지 못했지만 자기점검이 외부 설정으로 비활성화되고 48비트 KCV는 능동적 충돌 대조에 약하다. 축2는 단일 AEAD와 단일 예외를 구현했으나 비율 테스트는 원격 oracle 부재를 증명하지 않는다. 축3은 Kotlin 호출에서는 막지만 JVM 반사 경계에서 우회된다. 축4의 도메인·NOT NULL·smallint 정합성과 실제 INSERT 테스트는 맞지만 운영 마이그레이션 잠금이 위험하다. 축5는 같은 키를 두 세대에 공유하고 표준 JCA로 직접 열어 AAD 원인을 격리했으므로 요구를 달성했다.

Findings:
- [high] 기동 자기점검을 외부 설정 하나로 우회할 수 있다 (backend-kotlin/infrastructure/src/main/kotlin/kr/easydoc/infrastructure/crypto/CryptoConfiguration.kt:122-126)
  verifyOnStartup은 @ConfigurationProperties의 일반 프로퍼티이고 false이면 경고만 남기고 검증을 전부 건너뛴다. 저장소 YAML에 해당 키가 없다는 테스트는 Spring의 외부 설정을 막지 못한다. JVM 옵션 `-Deasydoc.encryption.verify-on-startup=false`나 명령행 프로퍼티로 제품에서도 즉시 비활성화할 수 있다. 그러면 형식만 맞는 잘못된 32바이트 쓰기 키가 기동을 통과해 이후 행을 복구 불가능한 키로 암호화한다.
  Recommendation: 제품 빈에서는 자기점검을 무조건 실행하고 bindable 스위치를 제거하라. 자기점검이 불필요한 테스트는 test source set의 대체 빈이나 명시적 단위 조립으로 격리하라.
- [medium] 6바이트 KCV는 키 복구 누설은 아니지만 2^48 작업으로 대조를 위조할 수 있다 (backend-kotlin/infrastructure/src/main/kotlin/kr/easydoc/infrastructure/crypto/KeyCheckValue.kt:58-66)
  공개되는 것은 48비트 PRF 출력이다. 균일한 서로 다른 두 키의 KCV 충돌 확률은 2^-48이고, 임의의 다른 충돌 키를 찾는 기대 비용은 약 2^48회 계산이다. 따라서 우발적 오키의 통과 확률은 매우 작지만 `같은 KCV = 같은 키`는 능동적 대조로는 성립하지 않는다. 반면 원래의 균일한 AES-256 키 복구가 2^48로 줄지는 않는다. KCV만 조건으로 약 2^208개의 키가 남고, 암호문으로 실제 키까지 확인하는 전수 탐색은 여전히 평균 약 2^255 후보가 필요하다. 태그는 `MSB48(E_K(J0) xor A·H² xor L·H)` 형태이며 H=E_K(0), J0=0^96||0^31||1이므로 H가 마스크 없이 노출되거나 알려진 AES 키 복구 지름길이 생기지는 않는다. 다만 코드가 키 엔트로피가 아닌 길이만 확인하므로 저엔트로피 32바이트 키에는 공개 KCV가 즉시 오프라인 사전 대조기가 된다. 또한 저장 nonce가 전부 0인 값을 배제하지 않아 KCV와 저장 암호화의 nonce 영역도 엄밀히 분리되지 않았다.
  Recommendation: 서로 독립된 신뢰 저장소의 능동적 대조까지 의도한다면 최소 128비트 KCV를 사용하고, HKDF 등으로 분리한 KCV 전용 키/도메인에서 계산하라. 저장 암호화에서는 예약한 zero nonce를 재추출하도록 하라.
- [medium] PlainBody의 서로게이트 불변식이 JVM 반사 경계에서 사라진다 (backend-kotlin/core/src/main/kotlin/kr/easydoc/core/crypto/StoredContent.kt:59-64)
  읽기 전용 javap 확인 결과 value class의 `ContentCipher.encrypt` JVM 시그니처는 PlainBody가 아니라 String을 직접 받고, 검증은 `constructor-impl`에만 있다. 생성된 public `box-impl`도 그 검증을 호출하지 않는다. 따라서 Kotlin의 정상 생성과 copy 부재는 안전하지만, 반사 호출이나 향후 Jackson/Java 프레임워크 상호운용은 검증되지 않은 String을 암호화 메서드에 전달할 수 있다. 현재 제품 저장 호출자는 아직 없지만, 이런 경로가 추가되면 짝 없는 서로게이트가 UTF-8 변환에서 `?`로 치환된 뒤 정상 태그를 가진 채 저장된다.
  Recommendation: AesGcmContentCipher.encrypt의 실제 UTF-8 인코딩 경계에서도 CharsetEncoder의 REPORT 모드로 재검증하라. 가능하면 inline value class 대신 검증된 factory를 가진 일반 클래스로 JVM 상호운용 표면을 명시하라.
- [medium] V4가 운영 테이블을 전체 검증하는 동안 강한 잠금을 유지한다 (backend-kotlin/infrastructure/src/main/resources/db/migration/V4__key_version_domain.sql:45-51)
  NOT VALID 없이 CHECK를 추가하면 PostgreSQL은 기존 행을 검사하는 동안 ALTER TABLE의 ACCESS EXCLUSIVE 잠금을 잡는다. 두 ALTER가 같은 Flyway 트랜잭션에서 순차 실행되므로 먼저 획득한 잠금도 커밋까지 유지되어 큰 documents/conversions 테이블의 읽기와 쓰기를 중단시킬 수 있다. 앞선 DO 블록은 두 테이블을 한 번 더 전수 검사할 뿐 이 잠금을 줄이지 않는다. DO 검사 뒤 동시 invalid INSERT가 들어오면 최종 ALTER가 이를 발견해 전체 마이그레이션을 롤백하므로 제약이 조용히 우회되지는 않지만 배포 실패와 장시간 대기가 가능하다. CHECK가 NULL을 통과시키는 문제는 V1의 NOT NULL이 막고, smallint 상한은 Kotlin 1..32767과 일치하며, 테스트도 실제 PostgreSQL INSERT 거부를 관측한다.
  Recommendation: 먼저 CHECK를 NOT VALID로 짧게 추가한 뒤 별도 단계에서 VALIDATE CONSTRAINT를 실행하라. 중복 DO 전수 검사는 제거하거나 같은 온라인 검증 절차의 진단 단계로 통합하라.
- [medium] 1.5 비율 테스트는 안정적인 타이밍 oracle도 통과시킬 수 있다 (backend-kotlin/infrastructure/src/test/kotlin/kr/easydoc/infrastructure/crypto/AesGcmContentCipherTest.kt:426-449)
  이 테스트는 짧은 입력과 현재 JCA 공급자를 사용하는 단일 warmed JVM에서 네 갈래 중앙값의 최대/최소 비가 1.5 이하인지 확인한다. 5,000회 워밍업·교차된 2,001개 표본·중앙값은 큰 조기 반환 회귀와 일부 GC 이상치에는 유효하지만, 1.5라는 문턱은 다른 인증 테스트에서 가져왔을 뿐 공격 모델이나 통계적 상한에서 유도되지 않았다. 안정적인 1.49배 차이도 통과하며, 공통 예외/스택트레이스 할당이 비율을 희석해도 반복 측정자는 절대 시간 차이를 평균낼 수 있다. 다른 JVM fork·JCA 공급자·본문 길이·JIT tier·엔드포인트 앞단 분기는 측정하지 않으므로 단일 JVM 비율은 원격 공격자에 대한 상한이 아니다. 구현이 모든 갈래에서 AEAD를 한 번 실행하고 동일 예외를 내는 구조적 개선은 확인되지만, 이 테스트만으로 oracle 부재를 인증해서는 안 된다.
  Recommendation: 이 테스트를 gross-regression smoke test로 명시하고, 여러 독립 JVM fork와 대상 배포 JCA 공급자에서 분포·신뢰구간·절대 효과량을 측정하라. 실제 HTTP 경로가 생기면 동일 크기 요청으로 엔드투엔드 실패 갈래도 검증하라.

Next steps:
- 외부에서 끌 수 없는 기동 검증으로 바꾼 뒤 잘못된 쓰기 키가 제품 컨텍스트 기동을 반드시 막는 통합 테스트를 추가한다.
- KCV 강도와 nonce 도메인을 재설계하고 충돌·저엔트로피 키에 대한 위협 모델을 문서화한다.
- 암호화 sink에서 엄격한 UTF-8 인코딩을 강제하고 반사/JVM 호출 우회 회귀를 추가한다.
- V4를 NOT VALID와 VALIDATE CONSTRAINT의 온라인 절차로 분리한다.
- 타이밍 테스트를 보조 증거로 낮추고 배포 환경 및 향후 HTTP 경로에서 독립 측정을 추가한다.
```

### 3.2 호출 B — 하네스 도달 범위

```
# Codex Adversarial Review

Target: branch diff against 1e685dc
Verdict: needs-attention

출하 불가. 축1의 파일·CI 스텝 단독 삭제는 서로와 전체 pytest가 실제로 실패시키지만, 실행 증거·403 탐지·미도달 선언·Kotlin 범위 장치에는 차단 결함이 남아 있다.

Findings:
- [high] 실제 Gradle XML을 잘못 식별해 실행 대조가 실패한다 (tests/test_kotlin_gate_reach.py:243-251)
  코드는 testsuite의 name을 FQCN으로 취급한다. 현재 PromptInjectionGuardTest$Generator.xml은 name이 `난수 id 생성기`이고 FQCN은 testcase의 classname에 있다. 실제 리포트로 요구 모드를 실행하자 PromptInjectionGuardTest 미실행 판정으로 실패했다. 더구나 CI는 전체 build 뒤 두 개의 필터링 core 테스트를 실행하므로 모듈 리포트가 부분 실행으로 덮어써진 뒤 이 검사가 돈다.
  Recommendation: testcase@classname 또는 검증된 XML 파일명으로 집계하라. 필터 테스트를 전체 테스트보다 먼저 실행하고 결과 디렉터리를 비운 뒤 전체 테스트 직후 검사하며, 리포트를 현재 run/commit에 결속하라.
- [high] 403 자리 allowlist가 실제 응답 형태를 놓치고 숫자는 비응답 문맥을 차단한다 (.claude/skills/migration-safety-gate/scripts/scan_privacy_invariants.py:509-526)
  현재 패턴에 직접 대입하면 Spring `setStatusCode(HttpStatus.FORBIDDEN)`, `ResponseEntity(body, HttpStatus.FORBIDDEN)`, Starlette/FastAPI `JSONResponse(body, status.HTTP_403_FORBIDDEN)`, FastAPI responses-map이 모두 무적중이다. 반면 자리 제한 없는 숫자 갈래는 문자열의 `HTTP 403`, 후행 주석, `if (status != 403)`도 적중한다. 이름 열거의 미탐·오탐을 줄인 것이 아니라 호출명·첫 인자 allowlist 밖으로 미탐을 옮기고 숫자 과잉 적중은 유지했다.
  Recommendation: 문자열·주석을 제거한 구문 층에서 응답 sink의 모든 인자, keyword, 생성자 overload를 해석하라. setStatusCode, Spring 응답 생성자, Starlette/FastAPI response 및 responses-map 회귀를 추가하고 숫자도 응답/API 선언 문맥에 결속하라.
- [high] strict xfail은 미도달을 강제하지 않고 예상 실패로 면제한다 (tests/test_privacy_scanner.py:1624-1678)
  strict xfail이 강제하는 것은 합성 사례가 탐지되기 시작하면 XPASS로 실패하는 것뿐이다. 계산된 403 응답이 실제 제품 코드에 추가되면 scanner는 계속 무적중이고 테스트는 예상된 XFAIL로 suite를 초록으로 만든다. 선언을 삭제해도 제품 gate 결과는 변하지 않는다. 이는 탐지형 선언이 아니라 알려진 구멍을 성공 종료로 바꾸는 은폐형 장치다.
  Recommendation: 실제 응답 sink는 보수적으로 차단하거나 정의-사용 추적으로 403 가능성을 판정하라. 지원 전에는 실제 저장소 occurrence가 0임을 독립 census로 강제하고 비응답 호출은 별도 비차단 문서로 분리하라.
- [high] 일반 class 게이트가 무인자 객체의 민감 필드를 검사하지 않는다 (backend-kotlin/api/src/test/kotlin/kr/easydoc/api/support/GeneratedToStringProbes.kt:164-175)
  주 생성자 파라미터가 비면 담을 값이 없다고 간주해 후보에서 제거한다. 그러나 `class Leak { lateinit var body: String; override fun toString() = body }` 같은 정상 Kotlin 클래스는 무인자 생성 후 사용자 값을 보관하고 유출할 수 있다. 전역 custom-toString 목록이 하나라도 있으면 분모 단언도 통과하므로 이 클래스는 undecidable이나 leak 목록 어디에도 나타나지 않는다.
  Recommendation: 생성자뿐 아니라 민감 이름·UserContent가 붙은 필드와 프로퍼티를 열거하라. 표본 주입이 불가능한 custom-toString 클래스는 제외하지 말고 undecidable로 실패시키며, 무인자 mutable/lateinit 표본을 추가하라.
- [medium] 환경변수 보장은 실행 여부가 아닌 YAML 모양의 자기 단언이다 (tests/test_kotlin_gate_reach.py:259-306)
  현재 env만 삭제하면 requiring이 비어 실패하고, 잡 이름 변경은 스텝이 계속 실행되는 한 안전하다. 그러나 스텝 탐색은 job/step의 if, continue-on-error, 실행 순서와 build 선행 여부를 확인하지 않는다. 따라서 `if: false`인 env 보유 스텝을 남기면 quality 실행은 리포트 검사를 skip하면서 정적 배선 검사는 통과할 수 있다.
  Recommendation: 리포트 검사를 별도 fail-closed 엔트리로 분리하고, 요구 스텝의 job, 전체-test 선행, 조건부 실행 및 continue-on-error 부재를 독립 workflow 검증에서 강제하라.
- [medium] SourceScanFormsProbe는 삭제해도 외부 장치가 실패하지 않는다 (backend-kotlin/api/src/test/kotlin/kr/easydoc/api/SourceScanFormsProbe.kt:25-33)
  이 클래스는 guard family에 포함되지 않고 CI에서 FQCN으로 명시 실행되지 않는다. 저장소 참조도 자기 자신과 KDoc뿐이어서 파일 삭제 시 Gradle은 테스트 한 개가 줄어든 채 통과한다. 그러면 ProductClasses 파서의 도달·미도달 회귀가 전부 사라지지만 장치 밖에서는 아무것도 빨개지지 않는다.
  Recommendation: 이 클래스의 FQCN을 외부 canonical manifest에 넣어 CI가 class-specific으로 실행하고, Kotlin guard inventory가 Probe 계열도 의미 기반으로 발견하게 하라.
- [medium] 23과 46의 정확 개수는 독립 불변식이 아니라 함께 고치는 복제값이다 (tests/test_kotlin_gate_reach.py:112-141)
  GUARD_CLASS_COUNT는 같은 파일의 목록 길이만 검사한다. 값만 틀리면 자기 단언이 알리지만 가드 파일·목록·개수를 함께 줄이면 트리 대조도 통과한다. 발견 집합 역시 실제 테스트 타입이 아니라 파일 stem과 같은 파일의 suffix 목록에서 파생되어 다른 파일 안의 guard나 새 suffix를 보지 않는다. 선언 수 46도 ProductClasses 파서 산출을 다시 적은 값이라 파서가 놓친 선언에는 변하지 않는다.
  Recommendation: 수동 개수 상수를 독립 canonical inventory로 교체하라. Gradle/JUnit이 실제 @Test 보유 클래스나 명시적 보안-gate annotation을 열거하고 CI가 그 inventory를 소비하도록 하며, 동수 교체와 새 suffix 음성 대조를 추가하라.

Next steps:
- XML 식별과 CI 실행 순서를 먼저 고쳐 required-report 모드를 실제로 통과시킨다.
- 403의 Spring/FastAPI 표본을 추가하고 strict xfail을 제거한다.
- Kotlin 선언·guard inventory를 현재 정규식과 개수 상수에서 독립시킨다.
```

### 3.3 호출 C — 미커밋 `CLAUDE.md`

```
# Codex Adversarial Review

Target: working tree diff
Verdict: needs-attention

No-ship: 새 전칭 게이트는 위반 탐지 도달이 0이고, 계획 파일 규약은 정본과 결합되지 않았으며, 변경 이력은 아직 반영되지 않은 하네스 변경까지 완료된 것처럼 기록한다. 미커밋 상태 자체는 working-tree 사전 리뷰가 지원되므로 독립적인 결함은 아니다.

Findings:
- [high] 전칭으로 선언한 필수 게이트의 실제 도달이 0이다 (CLAUDE.md:11-17)
  이 절은 모든 기능과 프론트엔드·도구 스크립트·`.claude/**`까지 리서치→기구현 확인→계획 순서를 강제한다고 선언한다. 그러나 추적 파일 검색 결과 이를 검사하는 테스트·훅·CI·스캐너·에이전트 정의가 없고, `kotlin-implementer`와 `migration-reviewer`에도 계획 존재 확인이 없다. 기존 리뷰 게이트는 Kotlin 코드 변경만 대상으로 하며 Definition of Done도 이 선행조건을 요구하지 않는다. 따라서 계획 없이 구현해도 기존 게이트와 완료 판정을 통과할 수 있다. 이는 실행 근거 없이 `모든`과 전 범위를 사용해, 바로 아래 기존 범위 대조 규칙이 금지한 형태를 이 절 자신이 재현한 것이다.
  Recommendation: 같은 변경에서 계획 산출물과 변경 범위를 결합해 누락·빈 문서·순서 위반을 실패시키는 검사와 CI 배선을 추가하고, 구현·리뷰 에이전트 및 Definition of Done을 갱신하라. 프론트엔드·스크립트·하네스 각각의 음성 테스트도 추가하라. 그 강제가 불가능하면 선언 범위를 실제 도달 범위로 축소하라.
- [medium] 계획 파일 패턴의 phase와 scope가 정본에 결합되지 않았다 (CLAUDE.md:11-15)
  `{phase}`의 정본은 마이그레이션 계획 §5이고 `{scope}`의 유일한 정본은 kotlin-migration 스킬의 표이지만, 새 문장은 어느 쪽도 가리키지 않는다. 실제 계획 파일 `00_contract-keeper_test-plan.md`는 본문상 scope가 `contract`인데 파일명에는 `test`가 들어가고, `03_contract-keeper_react-e2e-plan.md`의 `react-e2e`는 Phase 3 정본 scope 목록에 없다. `docs/plans/`의 기존 파일은 모두 날짜 접두 규약인데 새 절은 디렉터리만 지정한다. 이 상태에서는 구현자와 리뷰어가 어떤 파일을 필수 계획으로 인정해야 하는지 결정적으로 판정할 수 없어 중복 계획이나 거짓 누락·거짓 충족이 생긴다.
  Recommendation: `{phase}`와 `{scope}`의 정본을 명시적으로 참조하고 zero-padding 및 허용 scope를 고정하라. 기존 두 계획 파일을 그 규약에 맞추거나, 이 자리가 review scope가 아니라 일반 `{artifact}`임을 선언하라. `docs/plans/`에도 날짜·slug·대상 변경을 식별하는 파일명 또는 메타데이터 규약을 추가하라.
- [medium] 변경 이력이 미완료 하네스 반영을 완료 대상으로 기록한다 (CLAUDE.md:40)
  2026-08-19 행은 메모리와 함께 `skills/kotlin-migration`·`agents/kotlin-implementer`를 대상으로 열거하면서 같은 셀에서 하네스 반영은 다음 게이트라고 적는다. 실제 메모리 파일은 존재하지만 Git diff는 CLAUDE.md 하나뿐이고, 두 하네스 파일에는 새 리서치·계획 선행조건이 없다. 이 기록은 후속 리뷰어가 구현 에이전트까지 규칙이 전파됐다고 오인하게 만들어 도달 0을 은폐한다.
  Recommendation: 현재 행의 대상을 실제 완료된 CLAUDE.md와 메모리로만 줄이고 하네스 반영을 명시적인 미완료 항목으로 분리하라. 또는 두 하네스 파일을 실제로 갱신하고 그 변경까지 같은 working-tree 리뷰 게이트로 검증한 뒤 완료 이력으로 남겨라.

Next steps:
- 도달 강제와 음성 테스트를 추가하거나 선언 범위를 축소한다.
- 계획 파일명 정본과 기존 산출물을 정합시킨 뒤 변경 이력을 사실대로 고친다.
- 수정된 working tree를 다시 리뷰한 후 커밋한다. 이번 환경에서는 읽기 전용 임시·캐시 제한 때문에 pytest 실행 증거를 만들지 못했다.
```

### 3.4 호출 D — 바이너리 판정 파일 보충

```
# Codex Adversarial Review

Target: branch diff against 1e685dc
Verdict: needs-attention

NO-SHIP. AAD의 keyVersion 격리, JCA 방식 결속, X1 및 wireName 음성 통제는 유효하다. 그러나 핵심 테스트가 바이너리 diff로 은폐되고, 타이밍 게이트는 식별 가능한 차이를 허용하며, R-4 catch에는 실행 가능한 음성 통제가 없다.

Findings:
- [medium] NUL 바이트가 보안 테스트 전체를 diff 리뷰에서 숨긴다 (backend-kotlin/infrastructure/src/test/kotlin/kr/easydoc/infrastructure/crypto/AesGcmContentCipherTest.kt:75)
  원시 0x00 때문에 `git diff 1e685dc..HEAD`는 이 파일을 `Binary files differ`로 처리한다. 저장소의 Codex 리뷰 도구도 `git diff --binary`를 사용하므로 의미 있는 소스 변경 대신 불투명한 바이너리 패치만 전달된다. 반면 privacy scanner는 `.kt`를 확장자로 선택해 `read_text(errors="replace")`로 읽고, Gradle의 ktlint·detekt·test에도 제외 설정이 없어 CI는 파일을 실행한다. `tests/test_kotlin_gate_reach.py`만 일반 `*Test`인 이 클래스를 가드 이름군에서 제외하지만, 이것도 NUL 판정 때문은 아니다. 즉 자동 검사는 돌아가도 테스트 자체가 올바른지는 diff 리뷰가 확인할 수 없다. 해당 NUL은 빈 평문에서 `doesNotContain("")`을 피하려는 대체 토큰일 뿐이고, 빈 값의 암호화를 실제로 검증하지 않는 단언이라 원시 제어문자일 이유가 없다.
  Recommendation: 원시 바이트를 소스상 `"\u0000없는값"` 이스케이프나 평범한 비어 있지 않은 표식으로 바꾸고 textual diff를 다시 리뷰하라. `.gitattributes` 강제는 NUL을 남겨 다른 바이너리 판정기를 계속 속이므로 적절한 해결이 아니다.
- [medium] 타이밍 회귀가 최대 49%의 안정적인 oracle을 승인한다 (backend-kotlin/infrastructure/src/test/kotlin/kr/easydoc/infrastructure/crypto/AesGcmContentCipherTest.kt:426-449)
  5,000회 워밍업, 갈래별 2,001표본, 고정 시드 교차 순서와 중앙값은 과거의 2.84~6.5배 조기 반환 같은 큰 중앙값 회귀는 잡는다. 하지만 판정은 `max(median)/min(median) <= 1.5` 하나라 1.49배의 반복 가능하고 쉽게 식별되는 차이도 통과한다. 중앙값이 같은 꼬리·이봉 분포 차이도 보지 못하며, 짧은 본문과 기본 JCA 공급자를 직접 호출한 프로세스 내부 비용만 잰다. 공격자는 요청을 반복 평균해 훨씬 작은 안정적 차이도 구분할 수 있으므로 이 테스트는 '실패 시간이 구분되지 않는다'는 보안 성질을 증명하지 못한다.
  Recommendation: 공격자의 반복 질의 수와 실측 노이즈에 근거한 훨씬 작은 효과크기 한계를 정하고, 여러 교차 배치의 신뢰구간 및 상위 분위수도 비교하라. 현재의 조기 반환 mutation은 별도 구조적 음성 통제로 유지하라.
- [medium] R-4 RuntimeException 정규화에는 음성 통제가 없다 (backend-kotlin/infrastructure/src/main/kotlin/kr/easydoc/infrastructure/crypto/AesGcmContentCipher.kt:207-224)
  현재 실패 테스트들은 기본 JCA 공급자에서 태그·키·형식 오류를 만들며 모두 `GeneralSecurityException` 계열로 귀결된다. `Cipher.getInstance/init/updateAAD/doFinal`이 `ProviderException` 같은 `RuntimeException`을 내는 경로를 주입하지 않는다. 따라서 코드 경로상 두 번째 catch를 제거해도 이 파일의 테스트가 빨개질 이유가 없고, 문서의 mutation 표에도 R-4 변이가 없다. FIPS·HSM 등 다른 공급자의 런타임 실패가 다시 고유 500 응답·예외 타입·스택으로 노출될 수 있다.
  Recommendation: JCA open 연산을 주입 가능한 작은 경계로 분리하고 `ProviderException`을 던지는 대조군을 추가해 공개 `decrypt`가 고정된 `DecryptionFailedException`·메시지·null cause로 끝나는지 단언하라. RuntimeException catch 제거 mutation이 반드시 실패해야 한다.

Next steps:
- NUL을 이스케이프로 교체한 뒤 1e685dc 기준 textual diff를 다시 생성해 독립 리뷰한다.
- ProviderException 음성 통제와 강화된 통계적 타이밍 게이트를 추가한다.
```

---

## 4. 정리(가공) — 지적 목록화

**이 구획은 Claude 가 쓴 것이다.** 원문(§3)과 분리돼 있고, 여기서도 **옳고 그름을 판정하지 않는다.** 심각도는 codex 가 붙인 `high`/`medium` 라벨 **그대로**이며 `codex-review` 스킬 §5 척도(`Critical`/`Major`/`Minor`/`제안`)로 **환산하지 않았다** — 환산과 마감 부여는 2차 교차 종합의 몫이다. 근거 파일·라인은 codex 가 준 것을 **다시 세지 않고 그대로 옮겼다.**

**네 호출 모두 판정은 `needs-attention`** 이다. 「지적 없음」으로 끝난 호출은 없다. codex 라벨 집계: **high 6 · medium 12**.

### 4.1 호출 A — 암호 코어 (high 1 · medium 4)

| # | codex 라벨 | 지적 요지 | codex 가 준 근거 위치 | 비고 |
|---|---|---|---|---|
| A-1 | **high** | 기동 자기점검이 외부 설정 하나(`-Deasydoc.encryption.verify-on-startup=false`)로 우회된다. 저장소 YAML 에 키가 없다는 테스트는 Spring 외부 설정을 막지 못한다 | `CryptoConfiguration.kt:122-126` | 자기 보고 §2.5(F-2 면제 스위치 + 그 탐지기)가 겨눈 자리와 같은 자리를 **반대 방향**에서 짚는다 |
| A-2 | medium | 6바이트(48비트) KCV 는 키 복구 누설은 아니나 약 2^48 로 대조를 위조할 수 있다. 키 복구는 2^48 로 줄지 않으며 KCV 조건 뒤 약 2^208 개가 남고 전수는 여전히 평균 약 2^255 후보. 태그는 `MSB48(E_K(J0) xor A·H² xor L·H)` 형태이고 H 가 마스크 없이 노출되지는 않는다. 다만 코드가 키 **엔트로피**가 아닌 길이만 확인하므로 저엔트로피 32바이트 키에는 공개 KCV 가 오프라인 사전 대조기가 된다. 저장 nonce 가 전부 0 인 값을 배제하지 않아 KCV 와 저장 암호화의 nonce 영역이 엄밀히 분리되지 않았다 | `KeyCheckValue.kt:58-66` | **리더가 「계산으로 판정하라」고 요구한 항목의 답이다.** 자기 보고 §2.4 의 「키 재료는 한 조각도 넣지 않는다」와 방향이 갈리지 않고, 갈리는 것은 **48비트가 충분한가**다 |
| A-3 | medium | `PlainBody` 의 서로게이트 불변식이 JVM 반사 경계에서 사라진다. javap 확인 결과 `ContentCipher.encrypt` 의 JVM 시그니처는 `PlainBody` 가 아니라 `String` 을 직접 받고 검증은 `constructor-impl` 에만 있으며 `box-impl` 도 그것을 호출하지 않는다 | `StoredContent.kt:59-64` | 리더 확인 항목 4(서로게이트 거부의 **정의역**)에 대한 답 |
| A-4 | medium | V4 가 `NOT VALID` 없이 CHECK 를 추가해 기존 행 검증 동안 ACCESS EXCLUSIVE 잠금을 유지한다. 두 ALTER 가 같은 Flyway 트랜잭션이라 먼저 잡은 잠금도 커밋까지 유지된다. **NULL 통과 문제는 V1 의 NOT NULL 이 막고, smallint 상한은 Kotlin `1..32767` 과 일치하며, 테스트도 실제 PostgreSQL INSERT 거부를 관측한다** | `V4__key_version_domain.sql:45-51` | 리더 확인 항목 5(기존 행·NULL·상향값 경계)에 대한 답. **경계 3종은 통과로, 잠금은 지적으로** 갈렸다 |
| A-5 | medium | 1.5 비율 테스트는 안정적인 타이밍 oracle 도 통과시킬 수 있다. 5,000회 워밍업·2,001 표본·중앙값은 큰 조기 반환 회귀에는 유효하나, 문턱 1.5 는 **다른 인증 테스트에서 가져왔을 뿐 공격 모델이나 통계적 상한에서 유도되지 않았다.** 안정적 1.49배도 통과하고 반복 측정자는 절대 시간차를 평균낼 수 있다. 다른 JVM fork·JCA 공급자·본문 길이·JIT tier·엔드포인트 앞단 분기를 재지 않으므로 단일 JVM 비율은 원격 공격자에 대한 상한이 아니다. **구현이 모든 갈래에서 AEAD 를 한 번 실행하고 동일 예외를 내는 구조적 개선은 확인된다** | `AesGcmContentCipherTest.kt:426-449` | 리더 확인 항목 3(직전 회차 A-6 한계가 해소됐는가)에 대한 답. 「구조는 고쳐졌고 **증명 수단은 여전히 부족**」으로 갈라 적었다 |

**축5(AAD 결속 격리)에서 codex 는 지적을 내지 않았고, 요약에서 명시적으로 「달성」이라고 적었다** — *"축5는 같은 키를 두 세대에 공유하고 표준 JCA로 직접 열어 AAD 원인을 격리했으므로 요구를 달성했다."* 리더 확인 항목 8 에 해당한다. **지적 0건인 사실을 그대로 남긴다 — 대신 채우지 않는다.**

### 4.2 호출 B — 하네스 도달 범위 (high 4 · medium 3)

| # | codex 라벨 | 지적 요지 | codex 가 준 근거 위치 | 비고 |
|---|---|---|---|---|
| B-1 | **high** | 실행 대조가 `testsuite@name` 을 FQCN 으로 취급하는데 `PromptInjectionGuardTest$Generator.xml` 의 name 은 `난수 id 생성기`이고 FQCN 은 `testcase@classname` 에 있다. **실제 리포트로 요구 모드를 실행하자 `PromptInjectionGuardTest` 미실행 판정으로 실패했다.** 더구나 CI 는 전체 build 뒤 필터링 core 테스트 둘을 돌리므로 모듈 리포트가 부분 실행으로 덮인 뒤 이 검사가 돈다 | `tests/test_kotlin_gate_reach.py:243-251` | **codex 가 직접 돌려 실패를 관측했다고 적은 항목.** 자기 보고 §3.4 의 음성 대조 H(요구 모드 ON + 리포트 완비 → exit 0)와 결과가 갈린다 |
| B-2 | **high** | 403 자리 allowlist 가 실제 응답 형태를 놓치고 숫자는 비응답 문맥을 차단한다. `setStatusCode(HttpStatus.FORBIDDEN)`, `ResponseEntity(body, HttpStatus.FORBIDDEN)`, Starlette/FastAPI `JSONResponse(body, status.HTTP_403_FORBIDDEN)`, FastAPI responses-map 이 **모두 무적중**. 반면 자리 제한 없는 숫자 갈래는 문자열 `HTTP 403`·후행 주석·`if (status != 403)` 도 적중. **미탐을 allowlist 밖으로 옮기고 숫자 과잉 적중은 유지했다** | `scan_privacy_invariants.py:509-526` | 리더 확인 항목 7 전반부에 대한 답 |
| B-3 | **high** | strict xfail 은 미도달을 강제하지 않고 예상 실패로 **면제**한다. 강제하는 것은 합성 사례가 탐지되기 시작하면 XPASS 로 실패하는 것뿐이고, 계산된 403 이 실제 제품 코드에 들어오면 스캐너는 계속 무적중인 채 테스트는 XFAIL 로 초록이다. **선언을 삭제해도 제품 게이트 결과는 변하지 않는다 — 탐지형 선언이 아니라 알려진 구멍을 성공 종료로 바꾸는 은폐형 장치다** | `tests/test_privacy_scanner.py:1624-1678` | 리더 확인 항목 7 후반부(`xfail(strict)` 가 은폐형으로 기울지 않는가)에 대한 답. 자기 보고 §2.5 의 「선언 완료」와 정면으로 갈린다 |
| B-4 | **high** | 일반 class 게이트가 **무인자 객체**의 민감 필드를 검사하지 않는다. 주 생성자 파라미터가 비면 후보에서 제거하는데 `class Leak { lateinit var body: String; override fun toString() = body }` 는 무인자 생성 후 사용자 값을 보관·유출할 수 있다. 전역 custom-toString 목록이 하나라도 있으면 분모 단언도 통과하므로 이 클래스는 undecidable 에도 leak 에도 나타나지 않는다 | `GeneratedToStringProbes.kt:164-175` | 자기 보고 §2.6(R-10 이 「일반 class 미도달」을 닫았다)이 남긴 잔여를 짚는다 |
| B-5 | medium | 환경변수 보장이 실행 여부가 아닌 **YAML 모양의 자기 단언**이다. env 만 삭제하면 실패하고 잡 이름 변경은 안전하지만, 스텝 탐색이 `if`·`continue-on-error`·실행 순서·build 선행을 확인하지 않는다. `if: false` 인 env 보유 스텝을 남기면 실행은 skip 하면서 정적 배선 검사는 통과한다 | `tests/test_kotlin_gate_reach.py:259-306` | 리더 확인 항목 6 의 「그 파일 자신이 삭제되면」 축과 인접 |
| B-6 | medium | `SourceScanFormsProbe` 는 **삭제해도 외부 장치가 실패하지 않는다.** guard family 에 없고 CI 에서 FQCN 으로 명시 실행되지 않으며 저장소 참조가 자기 자신과 KDoc 뿐이라, 지우면 Gradle 은 테스트 하나 줄어든 채 통과한다 | `SourceScanFormsProbe.kt:25-33` | **규칙 5(장치 밖이 깨지는가) 미충족을 주장하는 항목** |
| B-7 | medium | 23·46 정확 개수는 독립 불변식이 아니라 **함께 고치는 복제값**이다. `GUARD_CLASS_COUNT` 는 같은 파일 목록 길이만 검사하고, 가드 파일·목록·개수를 함께 줄이면 트리 대조도 통과한다. 발견 집합도 실제 테스트 타입이 아니라 파일 stem 과 같은 파일의 suffix 목록에서 파생되며, 선언 수 46 도 파서 산출을 다시 적은 값이라 **파서가 놓친 선언에는 변하지 않는다** | `tests/test_kotlin_gate_reach.py:112-141` | 자기 보고 §2.7(하한 → 정확 일치 46)과 §3.5-2(함께 지우는 편집은 리뷰가 최종 방어선)가 스스로 적은 한계와 같은 자리 |

**codex 요약의 갈림 표시:** *"축1의 파일·CI 스텝 단독 삭제는 서로와 전체 pytest가 실제로 실패시키지만"* — 리더 확인 항목 6 의 앞부분(게이트 클래스 삭제를 잡는가)은 **통과**로, 뒷부분(실행 증거·자기 배선)은 **결함**으로 갈라 적었다.

### 4.3 호출 C — 미커밋 `CLAUDE.md` (high 1 · medium 2)

| # | codex 라벨 | 지적 요지 | codex 가 준 근거 위치 |
|---|---|---|---|
| C-1 | **high** | 전칭으로 선언한 필수 게이트의 **실제 도달이 0**이다. 검사하는 테스트·훅·CI·스캐너·에이전트 정의가 없고 `kotlin-implementer`·`migration-reviewer` 에도 계획 존재 확인이 없다. 기존 리뷰 게이트는 Kotlin 코드 변경만 대상이고 Definition of Done 도 이 선행조건을 요구하지 않는다. **바로 아래 기존 범위 대조 규칙이 금지한 형태를 이 절 자신이 재현한 것** | `CLAUDE.md:11-17` |
| C-2 | medium | 계획 파일 패턴의 `{phase}`·`{scope}` 가 **정본에 결합되지 않았다.** 실제 계획 파일 `00_contract-keeper_test-plan.md` 는 본문상 scope 가 `contract` 인데 파일명엔 `test` 가 들어가고, `03_contract-keeper_react-e2e-plan.md` 의 `react-e2e` 는 Phase 3 정본 scope 목록에 없다. `docs/plans/` 기존 파일은 모두 날짜 접두 규약인데 새 절은 디렉터리만 지정한다 | `CLAUDE.md:11-15` |
| C-3 | medium | 변경 이력이 **미완료 하네스 반영을 완료 대상으로 기록**한다. 2026-08-19 행이 `skills/kotlin-migration`·`agents/kotlin-implementer` 를 대상으로 열거하면서 같은 셀에서 「하네스 반영은 다음 게이트」라고 적는다. 실제 git diff 는 `CLAUDE.md` 하나뿐이고 두 하네스 파일에 새 선행조건이 없다. **후속 리뷰어가 규칙이 전파됐다고 오인하게 만들어 도달 0 을 은폐한다** | `CLAUDE.md:40` |

**미커밋 상태 자체에 대한 codex 판단:** *"미커밋 상태 자체는 working-tree 사전 리뷰가 지원되므로 독립적인 결함은 아니다."*

### 4.4 호출 D — 바이너리 판정 파일 보충 (medium 3)

**이 호출은 §5.2 의 재료 결함을 메우려고 파일 전문을 손에 쥐여 준 것이다.** codex 요약이 통과와 결함을 앞뒤로 갈라 적었다: *"AAD의 keyVersion 격리, JCA 방식 결속, X1 및 wireName 음성 통제는 유효하다. 그러나 핵심 테스트가 바이너리 diff로 은폐되고, 타이밍 게이트는 식별 가능한 차이를 허용하며, R-4 catch에는 실행 가능한 음성 통제가 없다."*

| # | codex 라벨 | 지적 요지 | codex 가 준 근거 위치 | 비고 |
|---|---|---|---|---|
| D-1 | medium | NUL 바이트가 보안 테스트 전체를 diff 리뷰에서 숨긴다. **저장소의 codex 리뷰 도구도 `git diff --binary` 를 쓰므로 소스 변경 대신 불투명한 바이너리 패치만 전달된다.** 반면 privacy scanner 는 `.kt` 확장자로 골라 `read_text(errors="replace")` 로 읽고 ktlint·detekt·test 에도 제외가 없어 **CI 는 파일을 실행한다.** `test_kotlin_gate_reach.py` 만 이 클래스를 가드 이름군에서 빼는데 그것도 NUL 때문은 아니다. 즉 자동 검사는 돌아도 **테스트 자체가 올바른지는 diff 리뷰가 확인할 수 없다.** 그 NUL 은 빈 평문에서 `doesNotContain("")` 을 피하려는 대체 토큰일 뿐이고 빈 값 암호화를 실제로 검증하지 않는 단언이라 원시 제어문자일 이유가 없다 | `AesGcmContentCipherTest.kt:75` | **리더 재료 결함 통지에 대한 codex 의 답.** 처방에서 `.gitattributes` 강제를 명시적으로 **기각**했다 — *"NUL을 남겨 다른 바이너리 판정기를 계속 속이므로 적절한 해결이 아니다"* |
| D-2 | medium | 타이밍 회귀가 **최대 49% 의 안정적 oracle 을 승인한다.** 5,000회 워밍업·갈래별 2,001표본·고정 시드 교차·중앙값은 과거 2.84~6.5배 조기 반환 같은 큰 중앙값 회귀는 잡지만, 판정이 `max(median)/min(median) <= 1.5` 하나라 1.49배의 반복 가능한 차이도 통과한다. 중앙값이 같은 **꼬리·이봉 분포 차이**도 보지 못하고, 짧은 본문과 기본 JCA 공급자를 직접 호출한 프로세스 내부 비용만 잰다 | `AesGcmContentCipherTest.kt:426-449` | **A-5 와 같은 자리에 독립 도달했다** — 호출 A 는 파일을 셸로 읽고, 호출 D 는 diff 전문을 받은 상태였다. 처방도 같은 방향(효과크기 한계·신뢰구간·분위수)이고, D 는 *"현재의 조기 반환 mutation은 별도 구조적 음성 통제로 유지하라"* 를 덧붙였다 |
| D-3 | medium | **R-4 `RuntimeException` 정규화에 음성 통제가 없다.** 현재 실패 테스트는 기본 JCA 공급자에서 태그·키·형식 오류를 만들어 전부 `GeneralSecurityException` 계열로 귀결되고, `Cipher.getInstance/init/updateAAD/doFinal` 이 `ProviderException` 같은 `RuntimeException` 을 내는 경로를 주입하지 않는다. **따라서 두 번째 catch 를 제거해도 이 파일의 테스트가 빨개질 이유가 없고, 자기 보고의 mutation 표에도 R-4 변이가 없다.** FIPS·HSM 등 다른 공급자의 런타임 실패가 다시 고유 500 응답·예외 타입·스택으로 노출될 수 있다 | `AesGcmContentCipher.kt:207-224` | **리더 확인 항목 1(음성 대조 11건이 실제로 그 장치를 검증하는가)에 대한 직접적 답.** 자기 보고 §3 의 MUT-1~12 목록에 R-4 변이가 없다는 관측과 맞물린다 |

**축4(음성 통제)에서 codex 가 유효하다고 적은 것:** X1(서로게이트 거부) · `wireName` 결속 · AAD 의 `keyVersion` 격리 · JCA 방식 결속. **결함으로 적은 것은 R-4 하나다.** 축1(AAD 격리)은 호출 A 의 축5 와 **같은 결론**이다.

### 4.5 전제 확인 필요 (지우지 않고 표시만 한다)

codex 출력을 편집하지 않되, `migration-reviewer` 가 대조할 때 먼저 확인할 자리를 표시한다. **어느 것도 오탐이라고 판정하지 않았다.**

| 항목 | 무엇을 확인해야 하나 |
|---|---|
| B-1 | codex 가 「실제 리포트로 요구 모드를 실행해 실패를 관측했다」고 적었다. 자기 보고 §3.4 의 음성 대조 H 는 같은 구성에서 exit 0(27 passed)이었다. **두 실행의 리포트 상태가 같았는지**가 갈림의 핵심이다 — codex 는 「CI 가 전체 build 뒤 필터링 테스트를 돌려 리포트를 덮는다」를 원인으로 든다 |
| A-3 | codex 가 javap 로 확인했다고 적었다. `PlainBody` 가 `@JvmInline value class` 인지, `box-impl` 이 검증을 부르지 않는지는 바이트코드로 재확인 가능하다 |
| A-2 | 「저장 nonce 가 전부 0 인 값을 배제하지 않는다」는 KCV 와 저장 암호화의 nonce 영역 분리에 관한 주장이다. `AesGcmContentCipher` 의 nonce 생성이 zero nonce 를 배제하는지 확인 대상 |
| C-2 | codex 가 든 두 파일명(`00_contract-keeper_test-plan.md`, `03_contract-keeper_react-e2e-plan.md`)의 실재와 그 scope 값이 정본 표에 있는지 |
| A-4 · A-5 · D-1 · D-2 | 네 항목 모두 **통과 판정과 지적을 한 문단에 섞어** 적었다. 종합 시 통과 부분(NULL·smallint·실제 INSERT 거부 / 구조적 개선 확인 / 스캐너·ktlint·detekt 는 이 파일을 실제로 읽고 실행한다 / 2.84~6.5배 회귀는 잡는다)이 지적에 묻히지 않게 갈라 옮겨야 한다 |
| D-3 | 「자기 보고 mutation 표에 R-4 변이가 없다」는 codex 의 관측이다. 자기 보고 §3 의 MUT-1~12 목록과 대조해 확인 가능하다(MUT-11 은 미실행으로 자기 보고가 밝혔다) |
| A-5 ↔ D-2 | **같은 자리에 두 호출이 독립 도달했다.** 한 지적을 두 번 센 것으로 오해하지 않도록, 종합 시 「codex 내부 2회 재현」으로 표시하되 **관점 수는 1**(둘 다 codex)임을 유지해야 한다 |

---

## 5. 미실행·실패 항목

### 5.1 실패·재시도 없음 — 네 호출 모두 수령

**이 회차에 codex 리뷰 누락은 없다.** 네 호출 모두 종료 코드 0 이고 출력이 비어 있지 않다. 재시도한 호출도 없다.

기록해 둘 사실 하나: 호출 D 는 중간에 **미완결 assistant 메시지 조각**을 한 번 냈다가 탐색을 재개하고 최종 응답을 냈다. stderr 에 남은 조각은 `{"verdict":"needs-attention","summary":"직접 확인 결과 AAD의 keyVersion 격리와 JCA 방식 결속, X1·wireName 변...` 이고, **§3.4 에 실은 것은 그 조각이 아니라 최종 출력**이다. 조각과 최종본의 방향은 같다.

호출 D 는 16분 27초가 걸려 A·B·C 보다 두 배 이상 길었다. 재현·재호출 시 Bash 도구 timeout 을 넉넉히 주거나 `run_in_background` 로 띄워야 한다(이 회차는 후자로 돌렸다). 프롬프트 원본은 `.../scratchpad/focusD.txt`(21,524바이트)에 남아 있고, 결과를 잃었을 때는 재실행하지 말고 회수한다:

```bash
node /Users/harris/.claude/plugins/cache/openai-codex/codex/1.0.6/scripts/codex-companion.mjs status --all
node /Users/harris/.claude/plugins/cache/openai-codex/codex/1.0.6/scripts/codex-companion.mjs result <job-id>
```

### 5.2 재료 결함 — 소스 파일 하나가 diff 에 보이지 않는다

**사실만 적는다. 결함 판정은 하지 않았다.**

- `backend-kotlin/infrastructure/src/test/kotlin/kr/easydoc/infrastructure/crypto/AesGcmContentCipherTest.kt` 는 **NUL 바이트(0x00) 1개**를 담고 있다. 위치는 약 75행의 Kotlin 문자열 리터럴 안이다 — `.ifEmpty { "<NUL>없는값" }` 형태로, `<NUL>` 자리에 실제 0x00 이 들어 있다(바이트 오프셋 8452).
- 그래서 git 이 이 파일을 binary 로 분류하고, 이 파일이 낀 diff 는 내용 대신 `Bin 26778 -> 40684 bytes` 만 보여 준다.
- **이 NUL 은 기준점 `1e685dc` 판에도 이미 있었다.** 이번 배치가 만든 것이 아니고, 따라서 이 파일은 **이전 게이트들에서도 diff 로는 보이지 않았다.**
- 저장소 전체 추적 파일 중 NUL 을 담은 것은 9개이고, 나머지 8개는 `gradle-wrapper.jar`·`tests/ingest/fixtures/*.zip|docx|hwpx` 다. **소스 파일로는 이 파일 하나뿐이다.**
- 대응: §1.5 대로 두 판을 떠서 텍스트로 대조했고 그 전문을 호출 D 에 첨부했다. **호출 A·B·C 는 이 파일을 diff 로 받지 못했다** — 다만 codex 가 저장소 셸 접근을 가졌고, A-5 가 `AesGcmContentCipherTest.kt:426-449` 를 라인 단위로 인용한 것은 codex 가 **작업 트리 파일을 직접 읽었음**을 보여 준다.
- codex 는 이 사실에 **D-1 로 답했다**(§4.4). 그 답에는 이 에이전트가 프롬프트에 넣지 않은 관측 둘이 들어 있다 — ⑴ **이 저장소의 codex 리뷰 도구 자신이 `git diff --binary` 를 쓴다**, ⑵ 스캐너·ktlint·detekt·test 는 이 파일을 실제로 읽고 실행하므로 **막힌 것은 자동 검사가 아니라 diff 리뷰뿐**이다. 판정은 하지 않는다.

### 5.3 이 회차가 하지 않은 것

- **코드를 고치지 않았다.** 산출물 파일 하나 말고 어떤 파일도 편집하지 않았다.
- **Gradle 을 돌리지 않았다.** 따라서 일회용 워크트리도 만들지 않았다(§1.6).
- 이번 회차의 다른 리뷰 산출물을 **열지 않았다.**
- codex 지적의 **심각도 환산·중복 병합·오탐 판정·표현 다듬기를 하지 않았다.** 그 셋은 2차 교차 종합의 몫이다.

---

## 6. 다음 단계 (스킬 §2.1)

이 파일은 게이트 26 의 **1단계 산출물**이다. 2단계는 `04_gate25-fixes_migration-reviewer.md` 와 이 파일이 **둘 다 존재하는지** 확인하는 것이고, 3단계는 `migration-reviewer` **재호출**로 두 파일을 스킬 §5 표에 대조해 `04_gate25-fixes_cross.md` 를 만드는 것이다. 그 호출에서 **새 지적을 만들지 않는다.**

교차 종합이 놓치기 쉬운 자리 셋을 미리 표시해 둔다. **판정이 아니라 대조 시 주의점이다.**

1. **A-5 와 D-2 는 같은 자리에 대한 codex 내부 2회 재현이다.** 두 행으로 세면 관점 수가 부풀고, 한 행으로 합치면 「두 번 독립 도달했다」는 정보가 사라진다. 한 행 + 재현 표시가 맞다.
2. **codex 가 「지적 없음」으로 남긴 자리를 그대로 옮겨야 한다** — 호출 A 축5(AAD 결속 격리 달성), 호출 D 축4 중 X1·`wireName`·JCA 방식 결속. 리더 확인 항목 8 의 답이 여기 있고, **지적 0건인 사실 자체가 교차 대조의 입력**이다.
3. **codex 는 `reviews/**` 를 보지 않았고 `04_crypto_cross.md` 의 항목 번호 체계(X1~X13·H1~H13·K1~K8)를 모른다.** 이 파일의 A-·B-·C-·D- 번호는 이 산출물 안에서만 유효한 지역 번호다. 교차 종합이 `04_crypto_cross.md` 항목과 이으려면 **근거 위치(파일·라인)로 이어야 하고 번호로 이으면 안 된다.**
