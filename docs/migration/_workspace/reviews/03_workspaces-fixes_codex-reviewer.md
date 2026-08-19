# 게이트 23 · 1단계 codex 독립 리뷰 — `03_workspaces-fixes`

> 이 파일은 **codex 원본**이다. §3 은 **무편집**이고 §4·§5 는 Claude 색인이다.
> 이 에이전트는 codex 지적의 옳고 그름을 **판정하지 않는다** — 심각도 재부여·중복 병합·오탐 표시
> 어느 것도 하지 않았다. 판정과 종합은 `migration-reviewer` 2차 호출(`03_workspaces-fixes_cross.md`)의 몫이다.

**어간**: `03_workspaces-fixes` — 리더가 1단계 호출에서 **고정 지정**한 값을 그대로 썼다(임의 슬러그 생성 없음).

---

## 1. 호출 메타데이터

| 항목 | 값 |
|---|---|
| 착수 시각 | 2026-08-19 14:11:42 KST (`2026-08-19T05:11:42.603Z`) |
| 종료 시각 | 2026-08-19 14:20:46 KST (`2026-08-19T05:20:46.546Z`) |
| 소요 | **9분 4초** |
| 대상 범위 | **`7205d37..e9502a6`** — 커밋 11개, 변경 파일 32개 |
| 모드 | `adversarial` (focus text 필수 — 인증 경계·소유권 은닉·게이트 무력화·범위 도달 축이라 일반 review 로는 초록불을 의심하지 않는다) |
| scope / base | `auto`(미지정) / **`--base 7205d37`** — base 지정 시 scope 는 무시된다 |
| 헬퍼 | `/Users/harris/.claude/plugins/cache/openai-codex/codex/1.0.6/scripts/codex-companion.mjs` |
| 헬퍼 출처 | plugins cache (버전 자동 선택, **1.0.6**) |
| **스크립트 종료 코드** | **`0`** — 리뷰가 돌았고 출력이 비어 있지 않다. 이 값일 때만 리뷰 근거가 된다 |
| job id | `review-mszmwkb1-36g9po` |
| codex thread / turn | `01a0186e-b640-73c0-ad49-91d1836993ba` / `01a0186e-b78f-76c0-bdf6-4363a7dfb1f6` |
| job 로그 | `~/.claude/plugins/data/codex-openai-codex/state/easy-doc-40cce15c488d0114/jobs/review-mszmwkb1-36g9po.log` |
| codex 판정 | **`needs-attention`** — "NO-SHIP." |
| codex 실행 셸 명령 | **36건 시작 / 34건 완료 / 2건 실패**(실패 목록은 §5) |
| focus text 크기 | 14,394 바이트 (sha256 `0d25d4cc37357a7eee8547b1c9560f5631aecf90d4eb0c98dfce4922711592a0`) |
| 지적 건수 | **5건 — high 4 · medium 1 · low 0** |
| codex 출력 크기 | 6,249 바이트 (sha256 `68f70a661c553df6983f2bdc91b1e87f2ace22e62cd22b164057361e74453153`) |

### 1.1 base 를 `7205d37` 로 잡은 근거

리더의 지정 문자열 `7205d37..e9502a6` 를 **그대로** 썼다. `7205d37` 은 이 배치의 **직전 상태**이지
리뷰 대상 커밋이 아니므로 `~1` 보정을 하지 않았다. `git rev-list --count 7205d37..e9502a6` 가
**11** 을 돌려주며 리더가 명시한 "11 커밋" 과 일치한다. 범위가 어긋나지 않았다.

커밋 배분도 리더 지정과 맞는다.

| 덩어리 | 커밋 |
|---|---|
| ⓐ 스캐너 처방 | `ea36330` |
| ⓑ 계약 D-2 신설 + `:2424` 정정 | `0fe654c` |
| ⓒ 게이트 22 조치 9커밋 | `fa87aed` · `b37012c` · `be363c8` · `9e2ce96` · `c663714` · `30cc405` · `3466f6d` · `bfbfc71` · `e9502a6` |

**재현 시 주의 — 리뷰 후 HEAD 가 움직였다.** 스크립트는 `--base <ref>` 에서
`merge-base(HEAD, ref)..HEAD` 를 리뷰하므로 base 뿐 아니라 그 시점의 HEAD 도 대상의 일부다.
리뷰 실행 창은 `05:11:42Z ~ 05:20:46Z` 였고 그때 HEAD 는 `e9502a6`(변경 파일 **32개**)였다.
리뷰 종료 **54초 뒤**인 `05:21:40Z`(`14:21:40+09:00`)에 다른 레인이 `16f3f48`
(`docs/migration/_workspace/03_contract-keeper_react-e2e-plan.md` 1개 파일)을 커밋해
지금 같은 명령을 다시 돌리면 대상이 **33개 파일**이 된다.
**이 회차의 대상에는 `16f3f48` 이 들어 있지 않다** — stderr 이 기록한 "변경 파일 32개" 가 그 증거다.
정확히 재현하려면 `--base 7205d37` 이 아니라 `e9502a6` 를 HEAD 로 둔 상태에서 돌려야 한다.

### 1.2 스크립트가 stderr 에 찍은 대상 판정 두 줄 (원문)

```
codex-review: 리뷰 대상 = branch diff vs 7205d37
codex-review: 대상 판정 = non-empty (merge-base=7205d379aec1, 변경 파일 32개 (branch 모드는 커밋된 변경만 센다))
```

빈 리뷰(exit 7)가 아니었음이 **사전 거부 단계에서** 확인됐다. `--dry-run` 선행 실행에서도 같은 두 줄이 나왔다.

### 1.3 리더가 지정한 문서를 codex 가 실제로 읽었는가 (전사 금지 지시의 이행 확인)

리더 지시는 "codex 에게 cross·두 산출물·privacy-gate 스캐너 산출물을 **읽게** 하라(전사 금지)" 였다.
focus text 는 다섯 문서의 내용을 옮겨 적지 않고 **경로와 줄 수만** 주었다.
codex 가 연 것이 셸 명령 목록으로 확인된다.

| 문서 | 확인 근거 |
|---|---|
| `reviews/03_workspaces_cross.md` | 셸 명령 #4 `sed -n '1,180p' …03_workspaces_cross.md` |
| `03_kotlin-implementer_workspaces-fixes.md` | 셸 명령 #5 `sed -n '1,180p' …03_kotlin-implementer_workspaces-fixe…` |
| `reviews/03_security-scanner_privacy-gate.md` | 셸 명령 #6 `sed -n '1,136p' …03_security-scanner_privacy-g…` |
| `contracts/easy-doc-v1.yaml` | 셸 명령 #7 · #11 · #26 (`git diff --unified=80` + `nl -ba … sed -n '250,335p;1435,1515p'` 등) |
| `reviews/03_security-workspaces_privacy-gate.md` | **확인되지 않음** — 이 경로를 여는 명령이 관측되지 않았다. 다만 #4·#5·#6 이 전부 `&& sed…` 로 이어지는데 stderr·job 로그가 명령을 잘라 기록하므로, 체인 뒷부분에서 읽혔을 가능성을 **배제할 수도 확정할 수도 없다**. 사실만 적는다 |

**diff 밖까지 읽었다.** codex 가 지적 근거로 인용한 파일 중 `core/easyread/StyleRules.kt` 는
이 배치의 변경 32개 파일에 **들어 있지 않다**. codex 가 diff 를 넘어 저장소를 훑은 결과다.
(사실 기록이며 평가가 아니다.)

### 1.4 인용 경로의 기계적 실재 확인

codex 가 준 파일 경로 5개를 **존재 여부와 행 수만** 대조했다(내용 판정 아님).

| 인용 | 파일 총 줄 수 | 인용 상한 | 판정 |
|---|---|---|---|
| `scan_privacy_invariants.py:454-469` | 1784 | 469 | 범위 안 |
| `AuthService.kt:146-150` | 207 | 150 | 범위 안 |
| `CountingDataSource.kt:49-59` | 67 | 59 | 범위 안 |
| `StyleRules.kt:157-162` | 211 | 162 | 범위 안 |
| `ContractSpec.kt:386-389` | 538 | 389 | 범위 안 |

**전부 실재하고 인용 라인 범위가 파일 길이 안에 든다.** 이 회차에도 "존재하지 않는 파일·범위 밖 라인" 인용이 없다.

### 1.5 codex 가 정적 읽기에 그치지 않고 실제로 실행했는가

리더는 축 ③에서 "음성 대조 5종 재현 — `git show` 로 스캐너를 읽고 **정적/메모리 실행**" 을 요구했다.
codex 는 셸 명령 36건 중 최소 5건에서 스캐너를 **실제로 적재해 실행**했다.

| 명령 # | 성격 |
|---|---|
| #18 | `.venv/bin/python` 존재 확인 |
| #19 | `importlib.util` 로 `scan_privacy_invariants.py` 적재 (exit 1 — 실패, §5) |
| #20 | 같은 적재 재시도 (exit 0) |
| #21 | `.venv/bin/python .claude/skills/migration-safety-gate/scripts/scan_privacy_invariants.py …` 스캐너 직접 실행 |
| #33 · #36 | 적재 후 `scan()` 메모리 프로브 |

§3 의 축 ③ 지적이 *"실제 `scan()` 프로브에서 … 적중 0건으로 통과했다"* 라고 쓴 것은 이 실행들을 가리킨다.
**이 에이전트는 그 프로브를 재현하지 않았다** — 재현·검증은 `migration-reviewer` 와 리더의 몫이다.

---

## 2. 전달한 프롬프트 전문 (focus text, 14,394 바이트)

실행 명령:

```bash
.claude/skills/codex-review/scripts/codex-review.sh adversarial --base 7205d37 "$(cat focus_gate23.txt)"
```

헬퍼로 나간 명령(스크립트가 stderr 에 찍은 것):

```
node /Users/harris/.claude/plugins/cache/openai-codex/codex/1.0.6/scripts/codex-companion.mjs adversarial-review --base 7205d37 '<focus text 전문>'
```

focus text 전문:

````text
[배경]
Python/FastAPI 런타임을 Kotlin/Spring Boot 로 교체하는 전환이다. Python 은 폐기 대상이므로 "Python 과 같은 값이 나오는가"는 기준이 아니고, 기준은 요구사항·정책 충족이다. 다만 정책 불변식(마스킹 선행, no-store, 소유권 은닉 404)은 요구사항이 형태 자체를 지정한 것이라 그대로 지켜야 한다. 이 배치(11 커밋)는 직전 리뷰 게이트에서 나온 지적들을 닫으려는 조치이고, 그 조치가 실제로 무엇을 강제하게 됐는지를 본다.

[먼저 읽을 것 — 여기에 내용을 옮겨 적지 않았다. 저장소에서 직접 열어라]
- docs/migration/_workspace/reviews/03_workspaces_cross.md  (직전 게이트 교차 종합 정본. X-1·X-3·X-4·X-6·X-7·X-8·X-12·A-1~A-4·F-1~F-3 항목 번호의 출처)
- docs/migration/_workspace/03_kotlin-implementer_workspaces-fixes.md  (이 배치의 조치 산출물 — 무엇을 어떻게 고쳤다고 주장하는가, 실측값, 하지 않은 것)
- docs/migration/_workspace/reviews/03_security-scanner_privacy-gate.md  (스캐너 8건 처방 산출물 — OWNERSHIP-403 정밀화·SIGNUP_PASSWORD_FIELD 개명의 근거와 음성 대조 표)
- docs/migration/_workspace/reviews/03_security-workspaces_privacy-gate.md  (R-3 = 삭제된 계정의 유효 토큰 네 갈래 실측의 출처)
- contracts/easy-doc-v1.yaml  (2517줄. 계약 정본. 이 배치가 D-2 조항을 신설하고 :2424 부근 6종을 정정했다)
이 문서들의 주장을 그대로 믿지 말고 코드로 대조하라. 산출물은 작성자의 자기 보고다.

[지켜야 하는 조건 — 위반 판정 기준]
1. 타인 자원 접근은 403 이 아니라 404 다. 자원의 존재 자체가 상태 코드·본문·헤더·응답 시간 어느 채널로도 드러나면 안 된다.
2. 오류 본문은 {"detail": ...} 이고 JSON 필드는 snake_case 다. Spring 기본 ProblemDetail 이 새어 나가면 위반이다.
3. 인증 실패는 401 이고 계약 components/responses/Unauthorized 가 정한 두 예시(헤더 없음 / 토큰 무효) 중 상황에 맞는 쪽이어야 한다. 계정이 존재하는지 여부가 401 응답의 어떤 바이트에도 드러나면 안 된다.
4. 로그·예외 메시지·toString·메트릭에 사용자 문서 본문·개인정보가 남으면 안 된다. 로깅은 ID·길이·상태까지다.
5. core 모듈은 Spring·DB 의존이 없어야 한다.
6. 게이트·검사·테스트는 "돌았다"가 아니라 "무엇을 잡는가"로 판정한다. 떼어내도 아무것도 빨개지지 않는 장치는 장치가 아니다.

[대상 — 이 배치의 32개 변경 파일. 대응 Python 원본은 인증·비밀번호에 대해 존재하지 않는다(신규 생성 결정)]
핵심 코드:
- backend-kotlin/application/src/main/kotlin/kr/easydoc/application/auth/AuthService.kt (207줄)
- backend-kotlin/application/src/main/kotlin/kr/easydoc/application/auth/AuthPorts.kt (236줄)
- backend-kotlin/api/src/main/kotlin/kr/easydoc/api/auth/AuthenticationInterceptor.kt (124줄)
- backend-kotlin/api/src/main/kotlin/kr/easydoc/api/auth/AuthController.kt (90줄)
- backend-kotlin/infrastructure/src/main/kotlin/kr/easydoc/infrastructure/auth/JdbcUserRepository.kt (131줄)
- backend-kotlin/api/src/main/kotlin/kr/easydoc/api/workspace/WorkspaceDtos.kt
- backend-kotlin/core/src/main/kotlin/kr/easydoc/core/workspace/Workspace.kt
- backend-kotlin/application/src/main/kotlin/kr/easydoc/application/workspace/WorkspaceService.kt
- backend-kotlin/infrastructure/src/main/kotlin/kr/easydoc/infrastructure/db/SpringTransactionRunner.kt
- backend-kotlin/api/src/main/kotlin/kr/easydoc/api/error/GlobalExceptionHandler.kt
장치(테스트·픽스처·스캐너):
- backend-kotlin/api/src/test/kotlin/kr/easydoc/api/DeletedAccountTokenReachTest.kt (255줄, 신규)
- backend-kotlin/infrastructure/src/test/kotlin/kr/easydoc/infrastructure/auth/CountingDataSource.kt (67줄, 신규)
- backend-kotlin/infrastructure/src/test/kotlin/kr/easydoc/infrastructure/auth/JdbcWorkspaceRepositoryTest.kt
- backend-kotlin/api/src/test/kotlin/kr/easydoc/api/WorkspaceEndpointReachTest.kt
- backend-kotlin/api/src/test/kotlin/kr/easydoc/api/WorkspaceContractTest.kt
- backend-kotlin/api/src/test/kotlin/kr/easydoc/api/support/ContractSpec.kt (538줄)
- backend-kotlin/api/src/test/kotlin/kr/easydoc/api/WorkspaceDtoLeakTest.kt · core/.../WorkspaceNameLeakTest.kt
- backend-kotlin/api/src/test/kotlin/kr/easydoc/api/PasswordHashingBackpressureReachTest.kt
- backend-kotlin/infrastructure/src/testFixtures 또는 test/.../PostgresTestSupport.kt (컨테이너 max_connections 400)
- .claude/skills/migration-safety-gate/scripts/scan_privacy_invariants.py (1784줄) + tests/test_privacy_scanner.py (1560줄)

[질문 — 네 축. 각 축에서 "고쳤다는 주장"이 아니라 "지금 무엇이 강제되는가"를 답하라]

축 ① X-1 인증 경계 (AuthService.authenticate 에 사용자 존재 확인 신설)
- 존재 확인이 보호된 요청마다 **정확히 한 번, 한 곳**에서 도는가? 인터셉터 경로와 /auth/me(AuthController) 경로가 각각 어떤 사슬을 타는지 따라가서, 같은 요청에서 사용자 조회가 두 번 도는 경로(exists + findById 중복)가 있는지 찾아라. 반대로 인증이 필요한데 그 경계를 타지 않는 엔드포인트가 하나라도 있으면 그것이 결함이다 — 컨트롤러/인터셉터 등록 설정을 실제로 열어 매핑 목록과 대조하라.
- 캐시가 정말 없는가? 존재 확인 결과가 요청 스코프·시큐리티 컨텍스트·리포지터리 층 어디에도 재사용되지 않아 계정 삭제가 다음 요청에서 즉시 반영되는지 확인하라.
- 401 두 갈래(헤더 없음 / 토큰 무효) 중 이 경우에 쓰인 문구가 계약의 어느 예시와 일치하는가? 계약 파일에서 그 예시를 찾아 실제 응답 본문과 바이트로 대조하라. 문구가 갈리면 "이 토큰은 한때 유효했다"가 새는지 판단하라.
- 새 타이밍 채널: 존재 확인이 추가되면서 (a) 존재하지 않는 사용자 (b) 존재하는 사용자 (c) 서명이 위조된 토큰 세 갈래의 처리 비용이 달라지는가? 특히 (c)는 DB 를 아예 안 타고 (a)/(b)는 타는 구조라면 그 차이가 무엇을 알려 주는지, 그리고 (a)와 (b) 사이에 인덱스 적중/불발 차이가 있는지 코드와 스키마로 판단하라.
- UserRepository.exists 가 "이메일을 힙에 올리지 않는다"는 주장이 SQL·매핑·드라이버 수준에서 실제로 성립하는가? 같은 커넥션/스테이트먼트 캐시·로깅·예외 경로로 이메일이 돌아오는 자리는 없는가?
- DeletedAccountTokenReachTest 가 단언하는 "다섯 경로가 서로 구분되지 않는다"의 실제 강도: 본문 distinct 1종·헤더 이름 집합 distinct 1종을 건다는데, 헤더 **값**과 응답 시간은 비교 대상이 아니다. 이 테스트를 통과하면서도 존재를 흘리는 구현을 만들 수 있는가?

축 ② X-3 탐지형 전환의 정직성 (시간 축 게이트가 자기 변이를 못 잡아 구조 축으로 갈아탔다)
- CountingDataSource 가 감싸는 것이 무엇인지 배선을 따라가라. 프로덕션 코드가 실제로 타는 DataSource 경로를 감싸는가, 아니면 테스트에서만 만들어 리포지터리에 주입하는 대리 객체인가? 후자라면 "SQL 왕복 수 1"이 프로덕션 동작에 대해 무엇을 증명하고 무엇을 증명하지 않는가?
- 문장 생성 호출을 세는 방식이 실제 DB 왕복과 등가인가? PreparedStatement 를 한 번 만들고 여러 번 실행하는 구현, 배치, 커넥션 풀의 재사용, 트랜잭션 경계에서 도는 부가 문장(BEGIN/COMMIT/SET) 이 카운트에 어떻게 반영되는지 확인하라. 카운트를 우회하면서 소유 판정을 분리하는 구현이 가능한가?
- "없음/타인/내것 = 각 1"이라는 단언이 소유 조건의 SQL 내장을 정말 강제하는가? 소유 판정을 애플리케이션 층으로 빼면서도 문장 수를 1로 유지하는 구현(예: 결과를 읽고 코드에서 비교하되 같은 문장으로 읽기)이 통과하는지 검토하라.
- 시간 축 테스트를 남긴 사유가 KDoc 에 적혔다. 그 문면이 장치의 실제 검출력보다 크게 읽히는가? 남은 시간 축 테스트가 지금 실제로 잡을 수 있는 변이가 존재하는가, 아니면 항상 초록인 장치인가? 항상 초록이면 그 자리는 무엇을 하고 있는가.
- MAX_TIMING_RATIO 1.5 와 정상 관측 최대 1.103 사이의 여유가 의미하는 것: 이 문턱으로 걸러지는 실제 공격 시나리오가 있는가?

축 ③ 스캐너 정밀화·계약 정정
- scan_privacy_invariants.py 의 OWNERSHIP-403 "소비형 대안"을 직접 읽고, 진짜 403 반환을 놓치는 입력을 구성해 보라. 최소한 다음을 정적으로 또는 파이썬을 실제 실행해 확인하라: (a) ResponseEntity.status(403), (b) HttpStatus.FORBIDDEN, (c) 상수 선언 + 사용처 status(FORBIDDEN), (d) @ApiResponse(responseCode="403"), (e) 불활성 형태와 같은 줄에 있는 진짜 403 반환. 소비형 대안이 먼저 매치를 삼켜 이후 진짜 반환을 놓치는 조합이 있는가? 특히 한 줄에 여러 토큰이 있을 때, 그리고 부호 반전 단언의 **두 번째 이후 인자**에 403 이 오는 경우를 보라.
- isNotEqualTo/assertNotEquals/assertNotSame/isNotIn 의 "첫 인자"만 소비한다는 설계가 놓치는 형태가 있는가? assertThat(FORBIDDEN).isNotEqualTo(x) 같은 뒤집힌 배치, 또는 체이닝으로 실제 403 기대가 이어지는 형태.
- 백틱 식별자·@DisplayName 대안이 토큰을 품은 것만 매치하도록 조였다는데, 그 정규식이 여전히 삼키는 정당한 신호가 있는가? 제외 집계 6건이 정확히 무엇인지 실행해 확인하라.
- UNMARKABLE_RULES 여섯 항목이 그대로인지, 마커 예산이 7/7 인지, 경로 면제·심각도 강등이 새로 들어오지 않았는지 diff 로 확인하라.
- 남은 것으로 선언한 HTTP_403_FORBIDDEN · SC_FORBIDDEN 미도달을 xfail(strict=True) 로 등재한 것: 이 선언이 정직한가, 아니면 은폐인가? xfail 이 xpass 로 뒤집히는 조건이 실제로 존재하는가? 그리고 이 미도달이 "기존 결함"이라는 주장이 옛 패턴으로 실측해 성립하는가.
- 계약 D-2: contracts/easy-doc-v1.yaml 의 paths./workspaces/{workspace_id}.delete.description 조항을 읽고, 그 문면이 정한 거절 순서가 WorkspaceService.delete 의 실제 분기 순서와 일치하는지 대조하라. ContractSpec.deletionRefusalPrecedenceExample(P-22)이 그 조항을 앵커로 집는 방식이 조항 문구가 바뀌면 깨지는가, 아니면 다른 문장에도 우연히 매치하는가.
- :2424 부근을 포함한 이 배치의 계약 정정 6종이 완전한가? 같은 종류의 오류가 계약의 다른 자리에 남아 있는지 전수로 확인하라(예: 같은 패턴의 응답 코드·헤더·예시 누락).

축 ④ 선언한 범위와 실제 도달
- ContractSpec 의 fail-open 수정(P-16): filterIsInstance 로 조용히 버리던 갈래를 error() 로 끊었다는데, 파일 전체에서 같은 형태로 **아직 조용히 버리는** 자리가 남아 있는가? filterIsInstance, mapNotNull, as?, ?: emptyList(), catch 무시 전부를 훑어라. 그리고 정상 계약에서 거짓 양성 0 인지, 손상된 계약에서 실제로 빨개지는지 가능하면 실행해 확인하라.
- 테스트 컨테이너 max_connections 를 100 → 400 으로 올린 조치가 **숨기는 것**이 무엇인가? Spring 테스트 컨텍스트 캐시가 프로퍼티 조합마다 컨텍스트를 새로 만들고 각각 HikariCP 풀을 유지하는데, 상한을 올리면 컨텍스트 누수가 지표를 잃는다. 400 이라는 값의 근거가 있는가, 다음 한계는 언제 오는가, 그리고 이것이 게이트를 은폐하는 형태인가.
- "산출물 검사 표에 스캐너 exit code 를 상시 포함한다"(F-3)는 선언이 실재하는가? 이번 산출물에 실제로 들어 있는지, 그리고 그 선언을 강제하는 장치가 있는지(다음 회차에 빠뜨리면 무엇이 빨개지는가) 확인하라. 강제자가 없으면 그 선언의 도달은 얼마인가.
- 이 배치가 새로 도입한 "전역"·"모든"·"항상" 류의 범위 선언이 있는가? 있으면 그 강제 수단이 닿지 않는 경로를 찾아라. 특히 toString 마스킹(A-3)은 Workspace·WorkspaceResponse·WorkspaceListItemResponse·WorkspaceNameRequest 넷에만 적용됐는데, 같은 위험을 가진 data class 가 저장소에 더 있는가? 넷만 고른 근거가 열거인가 구조인가.
- A-2 의 pg_constraint FK 개수 단언: 정확히 하나임을 못 박는 단언이 앞으로 정당한 FK 추가를 막는 형태인가, 아니면 예외 분류를 함께 고치게 만드는 형태인가. 실패 메시지가 처방을 담는지 실제로 읽어라.
- 이 배치가 더한 장치들(DeletedAccountTokenReachTest, CountingDataSource 단언, WD-9, WorkspaceDtoLeakTest, WorkspaceNameLeakTest, pg_constraint 단언) 각각에 대해: **떼어내면 정확히 무엇이 깨지는가?** 떼어도 아무것도 안 깨지는 것이 있으면 지목하라.
- 이 배치에 은폐형(무시 패턴·억제·면제 조항·전역 예외·범위 넓히기)이 하나라도 들어왔는가?

[위반 시 결과]
① 이 깨지면 해지한 계정의 토큰으로 남의 데이터에 계속 닿거나, 401 의 미세한 차이로 계정 존재가 열거된다.
② 가 깨지면 소유권 은닉을 지키는 게이트가 아무것도 재지 않으면서 초록만 낸다.
③ 이 깨지면 진짜 403 반환이 CI 를 조용히 통과해 타 사용자 자원의 존재가 노출된다.
④ 가 깨지면 지금 세운 장치들이 다음 Phase 에서 무력한 채로 남고, 아무도 그것을 모른다.

[출력 요구]
각 지적에 파일 경로와 라인을 붙여라. 추측이면 추측이라고 적어라. 지적이 없는 축이 있으면 "이 축에서는 발견 없음"이라고 명시하라 — 채우지 마라. 심각도(high/medium/low)를 붙이고 마지막에 ship/no-ship 판정을 적어라.
````

---

## 3. codex 원문 출력 (무편집)

> 아래는 codex 가 낸 출력 그대로다. Claude 가 한 글자도 고치지 않았다.
> 심각도·표현·경로·라인 번호 전부 codex 가 쓴 것이다.

````text
# Codex Adversarial Review

Target: branch diff against 7205d37
Verdict: needs-attention

NO-SHIP — 네 축 모두에서 실질 결함을 확인했다: 인증 실패 타이밍 분기, 소유권 게이트의 거짓 초록, 403 스캐너 우회, 계약 파서 fail-open, 사용자 본문·PII의 toString 노출이 남아 있다.

Findings:
- [high] 축 ③: 이름을 바꾼 403 상수는 OWNERSHIP-403을 완전히 우회한다 (.claude/skills/migration-safety-gate/scripts/scan_privacy_invariants.py:454-469)
  상수 선언 대안은 이름과 무관하게 모든 `val/var/const ... = 403`을 불활성으로 소비한다. 실제 `scan()` 프로브에서 `private const val ACCESS_DENIED = 403` 뒤 `ResponseEntity.status(ACCESS_DENIED)`를 호출한 Kotlin 코드와 동일한 Python 코드가 적중 0건으로 통과했다. 사용처에는 `403`이나 `FORBIDDEN` 토큰이 없기 때문이다. 별도 xfail로 남긴 `HTTP_403_FORBIDDEN`과 `SC_FORBIDDEN`도 같은 실제 미탐이다. 따라서 진짜 타인 자원 403 반환이 CI exit 0으로 출하될 수 있다.
  Recommendation: 상수 선언을 일괄 제외하지 말고 기본적으로 BLOCK하거나 언어 파서 기반 정의-사용 추적을 구현하라. 임의 별칭과 `HTTP_403_FORBIDDEN`·`SC_FORBIDDEN`을 반드시 잡는 회귀로 바꾸고 현재 strict xfail을 정상 통과 테스트로 전환하라.
- [high] 축 ①: 삭제 계정 토큰과 위조 토큰의 처리 비용이 실제로 갈린다 (backend-kotlin/application/src/main/kotlin/kr/easydoc/application/auth/AuthService.kt:146-150)
  위조 토큰은 `accessTokens.verify`에서 즉시 실패해 DB를 전혀 타지 않지만, 서명이 유효한 삭제 계정 토큰은 `users.exists`의 `SELECT 1` 왕복을 반드시 치른 뒤 같은 401을 낸다. 이는 반복 측정으로 토큰이 한때 유효했는지를 구분하는 타이밍 채널이다. `DeletedAccountTokenReachTest`는 두 응답의 상태·본문·헤더 이름만 비교하고 헤더 값과 시간은 재지 않는다. 또한 `/auth/me`는 이 존재 조회 후 `readUser/findById`까지 수행해 한 요청에서 사용자 조회가 두 번 돈다.
  Recommendation: 서명·클레임 실패에도 삭제 계정 실패와 동등한 더미 DB 조회를 수행하도록 실패 비용을 맞추고, 삭제/위조 요청을 교차 측정하는 타이밍 회귀와 변동 헤더를 제외한 전체 헤더 값 비교를 추가하라. `/auth/me`의 중복 조회도 요청 경계에서 한 번 읽은 결과를 안전하게 전달하도록 정리하라.
- [high] 축 ②: CountingDataSource는 DB 왕복이 아니라 Statement 생성 횟수만 센다 (backend-kotlin/infrastructure/src/test/kotlin/kr/easydoc/infrastructure/auth/CountingDataSource.kt:49-59)
  프록시는 `prepareStatement/createStatement/prepareCall` 호출만 증가시키고 반환한 Statement의 `execute*`는 감싸지 않는다. 따라서 Statement 하나로 소유자를 SELECT하고 Kotlin에서 비교한 뒤 같은 Statement로 UPDATE해도 없음/타인/내것이 모두 1로 남는다. 현재 기능 테스트와 1.5 타이밍 테스트도 이 형태를 통과할 수 있어, 소유 판정이 SQL 조건에서 애플리케이션으로 빠져도 구조 게이트가 초록이다. 별도 테스트용 DriverManagerDataSource에 직접 만든 저장소만 감싸므로 프로덕션 Hikari·트랜잭션 경로의 부가 실행도 증명하지 않는다.
  Recommendation: 반환된 Statement/PreparedStatement를 다시 프록시해 실제 `execute*` 호출과 SQL을 기록하고, 생산 DataSource 빈을 감싼 통합 테스트에서 변경 SQL이 `id`와 `user_id`를 같은 문장 조건으로 소비하는지 단언하라.
- [high] 축 ④: A-3 테스트는 네 타입만 열거하고 기존 본문·PII data class는 그대로 노출한다 (backend-kotlin/core/src/main/kotlin/kr/easydoc/core/easyread/StyleRules.kt:157-162)
  `SentenceIssue`는 사용자 문서의 문장 전체를 String으로 보유한 data class라 생성된 `toString()`이 본문을 그대로 반환한다. 같은 위험이 `RepairPrompt`, `Outcome.Body`, `Adoption`, `User`, `UserResponse`에도 남아 있다. 새 Workspace 테스트는 네 타입을 직접 생성하는 열거식 장치라 이 타입들이나 다음 data class에는 닿지 않는다. 현재 코드 자체가 사용자 본문·개인정보를 toString에 남기지 말라는 전역 조건을 위반한다.
  Recommendation: 본문·이메일을 보유한 모든 타입의 `toString()`을 ID·길이·상태만 남기도록 재정의하거나 안전한 래퍼 타입을 사용하라. 이후 민감 String 필드를 가진 생산 data class가 안전한 표현 정책 없이 추가되면 실패하는 구조적 검사로 열거식 테스트를 보완하라.
- [medium] 축 ③·④: P-16 수정 뒤에도 ErrorResponse 계약 파서가 같은 방식으로 fail-open한다 (backend-kotlin/api/src/test/kotlin/kr/easydoc/api/support/ContractSpec.kt:386-389)
  `errorDetailUnionTypes()`는 `oneOf` 갈래를 다시 `filterIsInstance<Map<*, *>>()`로 거른다. 현재의 string/array 갈래 옆에 스칼라나 지원하지 않는 노드를 주입하면 그 갈래만 조용히 사라지고, 호출 테스트는 여전히 `string,array`만 받아 통과한다. `headerComponentsByName`과 `requestFieldConstraint`에도 같은 필터링이 남아 있어 이번 수정은 파일 전체의 실패 모드를 닫지 못했다.
  Recommendation: 모든 계약 컬렉션을 `mapIndexed`로 순회해 각 원소가 기대한 매핑이 아니면 위치를 포함해 즉시 `error()`로 종료하라. 각 잔존 접근자에 스칼라 갈래를 주입하는 손상 계약 음성 대조를 추가하라.

Next steps:
- 403 별칭 프로브와 알려진 두 framework 상수를 모두 BLOCK하도록 스캐너를 먼저 수정한다.
- 인증 실패 비용과 전체 헤더 값의 동형성을 구현·측정한 뒤 삭제 계정 HTTP 테스트를 재실행한다.
- 실행 횟수·SQL 조건을 재는 생산 DataSource 통합 게이트와 잔존 ContractSpec 손상 계약 테스트를 추가한다.
- 민감 data class 전수 조치 후 전체 Kotlin 빌드와 privacy scanner를 `--no-fail` 없이 다시 실행한다.
````

---

## 4. 정리(가공) — Claude 색인

> **이 구획은 §3 의 원문을 찾아보기 쉽게 색인한 것뿐이다.** 옳고 그름·심각도 재부여·오탐 표시·
> 중복 병합 어느 것도 하지 않았다. 심각도는 codex 가 붙인 값 그대로다.

### 4.1 지적 5건 색인

| # | codex 심각도 | 축 | 한 줄 | 인용 위치 |
|---|---|---|---|---|
| C-1 | **high** | ③ | 이름을 바꾼 403 상수는 OWNERSHIP-403 을 완전히 우회한다 | `scan_privacy_invariants.py:454-469` |
| C-2 | **high** | ① | 삭제 계정 토큰과 위조 토큰의 처리 비용이 실제로 갈린다 | `AuthService.kt:146-150` |
| C-3 | **high** | ② | `CountingDataSource` 는 DB 왕복이 아니라 Statement 생성 횟수만 센다 | `CountingDataSource.kt:49-59` |
| C-4 | **high** | ④ | A-3 테스트는 네 타입만 열거하고 기존 본문·PII data class 는 그대로 노출한다 | `StyleRules.kt:157-162` |
| C-5 | **medium** | ③·④ | P-16 수정 뒤에도 `ErrorResponse` 계약 파서가 같은 방식으로 fail-open 한다 | `ContractSpec.kt:386-389` |

### 4.2 리더가 지정한 네 축 대 codex 지적의 분포

| 축 | codex 지적 |
|---|---|
| ① X-1 인증 경계 | C-2 (타이밍 분기 + `/auth/me` 이중 조회) |
| ② X-3 탐지형 전환의 정직성 | C-3 (대리 측정 + 목표 변이 통과 가능) |
| ③ 스캐너 정밀화·계약 정정 | C-1, C-5 |
| ④ 도달 범위 | C-4, C-5 |

**네 축 모두에서 지적이 나왔다.** "이 축에서는 발견 없음" 으로 명시된 축은 없다.

### 4.3 리더가 물었으나 codex 출력이 명시적으로 답하지 않은 항목

원문에 해당 문장이 없다는 사실만 적는다. **답이 없다는 것이 "문제 없음" 을 뜻하지 않는다** —
codex 는 지적만 출력하는 형식이라 무지적 항목이 침묵으로 나타난다.

| 축 | 리더 질문 중 원문에 대응 문장이 없는 것 |
|---|---|
| ① | `exists` 가 이메일을 힙에 안 올린다는 주장의 성립 여부 / 캐시 0 확인 / 401 두 갈래 중 맞는 문구인지의 계약 대조 결과 / 인증 경계를 타지 않는 엔드포인트 유무 |
| ② | `MAX_TIMING_RATIO` 1.5 로 걸러지는 실제 공격 시나리오 유무 / 시간 축 KDoc 문면이 장치보다 큰지 |
| ③ | `UNMARKABLE_RULES` 6항목·예산 7/7 유지 확인 / 제외 집계 6건의 내역 / 부호 반전 단언 둘째 인자·역배치 형태 / 계약 D-2 문면과 `WorkspaceService.delete` 분기 순서 일치 / P-22 앵커의 중복 매치 위험 / `:2424` 정정 6종의 완전성 |
| ④ | `max_connections` 400 이 숨기는 것(컨텍스트 캐시 누수) / F-3 스캐너 exit 상시 포함의 실재와 강제자 / A-2 `pg_constraint` 단언의 형태 / 이 배치에 은폐형이 들어왔는지 / 각 신설 장치를 떼면 무엇이 깨지는지 |

단 C-1 은 `HTTP_403_FORBIDDEN`·`SC_FORBIDDEN` xfail 을 "같은 실제 미탐" 이라고 언급했고,
C-4 는 A-3 의 열거식 선정을 "구조가 아니라 열거" 로 다뤘다 — 그 범위에서는 축 ③·④의 일부 질문에 답이 닿았다.

### 4.4 전제 확인 필요 (판정 아님 — `migration-reviewer` 가 확인할 자리)

codex 원문이 사실 주장으로 제시한 것 중, 이 에이전트가 **재현하지 않아** 참·거짓을 모르는 항목이다.
지우지 않고 그대로 남긴다.

1. C-1 의 *"실제 `scan()` 프로브에서 … 적중 0건으로 통과했다"* — codex 가 셸 명령 #33·#36 에서
   스캐너를 적재해 실행한 것은 §1.5 로 확인했으나, **그 프로브의 입력과 출력을 이 에이전트가
   재현하지 않았다.**
2. C-2 의 *"`/auth/me` 는 이 존재 조회 후 `readUser/findById` 까지 수행해 한 요청에서 사용자 조회가 두 번 돈다"*
   — 코드 대조 미수행.
3. C-3 의 *"현재 기능 테스트와 1.5 타이밍 테스트도 이 형태를 통과할 수 있어"* — 원문이 변이를 실제로
   만들어 돌렸다는 서술은 없다. 추론인지 실측인지 원문만으로 구분되지 않는다.
4. C-4 가 나열한 `RepairPrompt`·`Outcome.Body`·`Adoption`·`User`·`UserResponse` 다섯 타입의 실재와
   `data class` 여부 — 미대조. (`StyleRules.kt` 자체는 실재하고 인용 라인이 범위 안임은 §1.4 로 확인)
5. C-5 의 *"`headerComponentsByName` 과 `requestFieldConstraint` 에도 같은 필터링이 남아 있어"* — 미대조.

---

## 5. 미실행·실패 항목

### 5.1 codex 셸 명령 실패 2건

codex 가 실행한 36건 중 2건이 0 이 아닌 코드로 끝났다. **둘 다 codex 가 뒤이어 다른 명령으로
목적을 달성했고**(#19 실패 → #20 성공), 리뷰 자체는 정상 종료했다.

| 명령 | 종료 코드 | 잘린 명령 문자열(stderr 기록 그대로) |
|---|---|---|
| #19 | `1` | `/bin/zsh -lc ".venv/bin/python -c 'import importlib.util,pathlib; p=pathlib.Path(\".claude/sk...` |
| #27 | `2` | `/bin/zsh -lc "git diff --unified=50 7205d37..HEAD -- backend-kotlin/infrastructure/src/testFi...` |

명령 전문은 stderr·job 로그 양쪽이 **잘라서** 기록하므로 복원할 수 없다. 추측으로 메우지 않는다.

### 5.2 이 에이전트가 하지 않은 것

- codex 지적의 옳고 그름 판정, 심각도 재부여, 중복 병합, 표현 다듬기, 오탐 표시 — 전부 안 했다.
- codex 의 `scan()` 프로브 재현 — 안 했다(§4.4-1). 재현은 `migration-reviewer`·`privacy-gate`·리더의 몫이다.
- 커밋 — 없다. `00_progress.md` — 무접촉.
- `migration-reviewer`·`privacy-gate` 산출물 — 작성하지 않았다(리더 지시).

### 5.3 리뷰 누락 없음

⚠ codex 리뷰 누락 **해당 없음**. 스크립트 종료 코드 `0`, 대상 판정 `non-empty`, 출력 6,249 바이트,
재시도 0회. 이 회차는 리뷰 근거로 쓸 수 있다.

### 5.4 회차

이 어간(`03_workspaces-fixes`)의 **1회차**다. 같은 어간의 이전 codex 리뷰는 없다.
직전 게이트는 다른 어간(`03_workspaces`)이며 그 산출물은
`docs/migration/_workspace/reviews/03_workspaces_codex-reviewer.md` 로 별도 보존돼 있다(덮어쓰지 않았다).
