# Kotlin 마이그레이션 진행 상태

**기준 문서:** `docs/plans/2026-08-11-kotlin-react-migration.md`
**실행 모드:** 초기 실행 (2026-08-11 착수)
**갱신 규칙:** 에이전트는 자기가 담당한 행만 고치고 `마지막 갱신 주체`에 자기 이름을 적는다. 다른 에이전트의 행은 건드리지 않는다.

> `충족`은 `예`/`아니오` 둘 중 하나만 쓴다. `진행 중`·`대체로`는 쓰지 않는다 — 게이트는 이분법이어야 판정된다.
> 근거가 비어 있는 `예`는 `아니오`로 취급한다.

---

## 재개발 전환 (2026-08-12 2차) — 아래 역사 행 중 일부가 무효화됨

**사용자 결정 + 리더 판정.** 방향이 **전환(포팅)에서 재개발(rebuild)**로 바뀌었다. Python은 폐기 대상이다. 근거·상세는 계획 `docs/plans/2026-08-11-kotlin-react-migration.md`의 "2026-08-12 (2차)" 변경 이력. 이 파일의 아래 역사 행은 **고치지 않는다**(파일 규약) — 대신 여기서 무엇이 무효화됐는지 못 박는다.

**호환성 요구 소멸로 무효화된 행(역사 참고로만 남긴다 — 게이트로 쓰지 말 것):**

- **Phase 0 「Fernet JVM 호환 spike」 · 「결정 3 상세 — Fernet 직접 구현」 전체 · 필수조치 C(Fernet TTL·60초 토큰)** — 롤백이 없어 Fernet 자체가 불필요. **표준 AEAD 신규 구현**(계획 §4.3). crypto spike의 역방향·tamper **호환** 검증도 근거 소멸.
- **Phase 0 결정표 결정 3(Fernet 직접 구현)** → **무효.** 결정 2(보존 DB 없음)는 유효하되 그 함의가 "재암호화 창 불필요"에서 "**롤백·호환성 자체가 소멸**"로 확장됐다. 결정 1의 "Python 도구는 독립 oracle로 존치"는 "**한시 존치 후 이식/폐기**"로 바뀐다(계획 Phase 9 개정).
- **JWT·Argon2의 「양방향」·「역방향」·「기존 PHC 그대로 검증」 프레이밍** — Python이 Kotlin 산출물을 읽을 요구가 없다. crypto·jwt·argon2 parity의 `compat` 판정 모드 근거 소멸.
- **검증 게이트 표 「Crypto (Python ↔ Kotlin)」 → 「Kotlin 단독」**, **「Ops (cutover/rollback)」 → 「첫 배포·파일럿 관찰(롤백 없음)」**.
- **Phase 0 「종료 조건 문구 정정」의 '역방향/롤백 창' 읽기(37~45행)** — 절체 후 Python이 이어 쓸 요구가 없다(롤백 소멸). Phase 0 실제 종료 조건은 계획 §5 Phase 0(2차)·SKILL.md와 같다: **표준 AEAD·Argon2·JWT 구현 경로(round-trip·변조 거부·필수조치 A·B — migration-safety-gate I-7·I-8·I-9) + 문서 포팅 가능성**. "기존 암호문 읽기"·"역방향 이어쓰기"는 종료 조건 아님.
- **「기준 전환 재판정」 §의 `[호환]` 근거 행(아래)** — spike 행이 '예'로 남는 근거는 이제 **가능성 확인 + 필수조치 A·B(migration-safety-gate I-7·I-8·I-9)**이지 "롤백 창 값 동일성"이 아니다. 판정(예)은 유지, 근거만 재기술.

**무효화되지 '않는' 것(혼동 주의 — 순수 정확성 문제라 그대로 유효):**

- **필수조치 A(Argon2 재해시 전체 파라미터 동등성)·B(JWT clock skew 0 경계)** — 이 둘은 *호환*이 아니라 *정확성* 문제다. 재개발 Argon2·JWT에도 그대로 적용된다. 탐침 7건·경계 fixture 2건 회귀 유지.
- **필수조치 D(`encryption_scheme` additive)** — 컬럼은 남기되 의미가 "롤백 호환"에서 **"향후 키 회전용"**으로 바뀐다. 처음부터 `'fernet-v1'`이 아니라 새 AEAD scheme 값(예: `'aes-gcm-v1'`)으로 쓴다.
- **계약·보안 불변식(마스킹 선행·no-store·404 소유권·평문 미저장)**, **DB baseline/Flyway 구조**, **contract 3자 대조·CORS·오류 계약(C-1/C-2/C-3)** — 재개발 기준(요구사항·계약·React)에서 그대로 유효.

**신설 게이트 — 추출 완료(Python 폐기 선행 조건):** 재개발에는 Python 차분 그물이 없어, 코드에만 있던 판단(프롬프트 전문·스타일 상수·**치환 비문 실측 튜닝** `eae75c7`/`0894854`/`a4c9fd9`·골든셋 채점 기준)을 지우기 전에 명세·fixture로 옮겨야 한다. 목록·폐기 게이트는 **`03_rebuild-extraction-list.md`**. 이 게이트가 0으로 닫히기 전 `app/**`·`tests/**` 삭제 금지.

**품질 문제는 이 전환이 해결하지 않는다:** 골든셋 통과율(실수집 52.8%·전체 64.3%)·judge·장문 절벽은 언어 무관이며 프롬프트·긴 문서 전략의 문제다(`02_quality-baseline.md`). Phase 0 합격선 확정 + Phase 2 이후 프롬프트 작업으로 이월. **[3차 갱신]** 합격선 확정은 **완료**됐다(기제로 확정 — 위 Phase 0 표). 남은 이월분은 프롬프트·긴문서 작업이고, 그 성과를 판정하는 자리는 **Phase 5 종료 게이트**로 확정됐다(계획 §4.6). **52.8%는 "괜찮다"가 아니라 코퍼스 난이도 판정이며 KPI 0.90은 목표선으로 살아 있다.**

---

## Phase 0 — 범위·계약 동결

계획 문서 §5 Phase 0. 원문 종료 조건: "Kotlin이 **기존 암호문**을 안전하게 읽을 경로와 문서 포팅 가능성이 확인됨."

**종료 조건 문구 정정 (2026-08-12, 리더).** §9-2가 "보존할 DB 없음"으로 확정되면서 **기존 암호문이 존재하지 않는다.** 계획 §4.3은 "기존 DB 본문이 Fernet 토큰이라 호환을 증명 못 하면 기존 문서를 못 읽는다"를 전제로 쓰였고 그 전제가 사라졌다. 실제로 남은 요구는 **역방향**이다 — 계획 §5 Phase 7이 관찰 기간(1~2주) 동안 롤백을 준비하라고 요구하므로, 절체 **후** Kotlin이 쓴 데이터를 롤백한 Python이 읽고 **이어서 쓸 수 있어야** 한다.

따라서 이 Phase의 실질 종료 조건은 다음으로 읽는다:

> **롤백 창에서 Python이 Kotlin 산출물을 읽고 이어서 쓸 수 있는 경로와, 문서 포팅 가능성이 확인됨.**

> **[2026-08-12 2차 개정 무효화]** 위 '역방향/롤백 창' 종료 조건은 **폐기됐다** — 롤백을 포기해(상단 재개발 masthead) 절체 후 Python이 이어 쓸 요구가 없다. Phase 0 실제 종료 조건은 계획 §5 Phase 0(2차)·SKILL.md와 같다(표준 AEAD·Argon2·JWT 구현 경로 + 문서 포팅 가능성 — migration-safety-gate I-7·I-8·I-9). 위 두 문단(39~43행)은 **1차 개정 기록으로만** 남긴다.

~~계획 문서 §5 Phase 0 본문도 같은 취지로 갱신할 것을 제안한다(문서 갱신은 별건).~~ → 계획 §5 Phase 0은 2차 개정으로 갱신됨(이 정정과 정합).

> `실행 경로` 열의 어휘 정본은 `.claude/skills/kotlin-migration/SKILL.md` 의 「선언한 범위와 실제 도달을 대조한다」 절 → 「어디에 적용하는가」 → Phase 종료 판정 항목이다. 정의를 여기에 복제하지 않는다.

| 종료 조건 | 충족 | 실행 경로 | 근거 | 미해결 항목 | blocked-by | 마지막 갱신 주체 |
|---|---| --- |---|---|---|---|
| `contracts/easy-doc-v1.yaml` 작성 | 예 | `1회성:contracts/easy-doc-v1.yaml` | `contracts/easy-doc-v1.yaml` 작성 완료. 14 엔드포인트(제품 13 + `/health`)가 FastAPI 실제 노출 경로와 **차집합 양쪽 공집합**으로 일치. `openapi-spec-validator` → OK. 413·502/503·`detail` union·응답 헤더 5종·multipart 요청 본문·`status` enum·CORS·입력 상한 전부 수기 기입 | **U-1 해소 (2026-08-12)** — 리더 결정 + 사용자 승인으로 **개선 수용**(Python 동작을 재현하지 않는다). 계약 파일의 `x-cors.x-known-limitation` → `x-cors.x-unhandled-500-cors`("결정됨 — Python과 의도적으로 다름")로 대체, `components.responses.InternalError.description` 정정, v2 후보 `V2-2` **종결**, `x-changelog` 항목 추가. `info.version`은 1.0.0 유지. 근거 `00_contract-keeper_changelog.md` | - | contract-keeper (2026-08-12) |
| 응답·헤더·오류·인증·권한·입력 상한을 contract test로 고정 | 아니오 | `안 돎` | 목록·기준만 작성 (`00_contract-keeper_test-plan.md` — 엔드포인트별 세트 14 + 횡단 48종). **테스트 코드는 미구현**이며 실행도 하지 않았다 | 리더 지시로 Phase 0에서는 목록만 세웠다(Kotlin API가 Phase 3부터 생긴다). 추가로 **G-1: Python 기준선에도 없는 공백** — `POST /workspaces`·`PATCH /workspaces/{id}`의 캐시 헤더를 어떤 테스트도 단언하지 않는다(계약상 10곳 중 2곳). Python에 먼저 채울지 Phase 3에서 양쪽 동시에 넣을지 판단 필요.<br>**2026-08-12 OQ-1 판정 반영** — 사적 헤더가 **모든 응답**에 붙게 되면서 테스트 계획의 헤더 축이 바뀌었다: ① 오류 응답의 **부정** 단언이 **긍정**으로 뒤집힘(Kotlin 한정 — Python 부정 단언은 그대로 옳다), ② 열거 10곳 개별 단언은 **하한선으로 유지**(삭제 금지), ③ 신규 — DELETE 204·`/health`·프리플라이트·**헤더 중복 부착 부재**(H-2), ④ 신규 — 컨테이너 레벨 응답 도달 여부(**H-1**). 체크 항목 정본 `.claude/skills/api-contract-freeze` §5.1·§5.2.<br>**2026-08-12 H-1 실측 반영** — ④가 **실측 완료**로 닫혔다(X-D2c). 필터 1층으로는 파싱 단계 거절 7종에 못 닿아 **Tomcat Engine 밸브를 더한 2층**이 됐고, 테스트 축에 X-D2d(밸브 음성 대조)·X-D2e(Tomcat 결합 인지)가 추가됐다. 부수로 **오류 본문 사각지대**가 드러나 X-C7·X-C8(`sendError`→`/error` 본문이 `{"detail":…}`가 아니다)·X-C9(컨테이너 응답 본문 **미측정**)가 신설됐다. 계약 정본 `x-error-body-universality`, 절차 정본 스킬 §5.3 | 리더(G-1 시점 판단) → Phase 3 kotlin-implementer | contract-keeper (2026-08-12) |
| FastAPI OpenAPI·계약 파일·React 타입 3자 대조 | 예 | `1회성:docs/migration/_workspace/00_contract-keeper_three-way-diff.md` | `00_contract-keeper_three-way-diff.md`. 불일치 **21건 + 계획-코드 3건 + 미결 1건**. ①이 런타임과 **다른 값**을 말하는 곳 3건(422의 `input`/`ctx`, `loc` 타입, export의 `application/json`), 누락 6건, 느슨함 5건, 의도된 차이 6건. `DELETE /workspaces/{id}`가 React에 없는 것은 **의도된 차이**로 기록 | **없음** — 유일하게 남았던 U-1(§7)이 2026-08-12 리더 결정으로 종결(위 행) | - | contract-keeper (2026-08-12) |
| 대상 DB와 보존할 파일럿 데이터 유무 확인 | 예 | `결정:2026-08-12` | 사용자 확인 (2026-08-12): **보존할 운영/파일럿 DB 없음. 빈 DB로 시작한다.** 이로써 계획 §7이 "변동 폭이 가장 크다"고 지목한 두 변수 중 하나(기존 암호문 호환)가 소멸했다 | - | - | leader |
| 범위 승인: 런타임만 Kotlin화 vs 오프라인 도구까지 Python 제거 | 예 | `결정:2026-08-12` | 사용자 승인 (2026-08-12): **제품 런타임만 Kotlin화**(§9-1). Phase 9(오프라인 도구)는 착수하지 않는다. 골든셋 평가·벤치마크·수집·파일럿 리포트 도구는 Python으로 남아 **독립 검증 oracle** 역할을 유지한다 | - | - | leader |
| Fernet JVM 호환 spike | 예 | `1회성:docs/migration/_workspace/00_privacy-gate_crypto-spike.md` | `00_privacy-gate_crypto-spike.md` §4. `com.macasaet.fernet:fernet-java8:1.5.0` / Temurin 21.0.4 / Gradle 9.1.0. **정방향 8/8**(한글·빈 값·긴 값·제어문자·변조·다른 키·garbage), **역방향 5/5**(`verify-crypto` 통과, `crypto-verify.verified.json` status: pass), **tamper 5/5**(version·timestamp·IV·ciphertext·HMAC 각 1비트 변조 전건 `StorageError` 거부, 무변조 대조군 정상). 즉흥 암호 구현은 하지 않았다 | **(1) 조달 유보** — 이 라이브러리는 최신 1.5.0이 **2020-09-26** 릴리스로 약 5년 11개월 무릴리스(`maven-metadata.xml`·jar `Last-Modified` 실측). §4.3-2의 "유지보수 상태"를 만족한다고 보기 어렵다. 채택(코드 전량 검토 조건) vs JDK primitive 자체 조립 중 선택 필요 = **§9 결정 3**. **(2) 필수 조치 C** — 기본 Validator는 TTL 60초라 **유효 토큰 5건 전부 `TokenExpiredException`으로 실패**한다. 그대로 쓰면 업로드 60초 뒤 모든 문서가 안 읽힌다. Phase 4에서 TTL·maxClockSkew 명시적 무력화 + 60초 경과 토큰으로 회귀 테스트 필요. **(3)** AES-GCM(선택지 2)은 미검증 — 권고하지 않아 수행하지 않았다 | 리더(§9 결정 3) | privacy-gate |
| Argon2 PHC 검증 spike | 예 | `1회성:docs/migration/_workspace/00_privacy-gate_crypto-spike.md` | `00_privacy-gate_crypto-spike.md` §2. `spring-security-crypto:6.4.2`(`Argon2PasswordEncoder(16,32,4,65536,3)`) + `bcprov-jdk18on:1.78.1`. 파라미터는 `app/services/auth.py:59`에서 직접 읽었고 salt 16B·hash 32B는 fixture PHC base64 길이에서 역산. **정방향 13/13**(한글·NFD 불일치 거부·legacy `m=8192,t=2,p=2` 검증·변조·비PHC 문자열이 예외 아닌 `false`), **역방향 4/4**(`app/services/auth.py::_HASHER`가 Kotlin 산출 PHC를 전건 검증, `needs_rehash=false`, 틀린 비밀번호 거부, prefix `$argon2id$v=19$m=65536,t=3,p=4$` 동일) | **필수 조치 A** — 재해시 판정이 갈린다. Python `check_needs_rehash`는 **전체 파라미터 동등성**, Spring `upgradeEncoding`은 **memory·iterations의 "미만"만** 본다. 자체 탐침 7건 중 5건 불일치(parallelism만 다름·더 강한 memory·더 강한 iterations·hash_len·salt_len에서 Python `true` / Kotlin `false`). **공식 fixture 14건으로는 드러나지 않는다.** 지금은 무해하나(살아 있는 해시가 전부 현재 파라미터) 파라미터를 바꾸는 날 **이관이 조용히 멈춘다**. Phase 3에서 전체 동등성 판정 함수로 교체 + 탐침 7건 회귀 고정 필요 | Phase 3 kotlin-implementer | privacy-gate |
| JWT 양방향 호환 spike | 예 | `1회성:docs/migration/_workspace/00_privacy-gate_crypto-spike.md` | `00_privacy-gate_crypto-spike.md` §3. **정방향 17/17을 두 라이브러리에서 각각**(`nimbus-jose-jwt:9.41.2`, `auth0 java-jwt:4.4.0`) — alg=none·RS256 헤더 혼동·서명 위조·페이로드 변조·`sub`/`exp`/`typ` 누락·`typ` 불일치·비UUID sub·32B 시크릿 통과·31B `configuration_error` 전건 일치. **`exp` 경계 질문 해소**: skew 0에서 두 라이브러리 모두 `exp <= now`를 만료로 봐 PyJWT와 같다(`exp-2…exp+2` 훑어 예외 타입까지 확인 — 결과만 보고 오독하지 않도록 메커니즘 대조). **역방향 4/4**(`verify-jwt` 통과, subject 2종 × 유효/만료, `jwt-verify.verified.json` status: pass) | **필수 조치 B** — 경계가 맞은 것은 skew를 0으로 **명시했기 때문**이다. `DefaultJWTClaimsVerifier` 기본 `maxClockSkew`는 **60초**라 기본값으로 두면 만료 토큰이 `+59s`까지 ACCEPT돼 `jwt-exp-boundary-exact` fixture에서 실패한다. Spring Security `NimbusJwtDecoder`의 `JwtTimestampValidator`도 기본 60초라 같은 함정. auth0는 기본 leeway 0이라 무해. Phase 3에서 skew 0 명시 + 경계 fixture 2건 회귀 고정 필요 | Phase 3 kotlin-implementer | privacy-gate |
| DOCX/PDF/HWPX 라이브러리 spike | 예 | `1회성:docs/migration/_workspace/00_kotlin-implementer_doc-spike.md` | `00_kotlin-implementer_doc-spike.md`. **§4.5가 경고한 DOCX 위험은 해소됨** — POI를 usermodel이 아니라 OOXML DOM 순회로 쓰면 Python `_docx_blocks`와 **블록 리스트가 완전 일치**한다. 기존 fixture 6개 + 합성 fixture 4개 전부 Python 산출값 일치(거부 메시지 문자열까지). 동등성 6항목 전부 확인(표 제자리·텍스트박스·SDT·`w:ins`/`w:delText`·`mc:Fallback`·`a:t`/`m:t`·linked 머리글). HWPX: DTD/UTF-16 DTD/XXE 차단, 1GiB zip bomb을 힙 256MB에서 거부(힙 증가 0MB), 자체 round-trip·mimetype STORED 첫 항목·생성 결정성 PASS, Python↔Kotlin 패키지 교차 읽기 PASS. 검증 조합: Java 21.0.4 / Gradle 9.1.0 / Kotlin 2.2.0 / POI 5.4.1 / PDFBox 3.0.5 / commons-compress 1.27.1. `uv run pytest tests/ingest -q` 57 passed로 Python 기준선 무손상 | **가능성은 확인됐고 남은 것은 Phase 4 결정·구현이다.** (1) POI 산출 DOCX에 `styles.xml`/`theme` 부재 — Heading 1 서식이 사라짐, 템플릿 정책 결정 필요 (2) zip 컨테이너 바이트는 Python과 동일해질 수 없음(실측 `java=434B` vs `python=348B`) → parity fixture를 바이트 해시로 잡으면 안 됨, `parity-verifier` 합의 필요 (3) StAX DTD 판정을 예외 **메시지**로 하면 로케일 의존 — `DTD` 이벤트 직접 처리로 바꿔야 함 (4) 위조 크기 zip의 사용자 메시지가 Python과 갈림(`손상되었습니다` vs `너무 큽니다`) (5) **미검증**: 실제 한컴/Word 저장 파일, 실제 공공기관 PDF의 pypdf↔PDFBox 동등성, `MAX_EXTRACTED_CHARS`·10MB 경계, 암호 PDF/DOCX 실파일, Spring Boot BOM 적용 후 버전 재정렬 | - | kotlin-implementer |
| 리뷰 게이트 Critical 0건<br>→ **범위를 좁혀 판정**: "Phase 2 착수를 막는 Critical 0건" | 아니오 | `1회성:docs/migration/_workspace/reviews/02_criteria-pivot_cross.md` | **[criteria-pivot 2026-08-12 재판정] 예→아니오.** 3회차 리뷰(`02_criteria-pivot_cross.md` §6.2)가 이 행이 2회차 근거로 닫힌 **뒤** Phase 2 착수 차단 ② 5건을 새로 냈다 — X-05·X-06(privacy 레인)·X-09~X-12(parity 레인)·X-08(본 인벤토리 레인). 하나라도 열려 있으면 "Phase 2 착수를 막는 Critical 0건"은 거짓이다. blocked-by는 그 세 레인. 상세는 아래 「기준 전환 재판정」. 이하 근거는 2회차 시점의 역사 기록이다 — 2회차(Phase 1 골격) 실행 완료 — `reviews/01_skeleton_{codex-reviewer,migration-reviewer,cross}.md` 3건 (정본은 `_cross.md`). 1회차 수정(pre-phase0 X-1·X-2)이 2회차 리뷰를 실제로 받았고, 2회차가 새로 지적한 차단 중 **Phase 2 작업에 닿는 것은 parity CI(2회차 X-2) 하나**였으며 `01_kotlin-implementer_parity-ci-fix.md`로 닫혔다. 함께 닫힌 것: C-1·C-2·C-3(`01_kotlin-implementer_error-cors-fix.md`), T-5=P1-1 판정(`01_kotlin-implementer_boot41-upgrade.md`), F-8 확인(Phase 0 §9-2 "보존할 DB 없음"이 이미 답). **판정 근거는 교차 종합 §7.1·§8** — Phase 2는 순수 도메인 로직 포팅이라 HTTP·DB·CORS 경계를 쓰지 않으므로 심각도가 높다는 이유만으로 착수를 막는 것은 과잉이라는 권고를 채택했다 | **나머지 차단 지적은 사라지지 않는다** — 마감이 명시된 미결 원장(§Phase 1 종료 판정)으로 이월했고 각 Phase 착수 게이트에서 다시 센다. 원 판정("Critical 0건")을 그대로 쓴 것이 아니라 **범위를 좁혀** 닫은 것임을 명시한다. 상충-2(심각도 척도)는 1회차에 리더가 판정해 반영 완료 — 사건뿐 아니라 **탐지 장치의 무력화도 Critical**로 세되, 심각도와 착수 차단은 별개 축이고 마감은 그 게이트의 첫 실사용 시점이다 | - | leader (2026-08-12) |
| 전역 요구사항 인벤토리 1차본 작성·승인 (계획 §5 Phase 0 · §1.1) | 아니오 | `1회성:docs/migration/_workspace/00_requirements-inventory.md` | **[criteria-pivot 신설 2026-08-12]** 커밋 49ea2eb가 Phase 0 종료 조건에 "요구사항 인벤토리 1차본 승인"을 넣었으나 산출물이 없어 행조차 세우지 못했다(리뷰 A-1/X-08 — "미충족 0"이 항목 0개에서 참이 되던 구멍). **1차본 작성 완료** → `00_requirements-inventory.md`(항목 39 + 확인방법 미확정 4건 명시). 게이트가 이제 실제 항목을 가리킨다 | 1차본은 존재하나 **승인 미완**(충족=예는 승인까지 요구) | 리더·사용자 승인 | criteria-pivot 재판정 (2026-08-12) |
| 품질 합격선 **기제** 확정·승인 (계획 §5 Phase 0 · §4.6 게이트2·5)<br>*직전 행 제목: "합격선 **수치** 확정·승인" — 확정된 것이 수치가 아니므로 제목을 고쳤다* | 예 | `ci:quality` · `ci:llm-lane(조건:.github/llm-lane-paths.txt)` · `결정:2026-08-12` | **[2026-08-12 3차 갱신, 아니오→예]** 요지는 **합격선을 절대 수치가 아니라 기제로 확정**했다는 것이다 — 수치를 못 정해 우회한 것이 아니라, 고정할 수 없는 것(채점 모델)과 코퍼스 난이도에 좌우되는 것(통과율)을 차단축에서 분리한 결과다. **사용자 결정 4건**: ① 하한선의 출처 = **이 저장소의 직전 기록 측정치**(상대 — Python이 아니다) ② 실수집 52.8%는 **코퍼스 난이도**로 판정 — 목표를 낮춘 것이 아니며 **KPI 0.90은 목표선으로 존속**한다 ③ **judge에 차단 권한을 주지 않는다** ④ **통과하는 실행도 수치를 남긴다**. **두 차단축** — 필수 정보 보존 **절대**(누락 0건, 결정적, LLM-as-judge 미사용) / 규칙 통과율 **상대**(직전 기록 대비 하락 0, 코퍼스·판정 기준 지문이 다르면 비교 불가로 차단). **구현·검증·커밋됨** (`c43cae5`) — `tests/golden/baseline.py`(지문·상대 판정)·`tests/golden/report.py`(통과 실행도 기록)·`app/easyread/goldenset.py`의 `find_fact_losses`(절대 팩트축). **리더 직접 실행 실측**: `pytest` **916 passed / 68 skipped**, `pytest tests/golden` **63 passed**, mypy **122 files**, 지문·기록·하락 재현 **14건** 통과. 인벤토리 §9-A의 네 공백(코퍼스 고정 → 지문 2축 / 통과 시 기록 → `report.py` / 채점자 고정 → 결정 ③으로 **소멸** / 축별 허용치 → 팩트축 절대 0·스타일축 상대화) **전부 처분**. 계획 반영: §4.6 게이트2·5 재작성, 게이트5 단서 미결 **종결**, codex Q6 **확정**(담당 = **Phase 5 종료 게이트**, Phase 7 진입 전 필수), §6 검증 매트릭스 Quality 행 교체 | **ⓐ `tests/golden/baseline.json` 미기록** — LLM 키가 없어 실측할 수 없었고 **수치를 지어내지 않았다.** 지금은 "기준선 없음 → 차단, 기록 필요"로 떨어지며 **이것이 설계된 정상 상태**다. 첫 기록: `GOLDEN_RECORD_BASELINE=1 GOLDEN_PROVIDER=anthropic uv run pytest tests/golden -m llm`. **ⓑ 절대 팩트 게이트가 첫 실행에서 통과하지 못할 공산** — 마지막 저장 실행(2026-08-08)의 팩트 잔존 90.1%·14문서 누락이고 실손실과 `accept` 목록 공백의 비율은 **정적 판정 불가**. 게이트를 낮출 사유가 아니라 프롬프트 문제이며 첫 `-m llm` 실행이 삼분류 근거를 만든다. **ⓒ 날조(fidelity=1)에 결정적 차단 없음 — 리더 판정 대기.** 역방향 축(출력의 숫자·금액·날짜가 원문에 존재하는가)은 합의 범위 밖의 새 차단 게이트라 짓지 않았다. **ⓓ 독립 리뷰 미실시.** **적용 범위의 한계(오독 금지)**: 게이트 로직·배선은 매 CI 기본 스위트에서 돌지만(재현 40건, LLM 호출 없음) **실제 변환문에 대한 적용은 `-m llm` 레인 전용**이다 — 변환 없이는 검사할 대상이 없다. "매 커밋 차단"이 아니다 | ⓐ LLM 키 / ⓒ 리더 판정 / ⓓ 리뷰 레인 | leader (2026-08-12 3차) |

### Phase 0에서 사용자 승인이 필요한 다섯 결정 (계획 §9)

| # | 결정 | 상태 |
|---|---|---|
| 1 | 목표가 "제품 런타임 Kotlin화"인지 "오프라인 도구 포함 Python 완전 제거"인지 | **승인 (2026-08-12)** — 제품 런타임만 Kotlin화. Phase 9 미착수, Python 도구는 독립 oracle로 존치 |
| 2 | 파일럿/보존 대상 DB가 있는지, 유지보수 창을 쓸 수 있는지 | **승인 (2026-08-12)** — 보존 대상 DB 없음. 빈 DB로 시작 |
| 3 | Fernet JVM 호환 구현 승인 여부와 실패 시 재암호화 방식 | **승인 (2026-08-12)** — **JDK primitive로 직접 구현**(~150줄). 아래 상세 |
| 4 | PostgreSQL 작업 큐로 전환하며 Redis를 최종 제거할지 | 미승인 — Phase 5 착수 전까지 결정 |
| 5 | 시각 UI 개편을 이번 전환과 분리하는 원칙 승인 | 미승인 — 계획 §4.1이 이미 분리를 전제하므로 기본값대로 진행하되, Phase 6 착수 전 확인 |

#### 결정 3 상세 — Fernet 직접 구현

**채택하지 않은 것과 이유** (`00_privacy-gate_crypto-spike.md` + JVM Fernet 생태계 조사):

- `com.macasaet.fernet:fernet-java8:1.5.0` — 정방향 8/8·역방향 5/5·tamper 5/5로 **기능은 통과했으나**, 최신 릴리스가 2020-09-26으로 약 5년 11개월 무릴리스라 계획 §4.3-2의 "유지보수 상태"를 만족한다고 보기 어렵다. 기본 Validator TTL 60초를 무력화해야 하는데 이는 라이브러리 설계와 반대로 쓰는 것이고, 나중에 누군가 TTL을 켜면 1초 경계 불일치가 되살아난다
- `dev.ercan:fernet:1.0.0` — Python `cryptography`와 **시맨틱이 정확히 일치**(no-TTL 경로에 타임스탬프 검사 없음, 경계·60초 skew 규칙 동일, `MessageDigest.isEqual` 사용)하나 2026-07-26 릴리스로 2주 됐고 star 0·저자 1명·보안 정책 없음·fuzzing 없음. 시민 문서를 다루는 시스템에서 검증되지 않은 단독 저자 암호 라이브러리는 다른 종류의 위험이다
- `io.github.atkawa7:fernet` — **채택 금지.** HMAC 검증에 `Arrays.equals`를 써 첫 불일치에 단락되는 타이밍 사이드채널이 있고, 바로 위 주석은 "using a constant-time comparison function"이라고 적혀 있다. `<scm>` URL이 404라 신고 경로도 없다
- `core-dragonby7k/fernet-java8` — **무단 복제본**(fork 아님, 원본 CI 배지 유지). 검색으로 흘러들지 않도록 기록
- **Kotlin-native 구현은 존재하지 않는다** — 이름이 그런 저장소 하나는 전체 구현이 `TODO("Not implemented yet.")`이고 키 생성기가 urlsafe가 아닌 표준 Base64를 써 스펙에도 어긋난다

**§4.3-4 "즉흥 구현 금지" 해석**: 그 조항은 *호환 실패 시 대안으로 새 암호 방식을 급조하는 것*을 막는다. 여기서는 **공개 스펙(fernet/spec)이 있고, 교차 런타임 fixture로 검증하며, Python oracle이 §9-1로 존치**한다. 즉 "검증 없는 자작"이 아니라 "스펙 구현 + 기계 검증"이므로 조항 위반이 아니다. 다만 이 해석은 리뷰 게이트에서 다시 도전받아야 하며, 구현 시 **JVM Fernet 생태계 전체가 CVE 0건**이라는 사실이 면죄부가 아님을 유의한다.

**구현 요건** (Phase 4 착수 전 확정):
- 토큰 형식: version(0x80)·timestamp(8B BE)·IV(16B)·ciphertext·HMAC-SHA256(32B), Base64URL
- **HMAC 검증을 복호화보다 먼저**, `MessageDigest.isEqual`로 상수 시간 비교
- **TTL 검사 없음** — Python이 `ttl=None`으로 쓰는 경로와 같아야 한다. 이것이 `fernet-java8`에서 실패했던 바로 그 지점이다
- 교차 런타임 fixture(`parity/fixtures/crypto`)로 정방향·역방향·tamper 전건 검증. **60초 경과 토큰을 반드시 포함** — 갓 만든 토큰으로만 테스트하면 TTL 결함이 통과해 버린다

#### 결정 4·5는 아직 열려 있다

Phase 1 착수를 막지는 않는다. 4는 Phase 5(작업 큐)에서, 5는 Phase 6(React 통합)에서 처음 구속력을 갖는다.

---

## Phase 0 종료 판정 — **조건부 종료** (2026-08-12, 리더 + 사용자 승인)

종료 조건 10행 중 **8행 충족, 2행 미충족**. 오케스트레이터 규칙은 "이전 Phase의 종료 조건 행이 전부 `충족 = 예`가 아니면 다음 Phase 에이전트를 호출하지 않는다"이고, 예외는 "미충족인데 진행해야 할 사정이 있으면 사용자 승인을 받고, 승인 사실과 미충족 행을 남긴다"이다. **사용자 승인을 받아 Phase 1에 착수한다.**

### 미충족으로 남기는 2행과 닫는 시점

| 미충족 행 | 왜 지금 못 닫는가 | 언제 닫는가 |
|---|---|---|
| 응답·헤더·오류·인증·권한·입력 상한을 contract test로 고정 | 계약 테스트는 HTTP 경계에서 도는데 **Kotlin API가 아직 없다.** 지금 Python에만 채우면 절반이고, 그 절반이 "계약이 검증됐다"는 착각을 준다 | **Phase 3** — `/auth/*`·`/workspaces/*`가 Kotlin에 생기는 시점. 목록과 기준은 `00_contract-keeper_test-plan.md`에 이미 있다 |
| 리뷰 게이트 Critical 0건 | 지적된 Critical 2건은 코드로 닫았으나 **그 수정 자체가 리뷰를 받지 않았다.** 이번 세션에서 "고쳤다고 보고한 직후 codex가 그 수정의 결함을 잡은" 일이 세 번 있었다 | **Phase 1 종료 시** — Kotlin 골격이라는 실제 코드가 생긴 뒤 3단계 게이트를 돌린다. 문서·스크립트만 있는 지금보다 그때 리뷰가 값이 크다 → **2026-08-12 닫힘.** 예정대로 Phase 1 종료 시점에 2회차 게이트를 돌렸고, "Phase 2 착수를 막는 Critical 0건"으로 **범위를 좁혀** 판정했다(위 표) |

**2026-08-12 갱신.** 위 2행 중 아래 행이 닫혀 Phase 0 종료 조건은 **10행 중 9행 충족 / 1행 미충족**이 됐다.
남은 1행(contract test)의 마감은 그대로 Phase 3다. 위 "8행 충족" 문장은 2026-08-12 판정 시점의 기록이므로 고치지 않는다.

### 기준 전환 재판정 (criteria-pivot, 2026-08-12)

커밋 49ea2eb가 판정 기준을 "Python 출력 일치"에서 "요구사항 충족"으로 바꾸면서 **이미 닫힌 Phase의 종료 조건을 소급 개서**했다. 리뷰 02_criteria-pivot A-1/X-08의 지적에 따라 닫힌 판정 전부를 새 기준으로 다시 봤다.

**Phase 0 표 변동 3건**
- **신설 2행(둘 다 아니오)** — 커밋이 Phase 0 종료 조건에 넣은 "요구사항 인벤토리 1차본 승인"·"품질 합격선 승인"을 행으로 세웠다. 인벤토리는 1차본(`00_requirements-inventory.md`)을 만들어 게이트가 실제 항목을 가리키게 했으나 **승인 미완**이고, 품질 합격선은 **수치 자체가 없다**.
- **1행 뒤집음(예→아니오)** — "리뷰 게이트 Critical 0건". 2회차(Phase 1 골격) 리뷰로 닫힌 뒤 3회차(02_criteria-pivot)가 Phase 2 착수 차단 ② 5건을 새로 냈다(X-05·X-06·X-08·X-09~X-12). blocked-by: privacy 레인(X-05·X-06)·parity 레인(X-09~X-12)·본 인벤토리 레인(X-08). X-08의 계측기 공백은 이 1차본으로 닫혔고, 남은 것은 승인 판정과 네 건(병렬 레인 진행 중)이다.

**재판정 후 Phase 0 집계: 12행 중 8행 충족 / 4행 미충족.** (직전 기록 "10행 중 9행"은 기준 전환 전 판정이라 위 문장들과 함께 남긴다 — 고치지 않는다.)

> **[2026-08-12 3차 갱신]** 위 4행 중 **「품질 합격선 확정·승인」이 닫혔다**(아니오→예 — 위 Phase 0 표). 합격선을 **절대 수치가 아니라 기제로** 확정하고 계측기를 구현·검증·커밋했다(`c43cae5`). **현재 집계: 12행 중 9행 충족 / 3행 미충족**(남은 3행 = contract test 고정 / 리뷰 게이트 Critical 0건 / 인벤토리 1차본 승인). 위 "8행 충족" 문장은 판정 시점의 기록이므로 고치지 않는다. 이 행에 붙은 미해결 4건(ⓐ `baseline.json` 미기록 · ⓑ 첫 실행 미통과 공산 · ⓒ 날조 차단 부재 리더 판정 대기 · ⓓ 독립 리뷰 미실시)은 **행을 닫은 것과 별개로 살아 있다** — 행이 판정하는 것은 "합격선이 확정·승인됐는가"이지 "골든 품질이 합격했는가"가 아니다. 후자를 판정하는 자리는 **Phase 5 종료 게이트**로 확정됐다(계획 §4.6 codex Q6).

**뒤집지 않은 행 — 새 기준에서도 예가 유지되는 근거(‘Python과 같다’ 근거는 재기술)**
- **`[호환]` Fernet·JWT·Argon2 spike + DB baseline(필수조치 D)** — 계획 변경 이력·CLAUDE.md가 지정한 **기준 전환 예외**다. **[2026-08-12 2차 정정]** 아래 '롤백 창 값 동일성' 근거는 **폐기**됐다(상단 재개발 masthead) — spike '예'는 유지하되 근거는 **가능성 확인 + 필수조치 A·B·D(migration-safety-gate I-7·I-8·I-9)**다. 이하 문장은 1차 재판정 기록이다. 롤백 창에서 값이 같아야 하는 것 자체가 요구사항이므로 "Python과 같다"가 정당한 근거다. 예 유지. (단 spike는 **가능성** 판정이고 실제 구현·필수조치 A·B·C는 Phase 3·4 미결로 이미 원장에 있다.)
- **`[계약]` contract yaml·3자 대조·리뷰 차단 C-1/C-2/C-3·/health** — 계약은 요구사항이 요구하는 인터페이스다. Python(FastAPI)에 맞춘 부분은 계약 요구이고, 의도적으로 다르게 간 U-1은 "개선 수용"으로 기록됐다 — Python을 oracle로 삼지 않는다. 예 유지.
- **DOCX/PDF/HWPX spike** — 근거가 "Python `_docx_blocks` 완전 일치"라 **근거를 다시 댄다**: 요구사항(인벤토리 DOC-01, 필수 정보 누락 없는 추출)은 더 강한 결과(블록 동일성)로부터 **a fortiori** 충족된다. 동일성은 향후 합격 바가 아니며 갈림은 Difference 게이트가 분류한다(계획 §4.5 — 미결 원장의 바이트 해시 금지·2.3.21 재검증과 정합). 포팅 **가능성** 판정으로서 예 유지.
- **구조·기동 행(Gradle 멀티모듈·toolchain·설정 바인딩·Dockerfile·Testcontainers·빈DB/스냅샷 기동)** — Python 출력 품질과 무관한 구조·기동 요구다. 기준 전환의 영향을 받지 않는다. 예 유지.

**Phase 1 전면 재검토 — 뒤집음 0.** Phase 1 종료 조건 행은 전부 구조·기동·호환성·계약 근거이고 "Python 출력 품질 일치"로 닫힌 행이 없다. 이미 아니오인 2행(GitHub Actions 미검증·필수조치 E fixture 부재)은 새 기준에서도 아니오다. **Phase 2 표는 전부 미착수(아니오)라 뒤집을 닫힌 판정이 없다.**

**Phase 2 착수 함의:** X-08 계측기 공백은 닫혔으나 ① 인벤토리 1차본 **승인**, ② 품질 합격선 **수치**, ③ 나머지 차단 4건(X-05·X-06·X-09~X-12, 병렬 레인)이 남는다. Phase 2 착수 가부는 이 인벤토리 레인 단독으로 판정하지 않는다 — 리더 몫이다.
> **[2026-08-12 3차 갱신]** ②는 닫혔다 — 다만 확정된 것은 **수치가 아니라 기제**다(위 표 행). 남는 것은 ①과 ③이다.

### 착수 전 판단이 필요했으나 Phase 1을 막지 않는 항목

- ~~**U-1** — 미처리 500 응답의 CORS 헤더를 Kotlin에서 재현할지 개선할지~~ → **2026-08-12 결정됨: 개선 수용**(리더 판정 + 사용자 승인). 착수 전 전제였던 "React가 그 동작(`status = 0`)에 의존한다"는 **실측으로 무너졌다** — `grep -rn "NETWORK_ERROR_STATUS|status === 0|\.status" frontend/src` 결과 `status = 0` 경로에 의존하는 화면 분기가 없고, 모든 화면이 `caught instanceof ApiError ? caught.message : <폴백>` 한 모양이다. 상태로 갈리는 자리는 `client.ts`의 401 하나뿐이다. 바뀌는 것은 사용자에게 보이는 **문구뿐**("서버에 연결하지 못했습니다…" → "서버 오류가 발생했습니다"). 되돌리려면 `CorsFilter`를 감싸 **일부러 나쁜 동작을 만드는 코드**를 영구히 남겨야 한다. 근거 `00_contract-keeper_changelog.md`, 비용 비교 `01_kotlin-implementer_error-cors-fix.md` §5. **범위**: 달라지는 것은 CORS 응답 헤더의 유무뿐이며 상태 코드(500)·본문(`{"detail": "서버 오류가 발생했습니다"}`)이 갈리면 그것은 의도된 차이가 아니라 계약 위반이다
- **G-1** — `POST /workspaces`·`PATCH /workspaces/{id}`의 캐시 헤더를 **Python 기준선에서도 어떤 테스트가 단언하지 않는다.** 계약상 10곳 중 2곳이 회귀 방지 없이 구현에만 존재한다. Phase 3에서 Kotlin·Python 양쪽에 동시에 넣는다
- **DOCX 내보내기 템플릿** — POI 산출물에 `styles.xml`/`theme`가 없어 Heading 1 서식이 사라진다. 템플릿을 저장소에 동봉할지 **Phase 4 착수 전** 결정
- **내보내기 parity 비교 기준** — zip 컨테이너 바이트는 Python과 동일해질 수 없다(실측 java 434B vs python 348B). 바이트 해시가 아니라 정규화 텍스트로 비교하도록 `parity-verifier`와 합의 필요. **Phase 4 착수 전**

### Phase 1로 넘기는 필수 조치 (spike에서 확정된 구현 지침)

| # | 내용 | 마감 |
|---|---|---|
| A | Argon2 재해시 판정을 **전체 파라미터 동등성**으로 구현. Spring `upgradeEncoding`은 memory·iterations의 "미만"만 봐 Python `check_needs_rehash`와 갈린다. 탐침 7건을 회귀로 고정 | Phase 3 |
| B | JWT 검증에 **clock skew 0을 명시**. `DefaultJWTClaimsVerifier`·`NimbusJwtDecoder` 기본값이 60초라 만료 토큰이 +59초까지 통과한다. 경계 fixture 2건 회귀 고정 | Phase 3 |
| C | Fernet 구현에 **TTL 검사를 넣지 않는다**. Python이 `ttl=None`으로 쓰는 경로와 같아야 한다. **60초 경과 토큰**을 fixture에 반드시 포함 | Phase 4 |
| D | `encryption_scheme` 컬럼을 **Phase 1 첫 Flyway에 additive로** 추가(기본값 `'fernet-v1'`). 관찰 기간엔 `fernet-v1` 고정, AEAD 전환은 Phase 8 이후 단일 런타임 상태에서 별건 | Phase 1 |
| E | parity 게이트의 마지막 구멍 — **Kotlin 테스트 하네스가 `parity/actual/{도메인}/*.json`을 쓰도록 CI에 배선.** 지금은 "산출물을 Kotlin이 만들었다"는 보장이 없다(fixture가 키·시크릿을 공개하므로 Python으로도 같은 파일을 만들 수 있다) | Phase 1 |

---

## Phase 1 — Kotlin 골격과 CI

계획 문서 §5 Phase 1. 원문 종료 조건: "**빈 DB와 기존 schema snapshot 양쪽에서 Kotlin 앱이 기동되고 `/health`가 응답함.**"

작업 항목 6개를 종료 조건 행으로 쪼갰다. 상세 근거·명령·출력은 `01_kotlin-implementer_skeleton.md`.

> `실행 경로` 열의 어휘 정본은 위 Phase 0 표의 포인터를 따른다.

| 종료 조건 | 충족 | 실행 경로 | 근거 | 미해결 항목 | blocked-by | 마지막 갱신 주체 |
|---|---| --- |---|---|---|---|
| `backend-kotlin` Gradle 멀티모듈 생성 (§3.2의 5개 모듈, 의존 방향) | 예 | `ci:kotlin` | `core`/`application`/`infrastructure`/`api`/`worker` 생성. `api`·`worker` 는 `infrastructure` 를 **`runtimeOnly`** 로만 의존해 JDBC·(Phase 5) LLM SDK 타입이 컴파일 시점에 보이지 않는다. `application` 은 `infrastructure` 를 의존하지 않는다. `api`↔`worker` 상호 의존 없음. **`core` 의 Spring·DB 비의존을 `CoreModuleBoundaryTest` 가 실행으로 확인**(7개 클래스 부재: `ApplicationContext`·`SpringApplication`·`JdbcClient`·`Flyway`·`org.postgresql.Driver`·Jackson 2/3 `ObjectMapper`) | `application` 본 소스는 비어 있다(경계만 세움, 유스케이스는 Phase 3~5). 계약은 `application/README.md` | - | kotlin-implementer |
| toolchain·dependency locking·version catalog·ktlint/detekt·테스트 설정 | 예 | `ci:kotlin` | Java 21 toolchain(`jvmToolchain(21)`), `allWarningsAsErrors=true`. 락파일 6개 커밋(모듈 5 + settings, 792줄) — `clean build` 가 락 갱신 없이 성공. catalog 가 유일한 버전 선언 지점이고 **BOM 밖에서 버전을 고르는 것은 Kotlin 플러그인·ktlint·detekt 셋뿐**. ktlintCheck·detekt 모두 통과(위반 0). **locking 이 실제 드리프트를 잡았다** — kotlinx-serialization 1.11.0이 테스트 클래스패스 stdlib 만 2.2.21→2.3.20으로 올린 것을 발견해 BOM(1.9.0)에 넘겼다 | 기본값을 벗어난 규칙 2건(ktlint `class-signature` 임계 1→2, detekt `SpreadOperator` off) — 사유는 산출물 §2.4.<br>**2026-08-12 사실 정정** — detekt 1.23.8의 내장 파서는 Kotlin **1.9가 아니라 2.0.21**이다(`detekt-parser` POM 실측). 같은 종류의 간격이 ktlint에도 있고 Phase 1 문서에 언급이 없었다 — ktlint-cli 1.8.0은 **2.2.21**을 내장한다. Boot 4.0.7 시절엔 컴파일러도 2.2.21이라 우연히 같았고 **2.3.21로 올리면서 갈렸다**. 둘 다 올릴 곳이 없다(ktlint 1.8.0/플러그인 14.2.0이 최신, detekt 2.x는 `dev.detekt` 좌표에 alpha만). 남는 위험은 "Kotlin 2.3 신문법을 쓰면 그때 파싱 실패"인데 **태스크 실패로 드러나므로 조용히 틀리는 종류가 아니다**. 근거 `01_kotlin-implementer_boot41-upgrade.md` §6 | - | kotlin-implementer (2026-08-12) |
| `/health` 가 계약대로 응답 (상수 `{"status":"ok"}`) | 예 | `ci:kotlin` | `HealthContractTest` 4건 — 200·`{"status":"ok"}`(strict)·인증 불필요·**캐시 금지 헤더 없음**·DataSource 없이도 200(=의존 서비스 진단 안 함). compose 실측: `HTTP/1.1 200 / Content-Type: application/json / {"status":"ok"}`. Actuator 미도입(계약 14 엔드포인트 밖 경로를 노출하지 않으려고) | - | - | kotlin-implementer |
| 설정 바인딩·구조화 로그·비밀값 마스킹 | 예 | `ci:kotlin` · `1회성:docs/migration/_workspace/01_kotlin-implementer_skeleton.md` | `EasyDocProperties`(`app/config.py` 포팅) + `Secret` 타입(`SecretStr` 대응) + `SecretConverter`. `SecretTest` 7건 — `toString`·문자열 템플릿·**데이터 클래스 필드로 들어가도** 평문 미노출, 값 비의존 `hashCode`, 상수 시간 비교. 구조화 로그는 Dockerfile `LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs` 로 ECS JSON — compose 로그로 확인. `server.error.include-*` 를 전부 `never`/false 로 꺼 스택·입력값이 응답에 실리지 않게 했다.<br>**2026-08-12** — `easydoc.cors-origins` 가 `CorsConfig` 에서 **실제로 소비**되면서 바인딩 경로가 `CorsContractTest` 로 실행된다(리뷰 T-1 부분 해소) | `Secret` 필드(`jwtSecret`·`fernetKey`)와 `SecretConverter` 의 바인딩은 여전히 미실행 — 값을 쓰는 기능이 Phase 3·4에 온다 | - | kotlin-implementer (2026-08-12) |
| **리뷰 차단 C-1·C-2·C-3** — 오류 계약의 HTTP 경계 검증과 CORS | 예 | `ci:kotlin` | 상세는 `01_kotlin-implementer_error-cors-fix.md`. **C-1**: `GlobalExceptionHandler` 가 `ResponseEntityExceptionHandler` 를 상속해 프레임워크 예외 20종의 **상태 코드는 위임**하고 **본문만** `createResponseEntity` 한 곳에서 `{"detail": ...}`·`application/json` 으로 덮는다. `detail` 은 상태 코드의 표준 사유 문구만 쓴다(Spring `ProblemDetail.detail` 은 예외 메시지에서 유도돼 요청 본문 조각이 실릴 수 있다). `WebMvcConfig` 로 내용 협상을 끄고(FastAPI 는 협상하지 않는다) 검증 실패를 **422 + `[{loc,msg,type}]`** 로 되돌렸다(`input`·`ctx` 미노출). **C-2**: `ErrorContractTest` 를 핸들러 직접 호출 → `@WebMvcTest`+MockMvc 로 이전하고 테스트 소스셋 전용 `ErrorProbeController`(`/__probe`, 운영 JAR 에 없음)를 세웠다. **고치기 전 8건 실패 → 고친 뒤 31건 전건 통과**(도메인 매핑 12건은 전후 모두 통과 = 새 테스트가 정확히 C-1 범위만 새로 잡았다). **C-3**: `CorsConfig` 의 `CorsFilter`(order HIGHEST_PRECEDENCE) — origin 설정값·credentials false·메서드 5종·요청 헤더 2종·**노출 헤더 `Content-Disposition, Location`**·max-age 600. `addCorsMappings` 가 아니라 필터인 이유는 Starlette 미들웨어처럼 **라우팅 밖**이어야 404·405 에도 헤더가 붙기 때문(실측 일치). 살아 있는 두 컨테이너 16 케이스 대조: C-1 세 케이스 **전건 일치**, CORS 5 케이스 일치 | 남은 차이 5건 전부 기록·분류함 — **U-1(미처리 500 의 CORS 헤더)은 2026-08-12 리더 결정으로 종결**(개선 수용), `GET /health/` 307 vs 404는 판단 대기, **범위 밖 3건**(`OPTIONS` Origin 없음 = 리뷰 C-4, `HEAD /health`, preflight 본문).<br>**C-2 의 8건 중 1건은 고치지 않고 테스트를 철회했다** — `컨트롤러가 적어 둔 캐시 금지 헤더가 오류 응답으로 새지 않는다`. 서블릿 API 에 헤더 삭제가 없고(`setHeader(name, null)` 무시) `response.reset()` 은 CORS 헤더까지 지운다. 강제 가능한 규칙은 "쓰지 않는 것"뿐이며, 이 사실이 계약 §2.7-3 재작성(규칙 1 + 단언 A·B)의 근거가 됐다. 남아 있는 보호: "핸들러 자신이 캐시 헤더를 붙이지 않는다" 단언 7건.<br>`handleHandlerMethodValidationException` 은 `spring-boot-starter-validation` 이 없어 **HTTP 경계 미검증**(의존성 추가는 동시 작업 중인 빌드 스크립트를 건드려야 해 보류) | - | kotlin-implementer (2026-08-12) |
| Testcontainers PostgreSQL + Flyway baseline 구축 | 예 | `ci:kotlin` | `V1__python_schema_baseline.sql` 을 **Alembic 을 실제로 돌려**(`uv run alembic upgrade head` → `alembic_version=0006`) 뽑은 스키마로 작성. 지문 대조 **전건 일치**(extension 1 · table 4 · column 32(서수 포함) · constraint 11 · index 11). 회귀는 `PythonSchemaBaselineTest` 4건 + `FlywayBaselineGuardTest` 4건. `baseline-on-migrate=true` 를 쓰지 않고 **지문이 일치할 때만** baseline 하는 `FlywayBaselineGuard` 를 만들었다(§4.2-4). `alembic_version` 은 만들지도 읽지도 쓰지도 않는다(§4.2-7) | Testcontainers 컨테이너가 모듈마다 따로 뜬다(`withReuse` 미적용). 로컬 전체 16초라 지금은 무해 | - | kotlin-implementer |
| **필수 조치 D** — `encryption_scheme` additive 추가 | 예 | `ci:kotlin` | **V2에 배치**(V1 아님). 근거: ① V1은 "Python 스키마 재현"이라 신규 컬럼이 들어가면 지문 대조가 성립하지 않는다 ② **결정적** — baseline 은 V1을 건너뛰므로 V1에 넣으면 기존 Alembic DB에서 컬럼이 영원히 안 생긴다 ③ §4.2-5가 "Kotlin 전용 변경은 V2부터"라고 명시. 대상 `documents`·`conversions`, 기본값 `'fernet-v1'`, CHECK 제약 동반. `V2 는 encryption_scheme 을 additive 로 추가한다`·`Python 컬럼만 지정한 INSERT 가 성공한다` 테스트 통과 | 관찰 기간 내내 `fernet-v1` 고정. AEAD 전환은 Phase 8 이후 별건 | - | kotlin-implementer |
| Dockerfile·compose Kotlin profile 추가 (기존 Python 서비스 유지) | 예 | `1회성:docs/migration/_workspace/01_kotlin-implementer_skeleton.md` | `backend-kotlin/Dockerfile`(멀티스테이지, api·worker bootJar 한 이미지). compose에 `kotlin-migrate`·`kotlin-api`(8100)·`kotlin-worker` 를 `profiles:["kotlin"]` 뒤에 추가 — **기존 Python 서비스 정의를 하나도 바꾸지 않았고** 기본 `docker compose up` 동작이 그대로다. 실측: 두 스택 동시 기동, Kotlin 8100·Python 8000 양쪽 `/health` 200. `kotlin-migrate` exit 0. §4.2-6대로 **DB를 갈랐다**(`easydoc` / `easydoc_kotlin`) — Python DB에 `flyway_schema_history` 0개 확인 | `easydoc_kotlin` 은 기존 볼륨에서 자동 생성되지 않는다(initdb 는 빈 데이터 디렉터리에서만 실행) — 수동 절차 문서화. compose 실행 중 **worker 즉시 종료**를 발견해 `spring.main.keep-alive: true` 로 고쳤다(산출물 §9.5) | - | kotlin-implementer |
| CI에 Kotlin build/test 추가 + 기존 Python/React gate 유지 | **예** (2026-08-20, 리더 · 게이트 28 후속) | `ci:kotlin` · `ci:quality` · `ci:frontend` | `.github/workflows/ci.yml` 에 `kotlin` 잡 추가(9 steps: setup-java 21 · setup-gradle · setup-uv · 이미지 pull · `./gradlew build` · `parityHarness` · 배선 확인 · parity 비교). **기존 `quality`(8 steps)·`frontend`(6 steps) 잡을 건드리지 않았다** — 로컬에서 `ruff`·`ruff format`·`mypy`·`pytest`(820 passed, 68 skipped) 전부 통과 확인 | ~~**CI가 실제 GitHub Actions 에서 도는 것을 확인하지 못했다**~~ → **닫는다.** 실행 `32356589642`(headSha `90aff42`)에서 **`kotlin`·`quality`·`frontend`·`e2e` 네 잡 전부 `success`**. `setup-gradle@v4` 와 러너 Docker 데몬 위 Testcontainers 가 실제로 돌았다. **단서 하나**: run 전체 결론은 `cancelled` 다 — `llm-lane` 이 30분 타임아웃에 걸린다(아래 L-⑫). **이 행이 지는 것은 「Kotlin build/test 가 CI 에서 돈다」이고 그것은 충족됐다** | ~~첫 PR 실행~~ → **충족** | leader (2026-08-20, 게이트 28 후속) |
| **필수 조치 E** — Kotlin 테스트가 `parity/actual/` 을 쓰도록 CI 배선 | 아니오 | `ci:kotlin` | 배선 구조 완성: `ParityActual`(경로를 시스템 프로퍼티로만 받고 **없으면 던진다**) + `parityHarness` Gradle 태스크(`@Tag("parity")` 만, 저장소 루트로 출력) + 일반 `test` 는 모듈 `build/` 로 격리 + CI 3단계(생성·선언 대조 → 존재·`runtime:kotlin` 확인 → 비교). `ParityActualTest` 5건이 산출물 형식·경로·한글 비이스케이프·거부 조건을 고정. 실측 산출물 `parity/_harness-selfcheck/kotlin.json`(`runtime:kotlin`, JVM 21.0.4 Temurin, Kotlin 2.2.21).<br>**2026-08-12 X-1·X-2 수정 (`01_kotlin-implementer_parity-ci-fix.md`)** — 판정 범위를 디렉터리 유무가 아니라 버전 관리 선언 `backend-kotlin/parity-domains.txt` 에서 가져오고, 그 선언을 Gradle `parityManifestCheck` 가 실제 산출물과 **양방향 대조**한다(선언 O/산출 X·선언 X/산출 O·json 0건 전부 빌드 실패). `parityActualClean` 이 매 실행 전 `parity/actual/` 을 비워 stale 산출물 통과를 막는다. **종료 코드 2 사면은 제거**했고(실측: exit 2 는 "선언한 도메인의 역방향 산출물 미생성"일 때만 난다), 사면은 `--only-domain` 부분 검증(exit 3)으로 옮겨 **탐지(Gradle 단계)와 사면(비교 단계)을 다른 CI 단계에 분리**했다. 선언이 정본 11개를 덮으면 좁히기가 자동으로 사라진다. 실증 14종(CI 셸 8 + Gradle 6): 현재 상태 exit 0 / Phase 2 흉내 exit 0(값 21건 대조) / 선언했는데 산출물 없음 exit 1 / 값 불일치 exit 1 / fixture 트리 삭제 exit 1 / 전체 게이트 exit 0(값 101건 + 외부 2건). Python 게이트 무손상(820 passed).<br>**2026-08-12 가드 2종 추가 (`01_parity-canonical-floor.md`)** — ① **정본 0개 가드**: `canonical_count == 0`이면 exit 1(`ci.yml:197`). `--list`가 exit 0인데 출력만 비는 경로는 `pipefail`이 못 잡고, 그대로 두면 "11개를 안 봤다"는 경고가 "0개를 안 봤다"로 바뀌어 무검증이 통과한다. ② **정본 하한**: `.github/parity-canonical-floor.txt`(초기값 정본 11개) + **비대칭 검사**(현재 정본 ⊇ 스냅샷). 추가는 통과시키고 **삭제만 막는다** — 축소가 "전체 게이트 통과"로 위장되던 경로가 닫혔다. 실증 12종: 11→3 축소가 선언 0개·선언 3개 양쪽에서 exit 1, 가드 제거 변형 4종은 전부 exit 0(막고 있는 것이 정확히 이 비교임을 확인), 하한 파일 삭제·비움도 exit 1 | **채우지 못한 것**: `parity/fixtures/` 는 여전히 저장소에 없다(Phase 2 산출물). 실증에 쓴 Kotlin 산출물은 Python 스탠드인이며 실제 Kotlin 구현으로 도는 것은 Phase 2 첫 도메인에서 처음 확인된다. **GitHub Actions 러너 실행 미검증**(로컬 bash 3.2 재현). `parityManifestCheck` 는 도메인 입도까지만 봐서 **X-5 의 모듈↔도메인 대응 단언은 열려 있다**. 하한도 도메인 **이름**만 보므로 도메인 안의 케이스 축소는 잡지 못하고, 같은 커밋에서 정본과 하한을 함께 줄이는 것은 원리적으로 막을 수 없다(최종 방어선은 `.github/` diff 를 사람이 읽는 리뷰 게이트다). 미결 원장 `P1-2`(종료 코드 2 완화)는 **해소 확인**(아래 사실 정정) | Phase 2 (fixture 생성 · 첫 push 에서 러너 검증) | kotlin-implementer (2026-08-12) |
| 종료 조건: 빈 DB와 기존 schema snapshot 양쪽에서 기동 + `/health` 응답 | 예 | `ci:kotlin` | `ApiStartupOnEmptyDatabaseTest` 2건 + `ApiStartupOnPythonSnapshotTest` 2건. `@SpringBootTest(RANDOM_PORT)` + JDK `HttpClient` 로 **실제 소켓**을 친다. 빈 DB → `flyway_schema_history=[1,2]`, 200 `{"status":"ok"}`. 기존 스냅샷(Alembic 0006 상태) → `[1(BASELINE), 2(SQL)]`, `alembic_version=0006` 불변, 200 `{"status":"ok"}`. compose 실측으로도 재확인(산출물 §9).<br>**2026-08-12 Boot 4.1.0 업그레이드 후 재확인** — 같은 4건이 그대로 통과하고, Flyway 11→12(유일한 메이저 상승)에 대해 **11.14.1이 쓴 `flyway_schema_history` 를 12.4.0이 `Successfully validated 2 migrations` 로 수용**하는 것까지 compose 로그로 확인했다(체크섬 재계산 요구 없음). 근거 `01_kotlin-implementer_boot41-upgrade.md` §5·§8-4·§8-5 | - | - | kotlin-implementer (2026-08-12) |

**전체 테스트**: `./gradlew build` → **BUILD SUCCESSFUL, tests=75 failures=0** (core 19 · infrastructure 8 · api 45 · worker 3).
2026-08-12 리뷰 C-1·C-2·C-3 수정으로 48 → 75 (api 18 → 45: `ErrorContractTest` 10→18 경계 이전 ·
`FrameworkErrorContractTest` 9 신규 · `CorsContractTest` 10 신규). 기존 48건은 전건 유지.
**Boot 4.1.0 업그레이드 후에도 같은 75건이 그대로 통과한다** — 업그레이드 전(4.0.7) 기준선을 먼저 찍고 대조했다.

### 확정한 버전 조합 (Boot 4.1.0 BOM 적용 후 — 2026-08-12 갱신)

| 항목 | 값 | 이전(4.0.7) 대비 |
|---|---|---|
| JDK / Gradle | Temurin 21.0.4 / 9.1.0 | 동일 |
| Kotlin | **2.3.21** | 2.2.21 → BOM `kotlin.version` 정렬 (변경) |
| Spring Boot | **4.1.0** | 4.0.7 → **P1-1 판정 이행**. 4.1 계열 안정판은 4.1.0 하나뿐(Maven Central 메타데이터 실측) |
| Flyway | **12.4.0** | 11.14.1 → **유일한 메이저 상승**. §5 에서 따로 검증 |
| kotlinx-serialization | **1.11.0** | 1.9.0 → BOM |
| Spring Framework / Jackson / JUnit / Testcontainers / PG 드라이버 | 7.0.8 / 3.1.4 / 6.0.3 / 2.0.5 / 42.7.11 | **전부 동일** (BOM 관리) |
| ktlint(플러그인/CLI) / detekt | 14.2.0 / 1.8.0 / 1.23.8 | 동일 (§위 표의 사실 정정 참고) |

Boot 4가 spike 이후 바꾼 좌표(실측)는 4.1.0에서도 그대로다: `FlywayMigrationStrategy` → `spring-boot-starter-flyway` /
`org.springframework.boot.flyway.autoconfigure`, `@WebMvcTest` → `spring-boot-starter-webmvc-test` /
`org.springframework.boot.webmvc.test.autoconfigure`, Testcontainers → `org.testcontainers:testcontainers-postgresql`.
**Jackson 3.1.4**(패키지 `tools.jackson`)가 관리 버전이라 Phase 2 이후 JSON 처리 시 주의가 필요하다.

**업그레이드 결과 (`01_kotlin-implementer_boot41-upgrade.md`)** — **코드 변경 0줄.** 바뀐 파일은 version catalog 와
락파일 5개뿐이고, `clean build` tests=75 failures=0 · ktlint/detekt 위반 0 · 컴파일 경고 0(`allWarningsAsErrors=true`) ·
Python 게이트 820 passed 가 업그레이드 전과 동일하다. Kotlin 2.3.21이 실제로 런타임까지 갔다는 증거는
`parity/_harness-selfcheck/kotlin.json` 의 `kotlinVersion` 필드(JVM 이 `KotlinVersion.CURRENT` 를 읽어 쓴다)다.

두 가지가 부수 성과다.

- **Jackson 2가 클래스패스에서 제거됐다.** Flyway 12가 Jackson 2 → 3으로 갈아타면서, 4.0.7에서 api `runtimeClasspath` 에
  Jackson 2 databind 와 3 databind 가 **동시에** 올라와 있던 상태가 해소됐다. 직렬화 라이브러리 두 벌은 나중에
  오류 본문·`Content-Disposition` 같은 계약 지점에서 "어느 `ObjectMapper` 가 잡혔는가"로 번지기 쉬운 배치였다.
- **락파일 재생성 절차를 확립했다.** 증분 `--write-locks` 는 계열이 바뀌는 업그레이드에서 **stale 제약을 남긴다** —
  KGP 2.3이 더 이상 해석하지 않는 `kotlinCompilerClasspath` 의 락 항목이 2.2.21로 남아 2.3.21 요청을 **강등**시켰다
  (`dependencyInsight` 로 확인). 조치: 락파일 5개를 지우고 `clean build --write-locks --no-build-cache` 로 재생성.
  `clean` 과 `--no-build-cache` 가 함께 없으면 태스크가 up-to-date 로 건너뛰어 락파일이 실제보다 **비어 있게** 생성된다.

### 리더 판단이 필요했던 항목 — 둘 다 해소 (2026-08-12)

| # | 내용 | 결과 |
|---|---|---|
| P1-1 | **Spring Boot 4.0.7 vs 4.1.0.** 계획 §3.1은 "4.1 계열 후보"라 적었으나 4.0.7을 골랐다 (2차 리뷰 K-3 = 교차 T-5, 마감 Phase 2 착수 전) | **해소 — 4.1.0으로 올렸다.** 되돌릴 이유를 찾지 못했다(코드 0줄, 이동·폐기 좌표 0건, 테스트 75건 유지, 메이저 상승인 Flyway 12는 별도 검증). 되돌리는 비용은 시간에 비례해 커지므로 판단을 미루지 말라는 리뷰 권고를 따랐다 |
| P1-2 | CI parity 비교 단계가 **종료 코드 2를 통과 처리**한다 (마감 Phase 4 종료 시) | **해소 — 사면 자체가 제거됐다.** 아래 사실 정정 참고 |

### Phase 1에서 손대지 않은 것 (지시대로 보류)

- ~~**U-1**(미처리 500 응답의 CORS 헤더) — 리더 판단 전이라 **CORS 자체를 설정하지 않았다**~~
  → **2026-08-12 갱신**: CORS 는 구현했다(리뷰 C-3). **U-1 경로에는 헤더를 붙이는 코드도 떼는 코드도 넣지 않았고 테스트로도 고정하지 않았다.** 다만 `CorsFilter` 가 체인 앞에서 헤더를 쓰므로 **기계적으로 "개선" 쪽 값이 나온다**(측정: 미처리 500 에 `Access-Control-Allow-Origin`·`Expose-Headers` 있음)
  → **2026-08-12 종결**: 리더가 그 값을 **확정**했다(개선 수용, 사용자 승인). 계약 파일과 changelog 에 "의도된 차이"로 기록됐고, parity 대조에서 이 헤더 불일치는 차단 사유가 아니다. `kotlin-implementer` 는 이 결정을 **테스트로 고정**할 것 — 현재 이 경로에는 긍정 단언도 부정 단언도 없다
- ~~검증 실패(422) 응답의 `detail` **배열** 형태~~ → **2026-08-12 구현**. 상태 코드 422 + `[{loc,msg,type}]`, `rejectedValue`·`input`·`ctx` 미노출을 HTTP 경계 테스트로 고정. **`msg`/`type` 문자열은 Pydantic 과 바이트 동일할 수 없다**(검증 엔진이 다르다) — 계약이 동결한 것은 상태 코드·키 구성·입력값 부재이고 문구는 그 아래라는 판단이며 `contract-keeper` 확인이 필요하다
- ~~`GlobalExceptionHandler` 의 HTTP 경계 검증~~ → **2026-08-12 이전 완료**(리뷰 C-2). `@WebMvcTest`+MockMvc + 테스트 전용 `ErrorProbeController`. Phase 3 는 이 위에 **실제 엔드포인트의** 401·404·409·422·500 을 얹으면 된다
- **성공 응답의 캐시 금지 헤더는 Phase 3 에서 `ResponseEntity` 에 붙여야 한다** — 컨트롤러가 `HttpServletResponse` 에 직접 쓰면 예외가 나도 그 헤더가 오류 응답에 남는다(Python 과 반대 거동). 서블릿 API 에 헤더 삭제가 없고 `response.reset()` 은 CORS 헤더까지 지워 대안이 되지 못한다. 이 제약을 `GlobalExceptionHandler` KDoc 에 적어 두었다
- `spring-boot-starter-validation` 미추가 — 의존성·락파일·빌드 스크립트가 동시 작업 중이라 열지 않았다. 그래서 `handleHandlerMethodValidationException` 은 **미검증**이다. Phase 3 에서 입력 상한과 함께 회귀 고정
- `app/`·`tests/`·`frontend/`·`scripts/`·`.claude/`·`contracts/` — 읽기만 했다
  (이후 같은 라운드에서 `contract-keeper` 가 자기 소유 파일인 `contracts/easy-doc-v1.yaml` 과
  `.claude/skills/api-contract-freeze/SKILL.md` 를 U-1·§2.7-3 반영으로 고쳤다. `app/`·`tests/`·`frontend/` 는 무손상)

---

## Phase 1 종료 판정 — **조건부 종료** (2026-08-12, 리더)

계획 §5 Phase 1의 **명시 종료 조건**("빈 DB·기존 schema snapshot 양쪽에서 Kotlin 앱이 기동되고 `/health` 가 응답함")은
**충족**이며, Boot 4.1.0 업그레이드 후에도 재확인됐다.

**"리뷰 게이트 Critical 0건" 행은 "Phase 2 착수를 막는 Critical 0건"으로 범위를 좁혀 판정하고 충족으로 닫는다.**
근거는 2회차 교차 종합(`reviews/01_skeleton_cross.md` §7.1·§8)이다 — Phase 2는 순수 도메인 로직 포팅이고
종료 조건이 "외부 API·DB 없이 실행하는 parity suite"라서 **HTTP·DB·CORS 경계를 쓰지 않는다.**
실제로 Phase 2를 막던 것은 parity CI(2회차 X-2) 하나였고 그것이 닫혔다. 심각도가 높다는 이유만으로
착수를 막는 것은 과잉이라는 교차 종합의 권고를 채택했다.

**나머지 차단 지적은 마감이 명시된 미결 원장으로 이월한다.** 이렇게 하면 (a) 충족된 종료 조건을 인위적으로
열어 두지 않고, (b) 차단 항목이 마감 없이 사라지지 않으며, (c) Phase 2가 실제로 막히는 한 건만 선행 조건이 된다.

> **조건부인 이유** — Phase 1 표에 `아니오` 2행이 남아 있다: CI가 실제 GitHub Actions에서 도는 것 미검증(첫 push),
> 필수 조치 E의 fixture 산출물 부재(Phase 2). 둘 다 이 저장소 안에서는 닫을 수 없고 마감이 명시돼 있다.

### 이번 라운드에 닫힌 것

| 항목 | 내용과 근거 |
|---|---|
| **C-1** 프레임워크 예외 500 | 상태 코드는 프레임워크에 위임(`ResponseEntityExceptionHandler` 상속)하고 **본문만** `createResponseEntity` 한 곳에서 계약대로 덮는다. `detail` 은 상태 코드의 **표준 사유 문구**만 쓴다 — Spring `ProblemDetail.detail` 은 예외 메시지에서 유도돼 요청 본문 조각이 실릴 수 있기 때문이다. 살아 있는 컨테이너에서 404 · 405+`Allow: GET` · `Accept: application/xml` 에도 200 확인 |
| **C-2** 오류 계약 테스트가 HTTP 경계를 안 봄 | 핸들러 직접 호출 → `@WebMvcTest`+MockMvc + 테스트 소스셋 전용 `ErrorProbeController`(`/__probe`, 운영 JAR 에 없음)로 이전. **새 테스트를 먼저 넣어 8건 실패를 확인한 뒤 고쳤고**(도메인 매핑 12건은 전후 모두 통과 = 새 테스트가 정확히 C-1 범위만 새로 잡았다) → **31건 전건 통과**. 8건 중 1건은 고칠 수 없어 테스트를 철회했고 그 근거가 계약 §2.7-3 재작성으로 이어졌다(위 표 참고) |
| **CORS** (C-3) | `addCorsMappings` 가 아니라 **`CorsFilter`**(order HIGHEST_PRECEDENCE) — Starlette 미들웨어와 같은 **라우팅 밖** 위치여야 404·405에도 헤더가 붙는다(Python 실측 일치). 노출 헤더 `Content-Disposition`·`Location` 을 **preflight·실요청 양쪽에서** 확인. 회귀 테스트 10건 |
| **parity CI** (2회차 X-1·X-2) | 판정 범위를 **"디렉터리 유무"에서 "버전 관리되는 선언"으로** 옮겼다. `backend-kotlin/parity-domains.txt` + Gradle `parityManifestCheck` 양방향 대조(선언 O/산출 X · 선언 X/산출 O · json 0건 전부 빌드 실패) + `parityActualClean`. **exit 2 사면은 제거** — 실측 결과 그 조건은 발생하지 않았고, exit 2는 "선언해 놓고 역방향 산출물을 안 만든" 상태에서만 난다. 사면은 `--only-domain` 부분 검증(exit 3)으로 옮겨 **탐지(Gradle 단계)와 사면(비교 단계)을 다른 CI 단계에 배치**했다 |
| **정본 0개 가드** | `canonical_count == 0` 이면 exit 1. `--list` 가 exit 0 인데 출력만 비는 경로는 `pipefail` 이 못 잡는다 |
| **정본 하한** | `.github/parity-canonical-floor.txt` + **비대칭 검사**(현재 정본 ⊇ 스냅샷). 추가는 자유, **삭제는 차단** |
| **Boot 4.1.0** (P1-1) | Kotlin 2.3.21. **코드 변경 0.** 락파일 재생성 절차 확립(증분 `--write-locks` 는 stale 제약을 남긴다). Jackson 2가 클래스패스에서 제거됨 |
| **U-1** 미처리 500의 CORS 헤더 | **개선 수용**(리더 판정 + 사용자 승인). React 분기 실측 결과 `status = 0` 에 의존하는 화면이 없다 |
| **계약 §2.7-3** | 실행 불가능한 종료 조건을 **"규칙 1(사적 헤더는 `ResponseEntity` 에만) + 단언 A·B"** 로 재작성 |

### 사실 정정 (기존 기재가 틀렸다)

- **`P1-2`**(CI parity 비교 단계가 exit 2를 통과 처리) — **해소됨.** exit 2 사면 **자체가 제거**됐다.
  마감을 "Phase 4 종료 시"로 잡았던 것은 그 완화를 그때까지 유지한다는 전제였는데, 실측 결과 그 사면이
  겨냥한 조건("아직 포팅하지 않았다")은 애초에 exit 2를 내지 않았다 — exit 1을 낸다. 사면할 이유가 없었다.
- **detekt 1.23.8의 내장 Kotlin 파서는 1.9가 아니라 2.0.21이다**(`detekt-parser` POM 실측).
  그리고 **ktlint-cli 1.8.0은 2.2.21을 내장해 이제 컴파일러(2.3.21)와 다르다** — 4.0.7 시절엔 우연히 같아서
  Phase 1 문서에 기재되지 않았다.

### 미결 원장 (마감 명시) — 각 Phase 착수 게이트에서 다시 센다

| 항목 | 마감 |
|---|---|
| Flyway 지문 TOCTOU + Alembic head 미확인 (2회차 codex #3·#4 / 교차 F-3·F-5) — 지문 판정·baseline·migrate 가 각각 별도 연결이고 어떤 잠금에도 덮이지 않는다. `alembic_version` **읽기**는 계획이 금지한 적이 없다(구현자의 자기부과 제약이었다).<br>**[게이트 15 재판정 2026-08-15] 부분 해소 — 충족 아니오.** 원 지적의 TOCTOU 축은 `f9d78e0` 으로 닫혔고(잠금이 판정 구간을 실제로 지킨다는 것이 음성 대조로 실측됨), 남은 4건과 판정 근거는 아래 「게이트 15 후속」 §1 재판정 표가 정본이다 | ~~Phase 3 착수 전~~ **Phase 3 첫 기동/배포** |
| `CoreModuleBoundaryTest` 우회 (2회차 codex #5) — `compileOnly` + 목록 밖 타입이면 통과한다. `api`·`worker` 가 `infrastructure` 를 `runtimeOnly` 로 유지하는지 **단언하는 코드가 0건**이라, `runtimeOnly` → `implementation` 한 글자 변경에 아무 테스트도 깨지지 않는다.<br>**[게이트 15 재판정 2026-08-15] 부분 해소 유지 — 충족 아니오.** `ef7b4a8` 이 경계 강제를 세웠으나 X4·X4b 가 미수정이다. 상세는 아래 「게이트 15 후속」 §1 | ~~Phase 3 착수 전~~ **Phase 3 모듈 추가 시** |
| ~~provenance·external이 **같은 변경 가능 소스를 신뢰** (2회차 codex #2 / 교차 X-3)~~ — **판정 완료 (2026-08-12, 리더).** 아래 상세 | ~~Phase 2 착수 전~~ **판정됨** |
| crypto 음성 케이스가 정본 대조에서 빠짐 (H-3 = 교차 X-4) — `VOLATILE_INPUT_FIELDS` 가 도메인 단위라 crypto 의 `input`(`{key, token}`)이 통째로 빠지고 음성 3건의 `expected` 가 동일하다. **`crypto-tampered.token` 을 쓰레기로 바꿔도 게이트가 닫힌다.** 해법은 같은 파일 안 `argon2` 방식(파생 성질을 `expected` 에 담기)에 이미 실증돼 있다 | Phase 4 종료 전 |
| 계약의 요청 길이 제약 5개가 계약 자신의 422 규칙과 충돌 (F3 = 교차 C-5) — 코드에서 이 다섯은 스키마 제약이 아니라 서비스 계층 규칙이라 422 **문자열** `detail` 인데, 계약은 스키마 실패를 422 **배열**로 못박았다. 셋은 코드보다 엄격하기까지 하다(코드는 정규화 **후** 길이를 잰다).<br>**판정 완료 — `d03e5e8`** (다섯을 서비스 층 규칙으로 확정, `x-request-field-constraints` 신설 — `measured_on` normalized 3 / raw 2. `text`/`edited_text` 비대칭은 `x-open-asymmetry` 로 Phase 4 미결). **판정이 원장에 미반영이던 것을 게이트 15 X14 로 반영한다.** 검증 공백은 `526bfeb`(X-F11~13 신설), 정반대 단언은 `f9d78e0`(X6) 으로 메웠다. **`measured_on: raw` 2필드의 지위는 X13 으로 사용자 판단 대기**(아래 「게이트 15 후속」 §3) | ~~Phase 3 착수 전~~ **판정됨** (raw 2필드 심사만 X13) |
| 계약 multipart `contentType` 제약이 구현에 없음 (F2 = 교차 C-6) — 성실히 구현하면 `.hwpx` 업로드가 깨지고 **그 구현이 contract test 를 통과한다**(`.hwpx` 는 브라우저가 `application/octet-stream` 을 보내고, `application/hwp+zip` 은 내보내기 mimetype 이다) | Phase 4 착수 전 |
| **G-1** `POST /workspaces`·`PATCH /workspaces/{id}` 캐시 헤더 테스트 공백 — Python 기준선에도 없다(계약상 10곳 중 2곳).<br>**판정 완료 — `d03e5e8`** (단순화: Kotlin 계약 테스트가 10곳을 한 번에 덮는다 — Python 기준선에 따로 채우지 않는다). **판정이 원장에 미반영이던 것을 게이트 15 X14 로 반영한다** | Phase 3 (구현 시 집행) |
| ~~계약 §2.7-3 **규칙 1**을 Phase 3에서 **실측**해 성립 확인~~ — **폐기 (2026-08-12 리더 판정, OQ-1).** 규칙 1(사적 헤더는 `ResponseEntity`로만 싣고 필터 금지)이 전역 부착과 정면 충돌해 내려갔다. 이 항목의 자리는 아래 **H-1**이 대신한다 | ~~Phase 3 종료 전~~ **폐기** |
| ~~**H-1** 전역 헤더 필터가 **컨테이너 레벨 응답까지 닿는가 — 미실측**~~ — **실측 완료 (2026-08-12, kotlin-implementer / Tomcat 11.0.22 · Boot 4.1.0).** 원시 소켓 + `@SpringBootTest(RANDOM_PORT)`로 측정. **필터에 닿음**: 핸들러 없는 404 · 415 · 413 · 프리플라이트 OPTIONS 200 · `sendError`→`/error` 503. **필터에 못 닿음 7종**: 요청 대상 금지 문자 400 · 콜론 없는 헤더 줄 400 · 요청 줄 파손 400 · 헤더 상한 초과 400 · `Host` 없음 400 · 알 수 없는 HTTP 버전 505 · 알 수 없는 메서드 405 — 파싱 단계에서 거절돼 **필터 체인이 시작조차 하지 않는다**(배치로 고칠 수 없다). **해결: 계약을 좁히지 않고 강제 수단을 넓혔다** — Tomcat Engine 밸브가 7종 전부를 덮음을 계측 확인, 음성 대조(밸브 제거 시 malformed 3건만 깨짐)까지 돌렸다. 리더 재심 불필요. **후보 원인 ⓐ `shouldNotFilterErrorDispatch()`는 기각** — 그것과 `DispatcherType.ERROR`를 둘 다 기본값으로 되돌려도 헤더가 남는다(필터가 `chain.doFilter` 앞에서 쓰고 Tomcat이 포워딩에서 버퍼만 비운다). ⚠️ **회귀는 원시 소켓으로 유지** — MockMvc로 옮기면 "측정한 것처럼 보이는 통과"가 나온다. ⚠️ **밸브는 Tomcat 결합** — 컨테이너 교체 시 기동 시점에 깨진다(계약 `x-container-coupling`) | ~~Phase 3 종료 전~~ **완료** |
| **H-4** 오류 본문이 **경로를 가리지 않는가** — H-1 실측의 **범위 밖 부수 발견.** `sendError` → `/error` 응답이 `{"detail":…}`가 아니라 Spring `BasicErrorController` 기본 본문 `{"timestamp","status","error","path"}`를 낸다. 계약의 "오류 본문 최상위 `detail` 하나"는 계획 §2.2 **불변식**인데 이 경로가 사각지대였다. **지금은 운영 코드가 `sendError`를 안 불러 안 드러난다 — Phase 3에서 인증 필터가 401을 `sendError`로 내는 것이 가장 흔한 구현이고 그 순간 깨진다**(401은 React `client.ts`의 세션 만료 분기라 화면 동작까지 바뀐다). 계약에 `x-error-body-universality` 신설, 검증 E-1~E-3 = 테스트 X-C7·X-C8. **구현 수단은 규정하지 않는다** — 계약이 정하는 것은 나간 바이트뿐이다. 남은 미측정: 위 7종의 **본문 모양**(E-4 = X-C9) — 이번 실측은 헤더만 봤다. 만족시킬 수 없으면 계약을 좁히지 말고 리더 재심 | Phase 3 종료 전 |
| **H-2** 전역 필터 도입에 따른 **헤더 중복 부착** — 필터와 컨트롤러 `ResponseEntity`가 같은 헤더를 둘 다 실으면 `Cache-Control: no-store, no-store`가 나가 계약의 `const` 제약에 위반된다. 값만 보는 단언은 통과하므로 **개수까지** 단언해야 한다(`header().stringValues(...)`). 필터는 `add`가 아니라 `set` | Phase 3 |
| **문서 spike 재검증** — Phase 0 spike 는 Kotlin **2.2.0** 조합에서 통과한 것이다. 현재 2.3.21이므로 POI 5.4.1·PDFBox 3.0.5·commons-compress 1.27.1 조합으로 **DOCX 동등성 7항목을 다시 확인**해야 한다. 이번에 확인한 것은 좌표 해석과 컴파일뿐이다 | Phase 4 착수 전 |
| DOCX 내보내기 템플릿 동봉 여부 — POI 산출물에 `styles.xml`/`theme` 가 없어 Heading 1 서식이 소실된다 | Phase 4 착수 전 |
| 내보내기 parity 를 **바이트 해시가 아닌 정규화 텍스트로** — zip 컨테이너 바이트는 Python 과 같아질 수 없다(실측 java 434B vs python 348B). `parity-verifier` 합의 필요 | Phase 4 착수 전 |
| `handleHandlerMethodValidationException` **HTTP 경계 미검증** — `spring-boot-starter-validation` 부재. 입력 상한과 함께 회귀 고정 | Phase 3 |
| parity 하네스 배선 주의 — 디렉터리 비교는 `actual_root / fixture_path.relative_to(fixture_root)` 로 짝짓는다(`compare_parity.py:713-715`). 즉 **actual 파일명이 fixture 파일명과 같아야** 한다 — `masking.json` 이나 `kotlin.json` 이 아니다 | Phase 2 배선 시 |
| `resolveAndLockAll` 태스크 도입 · Gradle 플러그인 클래스패스 locking · Gradle 10 deprecation 경고 — 셋 다 기존 상태이거나 별건이며 구현자가 코드에 넣지 않았다 | 승인 대기 |
| **다음 회차 리뷰 focus 를 계약·보안 불변식·테스트 적정성 축으로** — 이번 codex focus 를 5축(모듈 경계·Flyway·`encryption_scheme`·CI 게이트·parity 우회)으로 좁힌 탓에 그 세 축의 Claude 지적 **17건(C-1~C-9, S-1~S-5, T-1~T-6)이 단일 관점 판정으로 남아 교차 검증을 받지 못했다** | Phase 2 리뷰 |
| CI가 실제 GitHub Actions 에서 도는 것 미검증 — YAML 파싱과 로컬 동등 명령만 검증했다. `::error::`/`::notice::` 렌더링, `setup-gradle@v4`, 러너 Docker 위 Testcontainers 는 첫 push 에서 처음 검증된다 | 첫 push |

### 판정 — codex #2 / 교차 X-3 (2026-08-12, 리더)

교차 종합 §7.1은 이 항목을 **"Phase 2 착수 전 필수"**로 놓았고 §8은 "4건을 닫은 시점에 리뷰 게이트 행을 판정"하라고 권고했다. 실제로 닫힌 것은 3건이고 이 판정만 열려 있었으므로 지금 내린다. (직전 기재의 마감 `Phase 2 종료 전`은 리더가 교차 문서보다 느슨하게 옮겨 적은 것이었다 — 정정한다.)

**판정: 이 항목은 "닫을 수 있는 결함"이 아니라 "관리해야 하는 한계"다. 추가 방어를 넣지 않고 현재 상태로 Phase 2에 착수한다.**

근거:

1. **원리적으로 닫히지 않는다.** 지적의 요지는 "생성기와 검증기를 같은 diff에서 함께 약화시키면 못 잡는다"이다. 그런데 무엇을 정본으로 삼든 그 정본도 저장소 안에 있고 같은 커밋에서 함께 바뀔 수 있다. codex 권고대로 manifest를 `BUILDERS`에서 분리해도 **분리된 manifest가 새 정본이 될 뿐**이며, 이번 세션이 그 연쇄를 이미 네 번 겪었다(proof 파일 → fixture 출처 → 도메인 디렉터리 → 정본 목록). 한 칸 더 옮기는 것은 방어가 아니라 이동이다.

2. **이미 세운 것이 "조용한 축소"를 막는다.** `.github/parity-canonical-floor.txt`의 비대칭 검사가 정본 축소를 차단하고(추가는 자유, 삭제는 exit 1 + 사라진 도메인 이름 출력), 하한 파일 자체를 줄이려면 `.github/` diff가 남는다. `01_parity-canonical-floor.md`가 그 성격을 정확히 적었다 — **"이 검사는 축소를 불가능하게 만들지 않고 조용할 수 없게 만든다."**

3. **최종 방어선은 코드가 아니라 리뷰 게이트다.** `codex-review` 스킬이 `.claude/**` 수정을 게이트 면제에서 **뺀** 것이 이 장치다(확장자가 `.md`여도 "문서 수정"이 아니다). 이번 세션의 지적이 전부 하네스에서 나온 것이 그 판단의 값어치를 보였다. 생성기·검증기·정본을 함께 건드리는 커밋은 정의상 하네스 수정이므로 반드시 리뷰를 지난다.

**채택하지 않은 codex 권고와 이유**

- *정본 manifest를 `BUILDERS`에서 분리* — 위 1의 이유로 기각. 다만 **분리 자체가 무해하므로**, Phase 2에서 도메인이 늘 때 `parity-domains.txt`·`parity-canonical-floor.txt`·`BUILDERS` 셋을 한 커밋에서 고치게 되면 그 커밋은 리뷰에서 특별히 다룬다.
- *알려진 손상 산출물을 먹여 exit 1을 단언하는 adversarial 테스트* — **부분 채택하되 별건으로 돌린다.** 이번 세션의 실증(손으로 쓴 proof → 2, 축소 fixture → 1, `runtime: not-kotlin` → 1, 역방향 패딩 → 1, 정본 0개 → 1, 정본 부분 삭제 → 1)이 전부 **일회성 수동 확인**이라 회귀로 고정돼 있지 않다. 지금은 사람이 다시 뚫어야 알 수 있다. 이를 자동 회귀로 만드는 것은 실질 가치가 있으나 Phase 2 착수를 막을 이유는 없다 → 아래 원장에 별건으로 추가.

**Phase 2 착수를 막지 않는 이유**: Phase 2는 순수 도메인 로직 포팅이고, 이 항목이 실제 피해를 내려면 "누군가 하네스와 정본을 함께 약화시키는 커밋을 만들고 그것이 리뷰를 통과"해야 한다. 그 시나리오의 방어는 리뷰 게이트이지 코드가 아니다.

| 별건으로 추가 | 마감 |
|---|---|
| ~~**게이트 우회 시나리오를 자동 회귀로 고정** — 이번 세션에서 수동 실증한 6종(손으로 쓴 proof / 축소 fixture / `runtime` 위조 / 역방향 패딩 / 정본 0개 / 정본 부분 삭제)이 회귀 테스트로 남아 있지 않다. 게이트가 다시 뚫려도 CI가 모른다~~ — **해소 (2026-08-15).** `42f9e20` 이 회귀 6종을 세웠고, 게이트 15 X2 가 연 **배선·CI 축**을 `04ced00` 이 닫았다(본류 회귀 4건 + `ci.yml` 경로 명시). 음성 대조: 본류 호출부 `compare_parity.py:1984`(runtime)·`:2282`(provenance) 를 지우면 **새 테스트만** 빨강, 기존 helper 테스트는 전부 초록 | ~~Phase 3 착수 전~~ **해소됨** |

---

## Phase 2 — 순수 도메인 로직 포팅

계획 문서 §5 Phase 2. 원문 종료 조건: "**외부 API·DB 없이 실행하는 parity suite가 동일 결과를 냄.**"

~~**전부 미착수다.**~~ → **2026-08-13 재개발 착수** (커밋 `0c377a5..3934f06`). 아래 표는 판정 기준이며, 근거 없는 `예`는 `아니오`로 취급한다는 규칙이 그대로 적용된다. 리뷰 게이트·판정 기록은 표 아래 「Phase 2 진행 기록 (리더)」 절.
`관련 정본 도메인`은 `dump_parity_fixtures.py --list` 의 11개 중 이 Phase 가 덮는 8개를 배정한 것이다
(나머지 `crypto`·`jwt`·`argon2` 는 Phase 3·4).

> `실행 경로` 열의 어휘 정본은 위 Phase 0 표의 포인터를 따른다.

| 종료 조건 | 충족 | 실행 경로 | 관련 정본 도메인 | 근거 | 미해결 항목 | blocked-by | 마지막 갱신 주체 |
|---|---| --- |---|---|---|---|---|
| 개인정보 마스킹 포팅 (`app/privacy/masking.py`) | 예 | `ci:kotlin` | `masking` | `core/privacy/Masking.kt` + 생산자 `core/src/test/.../privacy/MaskingParityTest.kt`. **parity 실측(2026-08-13): 성질 31건·단언 114개 충족**(exit 3 = 부분 검증 통과). **privacy-gate 판정 X-2 반영** — 유니코드 인식 패턴 안의 ASCII 전용 리터럴 두 종류를 닫았다(종류 A: RRN 성별코드를 `Character.digit` 값 판정으로 / 종류 B: 구분자 집합을 하이픈 6종·공백 6종으로, `\s` 미사용). 음성→양성 실측: 새 케이스 25건 중 **17건이 수정 전 패턴에서 실패**하고 수정 후 전건 통과(나머지 8건은 과잉 마스킹 회귀 가드라 수정 전에도 통과해야 정상). 참고 갈림 1건(`masking-known-gap-rrn-fullwidth`)은 이 개선이 만든 것이며 `parity/reference-ledger/masking.json` 에 기록됨 | ~~fixture 쪽 후속은 `parity-verifier` 몫~~ → `3934f06` 으로 처리됨(31→57 케이스). **[리더 재판정 2026-08-14, 게이트 08 C-12] 예 → 아니오.** 게이트 08 교차 종합이 차단 ①사건 **C-01** 을 세웠다 — 보충 평면 숫자(1 code point = surrogate 2문자를 `singleOrNull()` 이 거부)와 복합 카드 구분자(` - `·NBSP+하이픈+NBSP)가 **실측 items=0 으로 통과**(codex B-1, 컴파일된 core 에 실입력 투입). 차단 사건이 열린 행을 `예` 로 둘 수 없다. `EXPECTED_MET_YES_KEYS` 에서 이 행을 같은 커밋에서 제거했다 — 그 diff 가 판정 범위 변경의 리뷰 신호다. ~~재판정 경로: privacy-gate 묶음 판정 → 구현 → fixture 반영~~ → **전부 완료**(§4-ter 판정 → `c6e65a0` 구현 → `1301367` 동결, §4-sexies 해제). **[사유 갱신 2026-08-14, 게이트 09 Z-원장 지적]** 이 행이 지금 `아니오` 인 이유는 닫힌 C-01 이 아니라: ① 판정 1 해제조건 4(`docs/golden/` 실문서 마스킹 건수 대조) 미실시 ② 게이트 09(이 배치 자체의 리뷰) 미종결 — 닫힌 사유를 남기면 다음 사람이 그것만 보고 `예` 로 뒤집는다 | privacy-gate (해제조건 4) · 게이트 09 종결 | leader (2026-08-14) |
| 텍스트 정규화·제어문자 제거 포팅 | 예 | `ci:kotlin` | `text` | `core/text/TextNormalization.kt`·`TextWhitespace.kt` + `TextNormalizationTest` (커밋 `0c377a5`) — **코드·단위 테스트는 ci:kotlin 에서 돈다.** 08 리뷰 m-4(원장 과소 신고) 반영해 사실만 기재 — 충족이 아니오인 이유는 오른쪽 | ~~fixture 미생성·미선언~~ → **배선 완료**(`ad6ab92` fixture · `cd23aec` 생산자+선언, 8/8 값 일치). 남은 것: 게이트 11 종결 | 게이트 11 | leader (2026-08-14 2차) |
| 프롬프트 렌더링과 동적 어려운 말 목록 포팅 | 예 | `ci:kotlin` · `ci:quality` | `prompts` | `core/easyread/Prompts.kt`·`DifficultWords.kt` + `PromptsTest`·`PromptTextSnapshotTest`·`PromptInjectionGuardTest` (커밋 `81f1d84`) — 스냅샷은 07 리뷰가 Python 독립 재추출로 **전건 일치** 확인. 08 리뷰 m-4 반영.<br>**[게이트 15, 2026-08-15] 승격 유지 — 근거의 뒤 조각이 배선됐다.** X-9 마감의 원문은 *"스냅샷 생성기 커밋 **+ CI 재생성 diff**"* 두 조각이었고, 뒤 조각은 도달 0이었다(cross X1 — Claude P-A·codex C15-5 **독립 합의**). `04ced00` 이 `ci.yml` **quality 잡**에 `dump_python_snapshots.py --check` 스텝을 배선해 닫았다(생성기 실물 경로 `.claude/skills/migration-safety-gate/scripts/dump_python_snapshots.py` — JDK 가 아니라 `uv` 환경이 필요해 kotlin 잡이 아니다). **리더 판정: 마감 축소가 아니라 CI 배선 완성으로 승격을 유지한다.** 삭제 탐지력 자체의 회귀는 `tests/test_python_snapshot_guard.py`(경로 명시 — `4cba492`) | ~~fixture 미생성·미선언~~ → **배선 완료**(`ad6ab92`·`cd23aec`, 왕복 재마스킹으로 X-7 가드 무우회, 4/18 값 일치 — 문면 전문의 정본은 스냅샷). ~~X-9~~ → **해소**(`ef7b4a8` 생성기 + `04ced00` CI 배선). 게이트 11 종결 · 게이트 14 재실행분 교차 종합 | 게이트 11 · 게이트 14 교차 종합 | leader (2026-08-15, 게이트 15) |
| 스타일 규칙 포팅 (`app/easyread/style_rules.py`) | 예 | `ci:kotlin` · `ci:quality` | `style` · `style-tables` | `core/easyread/StyleRules.kt`·`GlossCollision.kt` + `StyleRulesTest`·`StyleRuleDataSnapshotTest`·`GlossCollisionTest` (커밋 `0c377a5`) — 사전 246(순서 포함)·파생 표·패턴 글로스 123, 07 리뷰 독립 재추출 **전건 일치**. 08 리뷰 m-4 반영.<br>**[게이트 15, 2026-08-15] 승격 유지 — 근거의 뒤 조각이 배선됐다.** 위 프롬프트 행과 같은 근거다(`04ced00` — `ci:quality` 의 `dump_python_snapshots.py --check`). 이 행에 걸린 파생 키 `GLOSS_COLLISION_PATTERN_GLOSSES`(123 항목) 단독 삭제가 **재생성 diff 0** 이던 것도 같은 커밋이 닫았다(무조건 재생성 → `[갈림]`·exit 1) | ~~fixture 미생성·미선언~~ → **배선 완료**(`ad6ab92`·`cd23aec`, `StyleRuleKind` 기계가독 필드 신설 — 계약·프롬프트 문안 불변 3중 확인). ~~X-9~~ → **해소**(`ef7b4a8` 생성기 + `04ced00` CI 배선). ~~C-3·C-4~~ → **해소**(`f73b8bd` 비교기 강화). 게이트 11 종결 · 게이트 14 재실행분 교차 종합 | 게이트 11 · 게이트 14 교차 종합 | leader (2026-08-15, 게이트 15) |
| 보정 채택 판정 포팅 | 예 | `ci:kotlin` | `repair-adoption` | `application/conversion/RepairDecision.kt`(판정식) + `ConvertDocumentUseCase.kt`(오케스트레이션) + 생산자 `application/src/test/.../conversion/ConversionParityTest.kt`. **parity 실측(2026-08-13): 성질 25건·단언 75개 전건 충족**. 호출 상한 2는 사후 계수가 아니라 **직선 코드 + `CompletionBudget`** 으로 강제한다 — 관대한 fake(응답 10건)를 줘도 정확히 2회에서 멈추고 8건이 남는 것을 단위 테스트가 단언. 전송 재전송과 완성 요청을 **따로 계측**(`FakeLlmProvider.transportAttempts`) | **갈림 후보 2건은 여전히 판정 대기** — ① 채택 판정식이 본문·팩트 손실을 보지 않는다(리더) ② 계약 `failure_code` 가 enum 대신 '예외 클래스명' 규칙이라 Kotlin 은 요구 수준 이름(`truncated`/`empty_result`/`provider_error`)을 쓴다(`contract-keeper`, §9-E) | - | kotlin-implementer (2026-08-13) |
| placeholder 보존 검사 포팅 | 예(변환 시점 산출만) | `ci:kotlin` | `repair-adoption` · `export` | `ConvertDocumentUseCase` 가 **채택된 최종 본문 기준**으로 산출하고 예외로 막지 않는다. `missing-placeholders-*` 4건 포함 25건 충족(2026-08-13). 내보내기 시점의 **복원**(`export`)은 별건이며 미포팅 | ~~**확인 필요**: `missing_placeholders` 산출이 어느 정본 도메인에도 미배정~~ → **2026-08-13 배정 판정 완료.** 도메인을 **신설하지 않고** `repair-adoption` 에 케이스 4건(`missing-placeholders-preserved`·`-dropped-reported`·`-basis-is-adopted-text`·`-partial-reports-only-lost`)으로 넣었다. 근거 4가지와 도메인 이름을 바꾸지 않은 이유는 `02_parity-verifier_conversion-spec.md` §4. `export` 는 내보내기 시점의 **복원**(`restore_placeholders`)을 그대로 맡는다 — 같은 자리표시자를 다루지만 다른 함수·다른 시점이다. **남은 미해결은 포팅 자체**(Kotlin 미구현) | - | parity-verifier (2026-08-13) |
| 내보내기 파일명·`Content-Disposition` 생성 포팅 | 아니오 | `ci:kotlin` | `export` | **배선 완료(2026-08-15)** — `dcfe04e`(core `Export.kt`: 파일명 정제·RFC 5987 ext-value·TXT 바이트, 복원은 `restoreForExport` 정본 유지) + `0c8516a`(단언 117개 ready — 값이 아니라 경계로) + 생산자·선언(`2839ec8`). 생산자 12/12 정본 일치. 바이트 해시는 TXT `content_sha256_hex` 하나뿐(zip 컨테이너 없음 — 미결 원장 준수) | 게이트 12 잔여: #4(파일명 over 방향 차단 단언 0 — 전부 대체 이름이어도 원장 갱신만으로 초록, parity-verifier 수정 중) · C-6(`take(80)` 선언 대 도달, kotlin-implementer 수정 중) | 게이트 12 잔여 | leader (2026-08-15, #8 집행) |
| LLM 응답 후처리 포팅 | 예 | `ci:kotlin` | `postprocess` | `core/easyread/Postprocess.kt` + `PostprocessTest` (커밋 `81f1d84`) — 후처리 29건(`keep_*` 9) 07 리뷰 독립 재추출 **전건 일치**. 08 리뷰 m-4(다섯 조각 내내 아무도 이 행을 안 건드림 — 과소 신고 방향) 반영 | ~~fixture 미생성·미선언~~ → **배선 완료**(`ad6ab92`·`cd23aec`, 8/12 값 일치·빈 결과 처분 경계 명시). 남은 것: 게이트 11 종결 | 게이트 11 | leader (2026-08-14 2차) |
| Python/Kotlin 공용 JSON fixture 생성 (`parity/fixtures/`) | 예 — 8/8 생성 · 8/8 ready | `local:uv run python .claude/skills/python-kotlin-parity/scripts/dump_parity_fixtures.py --domain masking --domain repair-adoption` | 전 도메인 | `parity/fixtures/masking/masking.json` **생성됨** (**83 케이스·단언 299개** — 2026-08-14 6차 개정 기준. 14 → 22(유니코드) → 31(보이지 않는 문자) → 57(표기 변형) → 69(구분자 문법) → **83**(TAB 전환·경계축)). 재현성 실측: 두 번 뽑아 `generated_at`(벽시계) 외 **바이트 동일**. 확장 시 기존 14 케이스의 `id`·`input`·`expected`·순서·`description` **전부 불변** 확인. 정본 대조(`provenance_problems`) 통과 — 새 케이스 기대값 손편집도 exit 1(`정본과 다르다`). 검출 실증 7종 — 정상 산출물 exit 3 / 값 1건 변조 exit 1 / 케이스 1건 누락 exit 1(`미실행`) / 자리표시자 번호 어긋남 exit 1 / `runtime` 비-kotlin exit 1 / **fixture 기대값 손편집 exit 1** / **fixture 케이스 삭제 exit 1**. **유니코드 확장 검출 실증 5종**(JVM 스탠드인, 대조군이 실제 `mask_text`와 22건 전건 일치함을 먼저 확인) — `java-default`(`\d`=`[0-9]`) exit **1**/6건 지목 / `java-ucc`(`UNICODE_CHARACTER_CLASS`만) exit **1**/1건 / `rrn-widened`(`[1-8]` 확대) exit **1**/1건 / `pre-strip-control`(마스킹 전 제어문자 제거) exit **1**/2건 / `faithful` exit **3**. **네 결함 모두 확장 전 14 케이스로는 0건 검출** — 공백이 실재했음의 증거다. CI 셸 실측 4종 — 현재 상태(선언 0개) exit 0(미가동 경고) / 선언 masking + 산출물 없음 exit **1** / 선언 masking + 산출물 정상 exit 0(부분 게이트 notice) / 산출물 틀림 exit 1. 명세는 `02_parity-verifier_masking-spec.md`(§3.3·§6.4·§6.5).<br>**2026-08-13 `repair-adoption` 생성 — 추출 목록 §G(변환 오케스트레이션) 집행.** `parity/fixtures/repair-adoption/repair-adoption.json` **25 케이스·단언 75개**(`equals_field` 64 · `equals_derived` 8 · `at_most` 3), 생성기 케이스 7건에서 **18건 확장**. 확장 안전성 실측 — 기존 7건의 `id`·`description`·`input`·`assert`·`reference` **전건 동일**·삭제 0·상대 순서 보존(확장 전 fixture를 `git show HEAD:` 생성기로 따로 뽑아 대조), 재현성 2회 덤프 `generated_at` 외 **바이트 동일**, 정본 대조 손편집 exit **1**(`$.assert: 길이 4 != 3`), `uv run pytest` **1061 passed/68 skipped** 무손상, ruff·`mypy . .claude` 통과. **검출 실증 7종**(대조군 먼저: 요구사항대로 구현한 `faithful` 과 현행 `ConversionService` 를 그대로 돌린 `python-verbatim` 이 25건 **전건 동일 산출** → 둘 다 exit **3**) — `always-reject-repair`(보정을 절대 채택 안 함) exit **1**/8건 · `all-or-nothing-placeholder-guard` exit **1**/1건 · `repair-failure-propagates`(보정 실패를 변환 실패로) exit **1**/3건 · `transport-counted-as-calls` exit **1**/1건 2단언 · `missing-from-first-draft` exit **1**/1건 · `repair-loop` 엄격 provider exit **1**(하네스 사망→산출물 없음)/관대 provider exit **1**/4건. **커버리지 검토가 자체 구멍 1건을 찾았다** — `missing-from-first-draft` 가 확장 24건을 통과(exit 3)해서 `missing-placeholders-basis-is-adopted-text` 를 추가로 넣어 닫았다(케이스 수 증가 ≠ 커버리지 증가의 실증). **`missing_placeholders` 도메인 배정 판정 완료** — 신설하지 않고 `repair-adoption` 에 케이스 4건으로 넣었다(근거: 하네스 1회 실행이 둘을 동시에 산출 · 보정 채택 결과가 유실 목록의 기준 본문 · `export` 는 복원이라 다른 함수 · 신설 비용 > 실익). 인벤토리 **§3.1** 신설(CNV-01·02·04 판정식)·**§9-E** 추가(실패 코드 문자열 정본 부재 → `contract-keeper`). 명세는 `02_parity-verifier_conversion-spec.md`.<br>**2026-08-14 masking 4차 개정 — `privacy-gate` 판정 1(`07_privacy-gate_masking-verdicts.md` §1) 집행.** **31 → 57 케이스 / 단언 114 → 214개.** 판정서가 실측으로 가른 두 결함(**종류 A** 성별코드 ASCII 리터럴 · **종류 B** 구분자 ASCII 리터럴) 중 **종류 B가 fixture에 0건**이었다 — 전각 하이픈 한 글자로 주민등록번호·카드번호가 **양쪽 다** 통과하는 상태였고, 이 도메인은 그때 '가장 잘 덮인 도메인'으로 분류돼 있었다. 신설 26건 = 종류 A 2 + 구분자 변형 RRN 9·CARD 9 + 성별코드 9·0 거부 가드 4(ASCII 2·전각 2) + 줄 갈림 가드 2(복귀 RRN·개행 CARD). **`known_gap` 1건을 검출 단언으로 전환해 이 도메인의 `known_gap`이 0건이 됐다** — 옛 제외 사유 *"Kotlin에 Python보다 넓은 구현을 요구하게 되므로"* 는 2026-08-12 재개발 전환으로 실효했고, *"판정 범위 밖"* 은 판정서 §1.3이 받아 **범주 포함**으로 닫았다. `input`·`reference` 를 그대로 두고 단언만 바꿔 `masking-rrn-unicode-digit-fullwidth` 로 이월(실측: `input 동일 True`·`reference 동일 True`). **참고 갈림 21건** — 요구사항이 현행 Python보다 넓어 갈리는 것이 정상이므로 `reference_divergence: "expected"` 로 선언하고 원장 `parity/reference-ledger/masking.json` 에 기록했다. 기준이 어느 쪽인지는 fixture `requirement` 헤더가 선언한다. 원장 절차 3단계 실측 — `--record-reference` exit **4**(`원장 변경 2건 / 원장 지적 22건 … 게이트를 닫지 않는다`) → diff 커밋 → 플래그 **없이** 재실행 exit **3**. 선언이 면제가 아님도 실측했다(선언 21건을 두고 원장 없이 돌리면 exit **1**). 확장 안전성 — 기존 30건 `id`·`description`·`input`·`assert`·`reference` **전건 동일**·상대 순서 보존, 사라진 기존 케이스는 전환된 1건뿐, 재현성 2회 덤프 `generated_at` 외 **바이트 동일**. **검출 실증 6종 — 스탠드인이 아니라 `./gradlew parityHarness` 가 만든 진짜 Kotlin 산출물에 회귀를 주입했다**(대조군 = 원본, exit **3**·불충족 0) — `ascii-separators-only`(= 현행 Python) exit **1**/18건 · `gender-ascii-literal` exit **1**/3건 · `gender-no-value-check`(넓히고 값 판정 누락 → 과잉) exit **1**/4건 · `separator-includes-newline`(`\s` 뭉뚱그림) exit **1**/3건 · `card-separators-forgotten`(구분자 집합이 두 벌로 갈라짐) exit **1**/9건. 마지막 것이 **RRN·CARD 대칭 배치가 없으면 아무것도 안 잡힌다**는 실증이다. Kotlin 재생성 `./gradlew parityHarness parityManifestCheck` exit 0(57건·`runtime: kotlin`), 선언 2도메인 동시 exit **3**(82건·289단언·불충족 0), `uv run pytest` **1061 passed/68 skipped** 무손상, ruff·`mypy . .claude`(129 files) 통과. **X-15(스냅샷 내 값 동일성 단언)** — 프롬프트 스냅샷 16건(system 6·user 5·repair 5)을 전수 훑어 표기 변형 29종·자리표시자 모양·Python 재계산 불일치를 검사한 결과 **전건 안전(잠복)**이고, 검사가 벙어리가 아님을 음성 대조로 확인했다(전각 하이픈 합성 입력 → `0xff0d` 검출). **코드는 고치지 않았다** — `_note` 는 생성기가 없는 생성물이라 손편집이 규약 위반이고(X-9), 단언 자체는 Kotlin 코드다. **X-9와 한 조각으로 처리하도록 `kotlin-implementer` 에 이관**하고 이 레인은 경계만 선언했다: **마스킹 요구 성질의 정본은 `parity/fixtures/masking/masking.json` 이고 프롬프트 스냅샷은 마스킹의 정본이 아니다.** 명세는 `02_parity-verifier_masking-spec.md` **§8**.<br>**2026-08-14 masking 5차 개정 — `privacy-gate` 판정 6(§4-ter · C-01·C-10·C-11) 집행.** **57 → 69 케이스 / 단언 214 → 255개 / 참고 갈림 21 → 29건.** 4차 개정이 구분자 **문자 집합**만 넓히고 **반복 상한**을 두지 않은 것이 반대 방향 결함을 만들었다(리뷰 08 Y-2 → C-10) — 열 맞춤 공백이 인접 칸의 두 숫자열을 결합 마스킹한다. 판정문이 준 유한 문법 `SEP := (?: SPACE? HYPHEN SPACE? \| SPACE? )` 하나가 과잉(C-10)과 남은 누락(C-01②)을 **동시에** 닫았고, 가르는 기준은 문자 종류가 아니라 **개수**다(자리당 0~1개 = 구분자, 2개 이상 = 정렬). 동결 12건 = 12탐침 신규 8(나머지 4는 기존 케이스가 담당) + 보충 평면 양성·음성 2(C-01① — 정규식은 코드포인트로 세고 가드는 UTF-16 `Char` 로 세던 **정합성** 결함) + VT·FF 비결합 2(C-11). `rrn-space-one`(가림)과 `keeps-rrn-space-two`(안 가림)가 **경계 짝**이다. **기존 57건은 하나도 뒤집히지 않았다** — 문법이 좁아지는 변경이라 전수 훑어 '자리당 공백 2개 이상' 입력이 **0건**임을 먼저 확인했다. 참고 갈림 8건 추가, 그중 **4건은 처음으로 `Python 이 과잉 마스킹하는` 방향**이다(`keeps-rrn-space-two/five` · `keeps-vt/ff-split-digits`) — 요구사항이 Python 보다 넓기만 한 게 아니라 **좁기도 하다**. **검출 실증 5종 — 수정 전 문법을 진짜 Kotlin 산출물에 주입**(대조군 exit **3**·불충족 0) — `pre-fix-unbounded-repetition` exit **1**/4건 · `pre-fix-char-gender-guard` exit **1**/1건 · `pre-fix-single-char-separator` exit **1**/3건 · `pre-fix-vt-ff-folded` exit **1**/2건 · `sep-not-shared-with-card` exit **1**/1건(해제조건 2 `SEP` 상수 공유를 **구현 밖에서** 검증하는 자리). **판정문 문구 불일치 1건을 회신 요청으로 올렸다** — §4-ter.4 조건 5가 *"2개 이상은 `absent`, 0~1개는 `present`"* 로 적었는데 이 하네스 검사 어휘로는 **반대**다(`absent` = 가려졌다). 정본인 §4-ter.2 12탐침 표의 `기대` 열을 따랐고 **판정문 파일은 건드리지 않았다**(privacy-gate 소유). 명세 §9.2.<br>**C-02(게이트 08 차단 ②장치) 집행** — parity CI 가 `declared_count=0` 에서 경고 + exit 0 이던 것을 닫았다. `.github/parity-declared-floor.txt` **신설**(선언 하한, 정본 하한과 같은 비대칭 검사) + 선언 0개를 **실패로 승격**. **만드는 중에 도달 0 을 스스로 잡았다** — 0개 검사를 하한 검사 뒤에 두었더니 어떤 경로로도 도달하지 않아 진단이 사라졌고(동작은 exit 1 이나 원인 문구가 다름), 순서를 앞으로 옮겨 각 검사가 고유 진단을 갖게 했다. **CI 셸 음성 대조 7종**(step `run:` 블록 212줄을 그대로 추출해 저장소 사본에서 실행) — 현재 상태 exit **0** / 선언 0개 exit **1** / 선언 1개로 축소 exit **1** / **선언+생산자 동시 제거 exit 1**(C-02 가 요구한 음성 테스트. 이전에는 Gradle `parityManifestCheck` 가 **불일치**만 봐서 둘을 함께 지우면 아무 데서도 안 걸렸다) / 하한 파일 삭제 exit **1** / 하한 비움 exit **1** / 하한까지 함께 축소 exit **0**(설계된 탈출구 — `.github/` 를 고쳐야 하므로 조용할 수 없다). 상세는 `01_parity-canonical-floor.md` **§7**.<br>**2026-08-14 masking 6차 개정 — `privacy-gate` 판정 8(§4-septies · K-1+M-06, 리더 승인) 집행.** **69 → 83 케이스 / 단언 255 → 299개 / 참고 갈림 29 → 32건 / `known_gap` 0 → 2건.** **게이트가 `masking-rrn-tab` 하나로 red 였다** — 결함이 아니라 승인된 **방향 전환**이고 fixture 가 옛 요구를 들고 있었다(TAB 이 `SPACE_CHARS` 에서 빠졌다). 그 자체가 "fixture 가 요구사항의 정본"이라는 규약이 작동한 증거다 — 방향이 바뀌면 fixture 가 먼저 빨개진다. **TAB 전환 4건**: `masking-rrn-tab` → `masking-keeps-rrn-tab`(입력 불변·방향만 뒤집음, `keeps-` 접두는 명명 규약) + 두 열 RRN·표 4열 카드·금액 4열 신규 3건(표 4열이 통째로 카드번호가 되던 과잉이 닫힌 자리). **이 전환은 누락 방향이고 그 대가를 케이스 설명에 명시했다** — `900101<TAB>-<TAB>1234567` 은 이제 안 가려지며 `keeps-rrn-space-two`(공백 2칸)와 **같은 자리**다. TAB 만 예외로 두면 "어느 조판 문자로 벌어졌는지에 따라 결과가 갈린다"가 되살아난다. **경계축 12케이스 동결** — 접기는 분리축(`SEP` 개수)에는 영향이 없고(폭 0 문자는 폭을 만들지 않는다) **경계축(lookaround)에서만 갈린다**(합집합이라 한쪽만 성립해도 가린다 → 폭 0 문자 **한 개**가 "긴 숫자열의 일부"라는 거부 근거를 무효화. 1,120조합 중 90건, 90/90 한 종류). **양성 3 · 음성 짝 3 을 반드시 인접 배치**했고(짝의 두 입력은 폭 0 문자 하나만 다르다) 판정 사유를 fixture 서술에 그대로 남겼다 — 따로 두면 다음 사람이 음성만 보고 "경계 검사가 있다"고 읽어 합집합을 지운다. 교차 3건(가시 0·1칸 가림 / 2칸 안 가림) + 비접기 경계 3건(CR·VT·FF)은 5차 개정분이 담당. **감수한 과잉 표면 2건을 `known_gap` 으로 기록** — `2021 2022 2023 2024`(연도 4열)가 카드번호로 가려지고 5열이면 앞 4개만 가려진다. **줄일 수 없는 모호성**이라(`1234 5678 9012 3456` 이 표준 표기라 공백 한 칸을 빼면 진짜 누락) `privacy-gate` 가 **판정하지 않고** 넘겼고 **교차 검증이 없다**(실측 1건). `absent` 를 걸면 과잉을 요구사항으로 굳혀 카드 패턴을 좁히는 개선이 회귀로 잡히고, `present` 를 걸면 판정되지 않은 방향을 이 레인이 대신 정하는 것이라 **어느 쪽도 단언하지 않았다**. ⚠ 이 자리는 "판정 요청을 받고 명시적으로 판정하지 않기로 한" 상태라 명세 §2.4 의 `known_gap` 정의("아직 판정을 요청하지 않은 자리")와 어긋난다 — 숨기지 않고 §10.3 에 적었다. **M-08(fixture 중복 입력 미검출) — 검출을 넣었다.** 같은 입력은 같은 산출물을 내므로 두 케이스가 같은 것을 두 번 재고, 성질은 안 늘면서 케이스 수만 늘어 **커버리지 대리 지표를 부풀린다**(리포트의 `성질 판정 N건`). 손편집은 정본 대조가 이미 막으므로 이 검사가 받는 것은 **생성기에 중복이 들어오는 경로**다. 현재 두 도메인 중복 **0건**(예방적). 음성 대조 — 생성기에 같은 입력 케이스를 주입하면 exit **1**(`입력이 masking-empty 와 같다`), 복원 후 exit **3**. **Z-7(C-02 음성 대조가 1회성·대리 경로) — 갈라서 처리했다.** 상시 테스트로 구현한 것: 가드 구간(정본·선언 하한, 선언 0개) → `tests/test_parity_ci_gate.py` **7종·1.3초**, `ci.yml` 을 **매 실행 다시 읽어** step 을 뽑으므로 블록 드리프트가 잡힌다. **테스트 자신의 음성 대조**도 했다 — 선언 하한 대조를 무력화하면 **2건 실패**, 되돌리면 7 passed. 대리 경로로 **남는 것**(한계 선언): ① 워크플로 배선(step 이 어느 job 에 있는지·`if:` 조건·job 이 도는지) ② 러너 환경(ubuntu bash·`uv`) ③ 가드 뒤 비교 구간(`parity/actual/` 이 `.gitignore` 대상이라 있는지에 따라 결과가 달라지는 테스트는 회귀로 못 쓴다). 상세는 `01_parity-canonical-floor.md` **§8**.<br>**2026-08-14 게이트 10 리더 판정 집행 — 케이스 정체성 장치(J-1+J-3) + 잔여 도메인 fixture(J-4).**<br>**① 케이스 정체성 장치.** `.github/parity-case-floor.txt` 신설 — 하한을 **개수가 아니라 케이스 id** 로 둔다(`86c6a99` "개수를 정체성으로 바꾼다"를 그대로 따름). 비대칭: 추가 자유·**삭제와 개명 차단**. **개명이 실증이 됐다** — 보류 케이스 2건 id 를 `known-gap-*` → `deferred-*` 로 바꾸자 게이트가 잡았고 메시지가 `83건을 요구하는데 fixture 에는 83건이 있다` 였다(**개수는 같은데 정체성이 갈린 상태** — 개수 하한이었다면 통과). **`known_gap` → `verdict_pending` 탐지형 전환**(R-4): `reason`·`owner`·`deadline`·`referred_by` **필수**, 옛 키가 남아 있어도 차단. 판정 수에서 **분리**(`성질 판정 106건 / 판정 보류 2건`) + 리포트에 소유자·기한·회부 표. **음성 대조가 구멍을 하나 찾았다** — 마커만 떼면 케이스가 조용히 `성질 판정` 수로 넘어가는데(106→108) 아무것도 안 막았다. 하한 줄에 ` !deferred` 꼬리표를 붙여 **보류 상태까지** 기억하게 해 닫았다. **음성 대조 5종**: 정상 exit 3 / 개명 exit **1** / 케이스 삭제 exit **1** / `owner` 빈 값 exit **1** / 마커 제거 exit **1**. **§2.4 정의 어긋남(직전 회차에 내가 신고) 해소** — 마커의 뜻이 "아무도 안 봤다"에서 **"열린 결정의 소유권"**으로 바뀌어 세 상태(안 봤다/회부돼 대기/회부돼 판정 유보)가 필드로 갈린다. 상세 `02_parity-verifier_case-identity.md`.<br>**② 잔여 도메인 fixture.** `style`·`style-tables`·`prompts`·`postprocess` 를 **pending → ready** 로 올렸다(`text` 는 이미 ready). **fixture 8도메인 · 케이스 151 · 단언 443**. 도메인마다 **판정하지 않는 것**을 먼저 갈랐다 — `style` 은 문장 분리 경계를 판정하지 않고(산출물이 보고한 `sentences` 에 규칙만 다시 적용. 유도 규칙 `style_length_rule`·`style_comma_rule` 신설, 정책 상수 50·2 는 **비교기가 자기 힘으로** 들고 있다), `prompts` 는 **문면 전문을 판정하지 않는다**(그 정본은 프롬프트 스냅샷 — X-15 경계를 반대 방향으로도 지킨 것), `postprocess` 는 빈 결과의 **처분**을 판정하지 않는다(`repair-adoption` 몫). `style-tables` 는 정책 상수만 값으로(`equals_field`) 잡고 큐레이션 표 7종은 포함 관계로(`contains_all`) 봐 **개선이 회귀로 잡히지 않게** 했다. **직전 회차에 내가 넣은 M-08 중복 검사가 나를 먼저 물었다** — `style-tables` 두 케이스의 `input` 이 똑같이 비어 있어 같은 산출물을 두 번 재고 있었고, 한 건으로 합쳤다. **`prompts` 재현성 결함도 이때 드러났다** — 난수 문서 id 가 `reference` 에 들어가 매 덤프 fixture 가 달라졌다(정본 대조가 **영구히 깨지는** 상태). 생성기에서 `<ID>` 로 고정했다. **검출 실증 8종**(대조군 `python-verbatim` 5도메인 전건 exit 3·불충족 0) — `text` 탭 제거 exit **1** / `style` 완전성·건전성 각 exit **1** / `style-tables` 표제어 삭제·정책 상수 변경 각 exit **1** / `prompts` 원칙 제거·자리표시자 파괴 각 exit **1** / `postprocess` 본문 제거 exit **1**. **선언과 생산자는 `kotlin-implementer` 인계**(같은 커밋 규약) — 이 레인은 fixture 와 하네스 계약까지만 만들었다. `export` 는 Kotlin 구현이 없어 pending 유지. 상세 `02_parity-verifier_domain-fixtures.md`.<br>**2026-08-14 게이트 11 비교기 몫(X-2·X-3·X-4·C-4) 집행.** **X-2·X-3(합의 차단)** — 케이스 하한이 `pairs`(비교 범위)를 순회해 부분 게이트에서 **선언 151 중 139만 도달**했고, 선언하지 않은 `export` 12건은 하한·형태·값 어느 것도 안 받았다(12→3 축소해도 지적 0건 재현). 하한 대조 대상을 **fixture 실물 전체**(`floor_scope`)로 바꿨다 — 하한은 "무엇을 비교했는가"가 아니라 **"무엇이 남아 있는가"**의 검사라 비교 범위와 다른 축으로 읽어야 한다. `spec_shape_problems` 의 `pending` 조기 반환도 열어 **중복 입력·보류 마커 형태**는 pending 도 받게 했다(단언을 전제로 하는 둘 — 단언 없는 케이스·방향 가드 — 은 제외. pending 은 정의상 단언이 없어 그것을 걸면 상태 자체가 결함이 된다). **codex 사각 닫음** — 하한에 `bogus/placeholder` 한 줄을 넣어도 지적 0건이었다(없는 도메인을 조용히 건너뜀). 빈 선언을 막는 검사는 있었는데 **무의미한 선언**을 막는 검사가 없었다 — 실물에 없는 도메인 선언을 차단한다. **X-4(충돌·리더 판정)** — 유도 **로직**은 독립이었으나 **입력**이 산출물 자기 보고라, 생산자가 문장을 버리면(`sentences: []`) 유도값도 비어 **양쪽이 사이좋게 0**이 되어 통과했다. 비교기가 **fixture 입력을 직접** 종결부호로 갈라 **더 쪼갤 수 없는 구간**을 얻고 그중 상한 초과분을 **하한**으로 요구하는 `contains_derived` + `style_length_floor`·`style_comma_floor` 를 더했다. 구간 안에 종결부호가 없으므로 **어떤 분리기도 더 잘게 쪼갤 수 없어** 문장 분리를 판정하지 않고도 성립한다(하한이지 정답이 아니다). **C-4(codex 단독)** — `contains_all` 이 표제어만 봐서 뜻풀이를 통째로 바꿔도 통과했다. 값은 프롬프트에 그대로 실려 모델에게 가고 246개는 실측 큐레이션이라 **값이 곧 자산**이다. `contains_entries`(표제어+값 쌍, 추가 허용·변경 차단)를 더했다. **검출 실증 4종**(대조군 = 7도메인 실제 Kotlin 산출물, exit **3**·성질 137건·단언 458개·보류 2건·불충족 0) — `bogus` 선언 exit **1** / `export` 케이스 삭제 exit **1**·9건 지목 / `style` 문장 버림 exit **1**(옛 `equals_derived` 로는 통과하던 형태) / `style-tables` 뜻풀이 변조 exit **1**(옛 `contains_all` 로는 통과, `가료: '치료' → '엉뚱한 뜻풀이'`). **부기 — 복원 사고 1건**: `cp` 백업이 기존 파일을 덮지 않아(`cp -iv` 별칭) 낡은 사본으로 되돌리면서 케이스 하한이 **151 → 108 로 깎였다.** `git status` 로 즉시 발견해 `git checkout` 으로 복원했고(151 확인), 이후 복원은 전부 git 으로만 했다. `CLAUDE.md` 2026-08-13 이력의 실패가 같은 형태로 재현된 것이다. 상세 `02_parity-verifier_case-identity.md` **§9**.<br>**2026-08-14 `export` ready 승격 — 8/8 도메인 ready.** 구현자가 core 포팅(`dcfe04e`)과 생산자까지 만들고도 **정본이 `pending`(12건 전부 단언 없음)이라 선언하지 못하던** 자리를 닫았다(선언하면 exit 2, CI 는 사면하지 않는다). `build_export` 에 단언 **117개** 작성 + `STATUS_READY` 승격. **값이 아니라 경계로 판정한다** — 파일명 정제 **결과**를 못박으면 공백 접기·앞뒤 점 깎기 규칙까지 값으로 고정돼 규칙 개선이 회귀로 잡히므로, 위험 문자 **부재**(`absent`)·확장자 **보존**(`present`)·길이 **상한**(`max_length` 신설, 상한은 `_MAX_FILENAME_STEM` 에서 읽어 리터럴 중복을 피함)만 건다. **`ascii_only` 신설** — RFC 5987 `ext-value` 는 비ASCII를 퍼센트 인코딩해야 하고, 안 하면 "한글이 깨진다"가 아니라 **응답 헤더 자체가 나가지 않는다**. 헤더 전문을 값으로 박으면 인코딩 방식 변경이 회귀가 되므로 요구("US-ASCII 안")만 건다. TXT 는 zip 컨테이너가 없어 **본문 UTF-8 바이트 = 파일**이라 BOM·NUL 부재와 제목 줄 미첨부를 값으로 판정한다. **자리표시자 복원** — 구현 정본을 `Masking.kt::restoreForExport` 하나로 둔 구현자 판단을 **확인했고 옳다**(두 벌이면 한쪽만 고쳐진다). 케이스는 export 에 남겼다 — 성질이 깨졌을 때 피해가 나는 자리가 내보내기이고(자리표시자가 남은 채 기관 밖으로 배포), 마스킹의 `restores_input` 은 마스킹 **시점**의 왕복이라 다른 성질이다. 세 성질을 명시했다: 모르는 자리표시자는 **그대로 둔다**(지어내면 없는 개인정보를 만들고 지우면 본문이 사라진다) · 치환은 **한 번만** 돈다(돌면 사용자 본문이 개인정보로 바뀌는 **주입 경로**) · 등록된 것은 복원. **검출 실증 6종**(대조군 `python-verbatim` exit **3**·12건·117단언·불충족 0) — 경로 구분자 미정제 / 길이 상한 없음 / 헤더에 한글 원문 / 모르는 자리표시자 삭제 / 다중 패스 치환 / BOM 첨부 — **전부 exit 1**. **⚠ 목표값 정정**: 정본 하한이 8개라 export 선언 순간 `declared_count == canonical_count` 가 되어 **전체 게이트**로 돈다 — 통과 조건은 **exit 0**이고 그때부터 3은 실패다. "8/8 exit 3"은 성립하지 않는 조합이다(구현자 지적이 옳다). 케이스 하한의 export 12건은 **이미 들어 있었다** — 직전 X-2 수정(하한 도달을 fixture 실물로) 덕분에 pending 시절에도 지켜지고 있었다. **인계 확인** — 승격 후 구현자 탐지기를 직접 돌려 `ParityDeclarationSyncTest … Expecting empty but was: ["export"]` 로 **실제로 빨개지는 것**을 확인했다. 그 red 가 인계 신호이고, 구현자가 `@Tag("parity")` + 선언 2파일을 같은 커밋에 넣으면 닫힌다. **부수 결함 1건 자체 발견·수정** — 직전 X-2 수정이 **도메인 디렉터리 지정 실행**에서 나머지 7개 하한 도메인을 "실물 없음"으로 오지목했다. 좁혀진 범위에서는 무의미-선언 검사를 하지 않도록 고쳤고 `bogus` 사각이 여전히 닫혀 있음을 함께 확인했다. 상세 `02_parity-verifier_domain-fixtures.md` **§7**.<br>**2026-08-14 masking 7차 개정 — `privacy-gate` §4-decies.4(리더 승인) 집행.** **83 → 84 케이스 / 단언 299 → 304 / 갈림 32 → 35 / `verdict_pending` 2 → 0.** **연도 배열 2건 방향 확정** — 보류를 닫되 **현행 동작 쪽으로 닫지 않았다.** `absent`(가림)로 얼리면 Luhn 도입이 **회귀로 잡히므로** 요구사항 방향 `present`(가리면 안 됨)로 걸었다(§8.2 "단언이 구현보다 앞선다"와 같은 방식). id 를 `deferred-*` → `keeps-*` 로 옮기고 케이스 하한의 ` !deferred` 꼬리표를 지웠다 — **이 도메인의 보류가 0건이 됐다.** 근거는 실측이다: 실문서 카드형 16자리 적중 30건 중 **Luhn 통과 1건**, 단일 공백 4×4 25건은 **전부 Luhn 실패**(그중 14건이 연속·인접 연도). **Luhn 대응 카드 값 교체** — 판정서가 "가장 놓치기 쉬운 자리"로 지목한 함정이다. 기존 표본 `1234-5678-9012-3456` 은 Luhn 실패라 `acceptsLuhn` 이 들어오면 가려지지 않는다. **규모를 실측했다 — 옛 fixture 에서 16자리 카드를 `absent` 로 단언하던 케이스 19건**이고 현행 Kotlin 은 그 값을 가리지 않는다(즉 교체 없이는 19건이 전부 불충족). 전 카드 표본을 `4111-1111-1111-1111` 계열 Luhn 유효값으로 옮겼고 양성 22건 전건 유효를 기계 확인했다. **음성 케이스도 함께 옮겼다** — Luhn 실패값을 남기면 두 겹으로 보호돼 구분자 규칙이 무너져도 통과한다. **신규 음성 1건**(`keeps-card-luhn-invalid`) — 양성을 전부 유효값으로 옮긴 지금 **도입 전후를 가르는 것은 이 케이스 하나뿐**이다. ⚠ **기존 케이스 보존 규약과의 충돌 처리**: 규약의 목적은 "검증하던 것이 조용히 사라지는 것"을 막는 것이지 입력을 성역으로 두는 것이 아니다 — 그대로 두면 오히려 19건이 검증을 멈춘다. **id·서술은 불변, 표본값만 이동**했고(성질은 한 글자도 안 바뀜) 원장 해시 변경이 그 교체를 리뷰에 올린다. **검출 실증** — 대조군 실제 Kotlin 7도메인 exit **3**(성질 140건·단언 467개·보류 0·불충족 0), Luhn 훅을 되돌린 주입 exit **1**/3건. **Kotlin 은 이미 Luhn 을 반영했다**(`Masking.kt::acceptsLuhn`) — 세 신규 케이스가 전부 충족이고 Python 만 갈린다(요구사항 쪽이 Kotlin). **남은 반대 방향 위험**: OCR·오탈자로 한 자리가 깨진 카드는 Luhn 을 통과 못 해 마스킹을 빠져나간다 — 코퍼스 관측 0건이라 fixture 에 고정하지 않았고 파일럿에서 카드 유입이 관측되면 재판정한다. 상세 `02_parity-verifier_masking-spec.md` **§11**.<br>**2026-08-15 게이트 12 비교기 몫(#4·#3) 집행.** **#4(Phase 2 저지)** — export 파일명 축에 **over 방향 차단 단언이 0개**였다. 재현: 파일명 21개를 전부 대체 이름으로 바꿔도 **성질 불충족 0건**(불충족 6건은 전부 원장 갈림) — 사용자 제목이 통째로 사라져도 성질 판정은 초록이었다. 처방은 **X-4에서 만든 입력 유도 하한을 파일명 축으로 옮긴 것**(`contains_derived` + `export_title_markers`). `equals_field` 로 정제 결과를 못박지 않으므로 §7.1의 "규칙 개선이 회귀로 잡힌다" 우려는 걸리지 않는다. 하한이 성립하는 근거 둘 — ① 금지 문자·공백·점으로 **가른 뒤**의 조각이라 정제가 조각 **안**을 건드리지 않는다 ② 제목 앞 40자·조각 8자만 쓰므로 정제(길이를 줄이기만 한다)와 80자 상한을 통과한다. 제목이 전부 금지 문자인 표본은 표지가 비어 하한이 무력하므로 **대체 이름 단언**을 따로 붙였다(별도 케이스로 만들려다 **M-08 중복 검사가 막았다** — 입력이 같아 같은 산출물을 두 번 재는 구조였고 지적이 옳아 합쳤다. 한계: 대체 이름은 계약이 아니라 구현 상수가 기준이다). 검출 실증 — 대조군 exit **3**(12건·141단언·불충족 0) / 전체 대체 이름 exit **1**·6건 / 확장자만 남김 exit **1**·7건. **#3(차단②)** — 전체/부분 게이트가 `declared == canonical`(BUILDERS 키)로 계산돼, **builder 하나만 늘어도 부분 게이트로 내려가 exit 3 사면이 되살아난다.** "8/8은 강제된 성질이 아니라 오늘 참인 상태"라는 판정 그대로였다. `.github/parity-full-gate.txt` 로 **전체 게이트 도달 상태를 하한으로 고정**했고, **자기 무장**으로 만들었다 — 도달했는데 표시가 없으면 exit **1**("고정하라"), 표시가 있는데 내려가면 exit **1**("내려왔다"). 도달하는 순간 비교기가 요구하므로 **도달 0인 채 잠드는 장치**가 되지 않는다. 음성 대조 4종 — 현재(7선언) exit **3** / 8선언·표시 없음 exit **1** / 표시 후 7선언 강등 exit **1** / **가짜 builder 추가(정본 9) exit 1**(리뷰가 지목한 시나리오). **자기 무장이 실제로 발화했다** — 이 작업 중 `export` 가 선언되어(`adb4197`) 선언 8이 됐고, 다음 실행이 `전체 게이트에 도달했다 — 하한을 고정하라`로 **exit 1** 막았다. 사람이 기억해 만드는 것이 아니라 게이트가 요구했다. `.github/parity-full-gate.txt` 를 만든 뒤 강등 음성 대조(선언 7 되돌림)가 `전체 게이트에서 내려왔다: export` 로 막는 것을 확인했다. **그리고 전체 게이트가 닫혔다 — `--only-domain` 없이 돌린 실행이 종료 코드 0**(`요구 성질 충족: 도메인 8/8 / 성질 판정 152건(단언 608개) / 판정 보류 0 / 미검증 0 / 불충족 0`). **이 시점부터 종료 코드 3은 통과가 아니다.** ⚠ **복원 사고 2건째(규칙 5)** — 가짜 builder 프로브를 `git checkout` 으로 되돌리다 같은 파일의 **#4 미커밋 변경을 함께 지웠다.** 즉시 발견·복구했고(grep 0 확인 → 재적용 → 대조군 exit 3 재확인), 지난 `cp` 별칭 사고와 **기제는 다르고 형태는 같다 — 복원 대상이 프로브보다 넓었다.** 규칙으로 옮긴다: 프로브로 제품 파일을 고쳤으면 되돌리기 전에 **미커밋 작업 유무를 먼저 보고**, 있으면 주입한 조각만 역으로 지운다(가장 싼 예방은 프로브 전 커밋). 상세 `02_parity-verifier_case-identity.md` **§10**. **C-21** — repair-adoption 판정이 미선언 `style` 도메인에 의존한다는 **조건부 판정 주의**를 fixture `requirement` 헤더에 표기했다(케이스 설명·문서에만 적으면 산출물을 읽는 사람에게 닿지 않는다). **C-24** — `_harness-selfcheck` 의 `purpose` 가 *"Phase 1 배선 증명 전용"* 이라 거짓이 된 것을 현행화했다(`ParityHarnessSelfCheck.kt`, 하네스 코드라 이 레인 소유). **C-20 — 하지 않는다**: 레거시 시나리오 2건 대본화는 ① 기존 케이스 `input` 을 바꿔 확장 규약을 어기고 ② 커버리지가 늘지 않는다(같은 성질을 대본 케이스가 이미 더 강하게 판정). 사유는 `02_parity-verifier_conversion-spec.md` §9.2. 검증: 두 도메인 재현성 2회 덤프 `generated_at` 외 **바이트 동일** / `./gradlew parityHarness parityManifestCheck ktlintCheck` exit **0** / 선언 2도메인 게이트 exit **3**(94건·330단언·불충족 **0**·미검증 0) / `uv run pytest` **1091 passed·68 skipped** / ruff·`mypy . .claude`(130 files) 통과 | 나머지 6개 도메인 미생성 — **의도적**(도메인은 검증에 들어가는 시점에 뽑는다. 미리 뽑으면 `style_rules.py` 변경 때 아무도 안 보는 도메인이 정본 불일치로 빨개진다). 전체 게이트는 지금 돌리면 exit 1(도메인 누락 10개)이며 이는 사실 그대로다. ~~**fixture 커버리지 공백**~~ → **닫힘(2026-08-12)**. 초판 §6.2의 "유니코드 공백 12종"은 **과소 집계**였다 — 전 코드포인트 재측정 결과 Python `re`의 `\s`는 **29종**이고 Java 기본이 놓치는 것은 6종이 아니라 **23종**, `UNICODE_CHARACTER_CLASS`로도 못 잡는 잔여가 **4종**(U+001C~1F)이다. 남은 한계: 이 게이트도 "그 산출물을 Kotlin이 만들었는가"는 증명하지 못한다(CI 배선이 유일한 방어). **`repair-adoption` 잔여 한계 3건** — ① 시나리오 17건은 참고값(`reference`)이 없어 갈림이 원장에 자동 기록되지 않는다(실패 경로를 Python이 값이 아니라 예외로 내므로 참고값을 지어 넣으면 두 번째 기대값이 된다. 대신 관측 필드를 `equals_field` 로 못박아 갈리면 성질 불충족으로 드러난다) ② 갈림 후보 2건이 **리더·`contract-keeper` 판정 대기** — 보정 악화 가드가 본문·팩트 손실을 보지 않음 / 계약 `failure_code` 가 enum 대신 "예외 클래스명" 규칙이라 값의 정본이 Python 구현이다(§9-E) ③ 도메인 **이름**(`repair-adoption`)이 범위(변환 오케스트레이션 전체)보다 좁다 — 이름 변경은 하한 파일 규칙상 삭제+추가라 하지 않았고, 범위의 정본은 fixture 헤더 `requirement` 다. **masking 잔여 한계 2건** — ① 판정서 §1.7 해제조건 4의 **`docs/golden/` 실문서 마스킹 건수 대조는 이 레인이 하지 않았다**(실문서라 산출물·리포트에 본문이 실릴 위험이 있어 `privacy-gate` §5.5 절차가 맞다) ② 판정서 §1.7 해제조건 3(`UnicodeRegex.kt` KDoc 종류 B 반영)은 `kotlin-implementer` 몫으로 열려 있다 ③ **판정문 §4-ter.4 조건 5의 `absent`/`present` 표기가 이 하네스 어휘와 뒤집혀 있다** — 정본인 12탐침 표를 따랐고 `privacy-gate` 회신 대기. 내가 잘못 읽은 것이면 12건의 방향이 뒤집힌다 ④ **`style` 미선언으로 repair-adoption 판정이 조건부다**(C-21) — 규칙 위반 건수의 정확성은 판정 범위 밖 ~~⑤ `known_gap` 2건이 판정 대기 상태를 `known_gap` 으로 표기~~ → **해소(2026-08-14)** — `verdict_pending` 필수 필드 체계로 교체 ~~⑦ 다섯 신규 도메인은 아직 Kotlin 산출물로 판정된 적이 없다~~ → **해소(2026-08-14)** — 생산자 배선(`cd23aec`, kotlin-implementer) 후 **7도메인 실제 산출물로 판정**했다(성질 137건·단언 458개·불충족 0) ~~⑤·연도 배열 `verdict_pending`~~ → **해소(2026-08-14 §4-decies.4)** — `present` 로 방향 확정, 보류 0건 ⑨ **Luhn 을 통과 못 하는 손상 카드번호는 마스킹을 빠져나간다** — 측정된 이득(오탐 29건 제거)과 가설적 손해(관측 0건)를 같은 무게로 놓지 않은 판정이고, 파일럿 관측 시 재판정 ⑧ **문장 분리를 조작하는 입력은 여전히 하한을 낮춘다**(X-4 잔여) — 하한이 막는 것은 *보고를 버리는 것*이지 *분리를 조작하는 것*이 아니다. 후자를 막으려면 문장 분리를 판정 대상으로 삼아야 하고 그것은 요구사항이 명시적으로 하지 않기로 한 자리다 ⑥ **CI 게이트의 워크플로 배선·러너 환경은 상시 테스트로 덮이지 않는다**(Z-7 한계 선언) — 최종 방어선은 첫 push 의 실제 러너 실행이다 | - | parity-verifier (2026-08-14) |
| 도메인마다 `backend-kotlin/parity-domains.txt` 선언 + Kotlin parity 테스트가 `parity/actual/` 산출 | 예 — 8/8 선언·산출 | `ci:kotlin` | 전 도메인 | **2026-08-13 첫 배선**(masking·repair-adoption, 성질 56건·단언 189개) → **2026-08-14 5도메인 확장**(`cd23aec` — text·style·style-tables·prompts·postprocess 생산자 + 선언 + declared-floor 같은 커밋). 실측: 선언 7개 전부 산출물 확인 · 선언 범위 비교 exit **3**(7/7 · 성질 137건·단언 437개 · 불충족 0 · 판정 보류 2). 입력 하네스 `ParityFixtures`는 fixture 를 파일에서 읽는다. `style`은 기계가독 필드(`length_violations`·`comma_violations`, `StyleRuleKind` 기본값 없음) 신설. 갈림 1건(`style-tables` 집합 순서 — 정렬로 덮지 않음) 원장 기록 | `export` 1개만 미선언(Kotlin 내보내기 미구현 — Phase 4 조각). **부분 게이트(exit 3)는 전체 통과가 아니다.** 게이트 11 지적 계류: C-3(style 생산자 문장 유실 통과)·C-4(사전 값 미검사)·C-2/export 하한 CI 도달 0 — 교차 종합 후 처분 | export 조각 · 게이트 11 | leader (2026-08-14, 게이트 11 Claude 리뷰 원장 모순 지적 반영) |
| **종료 조건**: 외부 API·DB 없이 도는 parity suite 가 양쪽에서 같은 결과 | 예 | `ci:kotlin` | 전 도메인 | **범위 8/8 — 전체 게이트 exit 0, 러너 실측(2026-08-15, run 31854263996 kotlin 잡: 성질 153·단언 630·불충족 0 — 게이트 14 Claude 리뷰가 러너 로그로 확인).** ~~584~~ → ~~608~~ → **630**(게이트 13 배치의 fixture 확장 반영 — 수치 드리프트 두 번째 재발이라 이후 이 칸은 러너 로그를 정본으로 인용한다, 게이트 14 F-2). ~~범위 7/8, exit 1(`parity/actual/export/` 부재)~~(2026-08-14 기록 — export 조각으로 해소) | 충족을 `예` 로 못 올리는 잔여(게이트 12 교차 종합): **A** #1 CARD 겹침 삼킴 회귀(차단①, 수정 중) · **B** CI 러너 도달 — ~~"동기화 완료"는 게이트 13 시점 거짓이었다(7커밋 미푸시)~~ → **2026-08-15 해소 진행**: 푸시(`cb26a77`) + **draft PR #1 생성 → CI 첫 실행 in_progress**(run 31838885595). 결과 확인이 남음 · **C** #4 export over 단언 0(수정 중) · #3 사면 부활은 차단②(마감 지금, 발화는 이후) | 게이트 12 잔여 A·B·C | leader (2026-08-15, #8 집행) |

### Phase 2 진행 기록 (리더, 2026-08-13~14)

**재개발 조각 순서** (사용자 방향 전환 2026-08-12 이후): ① core 도메인+LLM 경계(커밋 `c11a404..f73879b`, 5건 — 사전·스타일·마스킹·복원·프롬프트·주입방어·후처리·LlmProvider·Anthropic 어댑터) → ② application 변환 유스케이스+parity 배선(`f73879b..6d8e88c`, 6건) → ③ 후속 수정(`bed5300` toString 3종, `3934f06` masking fixture 4차). 다음 조각: **api 엔드포인트**.

**리뷰 게이트 이력** (어간은 기존 관례(순번_주제)를 따랐다 — SKILL.md `{scope}` 정본 표와의 정합은 하네스 점검 별건. 게이트 13 지적으로 03~06 역사 행을 소급 기재 — 그 회차들은 재개발 코딩 전 하네스·계획 점검이라 이 표 신설 전에 끝났다. **10~14는 표가 아니라 이 절 아래 산문 문단으로 기록돼 있다** — 표의 03~09 다음이 15인 것은 누락이 아니다):

| 어간 | 대상 | 상태 | 정본 |
|---|---|---|---|
| `03_rebuild-plan` | 재개발 전환 계획(추출 목록·progress) | 계획 심사(codex-plan-reviewer 단독) — needs-attention, 지적은 계획에 반영 | `reviews/03_rebuild-plan_codex-plan-reviewer.md` |
| `04_quality-gate` | 품질 합격선 계측기 | 3단계 완주 | `reviews/04_quality-gate_cross.md` |
| `05_scope-reach` | 실행 경로 규약·표기 검사기 | 3단계 완주 | `reviews/05_scope-reach_cross.md` |
| `06_baseline-guard` | 골든셋 하한선 가드 | 3단계 완주 | `reviews/06_baseline-guard_cross.md` |
| `07_core-rebuild` | `c11a404..f73879b` | **3단계 완주.** 합의 4·codex 단독 2·Claude 단독 12·상충 2(리더가 privacy-gate 회부로 처분). 조치 18항 중 이번 조각에서 X-1·X-2·X-5탐지기·X-6·X-7·X-8·X-16·X-17 처리, X-9(스냅샷 생성기)·X-4(모듈 경계) 등 잔여는 §6 마감대로 | `reviews/07_core-rebuild_cross.md` |
| `08_conversion-usecase` | `f73879b..6d8e88c` | **3단계 완주.** 27건 → 24행: 합의 2 · 부분 합의 1 · 충돌 1(C-09) · codex 단독 7 · Claude 단독 13 — **24행 중 21행이 한쪽만 봤다**(두 모델의 관심 표면이 거의 안 겹침). **종합 차단 성립**: ①사건 C-01(보충 평면 숫자·복합 카드 구분자 실측 통과) + ②장치 3건(C-02 parity 선언 0 exit 0 · C-03 스캐너 다중 줄 미탐지 · C-04 루트 부재 무시 — **별개 결함, 병합 안 함**). 한쪽이 닫은 자리를 다른 쪽이 연 곳 4건(X-8·CNV-02/04·X-5·모듈 경계) 재개방. `bed5300`·`3934f06` 해소 검증은 다음 회차(C-06·C-12 구현분) | `reviews/08_conversion-usecase_cross.md` (정본) |
| `09_masking-grammar` | `6d8e88c..c61c94e` | **3단계 완주** (2026-08-14). 합의 2 · 충돌 3 · codex 단독 3 · Claude 단독 17 — 24행 중 20행이 한쪽만 봄(**한쪽 레인만 돌렸으면 게이트가 닫혔을 구성**). **§4-ter 문법 본체는 두 레인 합의로 해제**(방법 비겹침 — 12탐침 재구성 vs 301조합 전수). **차단 ②장치 3건**: M-01(`_SAFE_ACCESS`가 점 연속만 — ktlint 표준 표기가 안전 판정) · M-02(`--tests` 집합 의미론) · M-03(논리 줄 40줄 fail-open·raw string). 평문 로그 탐지기 우회 세 갈래(M-01·M-03·M-09) → 탐지기 단위 재설계 배치 진행 중. K-1(탐색 뷰×SEP 합집합)은 KDoc이 의도된 설계로 명시 — 결함 통보가 아니라 **privacy-gate 방향 판정**(M-06 TAB과 묶음, 진행 중). 리더 판정: M-04 승격(감시 집합 자기 파생 — 수정, M-02와 같은 배치) · M-07 = C-09 재적용(변경 없음, M-08만 분리 → parity-verifier). codex 미언급 6건(C-06·C-12구현분·C-18 등)은 Claude 단독 근거 해제로 표기 | `reviews/09_masking-grammar_cross.md` (정본) |
| `15_phase3-preflight` | `5797d87..614afed` (6 커밋) | **3단계 완주** (2026-08-15). codex `needs-attention` — findings **8건 전부 high** / Claude **20건**(차단 2 · 수정 필요 8 · 권고 8 · 판정 필요 1 · 적정 1) / cross **23행**(합의 3 · 정면 충돌 2 = X2·X13 · 심각도 충돌 2 = X8·X9 · Claude 단독 13 · codex 단독 3). 독립성은 양쪽 산출물에 각각 기록됐고 범위 드리프트 0(codex 실행 시각 `HEAD` = `614afed` = 리더 지정 범위 끝). 후속 처리 현황은 아래 「게이트 15 후속」 절 | `reviews/15_phase3-preflight_cross.md` (정본) · 1단계 `reviews/15_phase3-preflight_codex-reviewer.md` · 2단계 `reviews/15_phase3-preflight_migration-reviewer.md` — 3종 전부 `c932b4f` 에 보존 |
| `16_gate15-fixes` | `614afed..1cb7bdf` (6 커밋) | **3단계 완주** (2026-08-18, `cc7f53c`). codex `needs-attention` — findings **3건 전부 high**(K16-1~3). **축① 음성 대조 재현은 미수행** — codex 샌드박스가 read-only 라 `mktemp` 가 `Operation not permitted` 로 막혔고, 그래서 이 회차의 음성 대조 3건은 **단일 관점**으로 남는다(cross §9.1). / Claude **21건**(차단② **1** = T-E · 수정 필요 **4** · 권고 **10** · 이월 **6**) / cross **23행**(합의 **8** · 충돌 **1** = F · codex 단독 **2** = B·I · Claude 단독 **12**). **완전 수령의 효과는 「합의 확인」이 아니라 Claude 통과 판정을 되연 것**이다 — codex 단독 B 가 Claude 가 "지적 없음"으로 닫은 구획(R-2)을 좁혔다(게이트 14 재실행분과 같은 형태). **리더 전제 오류 1건 확정** — 프롬프트가 codex 레인에 내려보낸 "새 CI 스텝 **4개**"는 실제 **3개**(`04ced00` 2 + `4cba492` 1, 전부 `quality` 잡)다. **게이트 15 별건 1과 같은 형태**(검증하지 않은 값이 프롬프트를 타고 하위 레인으로 내려감) — 아래 「게이트 16 후속」 §4 ②. **리더 판정 2건**: 충돌 F → **Claude 처방**(하한은 생성기 안에 두고 검사는 생성기 밖 가드가 케이스 이름 집합으로 대조 — 제2 정본 신설 없음) · T-D⑵ → **종류로 닫는다**. 후속 처리 현황은 아래 「게이트 16 후속」 절 | `reviews/16_gate15-fixes_cross.md` (정본) · 1단계 `reviews/16_gate15-fixes_codex-reviewer.md` · 2단계 `reviews/16_gate15-fixes_migration-reviewer.md` — 3종 전부 `cc7f53c` 에 보존 |
| `17_gate16-fixes` | `1cb7bdf..48a791c` (5 커밋) | **3단계 완주** (2026-08-19, `36b5ed4`). codex `needs-attention` — findings **3건 전부 high**(X17-1~3). **게이트 16 이 미수행으로 남긴 축① 음성 대조 재현이 이 회차에 회수됐다** — codex 가 **저장소에 쓰지 않는 경로**(`git show` + `/dev/stdin` + importlib 메모리 변이)로 성공해, 게이트 16 에서 단일 관점이던 것 중 닫히는 것이 닫혔다(cross §6.1·§6.2). / Claude **10건**(차단② **1** = ⑤ 빈 표 통과 · 수정 필요 **1** · 권고 **8**) + 검토함(지적 없음) **6** · 미검토 **0** / cross **16행**(합의 **3** · 충돌 **1** = ⑨ · codex 단독 **3** = ①②⑥ · Claude 단독 **9**). **codex 단독 3건 중 둘이 Claude 의 통과 판정을 되열었다** — ①은 R17-2 「검토함 — 지적 없음」, ⑥은 P17-2 「해소」로 닫은 구획을 각각 좁혔다(게이트 16 과 같은 형태가 **두 번** 재현). 반대 방향도 한 번 있다 — ⑤(차단②)는 codex 가 focus 에서 **물었으나 최종 출력이 답하지 않은** 자리를 Claude 실측이 채웠다. **전제 오류로 무효화된 지적 0건**(cross §7 — 넘긴 전제 6건 + 부속 1 전건 코드 대조. 부속의 `ci.yml` 경로 명시 스텝 **codex 5 vs Claude 7** 은 집계 기준 차이로 **무모순** — 게이트 16 의 리더 전제 오류와 다른 성격이다). **리더 판정 1건**: 충돌 ⑨(`SKILL.md:238` 이중 기재) → **Claude 채택** — 러너 머리 주석을 **지목만** 하는 형태로 축약하고 계약값 전문을 삭제했다(`107c8a5`). 후속 처리 현황은 아래 「게이트 17·18 후속」 절 | `reviews/17_gate16-fixes_cross.md` (정본) · 1단계 `reviews/17_gate16-fixes_codex-reviewer.md` · 2단계 `reviews/17_gate16-fixes_migration-reviewer.md` — 3종 전부 `36b5ed4` 에 보존 |
| `18_gate17-fixes` | `36b5ed4..318069b` (2 커밋) | **3단계 완주** (2026-08-19, `bbe49a1`). codex `needs-attention` — findings **4건 전부 high**(X18-1~4). codex 총평 문면은 "four independent false-green/scope-integrity defects remain" 이고, **4건 전부 이 배치가 방금 세운 하네스 자기 검사 장치**에서 나왔다. / Claude **11건**(차단 **0** · 수정 필요 **5** · 권고 **6**) + 검토함(지적 없음) **10** · 미검토 **1** / cross **19행**(합의 **4** · 충돌 **4** = X1·X4·R7·S2 · codex 단독 **3** = X2a·X5·X6 · Claude 단독 **8**). **Claude 통과 판정을 codex 가 둘 되열었다 — 세 회차 연속이다**: X1(1차 R18-4 「도달 가능한 상태가 아니다」) · X4(1차가 게이트 17 ⑭ 를 「해소」로 판정한 바로 그 관측). 이 회차가 제3 근거로 **양쪽 경로의 성립을 실행 확인**했고 1차 판정은 고쳐 쓰지 않았다. **게이트 17 ⑥·⑦ 은 codex 무응답이라 단일 관점 판정**이다(cross §6.1 — 「지적 없음」으로 읽으면 교차 검증 없이 닫힌 것으로 오독된다). **전제 오류로 무효화된 codex 지적 0건**(cross §6 — 6건 전건 코드 대조. 그중 `BASH_ENV` 마커 선점은 codex 가 샌드박스 제약으로 완주하지 못한 것을 이 회차가 **완주해 우회 성립을 확인**했다 — 보완이 codex 진술을 약화시키지 않고 하나를 완결시켰다). 후속 처리 현황은 아래 「게이트 17·18 후속」 절 | `reviews/18_gate17-fixes_cross.md` (정본) · 1단계 `reviews/18_gate17-fixes_codex-reviewer.md` · 2단계 `reviews/18_gate17-fixes_migration-reviewer.md` — 3종 전부 `bbe49a1` 에 보존 |
| `19_gate18-fixes` | `bbe49a1..5d58832` (코드 2 커밋 + 원장 1 참조) | **3단계 완주** (2026-08-19, `445c5cf`). codex `needs-attention` · "No-ship." — findings **high 4 · medium 2**(X19-1~6). / Claude **11건**(차단② **1** = R19-1 · 수정 필요 **6** · 권고 **4**) + 검토함(지적 없음) **9** · 미검토 **4** / cross **20행**(합의 **6** · 충돌 **1** = ⑤ 리더 제약 위반 여부 · Claude 단독 **5** · codex 단독 **3** · 합의(축)·사례 갈림 **1** = ⑯ · 검토함(대상 아님) **4축**). **전제 오류로 무효화된 codex 지적 0건** — cross §6 이 codex §4.5 「전제 확인 필요」 **7건 전건**을 실행·코드 대조로 확인했다(빈 `parametrize` → skip · 외부 강제자 전수 검색 참조 4건 전부 비-검사 · marker/rc 4입력 · env 간접 배선 33 passed · 행 번호 불일치는 사실이나 **오지목 0건**). **게이트 18 종결 — 미해소 0(양쪽 합의)**: 일치 해소 **2**(X4·T1) · 일치 부분 해소 **1**(X2a) · **갈림 3**(X1·X3·T2). 갈림 셋은 **실측값을 양쪽이 합의하고 이름표가 다르다**(부분 해소 ↔ 해소+별건) — **단 X3 만은 실질이 갈렸다**: codex 단독 ⑩(러너 잔여 (가)/(나) 밖 **세 번째 종류**)은 **Claude 가 보지 않은 새 사실**이다. **최고 심각도 2건이 서로 단독이다** — ①(Claude 단독 **차단**) · ⑧(codex 단독 **high**). 이 사실을 심각도 완화 근거로 쓰지 않았다. 후속 처리 현황은 아래 「게이트 19 후속」 절 | `reviews/19_gate18-fixes_cross.md` (정본) · 1단계 `reviews/19_gate18-fixes_codex-reviewer.md` · 2단계 `reviews/19_gate18-fixes_migration-reviewer.md` — 3종 전부 `445c5cf` 에 보존 |
| `03_auth` (**20회차** — 어간은 Phase 번호를 따랐다. 표의 마지막 줄이지만 회차는 19 다음이다) | `e91ecdd~1..fc21750` (하네스 3 + auth 12). **범위 실측 16커밋** = 하네스 3(`e91ecdd`·`e600861`·`e7f9bdb`) + 원장 3(`378c7d3`·`b3db059`·`5d762ec`) + auth 코드·문서 10(`05862fa..fc21750`)이고, auth 단위의 나머지 2(`6ece404` 계약 축 검증 · `bf08edd` 게이트 산출물)는 **범위 끝 뒤**에 붙는다 — 「auth 12」는 그 둘을 포함한 단위 기준 수다 | **3단계 완주** (2026-08-19, `bf08edd`). **Phase 3 첫 리뷰 게이트이자 제품 코드가 두 회차 만에 돌아온 회차다.** codex `needs-attention` — findings **high 3 · medium 3**(C1~C6) / Claude **차단 0 · 수정 필요 6 · 권고 13 · 판정 필요 3** / **privacy-gate 차단 1**(B-1 로그인 타이밍 열거 — *"B-1 해제 전까지 `auth` 종료 조건을 닫을 수 없다"*) · 통과 6 / **contract-keeper 독립 검증**(`6ece404` — 파서 P-1~P-12 · X-F11~13 · 음성 대조 재현 · 도달 결함 1건) / cross **합의 12 · 충돌 6 · 단독 9**(단독이 합의와 같은 수 — Claude 6 · codex 1 · contract-keeper 1 · privacy-gate 1). **전제 오류 0** — codex 「전제 확인 필요」 6건 + 인용 13건 전건이 코드 대조로 정확(심각도가 바뀐 항목 1건은 별도 표시). **C4(Jackson scalar coercion)는 4관점 중 0이 실행** — medium 표기에 「미검증」을 병기했다. **하네스 3커밋(`e91ecdd`·`e600861`·`e7f9bdb`) 「리뷰 미수령」은 이 게이트로 닫혔다**(§6 — 12변이 실행). 후속 처리 현황은 아래 「Phase 3 auth 단위」 절 | `reviews/03_auth_cross.md` (정본) · 1단계 `reviews/03_auth_codex-reviewer.md` · 2단계 `reviews/03_auth_migration-reviewer.md` · 보안 축 `reviews/03_security_privacy-gate.md` · 계약 축 `03_contract-keeper_auth-verification.md` — `bf08edd` 에 보존 |
| `03_auth-fixes` (**21회차**) | `bf08edd..3c5c8ad` (**9커밋**) — 게이트 20 확정 결함의 조치 배치. 완주 `d04ad98` | **3단계 완주** (2026-08-19, `d04ad98`). codex `needs-attention`("출하 차단") — findings **high 1 · medium 2**(C-1~C-3) / Claude **차단 0 · 수정 필요 5 · 판정 필요 2 · 권고 13** / **privacy-gate — 차단 B-1 해제**(해제 조건 3항 전건 독립 실측) · **M-1 수정 필요**(잠정 Minor · 차단 아님) · **R-1·R-2 기록** · **L-1 부분 조치**(WARN 스택트레이스 그대로) · 신규 차단 0 / **contract-keeper 판정 4건**(`9bee412` — 세마포어 500 통과 · C4 계약 준수 · 계약 대조 확대 성질 5 만족 · Gradle 선언 입력 닫힘, **차단 사유 0건**) / cross **합의 11 · 충돌 4 · 단독 20**(Claude 13 · codex 3 · privacy-gate 2 · contract-keeper 2). **codex 인용 3건 전건 실재·대응 일치**(§2-1 — codex 자신의 §4.4 와 **2관점 일치**, 전제 오류 0). **축 ④(codex 전건 미실행)는 Claude·contract-keeper 2관점 독립 재현으로 메워졌고 CI 원격 캐시 거동만 0관점**으로 남는다 — 다음 push 의 CI 실행이 첫 관측이다. **게이트 20 8항목: 해소 5 · 부분 해소 2(②·⑧) · 재개봉 1**(③ — codex C-1, 커버리지가 경로만 남기고 **메서드를 버린다**). 후속 처리 현황은 아래 「Phase 3 auth 단위」 §6 | `reviews/03_auth-fixes_cross.md` (정본) · 1단계 `reviews/03_auth-fixes_codex-reviewer.md` · 2단계 `reviews/03_auth-fixes_migration-reviewer.md` · 보안 축 `reviews/03_security-fixes_privacy-gate.md` · 계약 축 `03_contract-keeper_auth-fixes-verdict.md`(`9bee412`) — `d04ad98` 에 보존 |
| `03_workspaces` (**22회차**) | `d04ad98..cc7268c` (**21커밋**) — `auth-fixes2` 배치 + `workspaces` 단위. 완주 `7205d37` | **3단계 완주** (2026-08-19, `7205d37`). codex `needs-attention` "NO-SHIP" — findings **high 3 · medium 5 · low 0**, **동적 검증 0**(샌드박스가 `~/.gradle` 의 `.lck` 생성을 거부해 8건 전부 `[정적 확인]`·`[정적 추론]` — codex 자신이 명시) / Claude **차단② 1**(X-9 — CI `quality` red, BLOCK 8건이 **8스텝을 skip**) · 수정 필요 3 · 권고 7 · **판정 필요 4** / **privacy-gate 신규 차단 0** · 통과 7 · 기록 3 · **M-1 해제** · **R-3 기록**(삭제된 계정의 유효 토큰) / **contract-keeper 차단 0** · ⑴~⑺ 충족 · **⑻ 계약 미충족(401)** — 「계약 침묵」이 아니라 `x-auth.failure_uniformity` 가 계정 삭제 → 401 을 **요구**한다 · **D-2 조항 신설 권고(G2)** / cross **4관점 2 · 3관점 6 · 2관점 10 · 단독 15**(codex 5 · Claude 7 · privacy-gate 1 · contract-keeper 2). **codex 단독 5건이 전부 「장치가 재지 못한다」 형태**이고 나머지 세 관점 중 아무도 반박하지 않았다 — 실행할 수 없었던 것이 **실행 결과에 기대지 않는 독해**를 강제했다. **게이트 21 조치 12건 4관점 합성: 완전 종결 4(1·2·5·7) · 부분 5(3·6·8·9·11) · 재개 3(4·10·12)** — 게이트 21 이 「전건 해소」로 닫혔다면 **8건이 실제로는 열려 있었다.** 후속 처리 현황은 아래 「Phase 3 workspaces 단위」 절 | `reviews/03_workspaces_cross.md` (정본) · 1단계 `reviews/03_workspaces_codex-reviewer.md` · 2단계 `reviews/03_workspaces_migration-reviewer.md` · 보안 축 `reviews/03_security-workspaces_privacy-gate.md` · 계약 축 `03_contract-keeper_workspaces-verification.md` — `7205d37` 에 보존 |
| `03_workspaces-fixes` (**23회차**) | `7205d37..e9502a6` (**11커밋**) — 게이트 22 확정 결함의 조치 배치. 완주 `9b9d8ad` | **3단계 완주** (2026-08-19, `9b9d8ad`). codex `needs-attention` "NO-SHIP" (exit 0 · 출력 6,249바이트) — findings **high 4 · medium 1** / Claude **차단② 1**(스캐너 `OWNERSHIP-403` 정밀화가 **네 형태의 탐지를 잃었다** — 개명한 403 상수가 규칙을 통째로 우회) · 수정 필요 5 · 권고 12 · 판정 필요 1 · 취하 1 / **privacy-gate — R-3 해제**(다섯 경로가 전부 401 로 모이고 본문·헤더 이름 집합이 위조 토큰 401 과 **바이트 동일**) · 통과 5 · **수정 필요(Minor) 1**(A-3 — 같은 규율이 이메일에는 안 닿았다) · 기록 3(**401 네 갈래 시간 2.18배** · `/auth/me` `users` 이중 조회 · **TRACE 로거 3종 유출**) · **신규 차단 0** / cross **3관점 4 · 2관점 6 · Claude 단독 13 · codex 단독 0**. **codex 단독 0 은 「새 자리를 못 찾았다」가 아니다** — 세 관점이 **같은 네 자리**(스캐너·401 타이밍·`CountingDataSource`·`toString`)를 독립으로 짚었다는 뜻이고, 반대로 **Claude 단독 13 중 6건은 Gradle 층 실행이 있어야만 보이는 것**이라 codex 가 그 층을 돌리지 못한 결과다. 후속 처리 현황은 아래 「Phase 3 workspaces 단위」 §4~§6 | `reviews/03_workspaces-fixes_cross.md` (정본) · 1단계 `reviews/03_workspaces-fixes_codex-reviewer.md` · 2단계 `reviews/03_workspaces-fixes_migration-reviewer.md` · 보안 축 `reviews/03_security-workspaces-fixes_privacy-gate.md` · 스캐너 축 `reviews/03_security-scanner_privacy-gate.md` — `9b9d8ad` 에 보존 |
| `03_phase3-close` (**24회차**) | `9b9d8ad..2a4523d` (**7커밋**) — `01d78a1`(스캐너 복원) · `b401039`(원장·가드) · `f51295b`·`b529108`·`560c292`·`b9097f6`(Kotlin 4) · `2a4523d`(산출물). 완주 `76f6863` | **3단계 완주** (2026-08-19, `76f6863`). codex `needs-attention` — findings **high 3 · medium 2**(X24-1~5), 스크립트 exit **0** · 출력 9,849 B · 잘림 없음 / Claude **차단 0 · 수정 필요 4 · 권고 6 · 판정 필요 1 · 검증 통과 8** / **privacy-gate — Phase 3 종료 차단 없음**(즉시 중단 기준 4항 중 이 범위 해당 0, 차단 사유서 미작성) · **A-3 해제** · **신규 Minor A-3′**(탐지기가 value-class-first `data class` 를 통째로 건너뛴다 — 제품 실례 `MaskingResult`) · **잠정 위반 I-8**(`encryption_scheme` DEFAULT `fernet-v1` 이 폐기된 전제 위) · 기록 4 / cross **27행 — 3관점 4 · 2관점 4 · 충돌 4 · 단독 10**(분류 정본은 cross §2 표. 단독 10 은 **지적**만 센 것이고 Claude 7 · codex 2 · privacy-gate 1 이며, 여기에 단독 **사실·기록 2**(행 17·20)가 별도로 붙는다). **세 관점 모두 Phase 3 종료 차단 0.** codex 는 Gradle 전 구간 미실행(`~/.gradle` lock 권한)이라 Kotlin 지적 3건이 **전부 정적**이고 그 층은 Claude 음성 대조·privacy-gate 반사 탐침이 메웠다. **codex 단독이 0 → 2 로 바뀌었다** — 게이트 23 cross §7-3-3 이 제안한 「두 회차 연속 같은 분포면 focus 점검」은 **성립하지 않았다**(사실만 기록). 후속 처리 현황은 아래 「Phase 3 종료 판정」 절 | `reviews/03_phase3-close_cross.md` (정본) · 1단계 `reviews/03_phase3-close_codex-reviewer.md` · 2단계 `reviews/03_phase3-close_migration-reviewer.md` · 보안 축 `reviews/03_security-phase3-close_privacy-gate.md` — `76f6863` 에 보존 |
| `04_crypto` (**25회차** — **Phase 4 첫 게이트**) | `76f6863..9c7aa03` (**17커밋**) — ⓐ Phase 3 조건 처리 10 · ⓑ 계약 `765a377` · ⓒ crypto 5(`74ec2b0`·`fcf584b`·`e891a08`·`858347d`·`9c7aa03`). 완주 `6ac9158` | **3단계 완주** (2026-08-19, `6ac9158`). codex `needs-attention` ×2 (**호출 2회** · adversarial, 둘 다 `--base 76f6863` branch diff **42파일**) — findings **high 9 · medium 4** / Claude **차단② 1**(L-1 — 심판 산출물 `reviews/` 를 피심판 커밋이 편집) · 수정 필요 **9** · 권고 **19** / **privacy-gate — I-7 전 6항 통과 · 차단 0**(§5 Phase 7 즉시 중단 기준 해당 없음, `04_privacy-gate_blocking.md` **미생성**) · 수정 필요 7 · 기록 6 / cross **합의 8 · 단독 21 · 충돌·견해차 6**. **한쪽만 본 지적이 21건으로 합의 8건보다 많고**, **codex 단독 4건 중 3건(H4·H5·H11)이 Claude 가 「미실행」으로 신고한 자리에 정확히 떨어졌다** — 세 레인을 독립으로 돌린 값이 수치로 드러난 회차다. **①사건 0** (세 레인 일치 — `documents`·`conversions` 에 행을 쓰는 경로가 아직 없다). **codex 전제 6건 중 전제 오류로 무효화된 지적 0건**(cross §4-⑤ 코드 대조). **관점 수가 범위마다 다르다** — ⓒ crypto 는 **3관점**, ⓐ 하네스 10커밋과 ⓑ 계약은 **2관점**(privacy-gate 는 crypto 5커밋만 봤다). **ⓐ Phase 3 조건 처리 10커밋의 「첫 리뷰 수령」이 이 게이트로 성립**했다(Phase 3 종료 조건 ⑴ — 차단 0이므로 충족. **단 L-1 을 단서로 병기한다**). 후속 처리 현황은 아래 「Phase 4 crypto 단위」 절 | `reviews/04_crypto_cross.md` (정본) · 1단계 `reviews/04_crypto_codex-reviewer.md` · 2단계 `reviews/04_crypto_migration-reviewer.md` · 보안 축 `reviews/04_security_privacy-gate.md` — `6ac9158` 에 보존 |

**privacy-gate 판정** (`07_privacy-gate_masking-verdicts.md`): 판정1~5(§1~§4-bis)에 이어 **§4-ter 묶음 판정(2026-08-14)** — C-01①(보충 평면: 커버리지가 아니라 **정합성 결함** — 정규식은 코드포인트로, 가드는 UTF-16 Char로 셌다)·C-01②+C-10(**충돌 아님** — 유한 문법 `SEP := 공백? 하이픈? 공백?` 하나가 누락·과잉 동시 개선, 판정 기준은 문자 종류가 아니라 **개수**)·C-11(VT·FF 비결합). §4-quater·quinquies — 스캐너 red 오탐 확정(refine 훅 설계)·C-03(논리 줄 결합, AST 기각)·C-04(전수 0건 비영 종료). **§4-sexies 해제 심사 — 6건 해제·1건 재회부**(`_SAFE_ACCESS` 종단 미고정 — "결손은 구현자가 아니라 내 명세에 있다" 자인, `5ac039f`로 닫힘)·`document` 한정자 이탈 승인. 집행 커밋: `c6e65a0..888e3c9` + `5ac039f` + `1301367`. **레인 대기열 비었음. CI quality 스캔 단계 red 해소(전수 exit 0).** 잔여: 판정1 해제조건 4(`docs/golden/` 실문서 건수 대조 — privacy-gate §5.5 절차 몫). **조건 5 표기 리더 확인(2026-08-14)**: §4-ter.4의 "absent/present"가 하네스 어휘와 반대로 읽히는 문제를 parity-verifier가 발견 — 정본은 §4-ter.2 12탐침 표(자리당 공백 2개 이상 = 안 가림)이고 parity-verifier의 동결 방향이 옳다. 판정문 요약 한 줄의 느슨한 표기이지 판정 내용 불일치가 아니다.

**리더 판정 완료**: X-2 소관 = privacy-gate 지정 · X-8 CI 배선 승인(quality 잡) · missing_placeholders 도메인 배정(신설 없이 repair-adoption, parity-verifier 판단 수용) · 설정 소유권 = infrastructure(kotlin-implementer 결정 수용, `LlmProviderConfigurationTest` 가드 확인) · **C-12(2026-08-14)**: masking 행 예 → 아니오 재판정(위 표) — `충족 = 예` 의 의미는 "그 행을 겨냥한 차단 지적이 열려 있지 않음"을 포함한다 · **C-09(2026-08-14)**: parity 생산자 격리는 **규약이다 — 구조 봉쇄 기각.** 근거는 X-3 판정 전례(`정본도 같은 커밋에서 함께 바뀔 수 있다 — 한 칸 더 옮기는 것은 방어가 아니라 이동`)와 실질 방어의 소재: 기대값을 베끼는 생산자는 회귀 주입 검출 실증(`3934f06` — 진짜 Kotlin 산출물에 5종 주입, 전부 exit 1)이 잡고, `ParityFixtures` 를 우회하는 커밋은 하네스 수정이라 리뷰 게이트를 지난다. codex 지적(공개 `root()`·시스템 프로퍼티 우회 가능)은 사실로 인정하고 병기한다 — 닫은 것이 아니라 관리하는 한계다 · **C-15(2026-08-14)**: 과거 이력 rewrite 하지 않는다(ff4c323 단독 비컴파일은 기록으로 남김). 다음 커밋부터 선언·생산자 동일 커밋 규약 준수.

**§4-septies (2026-08-14, privacy-gate K-1·M-06 방향 판정)**: 접기는 개수 원칙의 뒷문이 아니다(분리축 성립 — 1,120조합 열거·컴파일 산출물 리플렉션 직접 호출). 실제 갈림은 경계축 한 종류(폭 0 문자가 "긴 숫자열" 거부 근거를 무효화, 90/90). **방향(누락 우선·합집합) 유지, KDoc 근거는 교체**(현행 근거 두 군데가 틀림 — 새 원칙: 분리축=적극 판정=일관된 한 읽기 / 경계축=거부권=두 읽기 만장일치). **M-06 TAB 수정 유효 — 리더 승인(2026-08-14)**: `SPACE_CHARS`의 `	` 제거(커버리지 축소 승인 — 단위 불일치 해소, 누락 비용은 기 감수 종류와 동일, INV-02 판정에 반영할 것). 실문서 실측: 접기 대상 1,006건 중 K-1 형태 발생 0, 합집합 대 뷰 전용 갈린 파일 0(2.67M자) — 단 붙여넣기 경로는 추출기를 안 거치므로 fixture로 고정(경계축 양성·음성 **짝** 3+3 필수). **신규 관측 1건(교차 검증 없음, 라우팅만)**: `2021 2022 2023 2024`(4×4 연도 배열) → 카드번호 과잉 — 줄일 수 없는 모호성이라 "감수한 과잉 표면"으로 원장 기록 요청 → parity-verifier.

**게이트 09 이후 수정 파동 (2026-08-14)**: 스캐너 재설계 `3570bdc`(M-01·M-03·M-09 — 사슬 파싱·상태 유지 lexer·균형 인자) · `75bfb40`(M-02·M-04 — 탐지기를 끄는 두 방법) · `3727905`(M-10·M-11 — 누락 6종 거절/실려 온 0 수용, usage 보존 절반은 M-12로 Phase 5 이월) · stop-gate 지적 `c2255dc`(주석 안 괄호가 인자 구간을 조기 종결 — "안전 접근이 방패" 기제, 재현이 회부 문구를 정정) · §4-septies 집행 `ac0307e`(TAB 제거·합집합 근거 교체·경계축 3+3) · fixture 동결 `56a70c1`(TAB 방향 전환 — **게이트 exit 3 복귀**, 108건·374단언, 경계축 12·감수 표면 known_gap 2·M-08 중복 검출·Z-7 분할: 상시 7종/한계 선언). **게이트 10(`10_detector-redesign`) 3단계 완주(2026-08-14)** — 합의 2 · Claude 단독 9 · codex 단독 4 · 충돌 0. **차단 4건(전부 ②장치)**: R-1(논리 줄 내 다중 로그 호출 첫 적중만 판정 — **첫 차단급 독립 합의**) · R-2(중첩 블록 주석, codex 단독) · R-3(`_?logger?\.`가 `log.` 못 봄 — 전역 예외 핸들러 탐지 밖, Claude 단독) · R-4(known_gap 게이트 도달 0, Claude 단독). "한 레인만 돌렸다면 어느 쪽도 게이트를 옳게 닫지 못한다." §4-septies 집행 축은 비대칭 수렴으로 충족(Claude 실측 4/4 + codex 무반증). 게이트 09 차단 3건·stop-gate 지적은 **전부 해소 확인**. **리더 판정 5건(2026-08-14)**: J-1+J-3 통합 — 케이스 축 장치는 개수가 아니라 **케이스 ID 정체성** 기준(`86c6a99` 판정 준수), known_gap 탐지형 전환과 한 장치, parity-verifier · J-2 — refine 훅 값 성질 휴리스틱 확장 금지(다섯 갈래 이력), 탐지형 대체 설계 privacy-gate(§4-octies 예정) · J-4 — **잔여 5개 도메인 착수 허용(조건부)**: R-3만 착수 전, 나머지 마감 "5개 도메인 병합 전", 증분 스캔 전환 시 판정 무효, R-4 닫히기 전 `성질 판정 N건`을 종료 근거로 쓰지 않음 · J-5 — C-07 Phase 5 유지. 정본 `reviews/10_detector-redesign_cross.md`.

~~**하네스 미결**: known_gap 정의 어긋남~~ → **해소**(`ad6ab92` — `verdict_pending` 전환: owner·deadline·referred_by 필수·분리 집계·`!deferred` 하한 꼬리표).

**게이트 11(`11_suppression-and-domains`) 3단계 완주(2026-08-14)** — 대상 `56a70c1..cd23aec`. **두 번째 차단급 독립 합의 X-1**(C-1≡N-01): 적중 입도(논리 줄×규칙) vs 표기 키(파일×물리 줄×규칙) 불일치로 표기 하나가 논리 줄 전체를 억제 — §4-octies 중심 주장("조용한 억제 불가") 반증, **설계 결함 선행 판정** → privacy-gate 재판정(§4-novies 예정) → kotlin-implementer 수정. 합의 X-2·X-3(export가 하한·형태·값 어느 검사도 안 받음), 충돌 확정 X-5(LoggerFactory 체인 — 탐지·도달 양쪽 도달 0, 두 정의가 "이름 붙은 수신자" 가정 공유 = Z-1), 충돌 X-4(style 유도 입력 — 리더 판정: 비교기가 출력 본문에서 독립 유도). R-1 해소는 층 한정 표기(검출 층 해소/억제 층 재도입). Claude 단독 판정 6건 교차 0회 — **prompts 왕복(마스킹 선행)·예산 3→7이 다음 회차 1순위**. 게이트 10 차단 4건 중 R-1~R-3 해소·R-4 부분 해소(id축 대체 적정). 정본 `reviews/11_suppression-and-domains_cross.md`. **후속 3레인**: privacy-gate(§4-octies 재판정 X-1·X-5·Z-1) · parity-verifier(X-2·X-3·X-4·C-4 비교기 강화) · kotlin-implementer(export 조각 — 스캐너 동결).

**게이트 12(`12_export-luhn-suppression`) 3단계 완주(2026-08-15)** — 대상 `cd23aec..516c0e9`. 차단 2건 추가: **#1 CARD 겹침 삼킴 = ①사건**(Luhn 배치가 만든 회귀 — 도입 전 14자리 가림 → 0자리, `findAll` 뒤 `filter`) · **#3 부분 게이트 사면 부활 = ②장치**("8/8은 강제된 성질이 아니라 오늘 참인 상태"). **도달 0 처방 정정** — 푸시는 이미 완료였고 트리거가 `push: main`뿐이라 **PR이 유일한 러너 실행 경로**. Phase 2 저지 A(#1)·B(PR)·C(#4 export over 단언)·D(원장) 확정. 집행: `2cf862a`(#1+C-6+N-08, ⚠비교기 변경 혼입)·`6e6fbbf`(full-gate 표시)·`8972c36`(#6 선언 정정)·§4-undecies·§4-duodecies(최종 감사 — 4조합 차분, Security 게이트 통과). **draft PR #1 생성(사용자 승인) → CI 러너 첫 실행**: kotlin·frontend 통과, quality는 ruff format 2파일로 실패 → 리더 수정(`4dafde5`) → 2차 실행 quality 포함 통과. 정본 `reviews/12_export-luhn-suppression_cross.md`.

**게이트 13(`13_regression-and-pins`) 3단계 완주(2026-08-15)** — 대상 `516c0e9..14b9d92`. **세 번째 차단급 독립 합의 X-1**(전체 게이트 하한 검사가 CI의 8/8 경로에서 완전 비활성 — `not selected` 즉시 반환, 리더 판정: 차단② 승격). X-5 충돌(#4 해소 기준)은 **목적 축 채택**(규칙 1 정합). D(원장 신규 오류)·B 정정(7커밋 미푸시)도 확정. 집행: 리더 레인(푸시·PR·원장 L-1) 선행 후 배치 — `142dac6`(N-20 `has_visible_reason`·U-3)·`496319d`(B2·B3 음성 7테스트 상시화)·`bbbdb6b`(B1 선언 하한 기준 재설계+내용 파싱 · B4 두 갈래 · B5 거부-겹침 fixture+N-03 동일 커밋 · U-2·U-3) + §4-terdecies(P1 지표 항진명제 정정 · P2 규약 축소 · P3). 전체 게이트 exit 0(성질 153·단언 630). 사고 2건 기록: 테스트가 제품 파일 덮어씀(4번째 오염, 새 형태 — 격리 수정) · 스테이징 경합 재발(→ `git commit -- <경로>` 규율). 정본 `reviews/13_regression-and-pins_cross.md`.

**게이트 14(`14_floor-hardening`) 3단계 완주(2026-08-15)** — 대상 `4dafde5..bbbdb6b`. **codex 부분 수령**(한도 소진 — 확정 지적 0 = 미수령이지 무지적 아님, 완전 재실행은 Phase 3 착수 조건). **[2026-08-15 갱신] codex 완전 재실행 = 완료.** `c932b4f` 에 보존 — `reviews/14_floor-hardening_codex-reviewer.md` 의 「완전 재실행 (2026-08-15) — **완전 수령**」 절(스크립트 종료 코드 0 · assistant 메시지 5건 · findings **6건 = high 5 · medium 1** · verdict `needs-attention`)과 job 로그 보존본 `reviews/14_floor-hardening_codex-rerun.log`. 1차 부분 수령분은 **삭제하지 않고 병기**한다. ~~**재실행분 교차 종합은 진행 중(별 레인) — 그것이 끝나기 전에는 "게이트 14 완전 재실행" 착수 조건을 닫지 않는다**(수령은 리뷰이지 대조가 아니다).~~ **[2026-08-18 갱신] 교차 종합 완료 (`4dabc0d`) — 게이트 14 종결.** `reviews/14_floor-hardening_cross.md` 의 「완전 재실행분 교차 종합 (2026-08-18)」 절(§R)이 정본이다: **합의 2**(R-1·R-3) · **충돌 2**(R-2⑴·R-5) · **codex 단독 2**(R-4·R-6 잔여), §R.4.5 「전제 확인 필요」 **6건 전부 코드 대조**(전제 오류로 무효화된 지적 **0건**), **「게이트 14 완전 재실행 = Phase 3 착수 조건」 충족 판정**(§R.10). 미해소 항목과 그 마감은 아래 「게이트 16 후속」 §1. 교차 종합 시점 판정은 "종료를 막는 것 = 기록 계열(F-1·F-2·F-12)+N-13·N-14·R-10"이었고 — **그 A 목록이 후속 배치(`b13d502`·`787bf69`·리더 커밋들)로 전건 해소되어 조건부 종료 판정(아래 절)으로 이어졌다.** 러너 재확인: 최종 배치 포함 run 31868504346 — kotlin·quality·frontend success(llm-lane cancelled — Phase 5 항목, 5차 실행으로 대체). 충돌②는 §4-quaterdecies(긍정 목록)로, 억제 층 계열은 §4-quindecies로 종결. 정본 `reviews/14_floor-hardening_cross.md`.

**열린 판정 — 처분 완료분(2026-08-14~15)**: ① **보정 채택 판정식의 본문 손실 축 — 사용자 결정(2026-08-15): ⓒ Phase 5 프롬프트 작업으로 이월.** 채택 판정식은 현행 2축(자리표시자·위반 건수) 유지, 본문 손실 기준 신설 여부는 Phase 5 프롬프트·긴문서 작업에서 골든셋 절대 팩트축과 함께 판정. Phase 2 종료를 막지 않는다 ③ absent/present 문언 — **종결**(§4-decies.3 정정) ④ 연도 배열 — **종결**(§4-decies.4: Luhn 별건 회부 → 리더 승인 → `2839ec8`·`516c0e9` 집행, present 방향).

**열린 판정 — 잔여**: ② Y-4/C-07(재시도를 가로지르는 누계 상한 — "변환 1건"의 요구 해석, **Phase 5**) · Luhn 재판정 조건(파일럿에서 카드 유입 관측 시 — privacy-gate) · X-4 잔여(문장 분리 조작 입력은 하한을 낮춘다 — 한계 선언됨) · **llm-lane 30분 타임아웃**(2026-08-15 러너 실측: `-m llm` 스텝이 30분 상한 초과로 취소 — 키 부재가 아니라 실행 시간. 골든셋 실 API 실행이 이 레인의 목적이므로 상한 상향 또는 문서 표본 분할 필요, **Phase 5 착수 전**) · ~~**codex 한도 소진**(2026-08-20 복구 — 게이트 14는 부분 수령으로 진행, stop-time 게이트도 그때까지 같은 오류)~~ → **해소 (2026-08-15).** 게이트 15 codex 리뷰가 2차 시도에서 `EXIT=0` 으로 성공했고 게이트 14 완전 재실행도 완료됐다(`c932b4f`). 남은 것은 재실행분 **교차 종합**이다.

---

## Phase 2 종료 판정 — **조건부 종료** (2026-08-15, 리더)

계획 §5 Phase 2의 명시 종료 조건("**외부 API·DB 없이 실행하는 parity suite가 동일 결과를 냄**")은 **충족**이다 — 단 "동일 결과"의 기준은 2026-08-12 전환대로 Python 출력이 아니라 **요구사항이 요구하는 성질**이다.

**근거 (실행 경로 포함):**
- 전체 parity 게이트 **8/8 도메인 · 성질 153건 · 단언 630개 · 판정 보류 0 · 불충족 0 · exit 0** — `ci:kotlin` 러너 실측 2회(run **31854263996**, 최종 배치 포함 run **31868504346** — kotlin·quality·frontend 전부 success). 수치 인용 정본은 러너 로그·재현 명령(`parity-full-gate.txt` 규약).
- 참고 갈림은 전건 원장 기록(`parity/reference-ledger/`) — 기준이 요구사항이므로 갈림은 차단이 아니라 기록이다. Python이 과잉 마스킹하는 방향 4건 포함 — 요구사항은 Python보다 넓기도 좁기도 하다.
- 보안: **§6 Security 게이트 통과**(privacy-gate §4-duodecies 최종 감사 + §4-quindecies 통합 재확인 — 억제 층 계열 미해제 0). 마스킹 선행 불변식·2종 범주 유지, 4조합 차분으로 Luhn·재탐색의 상쇄 분해 실측.
- 리뷰: 게이트 07~14 완주(3단계 규약 — 차단급 독립 합의 3회가 각각 실결함을 잡음). **게이트 14는 codex 부분 수령**(한도 소진) — 아래 단서. **[2026-08-15 갱신]** 게이트 **15**(`5797d87..614afed`)도 3단계 완주했고 게이트 14 codex **완전 재실행**이 수령됐다(`c932b4f`) — 이 판정문은 게이트 14 시점 기록이므로 고치지 않고, 갱신분은 단서 1과 아래 「게이트 15 후속」 절에 적는다.

**아니오로 남기는 1행과 마감:**

| 행 | 사유 | 마감 |
|---|---|---|
| L348 내보내기 | F-3·F-4(export 파일명 축의 C1·`*?<>` 무단언 — 코드가 아니라 fixture 커버리지) | **Phase 4 착수 전** |

> **[게이트 15 X14, 2026-08-15] 정면 모순 해소 — 이 표에서 「L344 프롬프트 · L345 스타일 규칙」 행을 뺀다.**
> 원장이 같은 두 행을 Phase 2 표에서는 `예`, 이 표에서는 `아니오` 로 **동시에** 적고 있었다(cross X14 — 이 축은
> codex 프롬프트 대상 파일 목록 밖이라 **교차 검증 없는 Claude 단독 판정**이다). 참인 쪽은 `예` 다: X-9 마감의
> 뒤 조각(CI 재생성 diff)이 `04ced00` 으로 `ci:quality` 에 배선되어 승격 근거가 완결됐다(리더 판정 — **마감
> 축소가 아니라 CI 배선 완성으로 승격 유지**). 근거 전문은 Phase 2 표의 두 행에 있다. **표 제목의 "3행"도
> "1행"으로 고쳤다** — 세지 않는 제목은 다음 사람이 다시 세지 않는다.

**단서 (판정문의 일부):**
1. **codex 게이트 14 부분 수령** — 확정 지적 0건은 무지적이 아니라 미수령이며, 부분 출력만으로도 Claude 해소 판정 안쪽을 한 번 뚫었다(Mc 갈래 — 이후 긍정 목록 교체로 해소). **게이트 14 완전 재실행을 Phase 3 착수 조건으로 이월한다**(codex 한도 복구 2026-08-20 이후 — 리더 선택 ⓑ, 하네스 규약의 기본 경로. ⓐ 크레딧 구매·ⓒ 연기는 사용자에게 열려 있음).<br>**[2026-08-15 갱신] 재실행 = 완료, 착수 조건 = 아직 열림.** codex 완전 재실행이 한도 복구를 기다리지 않고 성공했고(`c932b4f` — high 5·medium 1, 로그 보존본 동봉) 부분 수령분은 병기 보존됐다. **그러나 이 조건이 요구한 것은 "리뷰를 받는 것"이 아니라 "받아서 대조하는 것"이다** — 재실행분 교차 종합이 별 레인에서 진행 중이고, **그것이 끝날 때까지 이 착수 조건은 닫지 않는다.**<br>**[2026-08-18 갱신] 이 단서를 닫는다 — 교차 종합 완료(`4dabc0d`), 착수 조건 「게이트 14 완전 재실행」 충족.** 대조까지 끝났고(§R.3 합의 2·충돌 2·codex 단독 2 / §R.2 전제 6건 대조, 무효화 0) `reviews/14_floor-hardening_cross.md` §R.10 이 충족을 명시 판정했다. **닫는 것은 "조건"이지 "지적"이 아니다** — 미해소 6갈래(R-1 · R-2⑴⑵ · R-5 · R-6⑴⑵ · R-4⑶)는 살아 있고 마감은 **Phase 4 착수 전**이다. 그중 **R-5 는 사용자 판단 대기로 올린다**(L345 승격의 독립 입력 축 — 아래 「게이트 16 후속」 §3 ④). 착수 조건이 요구한 R-4⑴(전체 게이트 하한의 유일 본류 호출선 무검증, Y-7 변이 실측)은 **`48a791c` 로 해소**됐다. 별건 **Z-a·Z-b·Z-c** 는 「게이트 16 후속」 §5 에 이월 등재한다.
2. **privacy-gate 미확인 3건은 "확인하지 않음"이지 "위반 없음"이 아니다** — 불변식 3·5·7~12는 대상 코드가 Phase 3 이후에 생긴다.
3. **Phase 5 이월분**: 보정 채택 본문 손실 축(사용자 결정 ⓒ, 2026-08-15) · C-07(누계 상한 요구 해석) · llm-lane 30분 타임아웃 · Luhn OCR 잔여·실문서 사전확률(같은 파일럿 관찰 창).
4. **미결 원장(마감 명시)은 각 Phase 착수 게이트에서 다시 센다** — Phase 3 착수 전 항목들(Flyway TOCTOU·`CoreModuleBoundaryTest` 우회·게이트 우회 자동 회귀 등 기존 절)이 그대로 살아 있다.<br>**[2026-08-15 갱신 — 게이트 15 재점검을 실제로 수행했다]** 세 항목의 판정이 갈렸다: **게이트 우회 자동 회귀 = 해소**(`42f9e20` + `04ced00`) · **Flyway TOCTOU = 부분 해소**(원 지적은 `f9d78e0` 으로 닫히고 X8·X9·X10·X12 4건이 남아 마감이 **Phase 3 첫 기동/배포**로 이동) · **모듈 경계 우회 = 부분 해소 유지**(X4·X4b, 마감 **Phase 3 모듈 추가 시**). 이 문장의 열거를 그대로 두면 닫힌 것과 열린 것이 한 덩어리로 읽히므로 갈라 적는다. 정본은 아래 「게이트 15 후속」 §1.

다음 조각은 직전 세션이 예고한 **api 엔드포인트**(Phase 3 영역 — 인증·작업 공간·contract test 첫 실행)다. Phase 3 착수는 위 단서 1(게이트 14 재실행분 **교차 종합**)과 Phase 3 착수 전 미결 원장의 재점검을 선행한다 — 후자는 아래 「게이트 15 후속」 절이 수행 결과다.

---

## Phase 3 착수 판정 — **착수 허용 권고 · 사용자 승인 대기** (2026-08-19, 리더)

> **이 절이 판정한다.** 「게이트 16 후속」 §6·§6-a 가 모은 사실을 근거로 삼으며, 그 절은 이 갱신으로
> **닫혔다**(더 갱신하지 않는다). 판정 근거가 되는 수치·해시는 전부 위 절들이 들고 있고
> **여기로 옮겨 적지 않는다**.

### §1 — 착수 조건 대조

| 착수 조건 | 상태 | 실행 경로 | 근거 |
|---|---|---|---|
| **① 게이트 15 종결** | **충족** | `1회성:docs/migration/_workspace/reviews/15_phase3-preflight_cross.md` | 3단계 완주(`c932b4f`) — 「게이트 16 후속」 §6 ① |
| **② 게이트 14 완전 재실행 + 교차 종합** | **충족** | `1회성:docs/migration/_workspace/reviews/14_floor-hardening_cross.md` | 재실행 완료(`c932b4f`) + 교차 종합 완료(`4dabc0d`), §R.10 명시 판정 — 「게이트 16 후속」 §6 ② |
| **③ Phase 3 착수 차단 6건 처리** | **충족** | `ci:kotlin` · `ci:quality` · `안 돎` | 6건 중 **X6 강제자 축만 열려 있고 그 마감은 Phase 3 DTO 구현 커밋** — **착수가 아니라 종료를 막는다**(§6-a · 「게이트 16 후속」 §2 H·J·W). 나머지 5건은 해소·수정 완료이며 X5(test-plan)의 실행 마감은 **Phase 3 첫 계약 테스트**다 |
| **④ 게이트 16 종결** | **충족** | `1회성:docs/migration/_workspace/reviews/16_gate15-fixes_cross.md` | 3단계 완주(`cc7f53c`) · 잔여의 마감은 전부 Phase 3 **종료** 또는 Phase 4 — 「게이트 16 후속」 §6 ④ |
| **⑤ 게이트 17 종결** | **충족** | `1회성:docs/migration/_workspace/reviews/17_gate16-fixes_cross.md` | 3단계 완주(`36b5ed4`) — 「게이트 16 후속」 §6 ⑤ |
| **⑥ 게이트 18 종결** | **충족** | `1회성:docs/migration/_workspace/reviews/18_gate17-fixes_cross.md` | 3단계 완주(`bbe49a1`) · 잔여 8건은 게이트 19 가 재판정 — 「게이트 16 후속」 §6 ⑧ |
| **⑦ 게이트 19 종결** | **충족** | `1회성:docs/migration/_workspace/reviews/19_gate18-fixes_cross.md` | 3단계 완주(`445c5cf`) · **게이트 18 종결 합성 미해소 0(양쪽 합의)** · 전제 오류 0 — 「게이트 16 후속」 §6 ⑨ |
| **⑧ 게이트 19 ①·⑥ 수정 커밋** | **충족 — 리뷰 수령(2026-08-19 갱신)** | `ci:quality` | **`e91ecdd` + `e600861` + `e7f9bdb`** (차단② ①과 합의 ⑥. stop-time 게이트가 같은 우회를 **세 번** 잡아 3 커밋이 됐다). ~~독립 리뷰를 받지 않았으므로 세 커밋을 착수의 적극 근거로 쓰지 않는다~~ → **게이트 20 이 「리뷰 미수령」을 닫았다**(`reviews/03_auth_cross.md` §6 · 완주 `bf08edd`): 저작자와 다른 두 관점이 **읽기가 아니라 변이 실행**으로 리뷰했다(codex 메모리 변이 4종 + Claude 일회용 worktree 8변이 = 12변이, 결과가 서로를 확증). **닫힌 것은 「수령」이지 「지적 0」이 아니다** — 열린 항목 표 ⑤(하네스 동반 편집 처분)·표 ⑬(H-1)·H-2·H-3 은 아래 「Phase 3 auth 단위」 §2 에 실린다 |

**열린 것 전부(§6-a)의 성질.** 남아 있는 것은 두 종류뿐이다 — ⑴ **하네스 자기 검사 장치**
(게이트 18·19 잔여 · 러너 잔여 · 완전성 장치 잔여. 전부 리더 결정 ⑴⑵⑶ 으로 동결·부분 해소 표기·한계
등재 처분됐고 **제품 코드 지적은 두 회차 연속 0**이다) · ⑵ **마감이 Phase 3 종료 또는 Phase 4 로 명시된
항목**(L348 · 게이트 14 미해소 6갈래 · X6 강제자 축 · Flyway 4건 · 모듈 경계 2건).
**착수 시점을 마감으로 갖는 열린 항목은 없다.** 「사용자 판단 대기 11건」은 전부 심각도·처방·절차
라벨이며 각 행이 스스로 「차단 아님」을 적고 있다.

### §2 — 리더 판정과 승인 대기

**리더 판정: Phase 3 착수 허용 권고.** 착수 조건 8행이 전건 충족이고(⑧ 은 단서 병기), 열린 것 중
착수 시점을 마감으로 갖는 항목이 없다.

**단 아직 착수하지 않는다 — 사용자 승인 대기.** 하네스 규칙이 그렇게 정한다: **「이전 Phase 의 종료
조건 행이 전부 `충족 = 예` 가 아니면 다음 Phase 에이전트를 호출하지 않는다. 미충족인데 진행해야 할
사정이 있으면 사용자 승인을 받고, 승인 사실과 미충족 행을 `00_progress.md` 에 남긴다」**
(`.claude/skills/kotlin-migration/SKILL.md` 「Phase 의존성 강제」 — **그 절이 정본이다**).

**미충족 행은 하나다** — Phase 2 종료 조건 표의 **L348 「내보내기 파일명·`Content-Disposition` 생성
포팅」 = `아니오`**(export 파일명 축 F-3·F-4, 마감 **Phase 4 착수 전**). 게이트 14 R-1 이 같은 자리를
겨눈다. **이 행은 Phase 3 의 대상 코드(JDBC repository·인증 API·계약 테스트)에 닿지 않는다.**

> **승인 시 이 절에 남길 것** — ⑴ **승인 사실**(일자·승인자) ⑵ **미충족 행 = L348**(위 문단)
> ⑶ 승인 시점의 HEAD 해시. **세 가지를 적은 뒤에 Phase 3 에이전트를 호출한다.**
> 승인 없이는 호출하지 않는다 — 규칙이 요구하는 것은 판정이 아니라 **승인 기록**이다.

**승인 기록 (2026-08-19).** ⑴ **사용자가 "Phase 3 착수"로 승인**했다(2026-08-19, 사용자 harris.lee).
⑵ **미충족 행 = Phase 2 L348**(내보내기 파일명·`Content-Disposition` 포팅, `아니오`, 마감 Phase 4 착수 전 —
Phase 3 대상 코드에 닿지 않는다). ⑶ **승인 시점 HEAD = `b3db059`**(원장 갱신 커밋; 코드 HEAD 는 `e7f9bdb`).
이 기록 뒤에 Phase 3 에이전트를 호출한다. 첫 작업 단위는 §4 지침대로 **`auth`**(어간 `03_auth`) — 사용자
repository + Argon2 + JWT + 인증 엔드포인트 + 계약 테스트(OQ-3 직접 파싱). **첫 리뷰 게이트 범위에
`e91ecdd`·`e600861`·`e7f9bdb` 를 포함**한다(§4 지침 9).

**착수 완료 (2026-08-19).** 첫 단위 `auth` 커밋 범위 **`e91ecdd~1..fc21750`**(하네스 3 + auth 12) ·
첫 리뷰 게이트 **20회차**(어간 `03_auth`) 3단계 완주 `bf08edd` — 상세는 아래 「Phase 3 auth 단위」 절,
종료 조건 판정은 아래 「Phase 3 — 데이터·인증·작업 공간 API」 표.

**진행 (2026-08-19 갱신).** 게이트 **21**(`03_auth-fixes` · 완주 `d04ad98`) → 둘째 단위 `workspaces`
(`e31bbb4..cc7268c`) → 게이트 **22**(`03_workspaces` · 완주 `7205d37`) → 조치 배치(`7205d37..e9502a6`) →
게이트 **23**(`03_workspaces-fixes` · 완주 `9b9d8ad`) → **React ↔ Kotlin E2E**(`203831d`·`a1e1925`·`b3f76b2`).
상세는 아래 「Phase 3 workspaces 단위」 절. **Phase 3 종료 조건 7행 중 1행(React E2E)이 `예`로 올랐고
나머지 여섯은 `아니오` 유지**다 — 미충족 행 L348 은 그대로이고 마감(Phase 4 착수 전)도 그대로다.

### §3 — 리뷰어 조건 3건의 수용 (게이트 19 cross §7.1)

> 두 산출물 중 **「별도 게이트를 열지 말라」는 절차 자체를 반대하는 문장은 없었고**, 반대 근거로
> 읽히는 셋은 전부 **순서·조건**이었다. 셋을 어떻게 수용했는지 한 줄씩 적는다.

| 조건 | 출처 | **수용 방식** |
|---|---|---|
| **⑴ 6항목 마감(「Phase 3 착수 전」) 존중** — "게이트가 근거로 쓰이기 전에 게이트를 믿을 수 있어야 한다" | Claude 1차 §5.2 (cross §7.1 가) | **수용 — 마감을 실행 시점에 결속시켰다.** 리더 결정 ⑴ 이 러너·완전성 장치의 잔여 마감을 **「러너 실사용(CI 배선) 전」**으로 통일했고, 그 장치들은 **지금 실사용 0 · CI 배선 0** 이다. 즉 「믿을 수 있어야 하는 시점」이 아직 오지 않았고, **그 시점 전까지 닫는다**는 원 요구가 그대로 유지된다. 착수 자체를 막지 않는 이유는 마감 기준이 **날짜가 아니라 「첫 실사용」**이기 때문이다 |
| **⑵ 리더 선결정 — 「먼저 리더가 결정하고」** | codex X19-1 Rec. · X19-4 Rec. · Next steps 1 (cross §7.1 나) | **수용 — 결정을 먼저 냈다.** 「게이트 19 후속」 §2 의 **리더 결정 3건**(⑴ 러너 동결 · ⑵ 부분 해소 표기 · ⑶ 한계 등재)이 codex 가 선행을 요구한 그 결정이며, **이 원장 갱신이 그 기록이다.** 충돌 ⑤ 자체(제약 위반 여부)는 결과를 바꾸지 않으므로 **사용자 판단 대기로 올린다**(「게이트 19 후속」 §3 ⑪) |
| **⑶ 완전 종결 표기 금지 — 「독립 강제자가 승인될 때까지 X1/X2a/T1 을 완전 종결로 기록하지 않는다」** | codex X19-3 Rec. (cross §7.1 다) | **수용 — 표기를 보수적인 쪽으로 고정했다.** 리더 결정 ⑵ 가 세 항목을 **「부분 해소」**로 적고, 「게이트 16 후속」 §6-a 의 해당 행도 그렇게 고쳤다. **⑧(외부 강제자 0)은 「열림」으로 남는다** — 리더 결정 ⑶ 이 그것을 **한계로 등재**했지 해소로 적지 않았다 |

### §4 — Phase 3 첫 작업 지침 요약 (승인 시 그대로 내려보낸다)

> **요약이고 정본이 아니다.** 각 항목의 근거·전문은 표시된 절과 산출물에 있으며 **값을 여기로 옮겨
> 적지 않는다**(전사하면 갈린다 — 이 하네스가 이미 겪은 실패). 이미 확정된 지침만 싣는다.

| # | 지침 | 정본 |
|---|---|---|
| **1** | **인증** — Argon2 는 **전체 파라미터 동등성**(단일 값이 아니라 파라미터 집합 전건)으로 판정하고, **JWT 는 skew 0** 으로 검증한다 | `migration-safety-gate` I-8·I-9 (암호·인증 정본) |
| **2** | **F3 다섯 필드에 `@Size` 를 쓰지 않는다** — **서비스 층에서 정규화한 뒤 판정**한다(DTO 애너테이션은 원시값을 재므로 F3 계약의 `measured_on` 축과 어긋난다) | 「게이트 15 후속」 §3 ② · 「게이트 16 후속」 §2 H·J·W |
| **3** | **`limit`/`offset` 은 Bean Validation 으로** 상한·하한을 강제한다 | `api-contract-freeze` 입력 상한 |
| **4** | **OQ-3 — 계약 파일을 직접 파싱**한다(산문 대응표가 아니라 `contracts/easy-doc-v1.yaml` 자체를 읽는 파서. 현재 파서 **0건**이 X15 의 내용이다) | 「게이트 15 후속」 §5 X15 (마감 **Phase 3 첫 계약 테스트**) |
| **5** | **G-1 — 동일 단위로 잰다**(Python 기준선에도 없던 공백. 값 비교가 아니라 단위 일치가 요구다) | `00_contract-keeper_test-plan.md` §5 |
| **6** | **H-2 — 헤더 중복 부착 금지.** 필터는 `add` 가 아니라 `set` 을 쓰고, 단언은 값이 아니라 **개수까지** 본다(`header().stringValues(...)`) | 「Phase 1 종료 판정」 §미결 원장 H-2 |
| **7** | **H-4 — 오류 본문이 경로를 가리지 않게 한다.** `sendError` → `/error` 가 Spring 기본 본문을 내면 계약의 「최상위 `detail` 하나」 불변식이 깨진다 | 같은 표 H-4 |
| **8** | **`handleHandlerMethodValidationException`** 을 명시 처리한다(그러지 않으면 검증 실패가 `{"detail":…}` 계약 밖으로 샌다) | 같은 표 · `api-contract-freeze` 오류 본문 조항 |
| **9** | **첫 리뷰 게이트 범위에 `e91ecdd` + `e600861` + `e7f9bdb` 를 포함**한다 — 리뷰 미수령 **3 커밋**을 다음 게이트가 반드시 본다 → **집행 완료·닫힘**(게이트 20 `bf08edd`, cross §6 — 2관점 12변이 수령. 열린 지적은 「Phase 3 auth 단위」 §2 로 이월) | 「게이트 19 후속」 §1 말미 |

## Phase 3 — 데이터·인증·작업 공간 API

계획 문서 §5 Phase 3. 다섯 항목과 **원문 종료 조건 두 조각**(contract test·React 테스트 통과 / 계약
개선이 있었다면 계약 파일·Kotlin·React 3자 동일 + 근거 기록)을 **항목당 한 행**으로 옮겼다. 근거 없는
`예`는 `아니오`로 취급한다는 규칙이 그대로 적용된다. 첫 작업 단위 `auth` 의 구현·게이트 20·21 상세는
아래 「Phase 3 auth 단위」 절이, 둘째 단위 `workspaces` 와 게이트 22·23·React E2E 는 「Phase 3 workspaces 단위」
절이 든다 — **값을 여기로 옮겨 적지 않는다.**

> `실행 경로` 열의 어휘 정본은 위 Phase 0 표의 포인터를 따른다.
>
> **[2026-08-19 게이트 21 갱신] 차단 B-1 은 해제됐다** — 보안 축 정본이 *"Phase 3 `auth` 의 보안 축에서
> **B-1 이 막고 있던 종료 조건은 열렸다**"* 를 선언했고 해제 조건 3항 전건을 독립 실측으로 닫았다
> (`reviews/03_security-fixes_privacy-gate.md` 판정 표 1·1a~1c). **그래도 인증 관련 행은 아직 `아니오`다** —
> 막는 것이 차단에서 **조치 배치의 미완**으로 바뀌었을 뿐이다. 게이트 21 이 확정한 조치(C-1 · M-1 ·
> 세마포어 상한 · L-1 · C-2 잔여 · TST-1/2 · 열거자 7 vs 5)가 아직 커밋되지 않았고, 그 배치는 **게이트 22
> (`workspaces`) 범위에서 리뷰**한다(리더 결정 — 아래 「Phase 3 auth 단위」 §6). 옛 문면(B-1 차단 상태)은
> `reviews/03_security_privacy-gate.md` · cross(20) §7-2 ㉯ ① 에 그대로 남는다.
>
> **[2026-08-19 게이트 23 갱신] R-3 도 해제됐다** — 게이트 22 가 건 「삭제된 계정의 유효 토큰」 조건이
> **계정 삭제 기능보다 먼저** 닫혔다(`reviews/03_security-workspaces-fixes_privacy-gate.md` §1 — 다섯 경로
> 전건 401 · 위조 토큰 401 과 본문·헤더 이름 집합 **바이트 동일**). **그래도 여섯 행은 `아니오` 유지다** —
> 게이트 23 이 **새 차단 하나**(스캐너 `OWNERSHIP-403` 네 형태 상실)를 열었고, 그 복원(`01d78a1`)은
> **리뷰 미수령**이며 **조치 배치 2**(F-4 서비스 층 · `toString` 종류 탐지기 · C-5 파서 fail-open ·
> 401 타이밍 · TRACE 로거)가 남아 있다. 상세는 아래 「Phase 3 workspaces 단위」 §4·§6.
>
> **[2026-08-19 리더 판정] 행 5(React E2E)만 `예`로 올린다** — 근거는 ⑴ 로컬 12/12(실 Chromium ↔ 실
> Kotlin bootJar ↔ 실 PostgreSQL) ⑵ **CI 도달 관측**(run `32222249150`, 잡 `e2e` success) ⑶ 음성 대조
> **6/6** ⑷ 제품 코드 변경 **0**. 이 행이 지는 것은 계획 §1-2 의 「Phase 3 몫」이고 Phase 6 항목은
> 애초에 이 행 밖이다.
>
> **[2026-08-19 게이트 24 갱신] `충족` 열은 이 회차에도 움직이지 않았다** — 행 5 만 `예`이고 나머지
> 여섯은 `아니오` 유지다. **행 1 은 「남는 것이 전부 Phase 4 종속」인 유일한 행**이 됐으나(F-4 3관점 해소)
> `예`로 올리지 않았다 — **Phase 2 L348 전례**를 따라 `아니오` 를 유지하고 사유를 「Phase 4 종속」으로
> 명시한다(리더 판정). 행 4 는 스캐너 복원이 3관점으로 섰지만 그 확장(`6be9612`)이 **리뷰 미수령**이라
> **「조건부 — Phase 4 첫 게이트 통과 시」**로 둔다. 판정 전문과 미충족 행 목록은 아래
> 「Phase 3 종료 판정」 절 §3 이 정본이다 — **값을 여기로 옮겨 적지 않는다.**
>
> **[2026-08-19 게이트 25 갱신] 행 4 를 `예`로 올린다** — 게이트 24 가 이 행에 건 **유일한 조건**
> (「`6be9612` 리뷰 수령」)이 Phase 4 첫 게이트로 충족됐고 그 회차의 **차단은 0**이다. **다만 두
> 단서를 행에 병기했다** — L-1(그 커밋이 심판 산출물을 같은 커밋으로 편집했다는 사실이 이번에
> 함께 확정됐다)과 H7(403 잔여의 **종류**가 열려 있다). **닫힌 것은 수령이지 지적이 아니다.**
> 나머지 다섯 행(1·2·3·6·7)은 `아니오` 유지다. 판정 전문은 「Phase 3 종료 판정」 절 §3.

| 종료 조건 | 충족 | 실행 경로 | 근거 | 미해결 항목 | blocked-by | 마지막 갱신 주체 |
|---|---| --- |---|---|---|---|
| Spring JDBC repository 와 트랜잭션 경계 | 아니오 | `ci:kotlin` | **auth 몫은 배선됐다** — `JdbcUserRepository`·`JdbcWorkspaceRepository`·`SpringTransactionRunner`(`3da2d51`), 포트는 `application/.../auth/AuthPorts.kt`(`b87de0b`). 가입과 기본 작업 공간 생성이 **한 트랜잭션**이다. 게이트 20 에서 privacy-gate 가 `--rerun-tasks` 로 전체 Kotlin 테스트 **610건·실패 0**(api 117 · application 41 · infrastructure 95 · core 357) 실측.<br>**[게이트 22·23] 작업 공간 repository 가 CRUD 전부로 늘었다**(`ab53420`) — 설계 3: ⑴ 소유 조건을 Kotlin 이 아니라 **SQL `WHERE` 에** 둔다 ⑵ 삭제 잠금이 대상 행이 아니라 **그 사용자의 행 전부**에 걸린다(「마지막 하나」가 집합 판정이라 그렇고, `ORDER BY id` 로 교착을 막는다) ⑶ 문서 보유 삭제 거절은 **FK 방벽**으로 받는다. 게이트 23 은 **구조 축 탐지형을 변이로 확인**했다(소유 조건을 `WHERE` 에서 빼고 Kotlin 에서 비교 → FAILED · privacy-gate §4-1).<br>**[게이트 24] F-4 가 3관점 합의로 해소됐다**(`f51295b` — Claude K-1 **1 red** · privacy-gate `rename`·`delete`·`list` **3변이 red** · codex 「F-4 는 지적 없음」 **명시**). 구조 축 도달이 `rename` 1 오퍼레이션뿐이던 것도 **4 오퍼레이션**으로 늘었다. run `32225305372`(headSha `2a4523d`) 잡 `kotlin` **success** | 문서·변환 repository 미착수 · ~~**F-4** — 구조 축 계측의 도달이 repository 층까지~~ → **게이트 24 3관점 해소** · **codex 축 ②·⑤ 의 침묵은 「검토하고 문제없다」가 아니다**(레이어 분리·JDBC SQL·트랜잭션 원자성 — cross(20) §10) · **K-2 / 기록 ④ — `CountingDataSource` 가 `JdbcClient` 전제 위에 선다**(KDoc 만 막는다. Claude 권고 ↔ codex 미지적 ↔ privacy-gate 기록 — raw JDBC 로 내려가는 커밋에서 재확인) | Phase 4 문서 단위 | leader (2026-08-19, 게이트 24) |
| Argon2·JWT·가입과 기본 작업 공간 원자 생성 | 아니오 | `ci:kotlin` | **I-8 Argon2**(전체 파라미터 동등성·재해시 시점·PHC 오류 401·설정 출처)와 **I-9 JWT**(skew 0·alg 고정·키 하한 32·클레임)가 **각각 2관점 충족**(privacy-gate 실측 + contract-keeper S-1·S-2 — cross §7-1). 구현 `3da2d51`, 계약 결속 `f9ee3e6`·`9341e69`.<br>**[게이트 21] B-1 해제** — 해제 조건 3항 전건이 독립 실측으로 닫혔다(`3c5c8ad`): 더미 PHC 가 현행 정책 파라미터임을 JVM 탐침 4정책 + 설정 변경 재기동으로 확인(**103.97ms → 52.68ms 추종**), 더미에 재해시 미도달(제어 흐름 + 양방향 회귀), 비율 기준 회귀 재실측 **1.017배 / 절대 격차 1.72ms**(게이트 20 의 42배 / 95ms 가 소멸). Claude 가 음성 대조를 **40.1배**로 독립 재현 — **2관점 실측 일치**.<br>**[게이트 22·23] 게이트 21 조치 배치가 커밋되고 리뷰를 받았다** — 12건 4관점 합성 **완전 종결 4 · 부분 5 · 재개 3**(cross(22) §4). 그중 X-6(배압 집단 단언)·X-7(동시 삭제 예외 보존)은 게이트 23 에서 **변이 red + 옛 단언 초록 재현**으로 해소됐고, 원자 생성 자체는 **A-2 FK 전제·A-4 READ COMMITTED 전제가 산문에서 장치로** 바뀌었다(`30cc405`) | ~~게이트 21 조치 배치(미커밋) ⑴~⑺~~ → **커밋·리뷰 완료**(게이트 22 §4 합성) · **잔여**: ⑴ **R-2 교환비**(대기 상한 250ms 로 얻은 것과 잃은 것의 용량 결정 — **사용자 판단 대기**, 이 행의 마지막 미결) · ⑵ **X-2a**(`field_missing` 예시가 어떤 요청에서도 안 나온다 — 마감 Phase 4) · ⑶ 표 3·3a·3b(`CountingDataSource` 가 왕복이 아니라 Statement 생성을 센다 — codex high ↔ privacy-gate 통과, §4-Ⅰ).<br>**[게이트 24] 이 행은 무접촉이고 privacy-gate 가 I-9·I-9b 준수를 재확인했다** — 위조 토큰 대조 도구의 base64url **하위 2비트 함정**을 3관점이 함께 통과(판별자 = 401, 게이트 23 값 0.539ms 자기 감사). **재료는 갖춰졌고 남은 것은 결정(R-2 교환비)뿐**이다 | 사용자 판단(R-2 용량) · Phase 4 | leader (2026-08-19, 게이트 24) |
| `/auth/*` · `/workspaces/*` 엔드포인트 | 아니오 | `ci:kotlin` | `/auth/signup`·`/auth/login`·`/auth/me` 3종과 인증 인터셉터(`0b81fa6`). **계약 케이스 30건 미대응 0**(2관점 — contract-keeper 26 대응 + 부분 2 + 신설 1 / Claude 29 전용 + 1 겸함 + 신설 1). **I-6 사적 헤더 「모든 응답」 도달** — 원시 소켓 7종 포함 20변종 전건 `no-store=1 nosniff=1`(privacy-gate 실측, **1관점**).<br>**[게이트 22·23] `/workspaces` 네 오퍼레이션이 들어왔다**(`e4be6ff` — 목록·생성·이름 변경·삭제, 보호 목록 등재). 계약 케이스 **36건**과 파서 요건 P-16~P-21 을 contract-keeper 가 2단계에서 독립 재현(`2c4a44f`). **X-1 로 인증 경계가 다섯 경로를 덮는다**(`fa87aed`) — 삭제된 계정의 유효 토큰이 `/auth/me`·`GET·POST /workspaces`·`PATCH·DELETE /workspaces/{id}` 전건에서 **401** 이고, 살아 있을 때의 200·200·201·200·204 대조가 있어 「무엇을 보내도 401」과 구분된다(privacy-gate 3관점 확인 · **R-3 해제**).<br>**[게이트 24] 401 균일화가 「세 갈래」로 좁혀져 계약상 닫혔다** — ⑴ 토큰이 제시된 **3갈래**의 시간 비가 **2관점 독립 실측**으로 닫혔고(구현자 1.007~1.036 / privacy-gate **1.003**, 문턱 1.5 · 표본 각 101 · 워밍업 20 · 교차 순서), DB 왕복·본문 바이트·헤더 이름 집합이 전건 동일이다(`b9097f6`) ⑵ 구조 회귀가 **2관점**(음성 대조 재현 — 균일화 제거 시 `{성공=1, 삭제=1, 위조=0, 만료=0, 형식오류=0}` 1 red) ⑶ **계약 내부 모순(R-1)이 정합됐다**(`dec3124` — 정본은 `x-auth.failure_uniformity`, `Unauthorized.description` 은 열거를 걷어내고 정본을 **가리키는 포인터**로. **무헤더는 균일화 대상이 아니다** — 3관점이 「제외가 옳다」로 수렴, 정보 이득 0. 와이어 무변경 · `openapi-spec-validator` OK) | ~~`/workspaces/*` 전부 미구현~~ → **구현됨**(게이트 22·23) · ~~**401 네 갈래의 시간이 2.18배**~~ → **3갈래로 좁혀 계약상 닫힘**(`dec3124`·`b9097f6`). **잔여 X24-2** — 구조 단언은 시간의 **대리값**이라 지연·CPU·캐시 온도 변이를 통과한다(codex high ↔ Claude 「구조 선택이 옳다」 ↔ privacy-gate 「오늘 값 통과」 — cross §5-Ⅰ). **리더 판정: 고정 sentinel 유지 · 3갈래 비율 회귀(문턱 1.5) 추가를 Phase 4 착수 전 조건으로** · **기록 ①** `Bearer <임의 문자열>` 이 무자격 DB 왕복 1회를 만든다(배포 전 레이트 리밋 판단) · Phase 4 문서 경로 미착수 · ~~⑧ 422·`/auth/me` 401 계약 대조 8건 없음~~ · ~~⑨ X-D2 오류 응답 전역 헤더 5건 중 2건~~ · ~~⑩ 헤더 값 `const` 강제자가 auth 3곳뿐~~ → **게이트 21 에서 셋 다 해소**(게이트 20 ⑤ 배치 `8b5ede6` — 델타 6/2/27 을 Claude·contract-keeper 가 **케이스 이름까지 일치**시켜 독립 재현, 2관점) · **새로 열린 것: C-1**(보호 경로 커버리지의 **메서드 축** — codex high, 게이트 20 ③ 재개봉) → **게이트 22 에서 닫힘**(`07a8bc5` — 투영을 `(경로, 메서드)` 로. privacy-gate 가 보호 목록에서 2건 제거 시 FAIL 로 음성 대조, **3관점 닫힘**) · RCH-4·RCH-5(프로덕션 판정이 kotlin 클래스 1디렉터리 · `RequestMappingHandlerMapping` 만) · A-5(「보호 자리 **전부**」가 여전히 수기 열거) | Phase 4 착수 전(3갈래 비율 회귀) · 배포 전(기록 ①) · Phase 4 문서 단위 | leader (2026-08-19, 게이트 24) |
| 소유권을 숨기는 404와 unique/check/FK 오류 매핑 | **예** (2026-08-19, 리더 · 게이트 25) | `ci:kotlin` · `ci:quality` | ~~이 행을 겨냥해 돈 것이 없다~~ → **게이트 22 에서 처음 돌았다.** 작업 공간이 첫 소유 자원이다 — **[게이트 24 R-2 정정]** 이 근거는 `WorkspaceContractTest` 가 아니라 **`WorkspaceEndpointReachTest`**(`:181`·`:189`·`:354`·`:362`·`:591`)에 있다(전수 확인: `WorkspaceContractTest` 의 `FORBIDDEN` 적중 **0**). 그 파일의 WR-3·WD-2 가 소유권 거절에 **404 이고 403 이 아님**을 `isNotEqualTo(FORBIDDEN)` 로 명시하고(contract-keeper **마감** 판정), FK 409·유일 인덱스 409·`count(d.id)` 를 `JdbcWorkspaceRepositoryTest` 가 실제 PostgreSQL 에서 잰다(`693a246`). 게이트 23 privacy-gate 가 **소유 조건을 SQL `WHERE` 에서 빼는 변이**로 탐지형을 확인했다(FAILED).<br>**[게이트 25 — 이 행을 `예`로 올린다]** 게이트 24 가 건 유일한 조건(「`6be9612` 리뷰 수령」)이 **게이트 25 로 충족**됐다 — 3관점이 같은 범위를 봤고(codex 호출 A·B 가 `--base 76f6863` branch diff 42파일, Claude 1차가 `6be9612` 를 §4.4·§4.5 에서 직접 다룸) **차단 0**이다(cross §6.1·§6.2 — 차단②는 L-1 하나이고 그 대상은 이 행의 내용이 아니라 **통로**다). **단서 둘을 함께 적는다**: ⑴ **L-1** — 그 `6be9612` 가 심판 산출물 `03_security-scanner_privacy-gate.md` §8 을 **같은 커밋으로** 편집했고 앞선 `01d78a1`·`ea36330` 도 같은 모양이다(cross §4-① 코드 대조로 3건 확정). 닫힌 것은 **수령**이지 통로가 아니다 ⑵ **H7 — 403 잔여의 종류가 열려 있다**(`HttpURLConnection.HTTP_FORBIDDEN` 주입 → BLOCK **0**. `6be9612` 가 닫은 것은 **인스턴스 2개**이고 라이브러리 상수 축은 그대로다 — codex 는 반대 방향인 **오탐**(문자열·후행 주석)을 짚어 **두 관점이 같은 뿌리에 수렴**했다) | **표 1 — `OWNERSHIP-403` 정밀화(`ea36330`)가 네 형태의 탐지를 잃었다**(개명한 403 상수 — Claude 차단② · codex high · privacy-gate 통과. **오늘 유출은 없다**(403 을 내는 프로덕션 코드 0) — 잃은 것은 「없던 것이 생기는 순간」을 잡는 능력이고, **Phase 4 가 소유 자원을 대량으로 추가한다**) → **복원 완료 `01d78a1`**(네 형태 전건 BLOCK · 전수 exit 0 · 오탐 7/7 · 음성 대조 10종) — ~~다만 그 커밋은 아직 리뷰를 받지 않았다~~ → **게이트 24 가 3관점 합의로 통과 판정**(Claude F1~F4 실측 · codex 실행 마커 7/7·2차 제외 6 동일 · privacy-gate CI exit 0·억제 7/7) · ~~1a 두 상수 미탐(`HTTP_403_FORBIDDEN`·`SC_FORBIDDEN`)~~ → **`6be9612` 로 닫힘(리뷰 미수령)** · check 제약 → 계약 오류 본문 매핑은 문서·변환 자원이 없어 미착수 · **X24-4 — 백틱 제외의 오탐 4형태**(codex 실측. 마감은 백틱 함수명을 쓰는 새 테스트가 들어오는 커밋) · **R-6 — 제외 집계 6 vs 실제 7**(권고, 마감 경과 → 리더 재지정) · **[게이트 25 신규] H6·H7 — 스캐너 403 토큰이 어휘 분석 없이 원시 줄에 정규식을 적용한다**: 열거 안에서 **과잉 적중**(`val x = "HTTP_403_FORBIDDEN"`·후행 주석 — codex 단독) 하고 열거 밖에서 **무적중**(라이브러리 상수 — Claude L-3). **두 처방이 서로를 읽지 않은 채 수렴했다**(어휘 토큰/응답 생성 호출 인자 검사 ↔ 넓힘은 인스턴스가 아니라 **종류**만큼) — 마감 **Phase 4 문서 소유권 경로 진입 전**, 수신자 `privacy-gate` · **잔여 선언 0**(`xfail(strict)` 도 `reached=False` 도 없다) | ~~`6be9612` 리뷰 수령(Phase 4 첫 게이트)~~ → **게이트 25 로 충족** · Phase 4 문서 단위 | leader (2026-08-19, 게이트 25) |
| React 를 Kotlin API 에 연결한 로그인·작업 공간 E2E | **예** (2026-08-19, 리더) | `ci:e2e` · `local:frontend/e2e/run-local.sh` | ~~이번 단위는 프런트를 건드리지 않았다~~ → **브라우저 E2E 하네스가 섰다.** `203831d`(Playwright chromium 1프로젝트·케이스 12건·계약 파서 — 읽는 값 3) · `a1e1925`(CI 잡 `e2e` 신설 — Postgres + Kotlin bootJar + Playwright) · `b3f76b2`(산출물). **로컬 실측 12/12 통과**(실 Chromium ↔ 실 Kotlin bootJar ↔ 실 PostgreSQL, 교차 출처 5173→8100, 프록시 없음). **음성 대조 6건 전부 예측대로 빨강** — 그중 **M1**(`client.ts` 401 분기)과 **M4**(서버 허용 origin)은 계획이 「어느 층도 잡지 못한다」로 적은 자리이고 이 잡이 **첫 관측자**다. 제품 코드 `src/**` 변경 **0**, 기존 잡·스위트 영향 **0**(Vitest 10파일 60건 불변). **CI 첫 실행 관측(원장 갱신 시점)**: run `32222249150`(headSha `b3f76b2`) 의 `e2e` 잡 **success**(3m37s) — kotlin·quality·frontend 도 success. **[게이트 24 R-4 정정] 그 run 의 전체 결론은 `cancelled`** 다(`llm-lane` 이 취소돼 run 전체가 그렇게 찍힌다) — 승격 근거는 **잡 단위**이지 run 결론이 아니다.<br>**[게이트 24 후] `ci:e2e` 가 흔들렸고 원인을 잡았다.** HEAD `2a4523d` 의 run `32225305372` 에서 `e2e` 가 **cancelled**(30m16s) — step 9 「Playwright 브라우저 설치」에서 취소돼 12건이 **미실행**(§8-3-1). 원인은 CDN 이 아니라 **apt**: `InRelease` 취득이 **27분 38초 무출력**이었고 apt 에 취득 타임아웃이 없어 미러 폴백이 상한 없이 대기했다. 조치 `f3de501`(설치를 apt·다운로드로 분리 + 각각 상한 6분/8분 + `Acquire::*::Timeout 20`·`Retries 2`, apt 는 `continue-on-error`). **두 갈래 관측 완료**: run `32229496368`(`f3de501` · 캐시 미적중 — 설치 14초 · apt 39초 · e2e **success** 3m53s) · run `32230037832`(`3ea1983` · 캐시 **적중** — 설치 **skipped** · apt 1분56초 · e2e **success** 4m21s). **apt 가 같은 명령·같은 이미지에서 39초↔1분56초로 3배 흔들린다** — 28분은 그 분산의 꼬리였고 상한이 본 조치, 캐시는 부수 효과다 | **승격 근거는 계획 §1-2 의 「Phase 3 몫」 정의**이고 그 밖은 이 행이 애초에 지지 않는다 — 남은 것은 **Phase 6**(생성 타입 교체·a11y·nginx 프록시·전체 업무 흐름)과 **자원이 생겨야 쓸 수 있는 케이스**(삭제·타인 자원·이메일 형식 — 이메일 형식은 §6 ⑰ 종속) · **이 하네스가 막지 못하는 것 = 잡 `e2e` 자체의 삭제와 `frontend/e2e/` 통째 삭제**(「경로 명시」 규약을 이 잡에 옮겨 붙일지는 리더 판정 대기) · OQ-E5 compose 주석 드리프트 | Phase 6 | leader (2026-08-19, React E2E) |
| **종료 조건**: contract test 와 React 테스트가 Kotlin API 에서 통과 | 아니오 | `ci:kotlin` · `ci:frontend` · `ci:e2e` | auth 계약 테스트가 **계약 파일을 직접 파싱**해 돈다(`f9ee3e6` — 착수 지침 4/OQ-3·X15 마감 집행). **파서 요건 P-1~P-12 전건 실재·호출, 계약 값 하드코딩 0**(2관점). 음성 대조 **N-1~N-8 결속**(Claude 9회 — 수정 전 `f9ee3e6` 되돌림 재현 포함 · contract-keeper 4건 + 신규 3).<br>**[게이트 22·23]** `WorkspaceContractTest` 가 계약 케이스 **36건** + 파서 P-16~P-21 로 붙었고(`951b1fd`), 파싱 거절 열거자가 계약과 **집합으로** 대조된다(`5b28851` — 계약 정정 `4a25a7c` 로 「알 수 없는 메서드 405」가 서블릿까지 도달함이 확정돼 7종 → 6종). `ContractSpec` 파서의 **fail-open 두 자리**가 닫혔다(`9e2ce96`). **React 축은 `ci:e2e` 가 처음 열었다** — 행 5 참조(브라우저에서 실 Kotlin API 대상 12/12, 계약 파일 변이 3건이 각각 겨눈 케이스만 빨강).<br>**[게이트 24] 파서 fail-open 이 전건 닫혔다** — ⑴ `errorDetailUnionTypes` 의 **정확 일치**를 Claude 가 3방향으로 재현하고 codex 가 「지적 없음」을 명시했다(`560c292`) ⑵ **X24-5 인라인 헤더**: `collectHeaderRefs` 의 `?: return@forEach` 를 없애고 `headerDeclarations()` 로 갈래를 세어 **fail-closed 4자리**를 세웠다(`44eec3f`). **전제 정정 1건 — 「오늘 계약에 인라인 헤더 0건」은 사실이 아니었다**: 실측 **2건**(`Location` @ `POST /documents` 202 · `Content-Disposition` @ `GET /conversions/{id}/export` 200 — 둘 다 값이 계산돼 `const` 로 못 박을 수 없다). 그래서 codex 처방 문면(`$ref` 없으면 무조건 실패)은 **채택하지 않고** 갈래로 나눠 셌고, `ContractHeaderDeclarationTest` 가 인라인 집합을 `[Location, Content-Disposition]` 으로 **고정해 셋째가 들어오는 커밋을 실패시킨다**(마감의 강제자). 부수로 `$ref` 응답을 따라 들어가게 되어 `WWW-Authenticate` 가 처음 이 표에 올랐다 | ~~**⑭ 계약 파일이 Gradle 테스트 태스크의 선언된 입력이 아니다**~~ → **게이트 21 에서 해소**(`2660252` — 4조건을 Claude·contract-keeper 가 **2관점 독립 재현**: 무변경 `UP-TO-DATE` · 계약만 변경 시 재실행 · `cleanTest` 후 `FROM-CACHE` · 계약 값 변이 시 빨강). **다만 잰 것은 로컬 증분·로컬 캐시다 — CI 원격 캐시 거동은 4관점 중 0관점**(다음 push 의 CI 실행이 첫 관측) → **push 는 이뤄졌고 `kotlin` 잡이 세 실행에서 초록이다**(`ea36330`·`e9502a6`·`b3f76b2`) — 그러나 **「계약만 바꿨을 때 원격 캐시가 재실행을 내는가」는 여전히 아무도 재지 않았다.** 잡이 초록인 것과 캐시 거동을 잰 것은 다르다 · RCH-1(`inputs.file` 선언 문구가 실제 `subprojects` 보다 넓다) · **나머지 11 엔드포인트 계약 테스트 없음** · ~~React 테스트는 Kotlin API 를 겨냥해 돈 적이 없다~~ → **`ci:e2e` 12건이 겨냥한다**(행 5) · ~~**표 5 — `ContractSpec` 잔존 fail-open 3자리**~~ → **전건 닫힘**(union `560c292` **2관점 해소** · 나머지 2자리 `44eec3f` — 심각도 라벨만 사용자 판단으로 남는다) · **표 7 — D-2 앵커의 숫자→예시 이름 매핑이 계약에 안 묶였다**(C-3 — 마감 「Phase 3 종료 전」 **경과**, 무변경) · **행 20 — 종료 조건 (a) 「Kotlin API 에서」의 해석**: `AuthContractTest`·`WorkspaceContractTest` 는 `@WebMvcTest`+가짜 저장소이고 실 소켓은 별도 `ReachTest`, 실 bootJar 는 React E2E 다(codex 단독 사실 · 판정 아님) | CI 원격 캐시 관측(0관점) · 표 7(D-2 앵커) · Phase 4 나머지 엔드포인트 | leader (2026-08-19, 게이트 24) |
| **종료 조건**: 계약 개선이 있었다면 계약 파일·Kotlin·React 가 같은 내용을 담고 근거 기록 | 아니오 | `1회성:docs/migration/_workspace/03_contract-keeper_auth-verification.md` · `1회성:docs/migration/_workspace/03_contract-keeper_workspaces-verification.md` | auth 축은 **계약 소유자 독립 검증**(`6ece404`)이 파서·X-F11~13·음성 대조 재현으로 대조하고 도달 결함 1건을 등재했다. ~~다만 3자 중 React 는 대조되지 않았다~~ → **게이트 22·23 에서 세 자리가 움직였다**: ⑴ 계약 정정 `4a25a7c`(파싱 거절 7종 → **6종** — 「알 수 없는 메서드 405」는 서블릿까지 도달한다) · ⑵ **D-2 조항 신설**(`0fe654c` — 삭제 거절 두 갈래가 겹칠 때의 순서. 근거 **G2**, blast radius 1 오퍼레이션, React 무영향)과 그 회귀 결속 `WD-9`(`c663714`) · ⑶ **React 대조가 처음 이뤄졌다** — contract-keeper 가 수기 `types.ts`·`client.ts` 를 계약과 맞대 **런타임이 깨지는 드리프트 0건** · 타입만 어긋남 3 · 계약 침묵인데 React 가 값을 든 자리 2 · 화면 문구로 나가는 침묵 1 을 등재했다(`16f3f48` §2).<br>**[게이트 24] 3자 동일 이전에 「계약 1자가 자기와 달랐다」가 닫혔다** — `x-auth.failure_uniformity`(무헤더 **없음**)와 `components/responses/Unauthorized.description`(무헤더 **있음**, 세 줄 뒤 「메시지는 두 가지가 나온다」로 자기 반증)의 내부 모순을 `dec3124` 가 정합했다(정본 = `x-auth`, 열거 복사 제거 · 근거 G1 · blast radius: `Unauthorized` 참조 12 오퍼레이션의 description 만). **codex 가 3자 드리프트를 직접 관측**해 게이트 22 contract-keeper 등재분과 같은 자리임이 확인됐다(`types.ts:18-34` 통합 `CredentialsRequest`·넓은 `token_type` ↔ 계약 분리 스키마·`const bearer` ↔ `AuthDtos.kt:34-91` 분리 DTO — **2관점**) | ~~3자 중 React 대조 없음~~ → **대조는 됐고 남은 것은 교체다**(Phase 6 생성 타입) · ⑥ 이메일 ASCII 정책은 **4관점 병기**(3 허용 : 1 미승인, contract-keeper 허용 판정 유지 — 사용자 판단 대기)이고 ⑦ 비ASCII 422 회귀 단언은 **3관점 합의로 Phase 4** · ⑯ X6 강제자 축 **나머지 3필드**(`DocumentTextRequest.text`·`ConversionReviewRequest.edited_text`·`WorkspaceNameRequest.name`) — **auth 2필드 축은 리더 판정 ③ 으로 닫혔다** — `WorkspaceNameRequest.name` 은 `e4be6ff` 로 들어왔다 · **OQ-E2 이메일 형식 규칙 게시 여부** — 규칙이 Kotlin·React 소스 **두 곳에 따로** 있고 계약은 침묵한다. 근거는 G1+G2 로 서지만 **게이트 20 ⑥ ASCII 정책이 사용자 판단 대기**라 그 종속물이다 · OQ-E3(409 중복 이름 `detail` 침묵 — 침묵 유지 판정 불변, 화면 문구가 계약 밖이라는 사실만 등재) · OQ-E4(`GET /workspaces` 무상한 — 근거 G 를 지목할 수 없어 바꾸지 않는다) · **X-A2 잔재 — `00_contract-keeper_test-plan.md` §2 X-A2 행이 옛 넓은 문면(「헤더 누락」 포함)을 든 채 남았다**(`dec3124` 가 계약을 좁힌 뒤의 문서 드리프트. 강제되는 동작은 어긋나지 않는다 — 별도 **문서 커밋**에서 정정) | 사용자 판단(⑥ → OQ-E2) · 문서 커밋(X-A2) · Phase 4 DTO 커밋(⑯·T-1) · Phase 6 (타입 교체) | leader (2026-08-19, 게이트 24) |

## Phase 3 auth 단위 — 구현과 게이트 20·21 (2026-08-19, 리더)

§1~§5 는 **게이트 20** 이고 **§6 이 게이트 21**(조치 배치 `bf08edd..3c5c8ad`)이다.
게이트 20 정본은 `reviews/03_auth_cross.md`(`bf08edd`)다. 이 절은 그 판정과 후속 조치 배치를 원장 형식으로
옮긴 것이며 **수치·판정에는 커밋 해시를 함께 적는다**(기준 시점 없는 절대 수치는 다음 커밋에 거짓이 된다).

> 이 절의 표들은 종료 조건 표가 아니라 **처리 현황 표**라 표기 검사기(`tests/test_harness_scope_reach.py`)의
> 대상 표에 들어가지 않는다 — 그래도 같은 어휘를 쓴다. `실행 경로` 어휘 정본은 위 Phase 0 표의 포인터를 따른다.

### §1 — 구현 요지 (`05862fa..fc21750` 10 커밋 + `6ece404`·`bf08edd`)

| 커밋 | 내용 |
|---|---|
| `05862fa` | auth 계약 테스트 **명세** — 케이스 표·OQ-3 파서 요건·계층 지목 (contract-keeper) |
| `7a75f29` | **설정 바인딩 잠재 결함 수정** — 아래 참조 |
| `b87de0b` | 인증 도메인·포트·유스케이스 (`core`·`application`) — `User`/`StoredUser`·`PasswordHash`(마스킹 `toString`·상수 시간 비교)·포트 6종·**F3 두 필드를 서비스 층에서 판정**(착수 지침 2) |
| `3da2d51` | Argon2id·JWT·JDBC repository (`infrastructure`) — 가입과 기본 작업 공간 생성이 한 트랜잭션 |
| `0b81fa6` | `/auth` 3 엔드포인트와 인증 인터셉터 (`api`) — 보호 경로 목록은 **계약이 판정** |
| `f9ee3e6` | **계약 파일을 직접 파싱**하는 auth 계약 테스트 (착수 지침 4 / OQ-3 · X-J2 · X15 마감) |
| `11bc502`·`7e8c21a` | detekt·ktlint 대응 (파싱 분해 · 들여쓰기 정규화) |
| `9341e69` | 음성 대조가 드러낸 두 자리를 계약에 결속 (N-3 `CacheControlNoStore.schema.const` · N-4 `x-auth.clock_skew_seconds`) |
| `fc21750` | 구현 산출물 — 대응표·음성 대조·발견 결함 |
| `6ece404` | 계약 축 **독립 검증** (파서 P-1~P-12 · X-F11~13 · 음성 대조 재현 · 도달 결함 1건) |
| `bf08edd` | 게이트 20 산출물 4종 |

**스키마 변경 없음** — `users`·`workspaces` 는 `V1__python_schema_baseline.sql` 그대로이고 V3 를 만들지 않았다.

**잠재 결함 1건 — 기존 코드에서 드러났다(`7a75f29`).** Kotlin 은 주 생성자 파라미터가 **전부 기본값**이면
public 무인자 생성자를 하나 더 만든다(`javap` 실측: `EasyDocProperties` 생성자 3개). 그러면 non-synthetic
생성자가 둘이라 Spring 이 바인딩 생성자를 **추론하지 못하고**, 남은 경로인 Kotlin 주 생성자 조회는
`kotlin-reflect` 를 요구하는데 실행 클래스패스에 **없었다**(락파일 실측 0건). 결과는 JavaBean 바인딩이라
`No setter found for property: jwt-secret` 로 기동이 끊긴다. **오래 안 보인 이유**: `JavaBeanBinder` 는 바인딩
값이 기존 값과 같으면 예외를 던지지 않고, 지금까지 설정이 전부 기본값과 같았다 — **기본값과 다른 값을
처음 넣은 것이 이번 작업**이다. 처방은 `@ConstructorBinding`(실측 불가 — Kotlin 이 무인자 생성자에도 복사)·
기본값 제거(클래스마다 손으로 지켜야 함) 대신 **`kotlin-reflect` 실행 의존**을 골랐다 — 결함이 클래스 하나가
아니라 「data class + 전 파라미터 기본값」이라는 **형태 전체**의 문제이기 때문이다. 회귀 장치
`ConfigurationPropertiesBindingTest` 는 세 설정 클래스에 **기본값과 다른** 값을 바인딩한다(기본값을 넣으면
결함이 있어도 초록이므로 다르게 고른 것이 요점). 게이트 20 이 이 처방에 **음성 대조를 붙였다** —
contract-keeper 가 락 재생성까지 하고 의존을 제거하자 **양 레인 빨강**(`BindException` · `No setter found`).

**X6 강제자 축 — auth 2필드 닫힘 (리더 판정 ③).** `안 돎` → 실행 소스 실재로 바뀐 것은 `email`·`password`
**2필드**다. `DocumentTextRequest.text`·`ConversionReviewRequest.edited_text`·`WorkspaceNameRequest.name`
3필드는 **각자의 DTO 커밋으로 이월**되며 T-1(snake_case 미매칭)이 그 이월분의 강제력을 위협한다.

### §2 — 게이트 20 확정 결함과 조치 배치 (**진행 중**)

| # | 항목 | 심각도 | 마감 | 처분 | 관점 |
|---|---|---|---|---|---|
| **①** | **B-1 ≡ codex C1 ≡ Claude C-3 — 로그인 타이밍 열거.** 더미 해시 미구현 + 시간 축 회귀 장치 0 + 계약 조항 해석 갈림 | **차단 ①사건**(privacy-gate) · codex **high** | **auth 단위 종결 전** | **리더 판정 ① — 구현으로 닫는다**(조항 개정이 아니다). 해제 조건 3항: 더미 PHC 실검증 · 시간 축 회귀 테스트 · 음성 대조. 실측 근거: **2.3ms vs 97ms · 42배**, Argon2 1회 109.5ms(privacy-gate 결정적 측정) | **4관점**(3 지적 : 1 반대) |
| **②** | **codex C2 — Argon2 세마포어 타임아웃·격리** | **판정 필요**(codex high ↔ Claude 권고 ↔ privacy-gate 통과) | ①과 동반 | **리더 판정 ② — ①의 처방이 이 경로의 부하를 늘리므로 ①과 한 단위로 조치한다**(3관점이 결속에 합의). **심각도 라벨은 사용자 판단 대기**. 실측 40 동시는 Tomcat 스레드 상한(기본 200) **미만**이라 주장된 고갈 지점에 닿지 않았다 — **동시성 ≥ 200 구간은 어느 산출물도 재지 않았다**(cross §9 ②) | 2관점 지적 ↔ 1관점 통과 |
| **③** | **codex C3 ≡ Claude T-2 — 보호 경로 커버리지의 두 번째 손 목록** | **수정 필요**(codex high) | **보호 엔드포인트를 추가하는 바로 그 커밋** (= Phase 3 다음 단위) | **리더 판정 ⑥ — 자동 발견으로 처방**한다. 그리고 privacy-gate 의 **통과 판정과 2관점 지적을 양쪽 다 남긴다**(어느 쪽도 지우지 않는다). B-6 실측이 그 시나리오에서 **초록**임을 보였다 | 2관점 지적 ↔ privacy-gate 반대 견해 |
| **④** | **contract-keeper §5 — 계약 파일이 Gradle 테스트 태스크의 선언된 입력이 아니다.** 계약만 바꾸면 `:api:test` 가 `UP-TO-DATE`/`FROM-CACHE` 로 **한 번도 안 돌고 exit 0** (4조건 실측) | **심각도 미부여**(단독 발견 규약) | **Phase 3 종료 전** (당김 여부 리더 판단) | 배치 포함. **세 레인이 같은 캐시 거동에 조우했고 한 레인만 결함으로 등재했다** — Claude §7-1 ⑴(초판 지시에 `--rerun-tasks` 가 없어 계약 변이 미반영 결과를 볼 뻔했다, "내 발견으로 적지 않는다") · privacy-gate("최초 실행이 `UP-TO-DATE` 로 건너뛰었다 — 캐시 결과는 실행 증거가 아니다"). **한계**: 잰 것은 로컬 증분·로컬 캐시이고 **CI 캐시 거동은 관측되지 않았다** | 등재 1레인 / 조우 3레인 |
| **⑤** | **C-1**(422·`/auth/me` 401 계약 대조 8건 없음) · **C-2**(X-D2 오류 응답 전역 헤더 5건 중 2건 — 특히 M-2) · **C-6**(헤더 값 `const` 강제자가 auth 3곳뿐 — 전역 헤더 테스트가 계약을 안 읽는다) · **T-1**(`RequestFieldConstraintLayerTest` 의 snake_case 프로퍼티 매칭이 0건일 수 있고 도달 지표가 클래스 발견만 센다) · **T-7**(「보호 목록 비우기」 음성 대조가 **겨눈 방향의 반대**를 잰다 — 117 중 65는 `/health` 401 등 **과잉 보호 부수 피해**) | 수정 필요 5 | Phase 3 종료 전 (**T-1 은 Phase 4 해당 DTO 커밋**) | 배치 포함. **C-1 의 심각도는 갈렸다**(Claude 수정 필요 ↔ privacy-gate 낮음) — **리더 판정 ④ 로 라벨 다툼과 무관하게 조치에 넣는다.** T-1 은 contract-keeper 가 같은 장치를 *"명세보다 나은 쪽"*으로 평가한 자리라 **⑯ X6 표기와 함께 판정한다**(cross §9 ③) | C-1·C-2·C-6·T-7 각 1~2관점 · T-1 지적↔긍정 |
| **⑥** | **codex C4 — Jackson scalar coercion** | codex **medium · 미검증** | **재현 1회로 판정된다** | **실측 후 판정.** **4관점 중 0이 실행했다** — privacy-gate 의 signup 변종 13종은 `422(형식)`·`422(필드누락)`·`깨진 JSON` 이고 coercion 케이스가 아니다. codex 자신도 *"의존성 bytecode 에 근거한 추론"*으로 신고했다 | codex 단독 |
| **⑦** | **H-1 — 하네스 탐지 집합의 양성 경로가 한 번도 실행되지 않는다** | 권고 / **판정 필요** | 리더 판정 | 배치 포함. 리더 결정 ⑴(러너 동결)의 대상인지가 쟁점이다 | Claude 단독 |
| **⑧** | **L-1(기록) — `Argon2PasswordEncoder` 가 우리 통제 밖에서 WARN + 전체 스택트레이스를 찍는다**(`Malformed password hash`). 현재 예외 메시지는 상수라 값 유출은 없으나 `application.yml` 이 `org.springframework.security` 로그 레벨을 **고정하지 않는다** — 라이브러리 판올림이 메시지에 해시를 실으면 **조용히 유출된다** | privacy-gate 개선 권고 | Phase 3 종료 전 | 배치 포함(레벨 명시 또는 회귀 결속). **L-2**(Tomcat 400 로그가 요청 대상을 그대로 찍는다)는 **Phase 4 이후 새 엔드포인트 설계 제약**으로 남긴다 | privacy-gate 단독 |

### §3 — 리더 판정 6건 (cross §11 이 판단을 요청한 ①~⑥ 그대로)

> **어느 쪽도 지우지 않는다** — 양쪽 근거 전문은 cross §3 의 해당 소절에 있고 **여기로 옮겨 적지 않는다**.

| # | 판단 요청 | **리더 판정** |
|---|---|---|
| **①** | 타이밍 처분 — **구현 ↔ 조항 개정** | **구현으로 닫는다.** 계약 문면 `:299-302` 대조로 조항 안임이 확인됐고 표 ①은 **3관점 독립 합의**다. 마감 auth 단위 종결 전(§2 ①) |
| **②** | 세마포어 심각도와 ①과의 **동반 여부** | **①과 한 단위로 조치한다**(동반 인정). **심각도 라벨은 판정하지 않고 사용자 판단 대기로 올린다** — 3관점이 결속에는 합의했고 갈리는 것은 라벨뿐이다 |
| **③** | **X6 표기** — 닫힘 ↔ 부분 해소 | **auth 2필드 축은 닫힘**(`email`·`password` — 실행 소스 실재, 음성 대조 결속). **나머지 3필드는 각자의 DTO 커밋에서** 닫는다. 두 진술이 세는 대상이 달랐다(강제자 실재 ↔ 5필드 중 도달 수)는 cross 의 확인을 그대로 채택했다 |
| **④** | **C-1 심각도** — 수정 필요 ↔ 낮음 | **라벨을 확정하지 않고 조치 배치에 넣는다**(§2 ⑤). 어느 라벨이어도 마감(Phase 3 종료 전)과 처방이 같으므로 라벨이 결과를 바꾸지 않는다 |
| **⑤** | **하네스 동반 편집 처분** — 리더 결정 ⑶ 유지 ↔ 재개 | **리더 결정 ⑶ 을 유지한다** — 동반 편집(표 ⑤·M4 초록)은 **「한계」로 등재**하고 자동 강제자를 더 세우지 않는다. 「테스트 파일 안의 단언을 지키는 또 다른 단언」의 재귀를 여기서 끊고 **사람의 diff 리뷰에 맡긴다**는 기존 결정이 이 게이트의 실측(codex·Claude 12변이)으로도 뒤집히지 않았다 |
| **⑥** | **보호 경로 기록 방식** — privacy-gate 통과 판정과 2관점 지적을 어떻게 남기는가 | **양쪽을 병기하고 처방은 자동 발견으로 간다**(§2 ③). 통과 판정을 지우지 않는 이유는 그것이 **실행 근거**(B-6 초록)이고, 지적을 지우지 않는 이유는 그것이 **다음 단위의 마감**을 정하기 때문이다 |

### §4 — 사용자 판단 대기 (양쪽 근거 병기 — 어느 쪽도 지우지 않는다)

> **11건은 「게이트 19 후속」 §3 에서 그대로 이월**되고 **근거 전문은 그 표에 있다**(여기서 요약하지
> 않는다 — 옮겨 적으면 갈린다). 아래 ⑫·⑬ 이 게이트 20, **⑭·⑮ 가 게이트 21** 에서 새로 올라온 것이다.

| # | 쟁점 | 양쪽 |
|---|---|---|
| **⑫** | **C2 세마포어의 심각도 라벨** | codex **high**(스레드 풀 고갈) ↔ Claude **권고** ↔ privacy-gate **통과**(40 동시 실측, `/health` 무영향). **결속(①과 동반)에는 3관점이 합의**했으므로 판정 결과가 조치 여부를 바꾸지 않는다 — 남는 것은 라벨이다. 실측이 닿지 않은 구간(동시성 ≥ 200)이 있다는 사실을 함께 둔다.<br>**[게이트 21] 그 미측정 구간이 닫혔다** — privacy-gate **240 동시**(`3c5c8ad`): 500 **36건(15%)** · 로그인 중앙값 **~3.9s** · `/health` **최대 1244ms**. 원인은 permit 수가 아니라 **대기 상한 `maxHashWaitMillis = 5_000` 자체**(R-2). 라벨은 **여전히 갈린다** — privacy-gate **통과(불변식 아님 · 가용성 기록)** ↔ Claude KTL-2 **권고(값에 근거 없음)** ↔ codex **high**. 처방(상한 대폭 축소 + HTTP 경계 회귀)은 리더가 §6 ⓖ 로 확정했으므로 **라벨이 조치를 바꾸지 않는다**는 사정은 그대로다 |
| **⑬** | **이메일 ASCII 정책** — 비ASCII 로컬파트를 422 로 막는 현행 동작 | **4관점 병기(3 허용 : 1 미승인)**. **contract-keeper 의 허용 판정을 유지**한다(계약 소유자 축). 별건으로 ⑦ **비ASCII 422 회귀 단언 신설**은 **3관점 합의**이고 마감은 **Phase 4** 다 |
| **⑭** | **세마포어 배압의 응답 코드 설명 개정 — 계약 소유자가 `escalate_to_leader` ④ 로 올린 것**(배포·운영 동작이 달라지는 변경). 리더는 **500 유지**를 채택했고(계약 위반 아님 — §6 ⓐ), 남은 것은 `InternalError`/`ServiceUnavailable` **설명 문면 개정 여부**다 | **개정안**(contract-keeper §1-4 — 문면 **4곳** 제시) : 현행 문면이 500 의 세 갈래를 「매핑되지 않은 도메인 예외 / 도메인 밖 예외 / `StorageError`」로 한정하는데 **의도된 배압**은 그 셋 중 어느 것도 아니다(Claude CON-1 이 독립 도달). 503 으로 넓히면 근거 G2 가 서지만 `ServiceUnavailable` 을 **12 오퍼레이션이 `$ref`** 해 문서·작업 공간 API 전부의 503 의미가 바뀐다 — 문서 API 의 503 은 지금 「운영자가 키를 넣어야 한다」는 **배포 신호**로 읽힌다 ↔ **무개정안**(같은 문서, 계약 소유자가 **함께 올림**) : 현행 500 이 계약을 만족하므로 무개정이 **blast radius 0**. 비용은 §1-3 — 사용자에게 서버 결함으로 보이고 ERROR 로그가 건마다 찍힌다. **React 영향 없음**(`client.ts:128` 이 401 하나만 분기, 500↔503 어느 쪽도 화면은 `detail` 그대로). **바꾸지 않기로 하는 것도 판정**이라 무개정을 고르면 그 근거를 적어 같은 제안이 다시 올라오지 않게 한다. **0관점 — `InternalError` 개정의 blast radius 는 아무도 재지 않았다**(`ServiceUnavailable` 12곳만 셌다) |
| **⑮** | **SEC-2 — `POST /auth/signup` 409 가 1요청 계정 열거 오라클** | **Claude SEC-2**(판정 필요) : 계약 `:789`·`:1475` 의 409 는 이미 가입된 이메일을 **1요청으로** 확정해 준다 — 로그인 쪽 `failure_uniformity` 사유(타이밍까지 균일하게 만든 그 요구)와 **같은 자산을 반대 방향으로 연다** ↔ **privacy-gate 반대·부분** : 시간 축은 **1.027배로 「없음」**이고, **409 코드 자체의 노출은 계약이 승인한 사항**이라 보안 축이 뒤집을 자리가 아니다. **갈리는 것은 「계약 승인이 이 사유와 정합하는가」**이고 그 판정은 계약·제품 결정이다 |

### §5 — 사실 기록 4건

| # | 사실 | 근거 | 왜 남기는가 |
|---|---|---|---|
| ① | **privacy-gate 가 값이 아니라 성질로 쟀다** — Argon2 재해시는 **파라미터를 낮춰 재기동**해 "성공한 쪽만 갱신"을 실측했고, JWT 는 `exp` **−59s 에서도 401**(skew 0)을 확인했으며, 사적 헤더는 **원시 소켓 7종을 포함한 20변종 전건** `no-store=1 nosniff=1`, 이메일 비ASCII는 **6종 422** | `reviews/03_security_privacy-gate.md` · cross §7-1 | 재개발 전환 이후 판정 근거가 **값 동일성에서 성질 충족으로** 바뀐 첫 인증 단위다. "Python 과 같은 바이트"가 아니라 **round-trip·거부·경계**로 닫았다는 것이 이 회차의 형태다 |
| ② | **contract-keeper 가 구현자의 자기 신고를 재현해 「거짓 초록」 하나를 확정했다** — N-4 는 신고대로 첫 실행 exit 0(**M-6b 신설이 그 결속을 혼자 진다**), **N-3 은 「전부 초록」이 아니라 한 계층만 초록**이었다(수정 전 `AuthContractTest` S-1 하나만 빨강 → 정정 후 S-1·L-1·M-1 빨강). **실측 계층(C-R)이 눈이 멀어 있었다** | `03_contract-keeper_auth-verification.md` §(N-3·N-4 재현) · `9341e69` | **자기 신고가 사실이어도 그 뜻이 다를 수 있다**는 실측 1건이다. "통과했다"가 "겨눈 자리에서 통과했다"를 뜻하지 않았다 |
| ③ | **codex 축 ③(음성 대조 N-1~N-8 재현)은 미수행이었고, 8항목 중 7항목이 다른 관점의 실행으로 메워졌다** — 남은 하나가 **C4** 다 | cross §5 | **미수행을 「지적 없음」으로 읽지 않기 위해서다.** 메워진 자리 중 일부는 **요구보다 넓게** 메워졌고(수정 전 상태 재현은 요구에 없었다), 안 메워진 하나는 **4관점 중 0** 이다 — 이 비대칭을 표에 남긴다 |
| ④ | **codex 축 ②·⑤ 의 침묵은 「검토하고 문제없다」가 아니다** — focus 가 요구한 「지적 없음」 명시가 출력에 없다. `@Size`/`no-store`//error 누출/401 두 갈래/409·레이어 분리·JDBC SQL·트랜잭션 원자성이 그 범위다. Claude·privacy-gate 가 각각 덮었으나 **교차가 아니라 단독 확인**이다 | cross §10 | 위 Phase 3 표의 첫 행이 이 사실을 `미해결 항목` 으로 들고 있다. **덮이지 않은 것과 덮였는데 안 갈린 것을 같은 칸에 적지 않는다** |

### §6 — 게이트 21 결과와 리더 판정 (`bf08edd..3c5c8ad` · 완주 `d04ad98`)

정본은 `reviews/03_auth-fixes_cross.md`(`d04ad98`)다. **양쪽 근거 전문은 그 문서 §4·§5 에 있고
여기로 옮겨 적지 않는다.** 아래는 리더가 내린 처분과, 처분에 필요한 사실만 담는다.

**게이트 20 8항목 종결 (cross §8).** 해소 **5**(①·④·⑤·⑥ 타입 불일치 축·⑦) · 부분 해소 **2**(② 세마포어 ·
⑧ L-1) · **재개봉 1**(③ — codex C-1 이 같은 항목의 **더 세밀한 축**에서 다시 열었다).

| # | 쟁점 | **리더 판정** |
|---|---|---|
| **ⓐ** | **CON-1(Claude) ↔ contract-keeper 판정 ①** — 세마포어 배압의 500 이 계약 위반인가 | **500 을 유지한다 — 계약 위반이 아니다**(계약 소유자 축 채택). 구현을 바꾸지 않는다. **다만 두 관점의 문면 읽기는 둘 다 정확하다**(cross §2-2 — `InternalError` 도 원인을 세 갈래로 한정하고 `ServiceUnavailable` 은 `ConfigurationError` 하나로 좁힌다). 갈리는 것은 문면이 아니라 **그다음**이고, 그 「그다음」이 ⓑ 다 |
| **ⓑ** | `InternalError`/`ServiceUnavailable` **설명 개정** | **리더가 판정하지 않는다 — `escalate_to_leader` ④ 로 사용자 판단에 올린다**(§4 ⑭). 계약 소유자가 §1-5 에서 **단독 시행하지 않겠다**고 명시하며 문면 **4곳**과 **무개정 대안**을 함께 올렸고, 실질이 「배포·운영 동작이 달라지는 변경」(escalate ④)이기 때문이다. 리더가 여기서 고르면 그 절차를 우회하는 것이 된다 |
| **ⓔ** | **SEC-2 — signup 409 계정 열거 오라클** | **리더가 판정하지 않는다 — 사용자 판단**(§4 ⑮). privacy-gate 가 *"409 코드 자체의 노출은 **계약 승인 사항**"* 이라고 판정했으므로 이것은 보안 축이 뒤집을 자리가 아니라 **계약·제품 결정**이다. Claude 의 지적(로그인 쪽 `failure_uniformity` 사유와 같은 자산을 반대 방향으로 연다)은 **지우지 않고 병기**한다 |
| **ⓖ** | **② 세마포어 — 3관점이 서로 다른 결론** | **대기 상한을 대폭 축소하고 HTTP 경계 테스트를 신설한다.** 근거는 R-2 실측이다 — 240 동시에서 `/health` **최대 1244ms**, 그리고 원인이 permit 수가 아니라 **`maxHashWaitMillis = 5_000` 자체**다(5s 대기가 Tomcat 스레드를 붙잡아 소진시킨다). 상한값에 근거가 없다는 KTL-2 와 배압 응답 회귀가 0건이라는 TST-2 가 **같은 처방 하나로 닫힌다**. **심각도 라벨은 여전히 사용자 판단 대기**(§4 ⑫) — 처방이 확정됐으므로 라벨이 조치를 바꾸지 않는다 |
| **ⓗ** | **SEC-1 ≡ R-1** — 정책 상향 후 옛 파라미터 계정의 역방향 타이밍 격차(**1.834x / 43.9ms** 실측) | **B-1 재발로 보지 않는다 — 운영 지침으로 등재한다**(privacy-gate 처분 채택). 오늘 도달 0 이고(정책 미변경) **정책을 바꾸는 날 열린다**. 등재 내용: 「Argon2 파라미터를 올리면 **미로그인 계정**이 옛 파라미터로 남아 로그인 성공/실패 간 격차가 되살아난다」. **L-3b 회귀가 이 자리를 못 본다** — 그 테스트는 「없는 이메일 ↔ 틀린 비번」을 재는데 이 격차는 **옛 파라미터 계정 ↔ 더미** 사이에서 벌어지기 때문이다. 그래서 장치가 아니라 절차로 닫는다 |
| **ⓘ** | **L-1** — `security: INFO` 가 지목된 WARN 유출 경로를 막지 않는다(2관점 합의 · privacy-gate 실측) | **탐지형 회귀로 닫는다 — 억제형으로 넓히지 않는다.** 로그 레벨을 더 조이거나 무시 패턴을 다는 것은 **은폐형**이라 하네스 규칙 4가 금한 방향이다. 대신 「깨진 PHC 를 주입했을 때 그 WARN 에 해시가 실리지 않는다」를 **단언하는 테스트**를 세운다. 지금은 그 한 줄을 지키는 장치가 **0**이다(RCH-2) |
| **ⓙ** | **C-1**(codex 단독 high) — 보호 경로 자동 발견이 경로 단위 투영이라 계약 밖 **메서드** 추가를 놓친다 | **실결함으로 받는다 — 투영을 `(method, path)` 로 바꾼다.** `servedPaths(): Set<String>` 이 `flatMap { it.patternValues }` 로 **경로만** 남겨, 같은 경로에 보호돼야 할 메서드가 하나라도 있으면 새 미보호 메서드가 커버리지에 잡히지 않는다. **codex 만 이 자리를 봤고 나머지 세 관점은 이 항목을 보지 않은 상태에서 「차단 0」을 적었다** — 그 사실을 심각도 완화 근거로 쓰지 않는다 |
| **ⓚ** | **M-1** — 더미 PHC 의 선언된 불변식이 거짓(`DUMMY_PHC_SOURCE` 자신을 넣으면 `verify` true) | **난수 더미로 고친다.** 인증 결과를 바꾸지는 않지만 **「재해시가 더미에 도달하지 않는다」의 근거로 적힌 성질이 거짓**이라, 다음 사람이 그 KDoc 을 믿고 판단하면 틀린다. 상수 소스를 지우고 기동 시 난수에서 조립한다 |

**auth 단위 종결 판정 — 조건부 종결 (리더).**

4관점 중 **차단 0**이다 — privacy-gate *"어느 것도 배포 승인을 막지 않는다 · §5 Phase 7 즉시 중단
기준 해당 없음"* · contract-keeper *"차단 사유 0건"* · Claude 1차 *"차단은 0이다"*. **codex 만
`needs-attention`("출하 차단")** 이고 그 근거 셋 중 C-2·C-3 은 다른 관점도 같은 자리를 보고 차단으로
세지 않았다. 남는 **C-1 은 codex 단독**이며, 위 ⓙ 대로 **장치 결함**(커버리지가 메서드를 버린다)이라
조치 배치에 넣는다 — 「출하 차단」을 기각하는 것이 아니라 **처분을 배치로 옮기는 것**이다.

**조치 배치는 커밋 후 별도 게이트를 걸지 않고 게이트 22(`workspaces`) 범위에 포함한다.** 근거 둘:
⑴ 4관점 어디에도 차단이 없다, ⑵ 위 8항목이 **전부 소규모이고 측정을 동반**한다(투영 한 줄 · 난수 조립 ·
상수 축소 + HTTP 테스트 · 하한 단언 · 탐지 테스트 · 구현 한 갈래 · 열거자 2개). **한 배치를 위해
게이트를 하나 더 여는 비용이 그 배치가 실을 위험보다 크다**는 판단이다. 대신 게이트 22 의 범위를
`auth-fixes2` 배치까지로 **명시해** 리뷰 미수령분이 생기지 않게 한다(게이트 19→20 에서 하네스 3커밋에
같은 처리를 했고 그때 닫혔다).

**사실 기록 (게이트 21).**

| # | 사실 | 근거 |
|---|---|---|
| ⑤ | **privacy-gate 가 B-1 해제를 값이 아니라 성질로 재확인했다** — 더미 PHC 가 현행 정책을 **추종**함을 설정 변경 재기동으로 실측했고(**103.97ms → 52.68ms**), 시간 축 격차는 **1.017배 / 절대 1.72ms**(게이트 20 의 42배 / 95ms 가 소멸). 두 분포의 min–max 구간이 겹친다 | `reviews/03_security-fixes_privacy-gate.md` ⑴⑶ · 판정 표 1a·1c (`3c5c8ad`) |
| ⑥ | **240 동시 부하가 게이트 20 이 「어느 산출물도 재지 않았다」고 적은 구간(≥ 200)을 닫았다** — 500 **36건(15%)** · 로그인 중앙값 **~3.9s** · `/health` **최대 1244ms** | 같은 문서 C2 §(R-2) |
| ⑦ | **계약이 예시로 든 `field_missing` 모양이 어떤 요청에서도 나오지 않는다** — 필수 키 누락 · 명시적 `null` · 깨진 JSON 이 **바이트 단위로 동일**(`json_invalid`). **처분은 계약 수정이 아니라 구현 개선**이다 — 계약은 Kotlin 스냅샷이 아니라 요구하는 인터페이스이고, `field_missing` 예시는 요구하는 쪽을 정확히 표현하고 있다 | `03_contract-keeper_auth-fixes-verdict.md` §2-3 (`9bee412`) |
| ⑧ | **계약이 파싱 거절을 7종이라 적은 자리를 상시 회귀 열거자가 5종만 돈다** — 빠진 둘은 「콜론 없는 헤더 줄 → 400」·「알 수 없는 메서드 → 405」. **어느 갈래인지는 측정 전이라 판정하지 않는다** — 열거자를 늘릴 일이거나, 「알 수 없는 메서드」가 실은 서블릿까지 도달하는 것이면 **계약이 사실과 다르다(G1)** | 같은 문서 §3-4 |
| ⑨ | **CI 원격 캐시 거동은 이번에도 0관점이다** — Claude·contract-keeper·구현 레인이 **같은 자리에서 멈췄고**, 잰 것은 전부 로컬 증분·로컬 캐시다. 게이트 20 에 이어 두 회차 연속이다. **다음 push 의 CI 실행이 첫 관측**이 된다 | cross §7 말행 · §9 「0관점」 · ck §4 말미 |

**다음 단위 예고 — `workspaces`.** 보호 경로 자동 발견이 **`/workspaces` 인증을 강제하는 상태**에서
착수한다(privacy-gate 가 런타임 401 도달을 실측했다 — 토큰 없이 `/workspaces` 는 401). 즉 엔드포인트를
추가하는 순간 커버리지가 그 경로를 요구하므로, C-1 의 `(method, path)` 투영 수정은 **그 커밋과 같은
단위**에 든다(게이트 20 ③ 의 마감이 애초에 「보호 엔드포인트를 추가하는 바로 그 커밋」이었다).
**게이트 22 범위 = `auth-fixes2` 배치 + `workspaces`.**

**[2026-08-19 게이트 22·23 갱신] auth 단위 종결 조건의 재기재 — 「조건부」의 조건이 무엇으로 남았는가.**

조건부 종결이 걸었던 조건은 하나였다 — *"조치 배치는 커밋 후 별도 게이트를 걸지 않고 **게이트 22
(`workspaces`) 범위에 포함**한다."* **그 조건은 충족됐다**: 배치 9커밋이 `bf08edd..6fe4357` 로 커밋돼
게이트 22 범위에 들어갔고, 12항목 4관점 합성이 나왔다(§ 위 「Phase 3 workspaces 단위」 §2 앞 표 —
**완전 종결 4 · 부분 5 · 재개 3**). **「전건 해소」로 닫지 않은 것이 옳았다** — 8건이 실제로 열려 있었다.

**조건이 아니라 항목으로 남은 것**(auth 축에서 열린 채 다음 단위로 넘어간 것):

| 남은 것 | 지금 상태 | 어디서 닫히는가 |
|---|---|---|
| **R-3** — 삭제된 계정의 유효 토큰(게이트 22 privacy-gate 기록, 조건은 「계정 삭제 기능 커밋과 같은 단위」) | **해제**(게이트 23) — 기능보다 **먼저** 닫혀 조건이 **선행 충족**됐다. 다만 닫힌 것은 「토큰 수명 동안 API 를 쓴다」 **한 갈래**다 | 잔여 2조건은 **계정 삭제 기능 커밋**: ⑴ `users` 삭제의 CASCADE 연쇄가 Phase 4 의 암호문·보존 파기 정책과 맞는지(I-4·I-11) ⑵ 토큰 폐기 수단이 「사용자 행이 사라졌는가」 하나뿐이다(비밀번호 변경·로그아웃에 폐기 경로 없음) |
| **② 세마포어 R-2 교환비** | 처방(대기 상한 250ms)은 집행됨. **용량 결정이 사용자 판단 대기** | 사용자 판단(§ workspaces 단위 §6 이월 ①~⑮) |
| **③ 보호 경로 커버리지 메서드 축(C-1)** | **닫힘** — `07a8bc5` 가 투영을 `(경로, 메서드)` 로. privacy-gate 음성 대조 포함 **3관점** | 닫힘 |
| **⑧ L-1 WARN 유출** | **탐지형 회귀로 닫힘**(`0cb0d0b`) — 억제형으로 넓히지 않았다 | 닫힘 |
| **401 타이밍** | auth 의 **로그인** 축은 B-1 해제로 닫혔고(1.017배), **인증 경계** 축이 게이트 23 에서 새로 열렸다(2.18배 · 기록) | Phase 3 종료 전 (§ workspaces 단위 §4·§5 ③) |

**요약: auth 단위는 「조건부」의 조건이 소멸해 항목만 남았고, 그 항목들은 전부 다른 행·다른 단위의
마감에 결속돼 있다.** 그래도 Phase 3 표의 auth 관련 세 행은 **`아니오` 유지**다 — 조치 배치 2 와
사용자 판단이 남았고, 근거 없는 `예`는 `아니오`로 취급한다는 규칙이 그대로 적용된다.

## Phase 3 workspaces 단위 — 구현과 게이트 22·23 (2026-08-19, 리더)

§1~§3 이 **게이트 22**(정본 `reviews/03_workspaces_cross.md` · 완주 `7205d37`)이고
§4~§6 이 **게이트 23**(정본 `reviews/03_workspaces-fixes_cross.md` · 완주 `9b9d8ad`)이다.
auth 단위와 같은 규약을 쓴다 — **양쪽 근거 전문은 정본에 있고 여기로 옮겨 적지 않으며,
수치·판정에는 커밋 해시를 함께 적는다**(기준 시점 없는 절대 수치는 다음 커밋에 거짓이 된다).

> 이 절의 표들은 종료 조건 표가 아니라 **처리 현황 표**라 표기 검사기(`tests/test_harness_scope_reach.py`)의
> 대상 표에 들어가지 않는다 — 그래도 같은 어휘를 쓴다.

### §1 — 구현 요지 (`e31bbb4..cc7268c` 10 커밋)

| 커밋 | 내용 |
|---|---|
| `e6eb72e` | workspaces 계약 테스트 **명세** — 케이스 4표·파서 P-16~P-21·음성 대조 N-11~N-18 (contract-keeper) |
| `e31bbb4` | 작업 공간 도메인·이름 규칙·유스케이스 (`core`·`application`) |
| `ab53420` | 작업 공간 JDBC repository 와 조립 (`infrastructure`) |
| `e4be6ff` | `/workspaces` **네 오퍼레이션**과 보호 목록 등재 (`api`) |
| `4a25a7c`·`b6e3093` | 계약 정정 — 파싱 단계 거절을 **6종**으로(「알 수 없는 메서드 405」는 서블릿까지 도달한다) · escalate ④ 선택지 표 |
| `951b1fd`·`5b28851` | 계약 테스트(명세 §2 케이스 · 파서 P-16~P-21) · 파싱 거절 열거자를 계약과 **집합으로** 대조 |
| `693a246` | 실제 PostgreSQL 에서만 잴 수 있는 셋 — 행 잠금 · FK 409 · 유일 인덱스 · `count(d.id)` |
| `0c838ee`·`cc7268c` | ktlint·detekt 정리 · 구현 산출물(대응표·음성 대조 13건·갈림 3·미결 8) |
| `2c4a44f` | 계약 축 **2단계 독립 검증** — 파서 6노드 · 케이스 36건 · 음성 대조 10건 재현 (contract-keeper) |

**설계 판단 3 (리뷰가 볼 자리).**

1. **소유권 은닉을 SQL `WHERE` 에 둔다.** 포트 시그니처가 전부 `ownerId` 를 받아 「읽고 나서
   비교」 형태를 **만들 수 없게** 했다. 시간 축 실측 **1.031배**(없음 2.172ms / 타인 2.106ms).
2. **삭제 잠금이 대상 행이 아니라 그 사용자의 행 전부**에 걸린다. 「마지막 하나는 못 지운다」가
   **집합 판정**이기 때문이고, `ORDER BY id` 로 잠금 순서를 고정해 교착을 막는다.
3. **FK 를 두 번째 방벽으로 쓴다.** 유스케이스의 문서 수 확인과 DELETE 사이의 창에서 도는 것이
   `fk_documents_workspace_id_workspaces` 이고, 이 DELETE 에서 터질 수 있는 제약이 그것 하나뿐이라
   메시지·SQLState 를 읽지 않고 409 로 옮긴다.

**갈림 3건(계약 침묵 — 계약에 값이 생기면 그때 맞춘다)**: D-1 409 문구 · D-2 삭제 거절 순서 ·
D-3 목록 동점 정렬. **D-2 는 게이트 22 에서 「침묵이 맞지 않다」로 판정돼 조항이 섰다**(§3).

### §2 — 게이트 22 리더 판정 5건

> cross(22) §9 가 판단을 요청한 7건 중 5건을 여기서 판정한다. 나머지(⑥ 이미 대기 중인 것 ·
> ⑦ 검증 게이트 표 갱신)는 §6 과 별건이다. **양쪽 근거 전문은 cross §3 에 있고 옮겨 적지 않는다.**

| # | 판단 요청 | **리더 판정** |
|---|---|---|
| **X-9** | CI `quality` red 를 **착수 차단으로 볼 것인가** | **착수 차단으로 보지 않되 착수 조건을 건다** — 「`ea36330` 을 push 하고 `quality` 잡이 success 를 내는 것을 **관측한 뒤**에 다음 코드를 쌓는다」. 근거: 오탐 판정 자체는 privacy-gate·Claude 가 일치했고 갈린 것은 **층**(지적 ↔ 게이트)이므로, 닫아야 할 것은 심각도 라벨이 아니라 **초록 관측**이다. 관측 결과는 §5 ① |
| **X-1** | 삭제된 계정의 유효 토큰 — **심각도·마감** (high/Phase 4 전 ↔ 기록/계정 삭제 단위) | **구현으로 닫는다**(마감을 계정 삭제 단위까지 늦추지 않는다). 근거: 4관점이 사실에 합의했고, 인증 경계 한 곳을 고치면 **다섯 경로가 동시에** 닫히는데 Phase 4 는 같은 형태를 여섯 자리 더 만든다. 집행 `fa87aed` |
| **X-2** | 「계약이 정본」의 **강제 범위** (테스트까지 ↔ 런타임까지) | **테스트까지로 한다.** 런타임 주입(빌드 시 생성)은 codex 처방이지만 계약 파일을 **런타임 의존**으로 만들고, 그 대가가 Phase 4 상한 6종의 이득보다 크다. 대신 **테스트가 계약에서 읽는다**는 규약을 명시하고 산출물 문면을 정정한다(`bfbfc71`) |
| **X-3** | 시간 채널 문턱 2.0 — **ⓐ 실측 통과·ⓒ 음성 대조 부재와 분리해** 판단 | **세 축을 분리해 판정한다.** ⓑ 문턱은 **1.5 로 좁힌다**(auth 선례와 같은 값). ⓒ 는 문턱을 좁히는 것으로 닫히지 않으므로 **구조 축 탐지형으로 갈아탄다** — 「소유 조건을 SQL 에서 빼면 빨강」을 단언한다. 시간 축만으로 닫으면 ⓒ 가 사라진다는 cross 의 경고를 그대로 채택했다. 집행 `b37012c` |
| **X-11** | N-18 정정 수치 (Claude 13 ↔ contract-keeper 16) | **16 을 채택한다.** 두 관점이 어긋난 것이 아니라 **재현 방법의 좁기가 달랐다** — 문자열 치환은 `GET /documents` 의 쿼리 파라미터와 경로 변수를 함께 바꾸고, 앵커 유일성 선언 후 **행으로 좁혀** 재현한 쪽이 정확하다. 집행 `bfbfc71` |

### §3 — 게이트 22 조치 배치 (`7205d37..e9502a6` 11커밋)

| 커밋 | 항목 |
|---|---|
| `ea36330` | **X-9 처방** — 스캐너 BLOCK 8건. `OWNERSHIP-403` **규칙 정밀화(탐지형)** + 상수 개명. **은폐형(무시 패턴·억제)을 쓰지 않았다** — `CLAUDE.md` 규칙 4 |
| `0fe654c` | **계약** — D-2 삭제 거절 순서 조항 신설(근거 **G2**) + `:2424` M-405 정정 누락 보완 |
| `fa87aed` | **X-1** — 삭제된 계정의 유효 토큰을 **인증 경계**에서 401 로 끊는다 |
| `b37012c` | **X-3** — 시간 채널 문턱 2.0 → **1.5**, 구조 축 음성 대조 신설 |
| `be363c8` | X-1 후속 — 작업 공간 슬라이스 테스트의 소유자 계정을 실재하게 (§5 ⑥) |
| `9e2ce96` | **X-4** — 계약 파서 fail-open 두 자리 차단 + 배압·동시 삭제 단언 정확화(**X-6**·**X-7**) |
| `c663714` | **WD-9** — 삭제 거절 두 갈래가 겹치는 상태를 D-2 조항에 결속 |
| `30cc405` | **A-2·A-3·A-4** — 이름 노출·제약 전제·격리 수준 전제를 **산문에서 장치로** |
| `3466f6d` | 테스트 컨테이너 `max_connections` 400 |
| `bfbfc71`·`e9502a6` | 산출물 정정(N-18 **16건** · X-2 판정 문구) · 조치 산출물(항목별 처방·실측·잔여) |

### §4 — 게이트 23 리더 판정

| # | 쟁점 | **리더 판정** |
|---|---|---|
| **표 1** | 스캐너 `OWNERSHIP-403` 이 **네 형태**를 잃었다(Claude 차단② · codex high ↔ privacy-gate 통과) | **실결함으로 받는다 — 정밀화에 조건을 더하고 회귀를 `blocks=True` 로 등재한다.** privacy-gate 의 통과 판정을 **뒤집지 않는다**: cross §3-ⓐ 가 확인한 대로 **판정이 갈린 것이 아니라 대조 입력이 갈렸다**(음성 8종에 「토큰 없는 이름의 403 상수」 형태가 **없다**). 처방은 여전히 **탐지형**이다 — 무시 목록으로 넓히지 않는다.<br>**[완료] `01d78a1`** — 정밀화의 불활성 ③ 이 식별자 자리를 `\w+` 로 두어 **이름에 제약이 없었다**. *"선언을 빼도 사용처가 토큰으로 잡힌다"* 는 정당화는 **상수 이름이 그 자체로 403 토큰일 때만** 참인데 무조건형으로 적혔던 것이고(자기 정정), 이제 ③ 은 이름이 `_403_TOKEN` 일 때만 제외하며 ② 백틱은 `fun` 바로 뒤 **함수 이름 자리만** 소비한다. **탐지 토큰과 이름 관문을 한 조각에서 파생**시켰다 — 두 벌이면 갈리고 갈린 쪽은 늘 조용하다. 실측: 네 형태 **전건 BLOCK**(주입 후 종단 exit 1 · 잔여 0) · 전수 스캔 **exit 0** · 오탐 재검 **7/7 통과** · 2차 제외 6건 동일 · 음성 대조 **10종** · 157 passed · 7 xfailed |
| **1a** | `HTTP_403_FORBIDDEN`·`SC_FORBIDDEN` xfail 의 **성격**(Claude 「정직한 선언」 ↔ codex 「같은 실제 미탐」) | **xfail 을 유지한다.** 밑줄에 둘러싸인 토큰을 잡으려고 `\b` 경계를 풀면 `FORBIDDEN_IN_FILENAME`·`FORBIDDEN_ANNOTATIONS` 같은 **HTTP 와 무관한 이름**이 전부 BLOCK 이 되어 출구 없는 규칙에 새 오탐 무리를 들인다. **조용한 0 대신 `xfail(strict=True)` 로 선언**하는 것이 이 하네스의 규약이고(누가 탐지에 넣으면 `xpass` 로 뒤집혀 시끄러워진다), **정밀화 이전부터 있던 기존 결함**이라 이 배치의 회귀가 아니다. 넓힐지는 **별건** |
| **F-4** | 구조 축 계측의 도달이 repository 층까지라 **서비스 층 이탈은 두 게이트 다 통과한다** | **차단으로 보지 않고 이 배치에서 닫지 않는다** — 마감은 **서비스 진입이 생기는 커밋**이다. 근거: 오늘 서비스 층에 소유 조회 진입점이 **없어** 계수할 대상이 0이고, 없는 진입점을 겨냥해 계측을 세우면 그 계측이 **무엇을 재는지 모르는 채** 초록이 된다(게이트 22 X-3ⓒ 와 같은 형태). Claude 실증·codex 추정 둘 다 **지우지 않고** 원장 행 1 의 미해결로 남긴다 |
| **표 5** | `ContractSpec` 잔존 fail-open 3자리 — **Claude 권고 ↔ codex medium** | **라벨을 확정하지 않고 사용자 판단 대기로 올린다**(§6 ⑯). 마감(인라인 헤더·새 `oneOf` 갈래가 처음 생기는 커밋)과 처방이 어느 라벨에서도 같으므로 **라벨이 조치를 바꾸지 않는다** — 게이트 20 판정 ④ 와 같은 처분이다 |
| **행 4** | 「소유권 404」 행을 표 1 이 막는가 (cross §6-① ⒜ ↔ ⒝) | **⒝ 를 택한다 — 행 4 의 판정과 표 1 의 마감을 분리한다.** 「심각도」와 「그 행을 움직이는가」는 별개 축이고(cross 자신이 그렇게 적었다), 오늘 403 을 내는 프로덕션 코드가 **0**이라 잃은 것은 「없던 것이 생기는 순간」을 잡는 능력이다. 다만 그 능력이 **Phase 4 에서 처음 쓰이므로** 표 1 의 마감을 **「늦어도 Phase 4 착수 전」** 으로 고정한다. **행 4 의 `충족` 은 어차피 `아니오`** 다 — 조치 배치 2 가 진행 중이고 check 제약 매핑도 미착수다 |

**workspaces 단위 종결 판정 — 아직 하지 않는다.** 게이트 23 이 **새 차단 하나**(표 1)를 열었고,
그 **처방은 `01d78a1` 로 닫혔으나 아직 리뷰를 받지 않았다.** 게이트 21 → 22 에서 쓴 방식(조치 배치를
다음 게이트 범위에 포함)을 반복하지 않는 이유는, 그때의 근거가 *"4관점 어디에도 차단이 없다"* 였는데
**이번에는 차단이 있었기** 때문이다 — 차단을 닫은 커밋은 그 자체로 리뷰 대상이다.
**조치 배치 2 의 범위** = ~~표 1(스캐너 복원 + 회귀 `blocks`)~~ **→ `01d78a1` 로 닫힘(리뷰 미수령)** ·
F-4(서비스 층 진입 시) · 표 4·4a(`toString` + **종류 탐지기**) · 표 5(파서 fail-open) ·
표 2·2a(401 타이밍 판단 + 문면 정정) · 표 18(TRACE 로거 3종).

### §5 — 사실 기록 6건

| # | 사실 | 근거 | 왜 남기는가 |
|---|---|---|---|
| ① | **CI 가 세 회차 동안 red 였고, 그것을 본 관점이 0이었다.** 원격 HEAD `6fe4357` 의 run `32211120665` 가 `quality` **failure** 이고 실패 지점은 스텝 「데이터 보호 불변식 스캔」, 그 뒤 **8스텝이 skip** 됐다(그중 하나가 **스캐너 자신의 회귀 테스트**이고 또 하나가 `uv run pytest` **전체**). 경위: 게이트 20 이 `--no-fail` 로 재서 **CI 가 도는 명령과 다른 명령**으로 「실질 0」이라 적었고, 게이트 21·22 는 `--no-fail` 없이 재서 **exit 1 을 정확히 기록**했지만 그 종료 코드가 CI 스텝에서 무엇을 하는지 묻지 않았다. 스텝 이름은 「BLOCK **후보** 0건 유지」인데 판정은 「**실질** BLOCK 0」이라, **같은 낱말의 다른 뜻이 세 회차를 통과했다**.<br>**조치 후 관측(리더 실측)**: run `32215743807`(`ea36330`) · run `32218223676`(`e9502a6`) 둘 다 **quality·kotlin·frontend success**(llm-lane 은 취소 — Phase 5 항목). 그 앞 run `32193788969`(`b3db059`)의 `kotlin` failure 는 **Maven Central 429**(환경 사정)이지 코드 결함이 아니다 | cross(22) §3-9 · `gh run view` 실측 | **X-9 착수 조건이 무엇으로 닫혔는지의 기록**이다. 그리고 「도달 0을 특히 의심한다」(규칙 3)가 잡아야 했던 형태 하나가 실제로 세 회차를 통과했다는 실측이다 — 게이트가 **재긴 했는데 재는 자리가 CI 가 아니었다** |
| ② | **R-3 해제는 값이 아니라 성질로 닫혔다** — 다섯 경로(`GET /auth/me` · `GET`·`POST /workspaces` · `PATCH`·`DELETE /workspaces/{id}`)가 삭제 직후 전부 **401** 이고, 살아 있을 때는 각각 200·200·201·200·204 로 통한다(그 대조가 있어야 「무엇을 보내도 401」과 구분된다). 서로 다른 본문 종류 **1** · 헤더 이름 집합 **1**, 위조 토큰 401 과 **바이트 동일** | `reviews/03_security-workspaces-fixes_privacy-gate.md` §1 (`9b9d8ad`) | 종전 값은 401 이 아니라 **200 `{"items":[]}` · 500 · 404 · 404** 였다. 「고쳤다」가 아니라 **네 갈래가 하나로 모였다**가 정확한 진술이고, 그것이 이 불변식이 요구하는 형태다 |
| ③ | **401 네 갈래의 시간이 갈린다 — 2.18배**(`GET /workspaces`, 표본 각 101, 교차 순서, 워밍업 20라운드). 삭제 계정 p50 **1.067ms** ↔ 무헤더 **0.490ms**. **X-1 이 만든 채널이 아니다** — 고치기 전에도 삭제 계정 요청은 200·500 으로 **DB 왕복을 돌았고**, 그 위에 상태 코드 채널까지 있었다. X-1 은 엄격한 개선이다 | 같은 문서 기록 ① | **차단이 아니라 기록인 이유가 이 문장 하나**다. 남은 판단은 「저장소가 같은 성질에 쓰는 문턱 **1.5**(로그인 B-1 · 소유권 X-3ⓑ)와의 정합」뿐이고, 그것은 §6 으로 올린다 |
| ④ | **선언과 도달이 갈리는 자리 하나가 실측으로 확보됐다** — `api/application.yml` 주석은 *"요청 본문을 찍는 로거는 절대 DEBUG 로 내리지 않는다"* 인데, **실제로 요청 바이트를 찍는 로거**는 `org.apache.coyote.http11.Http11InputBuffer` 이고 명시 고정 세 줄 중 **어느 것도 그것을 가리키지 않는다**. 강제 TRACE 에서 이름 10 · 이메일 9 · 평문 비번 2 · PHC 2 · 토큰 9 가 찍혔고 유출 로거는 **3종**이다(위 하나 + `StatementCreatorUtils` + `QueryExecutorImpl`). 기본·DEBUG 에서는 **0** | 같은 문서 기록 ③ | 오늘 위반은 아니지만 **`toString()` 재정의로는 원리상 막을 수 없는 층**이다(바인딩 파라미터·원시 요청 바이트). `CLAUDE.md` 규칙 4 가 말하는 「선언한 범위 ≠ 실제 도달」의 교과서 형태이고, **문서 본문이 들어오는 Phase 4 전**이 마감이다 |
| ⑤ | **삭제 잠금의 범위가 음성 대조로 확인됐다** — 정상 구현은 12라운드 전건 `[204, 409]` · 남은 수 1. 집합 잠금을 **단일 행 잠금**으로 바꾸면 **11/12 라운드가 `[204, 204]` · 남은 수 0** 이 된다 | `reviews/03_security-workspaces_privacy-gate.md` (N2) | 「마지막 하나는 못 지운다」가 **잠금 범위에서 온다**는 것을 값이 아니라 **뒤집어서** 보였다. 그리고 11/12 는 **두 요청이 실제로 겹친다는 증거**이기도 하다 — 겹치지 않았다면 변조 구현에서도 0이 나오지 않는다 |
| ⑥ | **X-1 의 도달을 테스트 빨강이 증명했다** — `WorkspaceContractTest` 가 소유자를 `UUID.randomUUID()` 로 만들어 **계정이 없는 토큰**을 쓰고 있었고, X-1 이후 **13건이 빨개졌다**. 계정을 실제로 만들도록 고쳤다(`be363c8`) | `03_kotlin-implementer_workspaces-fixes.md` | **부수 피해가 아니라 도달 증거**다. 인증 경계가 「계정이 실재하는가」를 실제로 보게 됐다는 것을, 그 전제를 어긴 테스트 13건이 한꺼번에 빨개지는 것으로 확인했다 |

### §6 — 사용자 판단 대기 (양쪽 근거 병기 — 어느 쪽도 지우지 않는다)

> **15건은 위 「Phase 3 auth 단위」 §4 와 그 상위 절에서 그대로 이월**되고 **근거 전문은 그 표들에 있다**
> (여기서 요약하지 않는다 — 옮겨 적으면 갈린다). 아래 ⑯·⑰·⑱ 이 이 절에서 새로 올라온 것이다.

| # | 쟁점 | 양쪽 |
|---|---|---|
| ①~⑮ | **이월 15건** — 게이트 19 §3 의 11건 + auth 단위 §4 의 ⑫(세마포어 심각도 라벨) · ⑬(이메일 ASCII 정책) · ⑭(`InternalError`/`ServiceUnavailable` 설명 개정 — escalate ④) · ⑮(signup 409 계정 열거) | **무변경 · 사용자 확인 대기.** 정본은 각 절의 표다. **⑬ 은 아래 ⑰ 의 선행 조건**이 됐다 |
| **⑯** | **표 5 — `ContractSpec` 잔존 fail-open 3자리의 심각도 라벨** | **Claude 권고**(백스톱 `errorDetailUnionTypes` 가 있어 갈래 추가가 소비자 단언을 통과해도 다른 층이 잡는다) ↔ **codex medium**(그 백스톱 자체가 fail-open — 갈래를 더하는 방향은 두 소비자 단언을 **모두** 통과한다). **cross 가 제3 근거로 codex 쪽을 확증했다**(§3-ⓔ) — 다만 그 확증(소비자 두 곳의 단언 형태 대조)은 **어느 1차 산출물에도 없어** §7-3 에 미교차로 분리됐고, **다음 회차 실행 음성 대조로 확정할 것**이 제안돼 있다. **라벨이 조치를 바꾸지 않는다** — 마감과 처방이 양쪽에서 같다 |
| **⑰** | **OQ-E2 — 이메일 형식 규칙을 계약이 게시할 것인가**(RD-4) | **게시안**: 규칙이 **Kotlin 소스와 React 소스 두 곳에 따로** 있고 계약은 침묵한다. 근거가 **G1**(계약이 구현을 서술하지 못한다) + **G2**(정책이 실재한다 — ASCII 한정에 DB CHECK 제약이라는 이유가 붙어 있다)로 **둘 다 선다** ↔ **단독 판정 불가 두 이유**(계약 소유자 자신이 명시): ⑴ `validation.ts:13` 은 **React 런타임 코드**라 계약 조항이 그것을 규정하면 Phase 6 타입 교체의 범위가 바뀐다, ⑵ **게이트 20 ⑥ 의 ASCII 정책이 사용자 판단 대기**이고 이 조항은 **그 판정의 종속물**이다. 즉 ⑬ 이 먼저 닫혀야 이 조항의 문면이 정해진다 |
| **⑱** | **게이트 22 의 미결 충돌·판정 대기 6건 — 재상신** (X-2 강제 범위 · X-5 · X-13 · X-15 · X-16 · X-25) | cross(23) §5 가 *"이 배치에서 **의도적으로 열려 있고**, 리더 판단이 내려진 기록을 이 회차에서 **찾지 못했다**"* 고 적고 **규약대로 그대로 다시 올렸다** — *"답이 오지 않았다는 이유로 조용히 닫지 않는다."* **리더 확인**: X-2 는 위 §2 에서 판정했고(테스트까지) 그 집행이 `bfbfc71` 이다 — **cross 가 못 찾은 것은 판정이 원장에 아직 안 적혀 있었기 때문이고, 이 절이 그것을 적는다.** 나머지 다섯(X-5 계약 측정 상태 · X-13 O-7 문구 · X-15 계약 침묵 판정 · X-16 · X-25 R-2 교환비)은 **사용자·계약 소유자 축이라 리더가 닫지 않는다** — 무변경으로 다시 올린다 |

**⑯~⑱ 이 착수를 막지 않는 이유.** 셋 다 **심각도 라벨·계약 문면·절차**이고, 이 셋 중 어느 쪽으로
나든 **잔여 목록과 마감이 바뀌지 않는다**. 진행을 막고 있는 것은 이것들이 아니라 **§4 의 조치 배치 2**다.

## Phase 3 종료 판정 — **조건부 종료 권고 · 사용자 승인 대기** (2026-08-19, 리더)

게이트 24(`03_phase3-close`) 정본은 `reviews/03_phase3-close_cross.md`, 보안 축 정본은
`reviews/03_security-phase3-close_privacy-gate.md` 다. 이 절은 **그 재료 위에서 리더가 내린 판정**과
조건 처리 현황을 적는다 — 리뷰 산출물의 값을 옮겨 적지 않고 포인터를 둔다(옮겨 적으면 갈린다).
**수치·판정에는 커밋 해시와 run id 를 함께 적는다.**

> 이 절의 표들은 종료 조건 표가 아니라 **판정·현황 표**라 표기 검사기
> (`tests/test_harness_scope_reach.py`)의 대상 표에 들어가지 않는다 — 그래도 같은 어휘를 쓴다.

### §1 — 게이트 24 리더 판정 6건

| # | 쟁점 | **리더 판정** |
|---|---|---|
| **①** | **행 1 / X24-1 ≡ S-2** — `HTTP_403_FORBIDDEN`·`SC_FORBIDDEN` 이 스캐너를 그대로 통과(codex high ↔ Claude 판정 필요 ↔ privacy-gate 명시 위임) | **두 갈래를 분리해 다시 판정한다 — ㉮ 경계 완화는 기각 유지, ㉯ 명시 토큰 추가는 채택.** 게이트 23 의 기각(`:1302`)은 **㉮ 에 대해** 내려진 것이었고 그 사유는 전부 경계 완화의 부수 피해(`FORBIDDEN_IN_FILENAME` 류가 출구 없는 규칙에 들어온다)였다. ㉯ 는 `CLAUDE.md` 규칙 4 분류로 **탐지형 넓힘**이고 그 오탐 무리를 만들지 않는다. **판정을 바꾼 근거는 3관점 실측**(codex 게이트 23 C-1 후단 · X24-1 `scan()` 직접 호출 · Claude S-2 실제 Kotlin 파일 주입 + CI 동일 명령 **exit 0**)이 **같은 자리**를 가리켰음이 cross §3-ⓐ 로 확정된 것이다. 옛 판정의 「기존 결함이라 이 배치의 회귀가 아니다」는 **누구 책임인가**의 답이지 **닫아야 하는가**의 답이 아니었다.<br>**[집행 `6be9612`]** `_403_TOKEN` **한 조각**에 두 이름만 더했다(맨 `sendError` 는 올리지 않는다 — 범위는 근거를 넘지 않는다). `xfail(strict)` 2건 **소멸**(정상 통과로 전환), 회귀 26 → **34건**, 음성 대조 **13종**, 전수 리포트 **바이트 동일**(오탐 증가 0), `166 passed · 5 xfailed`. **P8·P9 가 ㉮ 기각 자체를 회귀로 고정**한다 |
| **②** | **행 4 / R-1** — 계약이 401 균일화 열거를 두 곳에 다르게 적는다(codex 관측 · Claude 수정 필요 · privacy-gate 부분 인용) | **contract-keeper 소관으로 넘겨 정정한다 — 정본은 `x-auth.failure_uniformity`.** 세 관점의 실질 판단이 「무헤더 제외가 옳다」로 이미 수렴해 있었다(codex 계약 열거 근거 · privacy-gate **기제 근거**(요청자가 만든 상태라 정보 이득 0) · Claude 유보).<br>**[집행 `dec3124`]** `Unauthorized.description` 은 **열거 복사를 없애고 정본을 가리키는 포인터**가 됐다(「무헤더만 빼고」 고치면 두 벌 목록이 남아 또 갈린다). 근거 G1 · 와이어 무변경 · `info.version 1.1.0` 유지 · React 영향 0(`client.ts:128` 이 status 만 본다) · `openapi-spec-validator` OK |
| **③** | **행 3 / X24-2** — 401 구조 단언이 시간의 **대리값**이라 상시 회귀가 없다(codex high ↔ Claude 「구조 선택이 옳다」 ↔ privacy-gate 「오늘 값 통과」) | **오늘은 코드를 넣지 않는다 — 고정 sentinel(`ABSENT_USER_PROBE_ID`)을 유지한다.** codex 처방의 「요청별 존재 불가능 UUID」는 Claude·privacy-gate 가 **고정 상수를 안전 근거로 든 것과 정면으로 갈리고**(공격자가 고를 수 없다 ↔ 전역 과열), 그 갈림은 어느 쪽도 상대 근거를 읽지 않은 상태에서 나왔다. 게다가 codex 가 든 「`UUID(0,0)` 전역 과열」은 privacy-gate 실측에서 **관측되지 않았다**(1.058 vs 1.061).<br>**다만 codex 의 본론(상시 회귀 부재)은 반박되지 않았다 — 그래서 조건으로 남긴다: 3갈래 비율 회귀(문턱 1.5, 저장소 선례와 같은 값)를 Phase 4 착수 전에 추가한다.** 4갈래는 대상이 아니다(②로 무헤더가 균일화 범위 밖이 됐다). **이 저장소는 시간 축 게이트가 흔들려 꺼진 선례를 갖고 있으므로** 표본·워밍업·교차 순서를 privacy-gate 재실측 방식(표본 101 · 워밍업 20)에 맞춘다 |
| **④** | **행 6 / X24-5** — 인라인 헤더 마감에 **강제자가 없다**(codex medium ↔ Claude 「마감 미도래, 이월 정상」) | **지금 fail-closed 로 넣는다.** 두 진술은 다른 질문에 답했고(「마감이 지났는가」 ↔ 「그 마감이 집행 가능한가」), 후자에는 답이 없었다.<br>**[집행 `44eec3f` · 전제 정정 1건]** 리더 지시와 cross ⓔ 가 전제한 「오늘 계약에 인라인 헤더 0건」이 **틀렸다 — 실측 2건**(`Location`·`Content-Disposition`, 둘 다 값이 계산돼 `const` 로 못 박을 수 없다). 그래서 codex 처방 문면(`$ref` 없으면 무조건 실패)은 **채택하지 않고** 갈래로 나눠 세는 fail-closed 4자리로 넣었고, 마감의 강제자는 **인라인 집합을 `[Location, Content-Disposition]` 으로 고정하는 테스트**가 진다 |
| **⑤** | **행 1·4 / A-3′ ≡ R-5 ≡ X24-3** — `toString` 탐지기의 후보 선정 빈자리(장치 하나 · 기제 셋) | **A-3′·R-5 는 지금 고친다. X24-3 의 「모든 `String` 명시 분류」는 채택하지 않는다.**<br>**[집행 `44eec3f`]** 판정 근거를 `componentN` 정규식에서 **Kotlin 반사(`primaryConstructor.parameters`)**로 옮겨 맹글링 기제를 소멸시키고, 후보 선정의 두 `return null` 갈래를 **끊기**로 바꿨다(판정 불가는 통과가 아니다 — 규칙 4 ⑶). 제외 사유는 KDoc 에서 **단언**으로 내려왔고(값을 감싸는 타입이 값을 찍지 않는다 — 클래스패스 전수 + 도달 기록 두 종류), 클래스패스 제외 자체가 소스 대조로 검사받는다. 음성 대조 **11건**(NC1~NC7b), api 테스트 178 → **183**.<br>**미채택 근거(등재)**: ⑴ 「안전」을 선언하는 애너테이션이 곧 **면제 조항 = 은폐형**이다 ⑵ **범위가 근거를 넘는다** — 새로 빨개지는 것을 실측하니 `HealthResponse`·`ErrorResponse`·`ValidationErrorItem`·`Argon2Phc`·`AnthropicSettings`·`LlmProperties`·`AuthProperties` 등 **8~10 종**이고 전부 고정 문구·설정값이라 **가려서 얻는 것이 없고 진단만 잃는다**. 남은 빈자리(이름 규약 밖 `String`, 오늘 대상 **0건**)는 `@UserContent` 가 메우고 그 사실을 테스트 KDoc 「막지 못하는 것」에 **선언으로** 적었다. **뒤집을지는 사용자 판단으로 올린다**(§5 ⑲) |
| **⑥** | **I-8** — `V2__encryption_scheme.sql` 의 `DEFAULT 'fernet-v1'` + CHECK 가 폐기된 전제 위(privacy-gate 단독 잠정 위반) | **Phase 3 종료를 막지 않는다 — Phase 4 암호 설계 첫 커밋의 조건으로 건다.** 오늘 차단하지 않는 근거 3항이 전건 참이다(이 컬럼을 쓰는 Kotlin 코드 0 · `documents`·`conversions` 에 행을 쓰는 경로 0 · 따라서 잘못된 scheme 이름이 붙은 행이 **존재 불가**). 그러나 **Phase 4 의 첫 INSERT 가 컬럼을 생략하면 DEFAULT 가 조용히 `fernet-v1` 을 채우고 그 값은 데이터에 대해 거짓**이 된다(암호문은 AEAD) — CHECK 가 `aes-gcm-v1` 을 거부하는 안전장치를 **DEFAULT 가 우회**한다. 해제 조건 3항(마이그레이션으로 CHECK 도메인 확대·DEFAULT 교체 또는 제거 / V2 주석의 무효 근거 3개 정정 / `encryption_scheme`·`key_version` 이 읽기·쓰기 경로에서 **실제로 쓰인다**는 회전 시나리오 실행)과 수신자는 보안 축 §5-4 가 정본 |

### §2 — 조건 처리 현황 (게이트 24 cross §7-3 의 19항 + 그 뒤 확인된 것)

**닫힘 — 이 회차에 집행된 것**

| # | 조건 | 집행 | 실측 |
|---|---|---|---|
| 1 | **행 1** — 403 두 상수 미탐(§1 ①) | `6be9612` | 명시 토큰 2개 · `xfail(strict)` 2건 **소멸** · 회귀 26→34 · 음성 대조 13종 · 전수 리포트 **바이트 동일** · `166 passed · 5 xfailed` · `mypy . .claude` 137 files 0 |
| 2 | **R-1** — 계약 401 균일화 정본 판정(§1 ②) | `dec3124` | 정본 `x-auth` · `Unauthorized.description` 포인터화 · 와이어 무변경 · `openapi-spec-validator` OK · blast radius 12 오퍼레이션(description 만) |
| 3 | **A-3′ · R-5** — 탐지기 후보 선정(§1 ⑤) | `44eec3f` | Kotlin 반사 전환 · 음성 대조 **11건** · `ktlintCheck detekt build --rerun-tasks` **exit 0**(81 tasks) · api **183**(core 359 · application 44 · infrastructure 115 · worker 3) · 제품 Kotlin **0줄** |
| 4 | **X24-5** — 인라인 헤더 fail-closed(§1 ④) | `44eec3f` | fail-closed 4자리 · 인라인 집합 고정 테스트 신설 · **전제 정정**(인라인 헤더 0건 → **2건**) |
| 5 | **R-2** — 원장 행 4 근거 파일 오지목 | 이 커밋 | `WorkspaceContractTest` → **`WorkspaceEndpointReachTest`**(`:181`·`:189`·`:354`·`:362`·`:591`). 전수 확인으로 전자의 `FORBIDDEN` 적중 **0** |
| 6 | **R-3 · R-4** — 가드 주석 사유 · 인용 run 전체 결론 | 이 커밋 | R-3: 행 4 의 `ci:quality` 유보 사유(「그 게이트가 네 형태를 잃어 조치 대기」)가 **소멸**해 표기를 더했다 · R-4: run `32222249150` 의 **전체 결론이 `cancelled`** 임을 행 5 에 병기 |
| 7 | **`ci:e2e` 안정화**(cross §8-3-1 이 연 것) | `f3de501` · 관측 `3ea1983`·`70ec78f` | 원인 **apt**(27분 38초 무출력 · 취득 타임아웃 부재) · 조치 = 분리 + 상한(6분/8분) + `Acquire::*::Timeout 20` · **캐시 두 갈래 관측 완료**(run `32229496368` 미적중 / run `32230037832` 적중, 둘 다 e2e **success**) |

**열림 — 마감과 수신자**

| # | 조건 | 마감 | 수신자 |
|---|---|---|---|
| 8 | **I-8** — `encryption_scheme` DEFAULT/CHECK(§1 ⑥) | **Phase 4 암호 설계 첫 커밋** | `kotlin-implementer` / 참조 `contract-keeper` |
| 9 | **X24-2 잔여** — 401 **3갈래 비율 회귀**(문턱 1.5) 신설(§1 ③) | **Phase 4 착수 전** | `kotlin-implementer` |
| 10 | **X24-3 남은 절반** — 「모든 `String` 명시 분류」 **미채택**(근거 등재, §1 ⑤) | 사용자가 뒤집을 때 | 리더 → 사용자(§5 ⑲) |
| 11 | **인라인 헤더 2건의 계약 처분** — `Location`·`Content-Disposition` 을 `components/headers` 로 올릴지, 계산값으로 두고 형식 테스트를 붙일지 | Phase 4 두 엔드포인트 생성 전 | **`contract-keeper`** |
| 12 | **test-plan X-A2 잔재** — `00_contract-keeper_test-plan.md` §2 X-A2 행이 옛 넓은 문면(「헤더 누락」)을 든 채 남았다 | **문서 커밋**(강제 동작은 어긋나지 않는다) | `contract-keeper` |
| 13 | **C-3 / 게이트 23 조치 6** — 표 7 D-2 앵커의 **숫자 → 예시 이름 매핑**이 계약에 안 묶였다(`ContractSpec.kt:341-345`) | **Phase 3 종료 전 — 마감 경과** | `contract-keeper`/`kotlin-implementer` |
| 14 | **T-2 / 게이트 23 조치 9 묶음** — 표 1b·8·9·10·13·14·15·16·17 · A-13 문면(권고, 개별). **최소 6건이 마감에 손대지 않은 채 닿았다** | **Phase 3 종료 전 — 마감 경과 · 리더 재지정 필요** | `kotlin-implementer` |
| 15 | **T-3** — 산출물 §6 「남긴 것」이 게이트 23 조치 **6·9·10·11** 을 열거하지 않는다 | Phase 3 종료 판정 전 | `kotlin-implementer` |
| 16 | **X24-4** — 백틱 제외의 오탐 4형태(`fun <T>`·`fun String.`·`fun\n`·애너테이션 뒤) | 백틱 함수명을 쓰는 **새 테스트 커밋** | 스킬 소유자 |
| 17 | **R-6** — 스캐너 제외 집계 **6 vs 실제 7** | 마감 경과 — 리더 재지정 | 스킬 소유자 |
| 18 | **표 18** — 강제 TRACE 에서 프레임워크 로거 3종 미도달(`Http11InputBuffer`·`StatementCreatorUtils`·`QueryExecutorImpl`) | **Phase 4 문서 본문 진입 전** | `kotlin-implementer` |
| 19 | **K-2 / 기록 ④** — `CountingDataSource` 의 `JdbcClient` 전제를 KDoc 이 아니라 장치로 | Phase 4(raw JDBC 하강 커밋) | `kotlin-implementer` |
| 20 | **표 11**(스캔 루트 비대칭 — **리더 판정 미수령**) · **표 20**(계정 삭제 잔여 조건) | 리더 판정 / 기능 커밋 | 리더 · `privacy-gate` |
| 21 | **기록 ①** — `Bearer <임의 문자열>` 이 무자격 DB 왕복 1회를 만든다 | 배포 전(레이트 리밋 판단) | 리더 |
| 22 | **탐지기의 `worker` 모듈 도달 = false**(조건 아님, 기록) | Phase 5 worker DTO 생성 시 | `kotlin-implementer` |
| 23 | **`llm-lane` 30분 타임아웃**(Phase 5 기존 이월) | **Phase 5 착수 전** | 리더 |

**이 절이 적는 것 중 `76f6863` 이후는 전부 리뷰 미수령이다.** `f3de501`·`dec3124`·`6be9612`·`3ea1983`·
`70ec78f`·`44eec3f`·`98702e4` 일곱 커밋은 어느 관점의 리뷰도 받지 않았다 — **Phase 4 첫 리뷰 게이트의
범위 = `76f6863..98702e4`** 로 못 박는다(이 원장 커밋을 포함해 그 뒤로 자란 것까지). 게이트 23 → 24 에서
쓴 방식(조치 배치를 다음 게이트 범위에 포함)과 같고, **차단을 닫은 커밋은 그 자체로 리뷰 대상**이라는
규율도 그대로다.

### §3 — Phase 3 종료 조건 표 판정 (7행)

| 행 | 원 조건(요지) | **이번 판정** | 사유 · 남는 것 |
|---|---|---|---|
| **1** | Spring JDBC repository·트랜잭션 경계 | **조건부 `예` — 그러나 표의 `충족` 은 `아니오` 유지** | F-4 가 **3관점 합의로 해소**되면서 **남는 것이 전부 Phase 4 종속인 유일한 행**이 됐다(문서·변환 repository 미착수 · K-2 는 raw JDBC 하강 커밋). **Phase 2 L348 전례**를 따른다 — 실체가 다음 Phase 에 있는 행은 `예`로 올리지 않고 **사유를 명시한 `아니오`** 로 둔다. 근거 없는 `예`를 만들지 않는 것이 이 표의 규약이고, 「Phase 4 종속」이라는 사유가 판정을 대신한다 |
| **2** | Argon2·JWT·가입과 기본 작업 공간 원자 생성 | **`아니오` — 사용자 판단 항목** | 이 배치 **무접촉**이고 privacy-gate 가 I-9·I-9b 준수를 재확인했다. **재료는 갖춰졌고 남은 것은 결정 하나** — R-2 교환비(대기 상한 250ms 로 얻은 것과 잃은 것의 **용량 결정**)다. **리더가 닫지 않는다**(§5 ⑱ X-25) |
| **3** | `/auth/*` · `/workspaces/*` 엔드포인트 | **`아니오` — 계약상 닫혔고 X24-2 만 잔여** | 401 균일화가 **세 갈래로 좁혀져** 계약상 닫혔다(`dec3124` 정본 판정 + `b9097f6` 구현 + 2관점 시간 실측 1.007~1.036 / **1.003**). **잔여는 X24-2**(상시 회귀 부재) 하나이고 §1 ③ 으로 **Phase 4 착수 전 조건**이 됐다. 그 밖: 기록 ①(배포 전) · Phase 4 문서 경로 |
| **4** | 소유권을 숨기는 404 + unique/check/FK 매핑 | **조건부 — Phase 4 첫 게이트 통과 시 `예` 가능** → **[게이트 25] 조건 충족 · `예`로 승격** (단서 L-1·H7 병기 — 위 Phase 3 표) | 스캐너 복원(`01d78a1`)이 **3관점 합의로 통과**했고 두 상수 미탐도 `6be9612` 로 닫혔다. 근거 파일 오지목(R-2)도 정정했다. **그런데 `6be9612` 가 리뷰 미수령**이라 지금 `예`로 올리면 **자기 배치를 자기 근거로 삼는 형태**가 된다(게이트 16 후속 §6 이 같은 이유로 판정을 미룬 전례). check 제약 → 오류 본문 매핑은 문서·변환 자원이 없어 **Phase 4 종속** |
| **5** | React ↔ Kotlin 로그인·작업 공간 E2E | **`예` 유지** | 승격 근거(`b3f76b2` · run `32222249150` 잡 `e2e` success · 로컬 12/12 · 음성 대조 6/6)는 그대로 서고 이 배치의 `frontend/**` diff 는 **0** 이다. HEAD `2a4523d` 에서 잡이 `cancelled` 였던 것은 **apt 사고**였고 `f3de501` 로 원인을 잡아 **연속 2실행 success** 로 재확인했다(run `32229496368`·`32230037832`). 즉 3실행 중 **2 success · 1 cancelled(원인 규명·조치 완료)** |
| **6** | contract test 와 React 테스트가 Kotlin API 에서 통과 | **`아니오`** | 파서 fail-open 은 전건 닫혔으나(`560c292`·`44eec3f`) **마감 「Phase 3 종료 전」인 C-3(D-2 앵커)이 무변경으로 경과**했다. 그리고 **종료 조건 (a) 「Kotlin API 에서」의 해석**이 리더 판정 재료로 새로 올라왔다(행 20 — contract test 는 `@WebMvcTest`+가짜 저장소, 실 소켓은 `ReachTest`, 실 bootJar 는 E2E). **CI 원격 캐시 거동은 게이트 21 이후 여전히 0관점**이고, 나머지 11 엔드포인트는 Phase 4 다 |
| **7** | 계약 개선이 있었다면 계약 파일·Kotlin·React 3자 동일 + 근거 기록 | **`아니오` — 사용자 판단 항목 포함** | **3자 동일 이전에 「계약 1자가 자기와 달랐던 것」이 닫혔다**(`dec3124`). 남은 것: ⑴ **사용자 판단** — ⑥ 이메일 ASCII → OQ-E2 종속(§5 ⑬·⑰) ⑵ 3자 드리프트는 **Phase 6 타입 교체** ⑶ **X-A2 문서 잔재**(문서 커밋) ⑷ ⑯ 3필드는 Phase 4 DTO 커밋 |

**리더 판정: Phase 3 을 「조건부 종료」로 권고한다.** 세 관점 전부 **Phase 3 종료 차단 0**이고
(Claude 차단 0 · privacy-gate 「Phase 3 종료 차단: 없음」·차단 사유서 미작성 · codex 는 `needs-attention`
이나 종료 여부를 **판정하지 않았다**), Phase 0·1·2 에서 쓴 조건부 종료와 같은 형태다.

**조건 셋:**
1. **Phase 4 첫 리뷰 게이트가 `76f6863..98702e4` 를 리뷰하고 차단 0** — 이 범위는 **차단을 닫은 커밋
   (`6be9612`)과 조건 처리 커밋 전부**를 담고 있어 리뷰 없이 종료를 확정하면 저작자와 심판자가 같아진다.
   **→ [2026-08-19] 조건 ⑴ 충족 — 게이트 25**(`04_crypto`, 범위 `76f6863..9c7aa03` 17커밋 · 완주
   `6ac9158`). 3관점이 그 범위를 봤고 **차단① 0 · privacy-gate 차단 0 · codex 차단 라벨 없음**이다.
   **차단② 1건(L-1)은 열려 있으나 그 대상은 이 조건이 겨눈 「리뷰 미수령」이 아니라 통로**이고, 리더는
   착수 차단으로 보지 않았다(아래 「Phase 4 crypto 단위」 §2 ①). 조건이 닫힌 것은 **수령**이지 지적이 아니다.
2. **사용자 판단 대기 항목은 착수 차단이 아니다**(§5 — 20건 전부 **심각도 라벨·계약 문면·절차·용량 결정**
   이고, 어느 쪽으로 나든 **잔여 목록과 마감이 바뀌지 않는다**). 그 사실을 라벨로 명시한다.
3. **I-8 · 401 3갈래 비율 회귀 · 인라인 헤더 2건의 계약 처분은 Phase 4 첫 커밋 조건**이다(§2 8·9·11).

**단 Phase 4 착수는 사용자 승인 대기다.** 하네스 규칙이 「이전 Phase 의 종료 조건이 **전건** 충족되지
않으면 다음 Phase 에 착수하지 않는다」이고, **미충족(`충족 = 아니오`) 행이 여섯이다 — 행 1·2·3·4·6·7.**
Phase 0(1행 미충족)·Phase 2(1행 미충족)에서 사용자 승인으로 넘어간 전례가 있으나 **이번은 여섯 행**이고,
그중 **행 2·7 은 사용자 판단이 입력**이라 리더가 대신 닫을 수 없다. 승인 없이 Phase 4 를 시작하지 않는다.

**승인 기록 (2026-08-19).** ⑴ **사용자가 "Phase 4 착수 진행"으로 승인**했다(2026-08-19, 사용자 harris.lee).
⑵ **미충족 행 = Phase 3 표 행 1·2·3·4·6·7**(위 문단 — 행 1 Phase 4 종속 / 행 2·7 사용자 판단 입력 /
행 3 X24-2 잔여 / 행 4 스캐너 커밋 리뷰 미수령 / 행 6 마감 경과 항목). ⑶ **승인 시점 HEAD = `b66fa46`**
(원장 커밋; 코드 HEAD 는 `98702e4`, toString 도달 게이트 이름 충돌 수정 레인 진행 중). 이 기록 뒤에 Phase 4
에이전트를 호출한다. **Phase 4 첫 리뷰 게이트 범위는 `76f6863..<첫 단위 끝>`** — 리뷰 미수령 조건 처리
커밋 전부 + 첫 단위(`crypto`)를 한 번에 본다(위 조건 1). 첫 커밋 조건(I-8 `V2` DEFAULT 정정 · 401 3갈래
비율 회귀 · 인라인 헤더 2건 계약 처분)은 첫 단위 배치에 포함한다.

### §4 — 사실 기록 6건

| # | 사실 | 근거 | 왜 남기는가 |
|---|---|---|---|
| ① | **`ci:e2e` 취소의 원인은 apt 였고 상한이 없었다** — HEAD `2a4523d`(run `32225305372`)의 `e2e` 가 30분 16초 만에 취소, `InRelease` 취득에서 **27분 38초 무출력**. 브라우저 다운로드는 **시작조차 못 했다**. 조치 후 실측에서 같은 apt 스텝이 **39초 ↔ 1분 56초로 3배 흔들렸다**(run `32229496368` ↔ `32230037832`, 같은 명령·같은 러너 이미지) | `03_react-e2e_implementation.md` §3-5 · `gh run view` | **28분은 특별한 사건이 아니라 분산의 꼬리**였다는 뜻이다 — 그래서 캐시는 부수 효과이고 **상한이 본 조치**다. 「도달 0 을 의심한다」가 아니라 「**상한 없는 대기**를 의심한다」는 형태의 기록 |
| ② | **`llm-lane` 이 세 실행 연속 `cancelled` 다** — run `32225305372` 는 06:53:09→07:23:27 로 **30분 18초**(상한 초과) · run `32229496368` 은 7분 만에 · run `32230037832` 는 6분 만에 취소됐다. 뒤 둘은 **후속 push 가 만든 취소**로 보이고(다음 run 시작 시각과 13초·수십 초 차) **원인을 판정하지 않았다** | `gh run view --json jobs` 실측 | 이 잡의 30분 상한 문제는 2026-08-15 부터 열려 있는 **Phase 5 착수 전** 항목이다. **세 실행 연속 초록을 못 본 것**은 사실이고, 그중 **한 건만 상한 초과**이며 나머지 둘은 다른 기제라는 것도 사실이다 — 셋을 한 원인으로 뭉치지 않는다 |
| ③ | **공유 작업 트리에서 `git stash`/`pop` 으로 옛 판을 대조했다 — 규칙 5 가 다루는 사고 유형이다.** 다른 레인이 같은 트리에서 미커밋 작업을 들고 있었고, `stash` 가 그것을 **통째로 들어냈다가** `pop` 으로 되돌렸다. **손실 0**(되돌아왔다) | 게이트 24 조건 처리 레인 **자기 보고**(리더 수령) | `cp` 사고 3건과 **같은 계열**이다 — 사본의 최신성·복원 내용을 **증명하지 않는 절차**로 옛 판을 만졌다. 이번엔 되돌아왔지만 **되돌아온 것이 절차의 성질이 아니라 운**이다. SKILL.md 규칙 5 에 한 줄로 부기했다(옛 판은 `git show HEAD:<path>` 로 사본을 떠서 대조한다) |
| ④ | **위조 토큰 대조 도구의 base64url 하위 2비트 함정** — 서명 마지막 문자만 바꾸면 디코딩 결과가 **같을 수 있어** 「위조 토큰」이 사실은 정상 토큰일 수 있다. 3관점이 각각 확인했고 privacy-gate 는 **자기 감사**로 게이트 20~23 실측이 함정 밖이었음을 보였다(판별자 = 401, 게이트 23 값 0.539ms) | cross 표 25 · 보안 축 §3 | **도구가 재는 것이 이름과 다를 수 있다**는 형태의 실측이고, 이번에는 **먼저 자기를 의심한 쪽이 통과**했다 |
| ⑤ | **「오늘 계약에 인라인 헤더 0건」이라는 전제가 틀렸다 — 실측 2건**(`Location` · `Content-Disposition`). 리더 지시와 교차 종합이 **같은 전제를 공유**했고 구현자가 실측으로 뒤집었다 | `03_kotlin-implementer_phase4-preconditions.md` §2.1 | 게이트 16 §4 ②·게이트 15 별건 1 과 **같은 형태**다 — **검증하지 않은 값이 프롬프트를 타고 하위 레인으로 내려갔다.** 이번에는 하위 레인이 실행으로 잡아 처방 자체가 바뀌었다(무조건 실패 → 갈래 분리) |
| ⑥ | **test-plan `X-A2` 행이 옛 넓은 문면을 든 채 남아 있다** — `dec3124` 가 계약을 좁힌 뒤에도 `00_contract-keeper_test-plan.md` §2 는 「헤더 누락」을 열거에 넣고 있다. 실제 케이스는 이미 좁은 범위로 갈라 재므로 **강제되는 동작은 어긋나지 않는다** | `00_contract-keeper_changelog.md`(`dec3124`) | **정본을 고쳤을 때 그것을 인용한 문서가 함께 안 움직이면 다음 사람이 옛 문면을 근거로 쓴다.** 「강제 동작은 같다」가 **닫았다는 뜻이 아니라는 것**을 적어 둔다 |

### §5 — 사용자 판단 대기 **통합 목록** 20건 (원장 각 절에 흩어진 것을 한 표로)

> **어느 항목도 요약이 정본이 아니다** — 근거 전문은 「근거 절」 열이 가리키는 표에 있다(옮겨 적으면
> 갈린다). 이 표가 새로 하는 일은 **번호·포인터·리더 권고·착수 차단 여부를 한자리에 모으는 것**뿐이다.
> **20건 전부 착수 차단이 아니다** — 심각도 라벨·계약 문면·절차·용량 결정이고, 어느 쪽으로 나든
> **잔여 목록과 마감이 바뀌지 않는다.**

| # | 쟁점 | 근거 절 | 리더 권고 | 착수 차단 |
|---|---|---|---|---|
| ① | X2 심각도 | 「게이트 17·18 후속」 §3 | 무변경 재상신 | 아니오 |
| ② | X13 | 「게이트 17·18 후속」 §3 | 무변경 재상신 | 아니오 |
| ③ | X8·X9 심각도 | 「게이트 17·18 후속」 §3 | 무변경 재상신 | 아니오 |
| ④ | R-5 처분 | 「게이트 17·18 후속」 §3 | 무변경 재상신 | 아니오 |
| ⑤ | E·G 심각도 | 「게이트 17·18 후속」 §3 | 무변경 재상신 | 아니오 |
| ⑥ | H 심각도 | 「게이트 17·18 후속」 §3 | 무변경 재상신 | 아니오 |
| ⑦ | X1 도달 가능성 | 「게이트 17·18 후속」 §3 | 무변경 재상신 | 아니오 |
| ⑧ | X4 라벨 | 「게이트 17·18 후속」 §3 | 무변경 재상신 | 아니오 |
| ⑨ | R7 `nounset` | 「게이트 17·18 후속」 §3 | 무변경 재상신 | 아니오 |
| ⑩ | S2 SKILL.md 축약 | 「게이트 17·18 후속」 §3 | 무변경 재상신 | 아니오 |
| ⑪ | `_root_helper_calls`·`_DYNAMIC_LOOKUP_NAMES` 가 리더 제약(새 기제 금지)을 넘었는가 | 「게이트 19 후속」 §3 ⑪ | **판정 필요** — 제3 근거가 양쪽을 다 참으로 만들었다. 결과는 갈리지 않는다(양쪽 다 X2a 부분 해소) | 아니오 |
| ⑫ | C2 세마포어의 **심각도 라벨** | 「Phase 3 auth 단위」 §4 ⑫ | 라벨이 조치를 바꾸지 않는다(처방은 §6 ⓖ 로 확정) | 아니오 |
| ⑬ | **이메일 ASCII 정책**(비ASCII 로컬파트 422) | 「Phase 3 auth 단위」 §4 ⑬ | contract-keeper 허용 판정 유지(4관점 3:1). **⑰ 의 선행 조건** | 아니오 |
| ⑭ | `InternalError`/`ServiceUnavailable` **설명 개정** 여부 | 「Phase 3 auth 단위」 §4 ⑭ | **바꾸지 않는 것도 판정**이다 — 무개정을 고르면 근거를 적어 같은 제안이 다시 올라오지 않게 한다. `InternalError` 쪽 blast radius 는 **0관점** | 아니오 |
| ⑮ | signup 409 가 **1요청 계정 열거 오라클** (SEC-2) | 「Phase 3 auth 단위」 §4 ⑮ | 갈리는 것은 「계약 승인이 `failure_uniformity` 사유와 정합하는가」이고 그 판정은 **계약·제품 결정** | 아니오 |
| ⑯ | 표 5 `ContractSpec` fail-open 3자리의 **심각도 라벨** | 「Phase 3 workspaces 단위」 §6 ⑯ | **처방은 이미 집행됐다**(union `560c292` · 2자리 `44eec3f`) — 남은 것은 라벨뿐이고 조치를 바꾸지 않는다 | 아니오 |
| ⑰ | **OQ-E2** — 이메일 형식 규칙을 계약이 게시할 것인가 | 「Phase 3 workspaces 단위」 §6 ⑰ | ⑬ 이 먼저 닫혀야 문면이 정해진다(종속). 근거 G1+G2 는 둘 다 선다 | 아니오 |
| ⑱ | 게이트 22 미결 **5건 재상신** — X-5(계약 측정 상태) · X-13(O-7 문구) · X-15(계약 침묵 판정) · X-16 · **X-25(R-2 교환비 — 대기 상한 250ms 의 용량 결정)** | 「Phase 3 workspaces 단위」 §6 ⑱ | 사용자·계약 소유자 축이라 리더가 닫지 않는다. **X-25 는 종료 조건 행 2 의 마지막 미결** | 아니오 |
| **⑲** | **X24-3 넓은 강제 미채택** — 제품 `data class` 의 모든 `String`(+컬렉션)을 안전/민감으로 **명시 분류**하도록 강제할 것인가 | 위 §1 ⑤ · `03_kotlin-implementer_phase4-preconditions.md` §1.5 | **미채택 유지 권고.** ⑴ 「안전」 선언 애너테이션이 곧 **면제 조항 = 은폐형** ⑵ 새로 빨개지는 **8~10 종이 전부 고정 문구·설정값**이라 얻는 것이 없고 진단만 잃는다. **사용자가 뒤집을 수 있다**.<br>**[게이트 25 새 입력] 같은 지적이 3회차 독립으로 재발했다**(codex B-3, 게이트 23 `:250` → 게이트 24 X24-3 → 게이트 25 H3 — 세 번 다 같은 `ExportEnvelope` 예시). codex 는 원장을 읽지 않으므로 이 미채택 판정을 **몰랐다** — 독립성의 대가이자 값이다. **권고는 바뀌지 않았고**(은폐형 근거 무변경) **재발 횟수 자체를 사용자 판단의 입력으로 등재**한다. 상세는 아래 「Phase 4 crypto 단위」 §2 ⑥·§4 | 아니오 |
| **⑳** | **X24-2 처방 선택** — 401 균일성을 **고정 sentinel + 주기적 독립 실측**으로 둘 것인가, codex 처방(요청별 존재 불가능 UUID + 시간 분포 회귀)으로 갈 것인가 | 위 §1 ③ · cross §5-Ⅰ | **고정 상수 유지 + 3갈래 비율 회귀 신설** 권고. 두 관점이 **고정 상수를 정반대 방향의 근거로 들었고**(공격자가 고를 수 없다 ↔ 전역 과열) 그 갈림은 서로의 근거를 읽지 않은 상태에서 나왔다. 전역 과열은 실측에서 **관측되지 않았다** | 아니오 |

**⑲·⑳ 이 새로 올라온 것이고 ①~⑱ 은 무변경 이월이다.** 게이트 24 는 **사용자 판단 항목을 하나도
닫지 않았다** — 리더가 닫을 수 있는 것만 닫았다(§1 여섯 판정). 답이 오지 않았다는 이유로
조용히 닫지 않는다는 규약이 ①~⑱ 에 그대로 적용된다.

## Phase 4 — 문서 API·암호화·내보내기

계획 문서 §5 Phase 4(`:411-420`). 여섯 항목과 **원문 종료 조건 네 조각**(실 PostgreSQL 업로드→조회→
검수→3형식 다운로드→삭제 / 평문이 DB·로그에 없음 + I-7 / 요구사항 인벤토리 미충족 0 / 차분 비교
`미확인` 0)을 **항목당 한 행**으로 옮겼다. 근거 없는 `예`는 `아니오`로 취급한다는 규칙이 그대로
적용된다. 첫 작업 단위 `crypto` 의 구현·게이트 25 상세는 아래 「Phase 4 crypto 단위」 절이 든다 —
**값을 여기로 옮겨 적지 않는다.**

> `실행 경로` 열의 어휘 정본은 위 Phase 0 표의 포인터를 따른다.
>
> **[2026-08-19 게이트 25] 열 개 행이 전부 `아니오`다.** crypto 단위가 만든 것은 종료 조건을
> **만족시킬 수 있는 도구**이고, 조건 자체는 **저장 경로가 그 도구를 실제로 쓰는지**로 판정된다 —
> 세 레인이 이 점에 일치했다(cross §8). 그중 **AEAD 정확성 축 하나만 이 단위로 닫혔고**(I-7 전 6항
> 통과, `9c7aa03`) 그 행조차 `예`로 올리지 않는다: 조치 배치가 진행 중이라 **미해결이 일곱**이다.

| 종료 조건 | 충족 | 실행 경로 | 근거 | 미해결 항목 | blocked-by | 마지막 갱신 주체 |
|---|---| --- |---|---|---|---|
| JSON/multipart 업로드와 제한 처리 | 아니오 | `안 돎` | 미착수. 계약 명세만 섰다 — `04_contract-keeper_documents-test-spec.md` 77 케이스(`765a377`) | **K2 — 77 명세가 저장 순서·소유권 확인 전 무변화·멱등·최신순을 단언하지 않는다**(codex 단독 high) · **K3 — 그 77 케이스에 강제자 0**(`grep DC/DL/…` 0건, Claude C-4) · **K5 — P-22 식별자 충돌**(`…test-spec.md:266` ↔ `ContractSpec.kt:409`, 마감 「Phase 4 문서 API 착수 전」) | documents 단위 | leader (2026-08-19, 게이트 25) |
| DOCX/PDF/HWPX 추출 | 아니오 | `ci:kotlin` | **C1 로 추출기가 섰다(`df0766e`, 2026-08-20). 판정은 게이트 27 뒤이므로 `충족` 은 그대로 `아니오` 다.** `infrastructure/ingest/**` — docx(POI)·pdf(PDFBox)·hwpx(StAX) 추출과 §5 파서 방어 D-4~D-17, 산출물 `04_kotlin-implementer_documents.md`. 이 행의 직전 값(*"미착수 · Kotlin 코드 0"*)은 C1 이후 **낡은 서술**이었고 C2 레인이 그 사실을 지적해 리더가 고쳤다 | **I-11 방어는 코드로 섰다 — 게이트 27 이 층과 음성 대조를 아직 판정하지 않았다.** 계획 §9.2 가 이미 한 건을 스스로 적었다(D-d: 앞쪽 방어 `ZipBudget` 에 가려 POI 설정의 행동 음성 대조가 성립하지 않아 구조 단언으로 바꿨다 — **같은 형태가 더 있는지가 게이트 27 의 질문**) · **X1 은 추출기가 섰는데도 도달 0이다**(C2 보고) — 고아 서로게이트를 넣을 입력 경로가 아직 없고, 계획 §6.4 의 「제목에도 같은 정의역 판정」은 **하지 않았다**(C3 몫) | 게이트 27 · C3 | leader (2026-08-20, C1·C2 반영) |
| 암호화 저장·복호화 조회 | 아니오 | `ci:kotlin` | **저장 경로가 섰다(C2, 2026-08-20). 복호화 조회 경로는 아직 0이라 `충족` 은 그대로 `아니오` 다** — 판정은 리더 몫이므로 이 행의 값은 올리지 않는다. 선 것: `DocumentService` 가 `ContentCipher` 를 부르는 **첫 제품 코드**이고(원문을 `PlainBody` → AEAD → `bytea` 로 넣는다), `JdbcDocumentRepository`·`JdbcConversionRepository` 가 봉투 두 값을 **명시적으로** 적으며, 문서·변환·작업 세 행이 **한 트랜잭션**이다. 산출물 `04_kotlin-implementer_documents.md` §A~§F | **X9/F-6 닫혔다** — `DocumentStorageContextTest` 가 실행 시점 난수 키 + `KeyCheckValue.of()` KCV 로 자기점검을 **통과해** 뜬 컨텍스트에서 실제 INSERT/SELECT 를 하고, **2세대 회전 왕복**까지 조립된 빈으로 돈다. 「자기점검이 정말 도는가」는 **틀린 KCV 로 기동이 실패함**을 같은 파일이 단언한다 · **X5/F-5 는 아직 닫히지 않았다 — 이 칸의 직전 문면이 재지 않은 것을 쟀다고 적었다**(게이트 27 CR-1, 리더가 2026-08-20 정정). 실제로 재는 것: 포트가 세 열을 함께 받아 단일 UPDATE 를 구조로 강제하고, 실 DB 에서 **문장 수 2(SELECT+UPDATE)** · NULL 보존 · 낙관적 조건을 잰다. **재지 않는 것: 「부분 복호화 실패 시 롤백 뒤 행이 무변화」** — 실 DB 케이스가 없고, 대역 저장소가 「UPDATE 를 부르지 않는다」까지만 본다. 계획 §8.2 는 네 조건 전부를 실 PostgreSQL 몫으로 열거했으므로 **층 이동이며 이탈 기록에 없다.** 마감: 실 DB 케이스는 **Phase 5 착수 전**, 이 칸의 문언은 **즉시**(이 정정) · 회전 경합 축은 `d19175b` 가 별건으로 닫았다(`FOR NO KEY UPDATE` + 암호문 전부를 낙관적 조건에 · 재현 테스트가 수정 전 3/3 빨강) · **X10 은 이미 닫혔다**(`7be37db`) · **남은 것**: 복호화 **조회**(C6) · X2(응답 DTO 와 같은 커밋, C3) · 타이밍 X3 의 codex A-6 처방(**미배정**) | documents 단위 | kotlin-implementer (2026-08-20, C2) |
| 문서 목록·삭제, 변환 조회·검수 저장 | 아니오 | `안 돎` | 미착수. 소유권 은닉 404 의 **패턴**은 Phase 3 작업 공간에서 섰다(위 Phase 3 표 행 4) — 문서·변환 자원에 그것이 실제로 적용되는지는 이 단위가 처음 잰다 | check 제약 → 계약 오류 본문 매핑이 미착수(Phase 3 행 4 에서 이월) · **H6·H7 — 스캐너 403 이 어휘 분석 없이 도는 문제**(마감 **이 행 진입 전**) · 표 18 — 강제 TRACE 에서 프레임워크 로거 3종 미도달(마감 「Phase 4 문서 본문 진입 전」) | documents 단위 | leader (2026-08-19, 게이트 25) |
| DOCX/TXT/HWPX 내보내기 | 아니오 | `안 돎` | 미착수. 계약 조항 `x-filename-charset` 은 있다(`contracts/easy-doc-v1.yaml:1238-1271`) | **K1 — `x-filename-charset` 을 실행 코드가 읽지 않는다**(도달 **0** · `\` 입력 커버리지 0 · `FORBIDDEN_IN_FILENAME` 이 `private` — codex high + Claude C-1 **2관점 합의**, 마감 내보내기 엔드포인트 커밋) · RFC 5987 `Content-Disposition` 은 계약의 **인라인 헤더 2건** 중 하나라 그 처분(조건 11)에 묶인다 | export 단위 | leader (2026-08-19, 게이트 25) |
| 저장 암호화 AEAD 정확성 검증과 문서 차분 비교(§4.5) | 아니오 | `ci:kotlin` · `1회성:docs/migration/_workspace/reviews/04_security_privacy-gate.md` | **AEAD 축은 이 단위로 닫혔다 — `충족` 은 그래도 `아니오`다.** privacy-gate 가 **I-7 전 6항 통과**를 저장소 **밖 독립 탐침**으로 판정했다(`9c7aa03` · 게이트 25): round-trip 16종 · nonce 200,000 충돌 **0** · 변조 **3,248건 전수 거부** · AAD 바꿔치기 **109/109 거부** · oracle 8갈래 **예외 1종** · 회전 실행. 저장소 회귀는 **729건 실패 0**이 2관점 독립 재현(Claude·privacy-gate 각각 `--rerun-tasks`, 수치 바이트 일치). **I-8 잠정 위반 해제**(`e891a08` — V3 가 CHECK 도메인 이전 + DEFAULT 제거, 해제 조건 3항 충족). **차분 비교(§4.5)는 아직 대상이 없다** | **일곱 중 여섯이 닫혔고 하나가 성격을 바꿨다 — 아래 「게이트 26 후속」 절이 정본이다.** X1·F-3/X6·F-4/X8·F-2/X7·R-1/X4·X10 이 3레인 교차로 해제됐고(**AAD 격리 X4 는 codex 가 명시적으로 「달성」**), **타이밍/X3 만 남았다** — 값은 2.84 → **1.02배**로 내려갔으나 codex 가 **문턱 1.5 가 유도되지 않은 값이라 안정적 1.49배 oracle 을 승인한다**고 지적했다(A-5·D-2, codex 내부 2회 재현이므로 **관점 수 1**). 그 처방(다중 실행·절대 격차·분포)은 리더가 `documents` 단위 배치로 이월했다 | documents 단위 | leader (2026-08-19, 게이트 25) |
| **종료 조건**: 실 PostgreSQL 에서 업로드 → 조회 → 검수 → 3형식 다운로드 → 삭제 전건 통과 | 아니오 | `안 돎` | 미실행 — 다섯 단계 중 **어느 하나도 엔드포인트가 없다**. Testcontainers PostgreSQL 자체는 Phase 1 부터 CI 에서 돈다 | 이 행이 요구하는 것은 **한 흐름의 통과**라 단계별 초록으로 대신할 수 없다 · 흐름을 어느 층에서 잴 것인가(계약 테스트 ↔ 실 소켓 ↔ 실 bootJar)는 Phase 3 행 20 이 연 해석 문제와 같은 자리다 | documents·export 단위 | leader (2026-08-19, 게이트 25) |
| **종료 조건**: 평문이 DB·로그에 없다 (I-7 round-trip·변조 거부가 Kotlin 테스트로 확인) | 아니오 | `ci:kotlin` · `ci:quality` | **I-7 축은 확인됐다**(위 행). **평문 축은 미실행 — 경로가 없다**: 「실 업로드 → 변환 → 내보내기 후 로그 전문 grep」을 **세 레인 모두 같은 사유로 신고**했고, 이 단위가 확인한 것은 **암호 서비스 자신의 로그**뿐이다(privacy-gate 탐침 7 — 키 바이트 반향 0 · 평문 반향 0) | **X2 — `PlainBody` 가 Jackson 으로 평문 직렬화된다**(기제는 열려 있고 **도달은 오늘 0** — `crypto` 패키지 밖 참조 0건. 세 관점이 같은 사실 위에 서고 갈리는 것은 「기제를 지금 닫을 것인가」뿐이다. **도달이 생긴 뒤에 넣으면 그때는 이미 유출 경로가 한 번 존재했던 것**) · 평문 로그 스캐너가 이 행을 겨냥해 돈 적은 없다(`ci:quality` 는 제품 소스 정적 탐지이지 실행 로그 grep 이 아니다) | documents 단위 | leader (2026-08-19, 게이트 25) |
| **종료 조건**: 문서 처리 요구사항 인벤토리 항목의 미충족 0 | 아니오 | `안 돎` | 미실행 — 문서 처리 축의 충족 판정을 돌린 것이 없다. 인벤토리 정본은 `00_requirements-inventory.md` | 「미충족 0」이 **항목 0개에서 참이 되는** 구멍(리뷰 A-1/X-08)을 이 행이 그대로 물려받는다 — 판정 전에 **대상 항목 수를 먼저 고정**해야 한다 · 판정 장치 자체가 아직 없다 | documents·export 단위 | leader (2026-08-19, 게이트 25) |
| **종료 조건**: 문서 차분 비교(§4.5)의 `미확인` 불일치 0 | 아니오 | `안 돎` | 미실행 — 차분 하네스도, 비교 대상 산출물도 없다 | `미확인` 을 **무엇으로 판정할 것인가**가 정해지지 않았다(기준은 Python 출력이 아니다 — 갈리는 자리는 차단 사유가 아니라 기록 대상이라는 규약이 여기에도 적용된다) · fixture 소재는 `parity/fixtures/` 이나 문서 도메인은 아직 없다 | documents·export 단위 | leader (2026-08-19, 게이트 25) |

## Phase 4 crypto 단위 — 구현과 게이트 25 (2026-08-19, 리더)

게이트 25(`04_crypto`) 정본은 `reviews/04_crypto_cross.md`, 보안 축 정본은
`reviews/04_security_privacy-gate.md` 다. 이 절은 **그 재료 위에서 리더가 내린 판정**과 조치 현황을
적는다 — 리뷰 산출물의 값을 옮겨 적지 않고 포인터를 둔다(옮겨 적으면 갈린다).
**수치·판정에는 커밋 해시를 함께 적는다.**

> 이 절의 표들은 종료 조건 표가 아니라 **판정·현황 표**라 표기 검사기
> (`tests/test_harness_scope_reach.py`)의 대상 표에 들어가지 않는다 — 그래도 같은 어휘를 쓴다.

### §1 — 구현 요지 (`74ec2b0..9c7aa03` 5커밋 · 착수 HEAD `b66fa46`)

| 커밋 | 내용 |
|---|---|
| `74ec2b0` | **도메인 타입과 포트** — `PlainBody`(평문 래퍼) · `EncryptedContent`(암호문 + `scheme` + `key_version`) · `EncryptedField`(암호문 컬럼 4종) · 단일 실패 예외 `DecryptionFailedException` |
| `fcf584b` | **AES-256-GCM 어댑터와 키 설정** — JCA `AES/GCM/NoPadding`, I-7 전건 회귀 17건 |
| `e891a08` | **`V3` 로 `encryption_scheme` 을 AEAD 로 정정** — CHECK 도메인 이전 + **DEFAULT 제거**(I-8 잠정 위반 해제), `V2` 주석의 무효 근거 정정, 마이그레이션 기대값을 디스크에서 유도 |
| `858347d` | **401 세 갈래의 응답 시간 비를 상시 회귀로 고정**(X24-2 — Phase 4 착수 전 조건 9) |
| `9c7aa03` | 산출물 — I-7 대응표 · 키 규약 · 음성 대조 |

**AEAD 설계 요지.** 저장 바이트는 `nonce(12) || AES-256-GCM 출력(암호문 || 태그 16)` 이고,
**방식 이름과 키 세대를 바이트에 넣지 않는다** — 두 값은 `encryption_scheme`·`key_version` 컬럼이
들고, associated data
`"easydoc-aead|{scheme}|{keyVersion}|{테이블.컬럼}|{행 UUID}"` 로 결속된다. 같은 사실을 두 곳에 적으면
어긋날 자리가 생긴다는 이유이고, 이 결속이 막는 것은 **같은 행의 다른 컬럼 바꿔치기**(초안↔검수본 —
수정률 KPI 조작)와 **다른 행의 같은 컬럼 바꿔치기**(소유권 검사를 통과한 경로로 남의 본문 복호화 —
§5 Phase 7 즉시 중단 기준)다. `|` 가 어느 조각에도 들어갈 수 없어 서로 다른 네 값이 같은 문자열을
만들지 못한다.

**Python 원본 대응 없음.** 이 단위는 Fernet 포팅이 아니라 **표준 AEAD 신규 구현**이고 구현자는
`app/privacy/crypto.py` 를 열지 않았다(CLAUDE.md 「명세가 있는 것을 확인하겠다고 Python 코드 읽기」 금지).

### §2 — 게이트 25 리더 판정 9건

| # | 쟁점 | **리더 판정** |
|---|---|---|
| **①** | **L1 / 차단②** — 심판 산출물(`reviews/`)을 **피심판 커밋이 편집**했다(`6be9612`·`01d78a1`·`ea36330`. Claude 단독 · cross §4-① 코드 대조로 3건 확정) | **처방 ⑴ 을 채택한다 — 조치 레인은 `reviews/` 를 쓰지 않는다.** 강제는 **SKILL 규칙**으로 두고 **스캐너에 얹지 않는다**: 스캐너는 제품 코드의 개인정보 불변식 탐지기이고, 문서 레인 규율을 거기 넣으면 축이 섞여 오탐이 그 게이트의 신뢰를 갉는다(같은 이유로 게이트 24 ①의 ㉮ 경계 완화를 기각했다). 집행 레인은 **하네스**이고 **진행 중**이다.<br>**처방 ⑵(원장 기록)도 함께 한다 — 배타가 아니다.** 세 커밋이 심판문을 편집했다는 **사실 자체**를 여기에 남긴다: `6be9612`(스캐너 33줄 + `03_security-scanner_privacy-gate.md` **122줄**) · `01d78a1`(65 + **85**) · `ea36330`(76 + Kotlin 20 + **136**). 규칙이 생겨도 **이미 편집된 문서는 그대로 남으므로**, 다음 사람이 그 파일을 근거로 쓸 때 이 사실을 함께 읽어야 한다.<br>**착수 차단은 아니다.** 근거 둘: ⑴ **내용이 정직하다** — Claude 스스로 「내가 차단으로 올리는 것은 내용이 아니라 **통로**」라고 적었고, 편집된 §8 의 서술은 cross 가 코드 대조로 참임을 확인했다 ⑵ **그 편집은 리더 지시의 인용**이었다(게이트 24 ① 집행 지시가 「음성 대조와 잔여를 산출물에 남길 것」을 포함했다) — **의도가 면죄가 되지는 않지만 은폐 의도의 부재는 심각도의 입력**이다. 통로는 닫되 배치는 세운다 |
| **②** | **X1 / 차단① 후보** — 문자열 포트라 비쌍 서로게이트가 **비가역** 손실(`x\uD800y` → `x?y`, `equals=false`). codex high **실행 근거** ↔ privacy-gate **「결함 아님」**(JVM 표준 동작) | **⑴ §5 Phase 7 「즉시 중단」 기준에 넣지 않는다 — 차단① 아니다.** 그 기준이 겨눈 것은 **AEAD 자신의 round-trip 실패**이고, 이 손실은 **암호화 이전**(`String.getBytes(UTF_8)`)에 일어난다 — privacy-gate 의 경계 규정이 맞다. 게다가 **오늘 데이터가 0**이라 사건이 성립할 대상이 없다.<br>**⑵ 처방은 codex 쪽 — 쓰기 전 거부(REPORT 모드)를 documents 단위에 강제한다.** privacy-gate 의 「치환을 수용하고 I-13 에 기록」은 **채택하지 않는다.** 근거: 이 제품의 종료 조건은 「AEAD 가 규격대로인가」가 아니라 **「사용자 문서가 그대로 돌아오는가」**이고, 치환은 **인증에는 성공하면서 본문이 영구 손상**되는 형태다 — 즉 **탐지도 복구도 불가능한 조용한 손실**이고, 그것은 이 하네스가 은폐형으로 분류해 온 바로 그 성질이다. **마감이 지금인 이유**: 문서 추출기가 붙는 순간 입력이 사용자 파일이 되고, **고른 시점에는 이미 그 문자를 쓴 행이 존재할 수 있다.**<br>**두 처방이 양립하지 않으므로 privacy-gate 안을 §4 에 병기한다** — 지우지 않는다 |
| **③** | **H4 / 차단② 승격 후보** — 새 Kotlin 게이트 파일을 **삭제해도 CI 통과**(`ci.yml:242-268`. codex 단독 high, Claude 미지적) | **승격하지 않는다 — 수정 필요로 두고 장치를 만든다.** 차단②의 정의(「그 사건을 탐지·차단하는 게이트가 **무력화된 상태**」)에 오늘 해당하지 않는다: 게이트는 존재하고 **돈다**. codex 가 지목한 것은 **미래의 조용한 제거**다.<br>**다만 `ci.yml:263-264` 의 자기 선언(「최종 방어선은 그 diff 가 리뷰에 올라가는 것 — 한 칸 더 옮기지 않는다」)을 근거로 현행 유지하는 것도 **거부한다.** 그 선언이 전제한 「리뷰」의 **주기가 이 회차에 10커밋으로 늘어났고**, 그 사이 새 게이트 클래스가 여럿 들어왔다 — **전제가 관측으로 흔들린 자리는 선언으로 닫지 않는다.**<br>**처방**: 클래스별 `--tests` 열거를 늘리는 대신 **「Kotlin 가드 테스트가 존재하는가」를 Kotlin 테스트가 검사**하게 한다(소스 선언 ↔ 적재 클래스 대조는 `70d4122` 가 이미 그 형태를 갖췄다 — 같은 기제를 게이트 클래스 목록에 적용). **열거를 손으로 늘리는 처방은 다음 게이트에서 또 벌어지므로 구조로 고친다**(2026-08-14 mypy 도달 수정과 같은 판단). 레인 **하네스**, 마감 **Phase 4 내** |
| **④** | **X7**(쓰기 키 부재 기동이 조용 — privacy-gate F-2 ↔ Claude 「fail-closed 라 문제 없음」) · **X8**(`key_version = -1` 이 **저장까지 성공** — privacy-gate F-4 실측 ↔ Claude 「도메인 타입이 넓다, 실무 도달 불가」) | **둘 다 privacy-gate 쪽을 채택한다. 두 판단이 서로 다른 것을 보고 있었다.**<br>**X7 → fail-fast 로 간다.** Claude 는 **데이터 안전성**(503 = fail-closed, 참)을, privacy-gate 는 **오설정 발견 시점**(첫 업로드까지 조용 → 사용자 화면의 503)을 봤다. `keys` 에 `writeKeyVersion` 이 없다는 것은 **기동 시점에 이미 아는 사실**이다. privacy-gate 처방(WARN 한 줄)보다 **한 걸음 더 간다 — 기동 실패**다: WARN 은 배포 파이프라인이 그것을 읽을 때만 잡히고, 이 저장소는 **읽히지 않는 선언이 도달 0 이 되는 사고를 이미 여러 번 겪었다.** 「기동은 막지 않는다」 규약은 **선택적 기능**에 대한 것이고, 쓰기 키는 문서 API 의 **필수 전제**다.<br>**X8 → `V4` 로 `CHECK (key_version > 0)` 를 넣는다.** Claude 가 본 `70000`(상향)은 저장에서 깨지지만 privacy-gate 가 실측한 `-1`(하향)은 **저장까지 성공**한다 — **같은 축의 다른 절반**이고 둘 다 참이다. 조립 시점 검증이 아니라 스키마로 가는 이유: `encryption_scheme` 에는 CHECK 가 있는데 `key_version` 에만 없는 **비대칭**이 결함의 형태이고, 비대칭은 같은 층에서 없애야 다음 사람이 읽는다. **마감이 「첫 INSERT 전」으로 앞당겨진다** |
| **⑤** | **S1** — 적용된 마이그레이션의 **체크섬 불변성**을 고정하는 코드 게이트 0(codex medium **예방형** ↔ Claude·privacy-gate **탐지형**(Flyway checksum mismatch, fail-closed, 실측 재현)) | **충돌이 아니라 층이 다르다 — 두 서술 다 참이다.** 그러므로 어느 쪽도 지우지 않고 **codex 처방(첫 배포 기준 체크섬 manifest 커밋 + 기존 `Vn` 변경을 CI 에서 거부)을 채택하되 마감을 `Phase 7`(첫 배포 전)으로 건다.** 지금 넣지 않는 이유는 **값이 없기 때문**이다(보존 DB 0 — 오늘 manifest 를 뜨면 그 뒤 정당한 스키마 수정마다 재작성해야 하고, 재작성이 반복되면 그 파일은 곧 무시된다). 미루는 이유는 **첫 배포 순간부터 되돌릴 수 없는 종류**라는 것이고, 그래서 마감을 「Phase 4 내」가 아니라 **배포 직전 게이트**에 건다 |
| **⑥** | **H3 = X24-3 3회차** — codex B-3 이 「텍스트를 담을 수 있는 **모든** `data class` 를 기본 민감으로, 제외는 `@NonSensitive` 명시 분류」를 다시 처방했다(게이트 23 `:250` → 게이트 24 X24-3 → 게이트 25 H3) | **기각을 유지한다 — 판정 근거는 회차 수가 아니라 결함의 구조다.** ⑴ 「안전」을 선언하는 애너테이션이 곧 **면제 조항 = 은폐형**이고, CLAUDE.md 규칙 4 는 은폐형을 **⑴이 참이어도 넓히지 않는다 — 탐지형으로 갈아탄다**로 못박는다. ⑵ **범위가 근거를 넘는다** — 새로 빨개지는 8~10종이 전부 고정 문구·설정값이라 가려서 얻는 것이 없고 진단만 잃는다.<br>**다만 「3회차 독립 재발」 자체를 사용자 판단 ⑲ 의 새 입력으로 등재한다.** codex 는 원장을 읽지 않으므로 이 기각을 **몰랐다** — **독립성의 대가이자 값**이다. 같은 처방이 서로 모르는 세 회차에서 나왔다는 사실은 판정을 뒤집지는 않지만 **사용자가 뒤집을지 정할 때의 입력**이다(§4).<br>**Claude R-10 은 같은 게이트의 다른 구멍**(`data class`·`value class` 밖의 **일반 class** — `EncryptedContent` 가 첫 사례)이고 이 기각의 대상이 **아니다** → 아래 ⑨ |
| **⑦** | **H11** — `858347d`(M-3b) 의 증명력. codex A-6 medium: 「max/min ≤ 1.5 를 **한 번** 볼 뿐 분포·신뢰구간을 보지 않는다 … 균일화 제거 시 관측한 2.399 는 **특정 실행 결과**이지 상설 음성 대조가 아니다」 | **원장 조건 9(「401 3갈래 비율 회귀 신설」, 마감 Phase 4 착수 전)를 **닫는다.** 그 조건이 요구한 것은 **상시 회귀의 존재**였고 `858347d` 가 그것을 세웠다 — 문턱 1.5·표본 101·워밍업 20·교차 순서는 **리더가 게이트 24 ③ 에서 지정한 방식 그대로**이고, privacy-gate 는 같은 1.5 를 F-1 판정의 자로 썼다.<br>**codex A-6 은 단서로 병기한다 — 닫힘의 취소가 아니라 그 장치의 한계 선언이다.** 「1.49배도 통과한다」·「단발 관측은 상설 재현이 아니다」는 **참**이고, 이 저장소는 **시간 축 게이트가 흔들려 꺼진 선례**를 갖고 있다. 그래서 codex 처방(격리 환경 다중 실행·절대 격차·분포)은 **X3 타이밍 조치와 한 배치로 묶어** documents 단위에 이월한다 — 같은 종류의 자를 두 번 만들지 않는다 |
| **⑧** | **Phase 3 종료 조건 표 행 4** — 게이트 24 가 「Phase 4 첫 게이트 통과 시 `예` 가능」으로 조건부로 둔 행 | **`예`로 올린다**(위 Phase 3 표 · 「Phase 3 종료 판정」 §3 행 4). 조건은 **「`6be9612` 리뷰 수령」 하나**였고 게이트 25 가 3관점으로 수령했으며 **차단 0**이다.<br>**단서 둘을 같은 행에 병기했다** — ⑴ **L1**(그 커밋이 심판문을 편집했다는 사실이 이번에 함께 확정됐다. 원장이 우려한 「자기 배치를 자기 근거로」가 **한 겹 더 안쪽에서** 성립해 있었다) ⑵ **H7**(`6be9612` 가 닫은 것은 **인스턴스 2개**이고 **라이브러리 상수라는 종류**는 열려 있으며 **그 잔여가 어디에도 선언되지 않았다**). **닫힌 것은 수령이지 지적이 아니다** — 이 문장이 행에 남아야 다음 사람이 「행 4 = 끝난 행」으로 읽지 않는다 |
| **⑨** | **R-10** — `toString` 도달 게이트가 **일반 class** 를 후보에서 조용히 제외한다(Claude 권고. 게이트 소유 레인 배정이 필요하다고 적힌 채 올라왔다) | **`kotlin-implementer` 에 배정한다.** 이 게이트의 본체가 Kotlin 테스트(`GeneratedToStringProbes`·`SensitiveToStringReachTest`)이고 후보 선정은 **그 코드 안의 판정**이라, 하네스 레인이 아니라 게이트를 소유한 구현 레인이 고친다. **⑥ 의 기각과 충돌하지 않는다** — ⑥ 이 거부한 것은 「모든 `String` 을 명시 분류시키는 **면제형 확대**」이고, R-10 은 **이미 민감으로 판정되는 형태가 클래스 종류 때문에 검사에서 빠지는 것**이라 **탐지형 보완**이다. 마감 **Phase 4 내** |

### §3 — 조치 배치 (**진행 중** · 2026-08-19 시점)

> **이 절은 「닫혔다」를 적지 않는다.** 배치가 끝나면 다음 게이트가 그 커밋들을 리뷰하고,
> 닫힘은 **그 회차의 판정**으로만 기록된다(게이트 23 → 24 에서 쓴 규율 그대로).

| 레인 | 항목 | 마감 |
|---|---|---|
| `kotlin-implementer` | **X1** 쓰기 전 거부(`CharsetEncoder` REPORT) — 판정 ② | **documents 단위 착수 전** |
| `kotlin-implementer` | **R-1/X4** AAD 2축 격리 증거(키 재료 공유 2세대 **또는** KAT 1건) | Phase 4 종료 전 |
| `kotlin-implementer` | **X10** `wireName` 변경 탐지기 | **첫 INSERT 착수 전** |
| `kotlin-implementer` | **타이밍/X3** 해제 조건 ⓐ(더미 키 균일화) ↔ ⓑ(근거 명시 + `key_version` 존재 여부만 차단) 선택 + M-3b 방식 상시 회귀 + 음성 대조 · **codex A-6 처방(다중 실행·절대 격차·분포)을 이 배치에 합침**(판정 ⑦) | documents 단위 |
| `kotlin-implementer` | **R-4/X11** `decrypt` catch 가 `ProviderException` 을 누출 | Phase 4 내 |
| `kotlin-implementer` | **F-3/X6** 키 지문(KCV) 또는 기동 시 세대별 왕복 자기점검 — privacy-gate 「가장 위험」 | **documents 단위 착수 전** |
| `kotlin-implementer` | **F-2/X7** 쓰기 키 부재 → **기동 실패**(판정 ④ — WARN 보다 한 걸음 더) | documents 단위 |
| `kotlin-implementer` | **V4 / F-4·X8** `CHECK (key_version > 0)` | **첫 INSERT 전** |
| `kotlin-implementer` | **H-1/H1** 소스 파서 `MODIFIERS` 에 `fun` 추가(제품 실례 `Prompts.kt:221`) + 미지원 형태 **fail-closed** | ⑴ 다음 중첩 커밋 ⑵ Phase 4 내 |
| `kotlin-implementer` | **U-1** `MIN_SOURCE_DECLARATIONS = 20` 인데 실측 선언 **44** — **24건까지 조용히 잃어도 하한이 안 울린다**(cross §7 미교차. 하한을 실측 기반으로 올릴지 **정확 일치**로 바꿀지 함께 판정) | Phase 4 내 |
| `kotlin-implementer` | **H11 잔여** — 위 타이밍 배치에 합침(판정 ⑦) | documents 단위 |
| `kotlin-implementer` | **R-10** 일반 class `toString` 축(판정 ⑨) | Phase 4 내 |
| **하네스** | **스캐너 403 을 「종류」로 전환** — 어휘 분석 기반 또는 응답 생성 호출 인자 검사(H6 오탐 ↔ H7 미탐이 **같은 뿌리**, 두 처방이 수렴) + **잔여 선언 복원** | Phase 4 문서 소유권 경로 진입 전 |
| **하네스** | **H4** 게이트 클래스 존재를 **Kotlin 테스트가 검사**(판정 ③ — 열거를 늘리지 않고 구조로) | Phase 4 내 |
| **하네스** | **SKILL L1** — 조치 레인의 `reviews/` 쓰기 금지 규칙(판정 ① · 스캐너에 얹지 않는다) | Phase 4 내 |

**계약 축**(`contract-keeper`)은 별 배치다 — **K5**(P-22 충돌, 문서 API 착수 전) · **K2**(77 명세의 저장
순서·멱등·정렬) · **K1**(`x-filename-charset` 소비자 배선, 내보내기 커밋) · **K4**(`x-changelog` 전칭
선언 범위) · **H5**(`ContractSpec` `$ref` 연쇄가 한 단계 뒤 조용히 열림 — codex 단독, Claude 미실행 자리).

### §4 — 사용자 판단 대기 갱신

> 통합 목록 정본은 위 「Phase 3 종료 판정」 §5(20건)다. 이 절은 **게이트 25 가 더한 것만** 적는다.

| # | 쟁점 | 갱신 내용 | 착수 차단 |
|---|---|---|---|
| **⑲ (갱신)** | X24-3 넓은 강제 미채택 — 제품 `data class` 의 모든 `String` 을 안전/민감으로 **명시 분류**시킬 것인가 | **새 입력: 「3회차 독립 재발」.** 같은 처방이 게이트 23·24·25 에서 **서로를 모르는 채** 세 번 올라왔다(codex 는 원장을 읽지 않는다). **리더 권고는 무변경**(은폐형이라 기각 — §2 ⑥)이고, 바뀐 것은 **사용자가 뒤집을 때 참고할 사실**뿐이다. 뒤집는다면 그 순간부터 이 게이트는 **면제 조항을 가진 장치**가 된다는 점을 함께 판단해야 한다 | 아니오 |
| **㉑ (신규)** | **X1 처방 충돌** — 비쌍 서로게이트를 **쓰기 전에 거부**할 것인가(codex), **치환을 수용하고 I-13 초안 원본 보존 축에 기록**할 것인가(privacy-gate) | **리더는 「거부」로 판정했다**(§2 ②) — 근거는 치환이 **인증 성공 + 본문 영구 손상**이라는 조용한 손실이라는 것. **그러나 privacy-gate 안을 지우지 않고 병기한다**: 「Kotlin `String` 을 UTF-8 로 저장하는 **어떤 구현도 같다** — 결함 아님」은 **사실 서술로서 참**이고, 거부를 고르면 **손상된 파일을 업로드한 사용자가 422 를 받는다**(오늘은 그 파일도 저장은 된다). **어느 쪽이 제품으로 옳은가는 사용자 축**이다 — 리더 판정은 그 답이 올 때까지의 **기본값**이다 | 아니오 (기본값이 서 있다) |

### §5 — 사실 기록 4건

| # | 사실 | 근거 | 왜 남기는가 |
|---|---|---|---|
| ① | **round-trip 치환 문자는 `?`(U+003F)이지 U+FFFD 가 아니다** — privacy-gate §1 표의 「U+FFFD 대체」는 **틀린 값**이다. 치환은 **인코딩 시점**에 `String.getBytes(UTF_8)`(CharsetEncoder REPLACE)가 넣고, U+FFFD 는 **디코딩** 시점 문자라 여기서 나오지 않는다. **원인 진단(「JVM 표준 동작이라 어떤 구현도 같다」)은 옳고 기록된 값만 틀렸다** | cross §4-② JDK 21.0.4 직접 측정(`in="x\uD800y"` → `78 3f 79` → `out="x?y"`, `equals=false`) | **감사 산출물의 값 오류가 판정의 입력이었다.** 판정 ② 는 값이 아니라 **비가역성**에 걸려 있어 결론은 바뀌지 않지만, 산출물을 인용할 다음 레인은 **틀린 값을 그대로 옮긴다**. 정정 대상은 `reviews/04_security_privacy-gate.md` §1 표 |
| ② | **AAD 109/109 와 MUT-A·B 17/17 은 모순이 아니다 — 두 측정이 서로 다른 것을 쟀다.** privacy-gate 가 잰 「다른 `scheme` 5건 거부」는 `AesGcmContentCipher.decrypt:128` **이른 관문**이 끊는다(AAD 에 닿기 전이다). 「다른 `key_version` 1건 거부」는 `keys[2]` 가 **다른 키**라 태그가 깨진다(AAD 에서 `keyVersion` 을 빼도 같은 결과다). **109/109 는 「오늘의 거부」를, 17/17 GREEN 은 「그 거부가 AAD 결속 때문이 아님」을 증명한다** | cross §4-③ 코드 대조(`:128`·`:129`·`:138`) | **초록 두 개가 서로를 보증하는 것처럼 읽히는 형태**다. 「전수 통과」와 「변이 전건 GREEN」이 한 문서에 나란히 있으면 다음 사람은 **결속이 검증됐다**고 읽는데, 실제로는 **아무도 그 축을 재지 않았다**. X4 가 유효한 이유이자, 이 하네스가 「대리 지표로 실물을 판정하지 않는다」로 부르는 것의 실례다 |
| ③ | **스캐너 403 의 오탐과 미탐이 같은 뿌리이고, 두 관점의 처방이 서로를 읽지 않은 채 수렴했다.** `scan_privacy_invariants.py:1524-1526` 은 **줄 전체가 주석일 때만** 건너뛰고, `:451` `_403_TOKEN` 은 **열거된 이름의 집합**이다 — **어휘 분석 없는 이름 열거는 열거 안에서 과잉 적중하고 열거 밖에서 무적중한다.** codex: 「코드 토큰이나 **실제 응답 생성 호출의 상태 인자**를 검사하라」 / Claude: 「넓힘은 인스턴스가 아니라 **종류**만큼」 | cross §5-ⓙ · §4-⑤ | **이 게이트에서 가장 강한 교차 근거**다 — 서로를 읽지 않은 두 관점이 **같은 구조적 처방**에 도달했다. 그리고 그 처방은 CLAUDE.md 규칙 4 의 「은폐형은 넓히지 않고 **탐지형으로 갈아탄다**」와 같은 형태다 — 규칙이 밖에서 독립으로 재발견된 셈이라 그 사실을 남긴다 |
| ④ | **원장 `:1419` 의 「연속 2실행 success」가 job 결론과 run 결론을 한 행에서 섞었다 — 게이트 24 R-4 의 재발이다.** run `32229496368`·`32230037832` 는 **`e2e` job 이 success**(3m53s / 4m21s, 12 passed)이고 인용 수치도 전건 참이지만, **두 run 의 전체 conclusion 은 `cancelled`** 다(`llm-lane` concurrency 취소). **표기 규약을 정한다 — 「job success / run cancelled(사유)」로 적는다** | Claude L-4(`gh run view` 실측) · 게이트 24 §4 R-4 | **같은 자리가 두 회차 연속으로 났다.** 한 번이면 그 자리만, 두 번이면 규약 — 이 문서는 CI 결과를 **승격 근거**로 쓰므로 job/run 을 섞으면 **근거의 단위가 흔들린다.** 값을 고치는 것이 아니라 **표기 형식을 고정**하는 것이 처방인 이유다(값은 전건 참이었다) |

## 2026-08-20 셋째 세션 — 리더 판정 (진행 중)

> 이 절은 **이 세션에 리더가 내린 판정**만 적는다. 실행 결과는 각 레인 산출물이 정본이다.

> **[2026-08-21 원장 2단 분리]** 이 세션의 종결 판정 **L-① ~ L-㉗ (26개 절)** 은 `00_progress-archive.md` 로 옮겼다.
> 옮긴 것은 **종결된 판정 기록**이고, 판정에 쓰이는 Phase 종료 조건 표는 이 파일에 그대로 있다.
> 표기 검사기(`tests/test_harness_scope_reach.py`)는 두 파일을 **합본으로** 읽으므로
> 옮김이 판정 도달을 줄이지 않는다(실측: 대상 표 6 · 행 62 불변).

### L-㉘ C6 착수 조건 닫힘 + 리더 판정 4건 + **새 결함 종류 하나**

**커밋** `65882bc`(X1 처방 + β 8건) · `e2038dd`(대조 프로브 + 파국적 백트래킹) · `fd00c38`(산출물).
**리더 독립 재실행 전건 초록**: Gradle `--no-build-cache --rerun-tasks` exit 0(89/89 실행) · 요구 모드
게이트 **268 passed**(16.64s) · 러너 **16종 전건 초록 · 1678 passed** · exit 3 은 알려진 미추적 3건뿐.

**변조를 전부 일회용 worktree 에서 했다** — 규칙 5 가 권한 방식이고, 본 저장소를 안 건드리면 복원
자체가 필요 없다. 이 세션에서 처음으로 그것이 지켜졌다.

**닫힌 것 (전부 「고치기 전 초록」 실측 → 「고친 뒤 그 자리 지목」)**

| 항목 | 고치기 전 | 고친 뒤 |
|---|---|---|
| **X1-1** 헤더·바이트 축 | 팔 비대칭 헤더 누출 변이에서 `DocumentListReachTest` **10건 전건 초록** | DL-9 ② 빨강 — 누출 헤더 이름을 지목 |
| **X1-2** P2 를 구조로 | 목록 전용 변이에서 `JdbcDocumentStoreTest` **24건 전건 초록** | `거절 경로의 문장 수` 빨강 — `expected 1 but was 2` |
| β-05 | 값 자리 추가 변이에서 3건 전건 초록 | 2건 빨강, `probe_slot` 을 이름으로 지목 |
| β-10 | 인터셉터 순서 교환에서 3건 전건 초록 | 2건 빨강(쿼리·경로 각각) |
| β-21 | 계약 방향 뒤집기에서 4건 전건 초록 | 「경계 방향이 계약과 반대다」 |
| β-22 | F3 위반 + 공유 판정 눈멀게 하기에서 바이트 축 초록 | 독립 oracle 빨강 |
| β-11 | 이름 변경 + 값 내리기 **한 커밋** → **196 passed exit 0**(`rev-list` 가 공격자 커밋만 냈다) | 「현재 5 < 이력 최댓값 9」 |
| β-03·β-24 | `@Test` 한 줄 주석 → 196 passed exit 0 | 클래스·개수 지목 + **핀 삭제 우회도 막힘** |
| β-04 | 단언 세 줄 삭제(`@Test` 유지) → 196 passed exit 0 | 「단언 토큰이 12 개다 — 하한 15 아래다」 |

**X1-1 을 지목된 셋이 아니라 다섯 자리에 적용했다**(`DC-17`·`WD-2·3` 추가) — `VARIABLE_HEADERS` 가
네 파일에 갈려 있던 것도 합쳤다. **β-21 은 계약 개정이 불필요**했다: 계약이 이미 `x-service-constraint`
의 **키 이름**으로 방향을 기계가독하게 적고 있었다.

**버린 것과 사유(값이 있다)**: `@Suppress("LargeClass")` — 은폐형이라 케이스 압축으로 갔다(단언 손실
0) · 「모든 `@Test` 본문이 단언에 도달」 축 — **오탐 8.8% 실측**(853건 중 전이 추적 후에도 75건 잔존).
**오탐이 큰 축은 곧 면제 목록을 부르고 면제 목록은 은폐형이다.**

**X1-3 시간 축은 붙이지 않았다** — 정본에 응답 시간이 없고, 40행 침묵이 실측됐고, 문장 수 축이
결정적이다. **X1-4 는 `workspaces` 를 감시 테이블에 넣기로** 결정(원천 둘을 각각 선언 — `EncryptedField`
파생을 느슨하게 하지 않는다).

**④ 판정 — 이미 닫혔다(중복 조치 없음).** 리더의 추론을 레인이 실행으로 검증했다: 리포트 삭제 → 표식
M1 → `--rerun-tasks` 빌드 → 요구 모드 1 passed. 표식 M2 → 플래그 없이 재실행(`UP-TO-DATE`) → 요구 모드
**1 failed**, 「리포트가 이번 실행보다 앞선다 … Gradle 이 그 테스트 태스크를 다시 돌리지 않았다」로 낡은
리포트 49건 열거. **남는 것은 로컬 비요구 모드의 경고뿐이고 침묵이 아니다.**

---

**리더 판정 4건 (재는 것은 레인, 판정은 리더 — X3 이 확인 한 번으로 갈렸던 그 분업이다)**

- **β-12 → 처방 ⓑ 채택, 마감을 C6 단위로 당긴다.** 자리가 3이 아니라 **5**이고 cross 가 놓친 **JSON
  본문 팔이 가장 넓다**(`""`·`" "` 둘 다 흡수). 그리고 **마감 사유가 사실이 아니었다** — `GET
  /conversions/{id}` 에 `workspace_id` 가 없어 「C6 이 세 번째 자리를 만든다」가 성립하지 않는다.
  근거가 거짓인 마감은 다시 정해야 하고, 가장 싼 처방(제품 1줄·클라이언트 영향 0)이 있으니 미룰 이유가
  없다. **ⓐ 기각**(유일한 강제자를 죽이고 R-6 결함 부활) · **ⓒ 기각**(강제자 0 — 규칙 4)
- **X4 → 보강(12줄) 채택.** 좁히기 6줄이 더 싸지만 저장소 문면이 이미 갈려 있다(`DocumentPorts.kt:173`
  이 `JdbcDocumentStoreTest` 를 지목) — 좁히면 그 문면이 **거짓으로 남고 그건 P-9 가 겨눈 종류**다
- **X5 → AST 교체 채택.** 결정적 근거는 「내일」이 아니라 **비용 실측**이다 — 35줄 프로토타입이 손
  분할을 **오차 없이 재현**하고 위장 면제를 잡았으며 **불가능 항목 0건**. 오늘 방향 있는 면제가 0건이라
  급하지 않지만 **교체가 유지보다 싸면 미룰 근거가 없다**(규칙 3 — 「오늘 0건」으로 닫지 않는다)
- **β-08 → 차단 확정.** 레인의 물음(「침묵을 허위 진술로 바꾸는 것」이 「침묵」과 같은 칸인가)에 답한다:
  **다르다.** 침묵은 탐지 0 이고 허위 진술은 **diff 에 흔적이 남아 리뷰라는 부분 탐지**를 얻는다.
  그래서 Cβ-10(40줄)은 **개선이지 해결이 아니고** B-21 을 인접 행으로 옮긴다는 지적도 맞다. 다만 레인의
  결론이 맞다 — **지금 상태로 백로그에 두는 처분은 어느 독해에서도 지지되지 않는다**

---

**새 결함 종류 하나 — 「가드가 예산을 먹어 다른 가드를 죽인다」**

레인 보고 ⑤. **하네스 게이트의 실행 시간을 판정 근거로 읽는 축이 없다.** 레인이 첫 실행 **304.86s** 를
결함으로 읽지 못했고, 병렬 레인이 **656.74s(83배 — 파국적 백트래킹)** 를 재서야 드러났다. **CI
`quality` 예산 15분을 검사 하나가 먹을 뻔했다.** 고친 뒤 17.4s.

이것은 이 세션이 스물여덟 회차 동안 다룬 「가드가 못 잡는다」와 **종류가 다르다** — 가드가 제 일을
하면서 **다른 가드의 실행 기회를 없앤다.** 악용 비용 **정규식 한 줄** × 자동 탐지 **없음** → 리더 X6
기준의 차단 칸이고, **C6 단위에 축 신설을 배정했다**(열거 금지 — 테스트 이름에 상한을 박는 것은 범위
선언형이다. 거짓 양성 대가를 재고 적을 것 — R-10 이 시간 축에서 겪은 문제다).

**리더 보고 나머지 4건**: ① `FLOOR_TEST_CLASSES` 편입 사유가 남아 있다(새 표는 **메서드 삭제**를 잡지만
**파일 삭제**는 바닥이 지킨다 — 우선순위 3클래스, **리더 판정 대기**) ② `JdbcDocumentStoreTest` 분할이
다음 단위에 필요하고 픽스처를 파일 최상위로 올리면 **두 번째 DB 없이** 된다 ③ 새 표 둘은 커밋 전 라쳇
이력이 없다 ④ **`backend-kotlin/.kotlin/` 이 `.gitignore` 밖이라 로컬 Gradle 실행 하나가 미추적을
4건으로 늘린다** — `.gitignore` 는 은폐형이라 넓히지 않고 **사용자 판정 대기**.

---

### L-㉙ 게이트 덩어리 **G-γ 완주** (C6) — 교집합 0 · 차단 6 닫힘 · P-4 의 이력

**커밋** `318bd36`(C6) · `b4c2fda`(차단 6 + DC-26·DC-27) · `6a9ca8b`(리더 판정·커버리지).
**어간** `04_documents-c6`. 최종 검증: Gradle `--no-build-cache --rerun-tasks` exit 0 · 요구 모드
게이트 **298 passed** · 전체 pytest **1654 passed**.

**이 회차의 수치가 격리 설계의 근거를 다시 냈다.**

| 상태 | 건수 |
|---|---|
| 완전 합의 | **1** — 새 상한 라쳇이 `MAX_TIMING_RATIO`(1.5)를 담지 않았다 |
| 부분 합의(층이 다름) | 3 |
| **codex 단독** | **2** — multipart 복합 오류(high) · AEAD 변조 detail(medium) |
| **Claude 단독** | **19** — 차단 ② 다섯 + 그 외 |
| 충돌 | 0 (처방 상충 1건은 전문 병기) |

**Claude 차단 6 × codex 3 의 교집합이 0 이다.** Claude 는 「게이트가 무력한가」를, codex 는 「와이어에
무엇이 나가는가」를 봤다. 결론은 「한쪽이 깊었다」가 아니라 **단독 리뷰 하나로는 두 표면 중 하나를
잃는다**다. G-β 와 방향이 반대였다(그때는 codex 가 게이트 스크립트, Claude 가 제품 불변식) — **축을
셋으로 줄인 것이 영향을 준 것으로 보이지만 그건 추정이고 재지 않았다.**

**각주 하나**: codex 는 K-1 을 이루는 **세 파일을 모두 읽었고 지적하지 않았다.** 격리는 「서로 다른
것을 본다」는 뜻이고 **「한쪽이 본 것은 반드시 잡는다」는 뜻이 아니다.**

---

**차단 6건 — 전부 닫혔다(`b4c2fda`). 여섯 중 다섯이 「1줄 또는 1낱말 × 탐지 0」이었다.**

- **K-1(마감 도과)** — `ConversionReadContractTest` 가 `@Import(PrivateResponseHeadersConfig)` 로
  전역 필터를 태워 X-D1 하한선을 재지 못했다. **리더 실측**: 컨트롤러 개별 부착 2줄 삭제 → 그 테스트
  **exit 0**. 1회차의 「추정」을 리더가 실행으로 확정으로 올렸고 3단계가 그것을 받았다.
  **전수 확인이 범위를 여덟 배로 넓혔다** — 하한선을 실제로 재는 것은 `DocumentListHeaderFloorTest`
  **하나뿐**이고 나머지 아홉(`/auth` 셋·`/workspaces` 셋·DL-1·CR-1)이 **재는 것 0** 이었다. 자리마다
  클래스를 두는 대신 **분모를 계약에 둔 인구조사**(`PrivateHeaderFloorCensusTest`)를 세웠다 — 열거를
  열거로 지키지 않았다. **G-β X2 재발**이고 명세가 마감을 「그 엔드포인트를 구현하는 커밋」으로
  못박았으므로 `318bd36` 이 그 커밋이다
- **G-1** — 시간 예산 축이 **사고의 절반을 원리적으로 못 봤다.** `e2038dd` 가 고친 원인은 둘
  (`findall` 205.9s + **캐시 부재로 전수 스캔 2회**)인데 축은 캐시를 비우고 한 번만 부르므로 후자는
  **데코레이터 한 줄 삭제에 초록**이었다. 656.74s 중 205.9s 만 설명됐다
- **G-2** — `TIMED_SCANNERS`·`RATCHET_PIN_TABLES` **자신이 무보호**(β-08 이 고친 결함의 재귀 형태)
- **S-1** — 대조 프로브가 실사용 여섯 자리의 **어댑터를 지나지 않았다.** `observe()` 를 뭉개면 두 팔이
  **대칭으로** 뭉개져 여섯이 상태 코드만 비교하고 프로브는 초록 — `e2038dd` 는 단일 실패점을 없앤 게
  아니라 **옮겼다**
- **S-5** — `EXPECTED_UNGUARDED` 에 상한 라쳇이 없어 새 owner-less SELECT + 핀 한 줄 = **2줄 전건 초록**
- **S-2** — `VARIABLE_HEADERS`(**은폐형**)의 증가를 재는 것이 0. 넓히지 않고 상한 + 이력 라쳇으로 닫았다

**조치 레인이 자기 처방의 결함을 스스로 잡았다** — N-42(조항 문단만 삭제)에서 첫 표식 `"복합 결함"` 이
같은 description 의 다른 문단에 남아 **초록**이었다. 표식을 `"복합 결함에도 적용된다"`(적중 1건)로 좁혀
재측정해 FAILED 를 확인하고 amend 로 넣었다. **그리고 스캐너가 그 레인을 잡았다** — 변조 팔 관측을
`println` 으로 찍었다가 **LOG-BODY BLOCK 1건**, 「스캐너가 옳다」고 적고 제거했다.

---

**P-4 의 이력이 이 회차에서 가장 값나가는 기록이다.** codex 가 multipart 복합 오류 우선순위를 **독립
재발견**했고 기제 서술까지 일치했다. 그런데 **판정이 이미 있었다** — `04_contract-keeper_p5-verdict.md
:550-568` 「검사 순서 조항은 복합 결함에도 적용된다 → 오늘 구현은 422 를 낸다 → **구현 결함**」이고
처방 **DC-26·DC-27** 도 명세됐는데 `grep DC-26|DC-27 backend-kotlin` → **0건**이었다.

**이연 장부의 실효가 두 방향으로 갈렸다: 재발견의 근거는 됐고, 닫힘의 강제는 아니었다.** 종합의 문장이
정본이다 — *"DC-26 이 서 있었다면 이 지적은 리뷰가 아니라 빨간 테스트로 왔어야 한다."* 이번 회차에
그 둘을 구현했다(`DocumentService.createFromFile` 이 `workspace_id` 를 **파싱하지 않은 원문**으로 받고
`store(...)` 가 본문 길이 판정 **뒤**에 파싱한다 — 인자 자리 파싱이 곧 「언어의 인자 평가 순서가 와이어를
정한다」였다. 기대값은 코드에 없고 `ContractCheckOrder.stages()` 가 계약 `description` 에서 유도한다).

---

**AEAD 변조 팔 — codex 의 사실 서술이 실측으로 확정됐다.** 실 소켓·실 PostgreSQL 관측:
**`500` · `{"detail":"저장된 문서를 읽을 수 없습니다"}`**. 계약 `:2123` 예시는 `"저장된 변환 결과를 읽을
수 없습니다"` — **두 문자열이 다르고**, 게다가 **계약 쪽 문구를 만드는 코드가 저장소에 없다**(「고아
예시」 방향이 사실로 보인다). **「이탈인가 낡은 예시인가」는 `contract-keeper` 판정으로 남겼고**, 테스트는
독해와 무관한 성질만 건다(거절·계약 선언 상태·평문 미노출·**두 변조 행의 detail 동일**). 리더 자평이
옳았다 — **C6 검증에서 이 축을 명시적으로 재지 않았다.**

**변조 방법도 갈아탔다** — 바이트를 뒤집는 UPDATE 는 `EnvelopeColumnWriteGuardTest` 를 실제로 빨갛게
만들었으므로(실측) **AAD 결속 위반**(행 식별자만 다른 암호문)으로 같은 성질을 만들었다. 새 SQL 문장이
없어 그 가드의 정확 열거 핀을 건드리지 않는다.

---

**리더 판정 5건**(`6a9ca8b`) — 커밋 메시지가 정본이고 여기 요지만 적는다: 인구조사 **바닥 편입** ·
**`FLOOR_TEST_CLASSES` 자신을 이름 튜플 이력 축에** (리더 핀이라 조치 레인이 넣지 못했다) · 라쳇 둘 ·
`DocumentListHeaderFloorTest` **유지**(「다른 장치가 덮는다」를 재지 않았고, 은퇴는 라쳇을 내리는 편집이라
막히는 것이 옳다) · 주석 예산 **상한 유지**(막히는 것 자체가 신호다).

**리뷰 커버리지 강제자가 리더의 미기재를 잡았다.** `test_review_coverage_reach` 가 기준선에서도 빨갰고
원장 기재로 닫혔다. **커버리지를 두 행으로 나눈 이유**: 미리뷰 커밋 사이에 다른 레인 15커밋이 끼어
연속 범위 하나로는 그쪽까지 「리뷰했다」고 **선언**하게 된다 — 재지 않은 것을 적지 않는다.

**리더 편집이 두 번 불완전했고 두 번 다 게이트가 즉시 짚었다** — ⑴ 단언 하한 표 누락(β-04 축이
**오늘 만들어져** 만든 회차 안에서 리더를 잡았다) ⑵ 값을 판정 장치에게 물어 채웠다(`grep` 금지 규율).
표식 없이 요구 모드로 돌려 신선도 축이 fail-closed 로 빨개진 것은 **절차 위반을 장치가 잡은 것**이다.

---

**범위 결함(리더 소유) — 결과는 무해했으나 강제자는 0.** 심판 범위를 이 레인 3건으로 지정했으나
codex 의 `--base 1fb5200` 이 다른 레인 15커밋(276파일)을 포함했다. 산문의 「다시 심판하지 마라」를
**기계적 경계가 강제하지 않았다.** 3단계 대조 결과 **다른 레인에 귀속되는 지적 0건** — 판정 오염은
없었고 소비된 것은 심판 예산이다. **「이번엔 갈림이 운이 좋았다」로만 기록한다.**

**리더 문면 정정 하나** — L-㉘ 에 시간 예산 축의 잔여를 「24배 미만은 못 잡는다」는 **배수**로 적었으나
1회차가 **형태**로 정정했다: 축 밖에 있는 것은 완만한 잠식이 아니라 **같은 사고의 나머지 절반**(캐시
제거형). 배수로 적으면 「조금 못 잡는다」로 읽히고 형태로 적으면 「절반을 원리적으로 못 본다」가 된다.

---

**판정 대기 (수신 레인 명시)**
| 항목 | 수신 | 이유 |
|---|---|---|
| 타이밍 층 ⓐ 처방 상충(codex 제거 / Claude 유지) | `951b1fd` 소유 단위 | 대상 코드가 심판 범위 밖이고, 증명 강도를 저작 레인이 정하면 자기 승인 |
| AEAD 문구 「이탈 대 낡은 예시」 | `contract-keeper` | 계약 소유자의 물음 |
| 주석 예산 상한(여유 624줄) | 사용자 | 다음 단위가 곧 막힌다 |
| 미추적 3건(`.gitignore` 은폐형) | 사용자 | 러너가 출하 근거를 못 내는 유일한 이유 |

**마감이 붙어야 사라지지 않는 것 셋**(3단계 지적): 음성 대조 미실행 5건(K-1 만 실측) · **미검토 축 둘**
(parity·Kotlin 관용성 — **양쪽 모두**. 리더가 축을 셋으로 자른 결과다. 축을 줄여 얻은 것과 잃은 것이
같은 회차에 드러났다 — 지적의 깊이는 늘고 덮는 표면은 줄었다) · 범위 결함 강제자 0.

---

### L-㉚ stop-time 게이트 — C6 응답 계약 위반 둘 (`6c970b6`)

**리더 독립 재실행 전건 초록**: Gradle exit 0 · 요구 모드 게이트 **298 passed** · 전체 pytest
**1654 passed** · 스캐너 exit 0.

**게이트가 잡은 것.** 「C6 조회 경로에 두 가지 응답 계약 위반이 남아 있습니다.」 리더가 계약과 구현을
대조해 두 자리를 특정했다 — 추측 셋(Jackson null 생략 · 경로 변수 400 · `date-time` 형식)이 **전부
틀렸고** 넷째로 오퍼레이션 `description` 의 **산문 규범 규칙**에서 찾았다.

**G-γ 가 이 둘을 놓친 자리가 분명하다.** 계약이 `required`·`enum`·상태 코드로 적은 것은 양쪽 리뷰가
다 봤는데 **산문으로 적은 조건부 규칙은 아무도 읽지 않았다.** 이 저장소가 반복해 겪은 「계약이 표현하지
못하는 자리」의 **반대 형태**다 — 표현은 됐는데 아무도 읽지 않은 자리. 리더가 축을 셋으로 자를 때 축②에
「조건부 응답 규칙이 강제되는가」를 넣지 않은 결과다.

**① 「`done` 전에는 비어 있다」 — 강제자 0 이었다.** 계약: *"`masked_items[].original`에 가려졌던 실제
개인정보가 실린다 … 완료(`done`) 전에는 `easy_text`·`edited_text`·`masked_items`가 비어 있고 상태와
`failure_code`만 나간다."* 구현 `ConversionResponse.of(view)` 는 **상태로 걸르지 않았다**(리더 실측:
`if`·`when` 0건). 같은 설명이 `original` 에 실제 개인정보가 실린다고 적었으므로 **노출 범위 규칙**이다.

강제자를 **세 자리로 나눴다**: 규칙 정의(`ConversionStatus.exposesResult` — 열거형 항목마다 값이라 상태를
더하는 사람이 판정을 건너뛸 수 없다) · 구현(`ConversionQueryService` 가 **복호화 앞에서** 걸른다) ·
되짚기(`ConversionResponse.of` 의 `require`, fail-closed).

**매퍼 단독을 기각한 사유가 정확하다** — 매퍼는 복호화 **뒤**라 ⑴ 버릴 평문이 생기고 ⑵ **열 수 없는
완료 전 행이 500 이 된다**(계약이 그 상태에 준 응답은 200). **즉 매퍼 단독 강제는 계약을 만족시키지
못한다.**

**그리고 레인이 리더의 지시 방향 하나를 실측으로 기각했다 — 옳다.** `ConversionView.init` 에 `require`
를 넣으니 `SensitiveToStringReachTest`(리플렉션으로 민감 data class 를 조립하는 **살아 있는 탐지기**)가
빨개졌다(`GeneratedToStringProbes.firstEnumConstant` 가 `PENDING` 을 골라 결과를 실은 표본을 만든다).
그 탐지기를 고치는 대신 `require` 를 리플렉션이 지나지 않는 companion factory 로 옮겼다 — **새 사변적
강제자 때문에 기존 탐지기를 약화시키지 않는다.**

**실현 가능성 실측(추정 아님)**: `conversions` 에 쓰는 제품 코드는 `insertPending`(암호문 세 열 NULL)과
`rewriteEnvelope`(status 를 건드리지 않는다) **둘뿐**이고 `SET status` 하는 제품 SQL **0건**, 워커는
파일 하나다. → **오늘 제품 경로로는 그 조합이 만들어지지 않는다.** Phase 5 워커가 오면 실현된다
(마스킹이 LLM 호출 앞이라 `failed` 행이 대응표를 든다).

**음성 대조**: 고치기 전 — `markDone` 으로 세 열을 채우고 `forceStatus` 로 `pending` 으로 되돌린 뒤 조회
→ `상태 pending 인데 저장된 초안이 실렸다`. 고친 뒤 — 계약 4 상태 전부에서 세 필드가 비고 응답 원문에
개인정보·초안·검수본 **0건**, `cipher.decryptions` 도 비어 있다(**열지도 않는다**). **기존 `CR-3` 은 결과
열이 NULL 인 행만 밟아 공허하게 통과하고 있었다** — `CR-3b` 가 그 구멍을 메운다.

**② 500 저장 문구 — 후보 ⒝(공용 문구를 계약 쪽으로) 채택.** `DecryptionFailedException.MESSAGE` 를
`"저장된 변환 결과를 읽을 수 없습니다"` 로 바꿨다.

**리더 브리프의 전제 둘이 실측으로 거짓이었고 그것이 판정 근거의 핵이다.**
⑴ 「계약 쪽 문구를 만드는 코드가 저장소에 없다」 → **셋이 있고 전부 변환 조회 경로**다
(`ConversionStatus.UNKNOWN_STATUS_MESSAGE`·`MaskedItemCodec.STORAGE_FAILURE_MESSAGE`·
`JdbcConversionRepository.UNREADABLE_RESULT_MESSAGE`). 같은 사건 부류에서 **그 예외 하나만 갈려 있었다.**
⑵ 「문서 조회 경로도 같은 문구를 쓴다」 → **오늘 HTTP 로 쓰지 않는다.** `DecryptionFailedException` 이
응답이 되는 표면은 변환 조회 하나이므로 ⒝ 의 위험(「문서 경로 응답이 바뀐다」)은 **도달 0** 이었다.
⑶ 계약 전문에 `읽을 수 없습니다` 는 한 줄뿐이고 문서용 storage 예시는 **없다** — ⒜ 의 걱정도 전제가 거짓.
**리더의 `grep` 이 좁았고 그 결과로 부정 명제를 단언했다 — 이 세션에서 세 번째다.**

음성 대조: 변조 팔에 「detail == 계약 `InternalError.examples.storage`」 단언을 추가했고 값을 코드에
박지 않고 `ContractSpec.responseExampleDetail` 로 **계약에서 읽는다** — 계약이 개정되면 이 단언이 먼저
빨개진다. ② 는 그 전까지 **강제자 0** 이었다.

**리뷰 커버리지 강제자가 이 세션에서 세 번째로 리더의 미기재를 잡았다**(`b4c2fda`·`6a9ca8b`·`6c970b6`).
기재로 닫혔다. 이 장치는 **리더만 고칠 수 있는 파일**을 요구하므로 조치 레인은 매번 여기서 멈춘다 —
그 분업이 의도된 것이다.

**계약 레인(`contract-keeper`)에 올릴 것 셋**
1. **② 의 판정이 뒤집힐 수 있다.** 계약의 규범 서술은 「저장소가 만든 고정 문자열」이고 문구는
   `examples` 에만 있다. **예시를 규범으로 읽어 코드를 맞췄다** — 「예시는 규범이 아니다」로 판정되면
   되돌려야 하고, 그때 새 단언이 먼저 빨개지는 것이 설계된 신호다
2. `InternalError` 에 storage 예시가 **하나뿐인데 13 오퍼레이션이 공유**한다 — 자원별 문구를 허용할지
   조항으로 정해야 이 자리가 다시 갈리지 않는다
3. `get.description` **후단** 「상태와 `failure_code`만 나간다」의 규범성 — 앞단 세 필드만 강제했고
   나머지 여섯(`reviewed_at`·`missing_placeholders`·`model`·`provider_name`·`input_tokens`·
   `output_tokens`)은 **여전히 강제자 0** 이다

**Phase 5 착수 조건으로 올리는 것** — ① 의 강제자는 **「지금 갈 수 없는 길」에 문을 달았다.** 실현
가능성이 0 이므로 Phase 5 워커가 `failed` 행에 대응표를 남길 때 **처음 일한다.** 그때 워커가
`exposesResult` 를 지나지 않는 **네 번째 조립 지점**을 만들면 서비스 게이트는 무력하고 매퍼의 `require`
만 남는데 **그건 500 이고 계약이 그 상태에 준 응답은 200** 이다 — **미래의 잘못된 조립은 「유출 없음」
까지만 보장되고 「계약 준수」는 보장되지 않는다.**

**사용자 판단이 필요해진 것 — 주석 예산이 소진됐다(여유 10자).** 리더는 직전 회차에 「상한을 올리지
않는다, 막히는 것 자체가 신호다」로 판정했고 그 판단을 유지한다. 그런데 이번 레인의 초판이 이미
`130634 > 130000` 으로 빨갰고 압축해 통과시켰다. **다음 회차는 `.kt` 한 줄에 막힌다.** 권고는 상한
유지이나, **압축이 「근거를 `_workspace/` 로 옮기는 것」이어야 하고 「지우는 것」이면 안 된다 — 그 구분에
강제자가 없다**는 것이 이 예산의 진짜 빈자리다.

**남는 것 (악용 비용 × 자동 탐지)**: 새 상태를 `exposesResult = true` 로 **잘못** 선언 — 한 줄 × **없음**
(열거형이 결정을 강제하지만 **어느 값이 옳은지**는 아무도 재지 않고, `CR-3b` 는 `!exposesResult` 만
훑으므로 잘못 `true` 로 준 상태는 **분모에서 빠진다**) · `PUT /conversions/{id}` 는 구현이 없어 재지
못했다(계약이 같은 스키마를 선언하므로 C7 에서 같은 규칙이 걸린다) · 다른 `StorageException` 문구들은
여전히 상수 대조가 없다.

---

### L-㉛ 같은 규칙의 남은 절반 — 결과 필드 셋 → **아홉** (`4ac13ec`)

**stop-time 게이트가 L-㉚ 이 계약 레인에 올린 항목 3을 그대로 차단 사유로 냈다.** 즉 **양쪽이 알고
있었고 양쪽이 미뤘다** — 레인이 여섯을 「범위 밖」으로 판정하고, 리더가 그것을 「여전히 강제자 0」으로
원장에 옮겼다. 게이트는 그 미룸을 위반으로 봤고 근거가 단순하다: 계약 문장이 **무엇이 나가는가**를
규정했으므로 여섯이 나가는 것은 그 문장에 어긋난다.

**기제는 「축 판정과 범위 판정을 섞은 것」이다.** 레인의 범위 사유는 *"그 여섯은 사용자 본문·개인정보를
담지 않는다"* 였고 그것은 **PII 축에서 참이며 계약 문면 축에서 무관**하다. L-㉚ 이 「리더가 축②에
조건부 응답 규칙을 넣지 않았다」를 진단하고도 같은 회차에 그 절반을 남긴 것이 이 혼합의 결과다.

**계약이 두 곳에서 독립적으로 규정한다** — 오퍼레이션 설명의 *"상태와 `failure_code`만 나간다"*(`:1517`)
와 스키마 자신의 *"완료 전에는 결과 필드가 **모두** 비어 있다"*(`:2432`). 「모두」다. 즉 후단이 규범이라는
독해는 추론이 아니라 **두 문장에서 나온다.** 나가는 넷(`id`·`document_id`·`status`·`failure_code`)의
근거도 계약이 준다 — `failure_code` 는 *"변환이 접수된 뒤의 실패만 여기 실린다"*(`:2480`) 이므로
완료 전에 나가는 것이 그 필드의 **존재 이유**다.

**아홉을 코드에 손으로 적지 않았다.** 두 테스트가 계약에서 계산한다 —
`schemaRequired("ConversionResponse") - {id, document_id, status, failure_code}`. 계약에 필드가 늘면
그 집합 대조가 **먼저** 빨개진다. 구조는 지난 회차 그대로 쓰고 대상만 넓혔다(규칙 =
`ConversionStatus.exposesResult`, 아홉의 단일 정의 = `ConversionView.carriesResult` 신설, 집행 =
`ConversionQueryService.beforeDone` 분리, 되짚기 = 매퍼 `require`).

**`required` 를 깨지 않았음을 실측했다.** 계약(`:2434~2438`)이 이 함정을 이미 적어 뒀다 — Kotlin 이
null 을 생략하면 React 가 `undefined` 를 받는다. `CR-3b` 가 완료 전 세 상태 전부에서 **키 열셋 정확
일치**를 재고 배열 둘은 `[]`, 나머지 일곱은 `null` 로 개별 대조한다. **키 생략 0건.** 생략은 위반을
위반으로 바꾸는 것이었다.

**실현 가능성 여전히 0(추정 아님).** 마이그레이션 5개 전수에 `TRIGGER` **0건**, 여섯 중 DEFAULT 가 있는
것은 `missing_placeholders`(`'[]'` — 빈 값이라 위반이 아니다) 하나, `SET reviewed_at`·`SET status` 하는
제품 SQL **0건**, 제품 writer 는 `insertPending`(다섯 열)·`rewriteEnvelope`(암호문 3 + 봉투 2) 둘, PUT
미구현. → 이 강제자도 **Phase 5 워커 / C7 PUT 이 올 때 처음 일한다.**

**음성 대조 — 「전」을 저장소 밖 일회용 worktree 에서 쟀다.** 새 테스트와 관측 헬퍼만 복사하고 **강제자는
넣지 않아** 「전」이 정확히 「셋만 닫힌 구현」이 되게 했다. 세 축(HTTP · 매퍼 · 단위)이 **같은 여섯을
지목**했고 `easy_text`·`edited_text`·`masked_items` 는 목록에 없다 — 지난 회차 강제자가 살아 있음을 같은
실행이 함께 증명한다. **첫 판은 위반 하나에서 멈춰 `reviewed_at` 만 보였다** — 전부 모으는 형태로 고쳐
여섯이 한 번에 드러나게 했다(값이 아니라 필드 이름만 싣는다).

**분모 결함: 레인이 자기 지난 보고를 정정했고 그 정정이 옳다.** L-㉚ 이 적은 *"`CR-3b` 는
`!exposesResult` 만 훑으므로 잘못 `true` 로 준 상태는 분모에서 빠진다"* 는 **HTTP 팔에는 거짓**이었다 —
그 팔의 분모는 계약 `enum` 이라 상수를 잘못 줘도 상태가 남는다. 그 서술의 대상은 **단위 팔**이었고,
분모를 상태 **이름** 축으로 바꿨다. 여기에 **상수 자체를 재는 단언**을 두 팔에 넣었다 — 단위 팔은
`filter { exposesResult } == [DONE]`, HTTP 팔은 그 `wireName` 을 계약 enum 과 대조. L-㉚ 의 「악용 비용
한 줄 × 탐지 없음」 칸이 이것으로 닫혔다.

**남는 것 (악용 비용 × 자동 탐지)**
- **`beforeDone` 이 넷을 *잘못 채우는지*는 못 잰다** — 아홉의 빈 상태만 보므로 `failureCode = null`
  하드코딩은 통과한다. 단위 팔에 non-null 단언 한 줄로 **절반만** 막았고 `id`·`document_id` 오배정은
  미검사. 악용 비용 한 줄 × 탐지 부분
- **`GET /documents` 목록의 `reviewed_at` — 리더가 계약을 읽어 판정했다: 빈자리가 아니다.** 레인이
  「계약을 읽지 않았다」로 올렸고, `DocumentListItem`(`contracts/easy-doc-v1.yaml:2486`)에는 완료 전
  조항이 **없을 뿐 아니라** 그 필드를 **적극적으로 요구**한다 — *"목록에서 「검수함/초안」을 표시하려면
  필요하다 — `status`만으로는 알 수 없다"*. 목록은 결과 본문(`easy_text`·`masked_items`·토큰)을 아예
  싣지 않으므로 노출 범위 규칙의 대상도 아니다. **계약 레인에 올리지 않는다.**
  다만 **비대칭 하나는 남는다**: 이제 같은 `reviewed_at` 이 상세 응답에서는 완료 전 `null` 로 강제되고
  목록에서는 그대로 나간다. 오늘은 실현 가능성 0 이라 관측되지 않고, C7 PUT 이 `done` 만 검수 대상으로
  두면 그 뒤에도 0 이다 — **C7 이 그 전제를 깨면** 두 표면이 같은 값을 달리 보고하므로 그때 계약이
  판정해야 한다(**탐지 없음**)
- **네 번째 조립 지점** 위험은 L-㉚ 그대로다 — 매퍼 `require` 만 남으면 500 이고 계약은 200 이다
- ② 의 500 문구 판정(예시 = 규범?)은 **여전히 미결**이고 뒤집히면 그 절반이 되돌려진다

**주석 예산 — 사용자 판단 항목을 갱신한다(여유 10자 → 53자).** 압축으로 자리를 만들었으나 이번 회차에
예산 초과로 **세 번** 되돌아갔고(834 → 108 → 4자 초과) **셋째 판부터 정보를 깎기 시작했다**(서사 →
요약 → 축약). 옮긴 곳은 `04_kotlin-implementer_c6-test-sql-constraints.md` 이고 「왜 코드에 남기지
않았나」를 함께 적어 되돌림을 막았다 — L-㉚ 이 지목한 「옮김과 지움을 구분하는 강제자 0」에 대한 **규율
차원의 대응이고 기계 차원의 대응이 아니다.** 분포가 **「한 곳도 뚱뚱하지 않은데 총합이 꽉 찬」** 상태
(`ContractSpec.kt` 5,073 = 파일 상한의 92%)라 다음 회차는 어느 파일을 줄여도 얻는 것이 적다. 선택지:
⑴ 상한 상향 ⑵ **테스트 파일을 예산에서 분리** ⑶ 계속 압축(이미 정보 손실 구간). 리더 권고는 ⑵ 다 —
예산의 목적이 「`.kt` 에 이력이 쌓이는 것」을 막는 것이고 그 위험은 제품 코드에서 크다.

---

### L-④ 게이트 28 범위 추가 (기존 목록에 더한다)

- **L-① 의 종류** — 「세션 종료 시 미추적으로 남는 새 산출물」을 드러내는 **탐지형** 장치
- **L-㉓ ⒞ 의 종류** — 「계약이 선언하지 않은 상태 코드가 **어느 엔드포인트에서든** 나가는 것」을
  드러내는 **탐지형** 장치. ⑴ 종류를 댈 수 있다 — 「프레임워크가 계약 밖 상태 코드를 만드는 경로」
  (경로 변수 변환 실패가 그 하나였다). 인스턴스는 `276e2a5` 가 닫았고 **종류는 열려 있다** —
  400 이 지금까지 발각되지 않은 것이 기존 계약 테스트의 도달 부족의 증거다
- **파이프가 종료 코드를 가리는 사고의 강제자** — 이 저장소에서 **세 번째** 발생이다(`e90cfe4` · 게이트 15 X3 리뷰어 자신 · 이번 `5038968` 레인). **분류: 강제·표현형**(규칙 4 ⑵ 거부권 없음). ⑴ 종류를 댈 수 있다 — **「종료 코드를 판정 근거로 쓰는 모든 명령」**. 규칙은 이미 있고(`run_gate.sh` 경유 또는 파이프 금지) **강제자가 0**이다. 넓히는 것이 맞으나 하네스 수정은 게이트 면제 대상이 아니라 여기로 올린다
- **감사 §7 부수 관찰** — 스캐너를 `--rule` 로 좁혀 돌리면 다른 규칙의 억제 표기가 `[BLOCK] MARKER
  알 수 없는 규칙 id` 로 뜬다(7건). 전수 실행에서는 정상 억제되고 BLOCK 0. **실제 위반이 아니라
  실행 방식의 산물**인데, 감사자가 단일 규칙으로 돌리는 일이 흔해 **BLOCK 출력을 무시하도록 학습시키는
  방향**이다. 수신자 = 스캐너 소유 레인

---

## 아직 돌리지 않은 검증 게이트 (계획 §6)

> `실행 경로` 열의 어휘 정본은 위 Phase 0 표의 포인터를 따른다.

| 게이트 | 실행 경로 | 상태 |
|---| --- |---|
| Build (Gradle, TypeScript) | `ci:kotlin` · `ci:frontend` | **Gradle 실행됨** (Phase 1) — `./gradlew clean build` BUILD SUCCESSFUL (컴파일 + ktlintCheck + detekt + test). TypeScript 는 기존 `frontend` 잡이 그대로 담당 |
| Unit (core, application, React) | `ci:kotlin` · `ci:frontend` | **부분 실행** (Phase 1) — core 19건(모듈 경계 7 · Secret 7 · parity 하네스 5) 통과. **도메인 로직은 아직 없다**(마스킹·스타일 규칙 등은 Phase 2). `application` 은 본 소스가 없어 테스트도 없다. React 는 기존 `frontend` 잡 |
| Contract (14 endpoints) | `안 돎` | 미실행 — 계약 파일은 **작성됨**(`contracts/easy-doc-v1.yaml`)이나 contract test 미구현. 실행은 Kotlin API가 생기는 Phase 3부터 (`00_contract-keeper_test-plan.md` §5) |
| DB (Testcontainers) | `ci:kotlin` | **실행됨** (Phase 1) — pgvector/pgvector:pg16 컨테이너로 8건. V1↔Alembic 지문 대조, V2 additive, baseline 가드 4갈래, 빈 DB·기존 스냅샷 기동. **repository·트랜잭션·SKIP LOCKED 는 아직 없다**(Phase 3·5) |
| Crypto (Python ↔ Kotlin) | `안 돎` | 미실행 — fixture **생성기**가 11개 도메인을 지원할 뿐, **`parity/fixtures/` 산출물은 저장소에 존재하지 않는다**(`parity/` 디렉터리 자체가 없음). Kotlin 측도 부재 |
| Document (docx/pdf/hwpx/txt) | `안 돎` | 미실행 |
| Worker (lease/retry/crash) | `안 돎` | 미실행 |
| Quality (골든셋) | `ci:llm-lane(조건:.github/llm-lane-paths.txt)` | 미실행 |
| Security (소유권·로그·캐시) | `ci:kotlin` | 미실행 |
| E2E (compose + browser) | `1회성:docs/migration/_workspace/01_kotlin-implementer_skeleton.md` | **compose 부분 실행** (Phase 1) — Kotlin api·worker·migrate 3서비스가 Python 스택과 동시 기동, `/health` 200 확인. **browser·업무 흐름은 미실행**(Phase 6) |
| Ops (cutover/rollback) | `안 돎` | 미실행 |

**돌리지 않은 게이트를 통과한 것처럼 보고하지 않는다.**

---

## 착수 전 정리된 선행 작업 (하네스 구축 중 확인·수정)

Phase 0 착수 전에 하네스 구축 과정에서 발견해 처리한 항목이다. 마이그레이션 Phase 자체는 아니지만 기준선에 영향을 주므로 남긴다.

| 항목 | 내용 | 상태 |
|---|---|---|
| 계약 사실 정정 | 계획 §2.2에 없는 **413**(10MB 초과) 실재. 오류 본문 `detail`이 문자열/객체배열 **union**. 401에 `WWW-Authenticate: Bearer`. 엔드포인트는 제품 13 + `/health` = 14 | 반영됨 (`api-contract-freeze`) |
| 502/503 구분 | 큐 **등록 실패** = 502(`QueueUnavailableError`), 큐/설정 **미배선** = 503(`ConfigurationError`, `app/api/deps.py`) | 반영됨 |
| `PUT /conversions/{id}` 캐시 헤더 누락 | GET과 같은 스키마라 `masked_items[].original`에 개인정보가 실리는데 `no-store`/`nosniff` 부재 | **완료** — 커밋 `0fafac7`. 회귀 테스트 `tests/api/test_documents.py::test_검수_저장_응답은_캐시하지_않는다` |
| `/auth` 3종 캐시 헤더 누락 | `POST /auth/signup`·`POST /auth/login`·`GET /auth/me`가 `PRIVATE_RESPONSE_HEADERS`를 import조차 하지 않았음. 로그인 응답 본문은 Bearer 토큰 자체, 나머지 둘은 이메일 | **완료** — 커밋 `0fafac7`(같은 커밋). 회귀 테스트 3건 `tests/api/test_auth.py::test_{가입,로그인,내_정보}_응답은_캐시하지_않는다` |
| 캐시 금지 헤더 대상 범위 | 위 두 건으로 대상이 **6개 → 10개**로 늘었다 (documents 4 · workspaces 3 · auth 3). 계약 스킬 §2.5가 정본이고 §1 표는 포인터 표시만 둔다 | **완료** — 코드 확인: `grep -rn "headers.update(PRIVATE_RESPONSE_HEADERS)\|\*\*PRIVATE_RESPONSE_HEADERS" app/api/ \| wc -l` = 10 |
| parity 게이트 공백 | crypto 도메인이 Fernet만 검증. JWT·Argon2 fixture 부재 상태로 Crypto 게이트가 닫혔음 | 수정됨 — 커밋 `e88db3e`. 생성기에 jwt 18건·argon2 14건 추가 (11 도메인). **fixture 산출물은 아직 없음** |
| parity 게이트 우회 | 도메인 디렉터리를 통째로 빼면 "전건 일치"로 통과 | **완료** — 커밋 `e88db3e`. `compare_parity.py`의 `EXPECTED_DOMAINS` 검사 + 도메인 누락 시 exit 1 |
| 리뷰 게이트 1회차 | Phase 0 착수 **전** 점검으로 codex·Claude 독립 리뷰 + 교차 종합을 실행 | **완료** — `reviews/00_pre-phase0_{codex-reviewer,migration-reviewer,cross}.md` 3건. 교차 결과 **합의 9건 · codex 단독 4건 · Claude 단독 23건 · 상충 2건**. 정본은 `_cross.md` |
| 계약 스킬 §1↔§2.5 불일치 | §2.5는 헤더 대상 10개인데 §1 표에는 8개만 표기(`GET /conversions/{id}/export`·`PATCH /workspaces/{id}` 누락). §1만 보고 계약을 쓰면 두 곳이 헤더 요구 없이 동결됨 (X-14) | **완료** — 코드 기준으로 §1을 §2.5에 맞추고, §2.5를 정본으로 선언 |
| 오류 응답 캐시 헤더 | 오류 경로(`app/api/errors.py`)에 헤더를 붙일지 미결 (X-15) | **완료 — 현행 유지 판정**(붙이지 않음). 근거: 오류 본문에 개인정보 없음을 실측 확인. `api-contract-freeze` §2.7 해결 3에 전제 파기 조건과 함께 기록 |
| `status` 필드 넓이 | 백엔드 Pydantic `str` vs React 4값 리터럴 union. OpenAPI 생성 타입으로 그냥 교체하면 타입 안전성 퇴보 | **완료** — `contracts/easy-doc-v1.yaml`의 `components/schemas/ConversionStatus`에 enum 4값으로 고정. **Phase 6 타입 생성은 FastAPI 산출물이 아니라 이 계약 파일에서 한다**(3자 대조 D-1) |

---

## 메타 층 동결 (사용자 결정, 2026-08-22)

**가드를 지키는 가드(메타 층)를 더 고치지 않는다.** 교체 계획은
`xx_harness_meta-layer-replacement-plan.md` 이고, 구현은 **제품 Phase 4 C6 완료 후 별건**이다.

**근거 — 사용자 지적 두 개.** ⑴ 이 정리 작업이 하루를 더 먹을 참이었다. ⑵ **"Kotlin 작업인데
pytest 문제로 계속 반복 실패한다면 Python 을 재검토해."** 실측이 그 지적을 뒷받침한다 —
Python 메타 가드 **6,036줄**(5파일, Kotlin 을 텍스트로 26곳에서 파싱)이 제품 Kotlin
15,375줄을 판정하고 있고, **이번 두 회차의 반복 실패가 전부 그 층에서 났다**(E501 → CI 도달 0,
pytest 수집 의미론, Markdown SHA 부기, 열거를 열거로 지키는 우회 연쇄 5회). **제품 결함 0건.**

그리고 각 관심사에 네이티브 답이 이미 있다 — 테스트 소실은 **pitest**(백로그 B-19, 규칙 7이
스스로 3번 지목), lint 사슬은 **Gradle `check`**(ktlint·detekt 이미 배선), 리뷰 강제는
**GitHub branch protection**. 마지막 것이 이 원장의 「리뷰 커버리지」·「리뷰 이연 장부」 두 표와
2단 춤을 한꺼번에 대체한다 — 「커밋이 리뷰를 받았는지 검사하는 Python 테스트」는
`CLAUDE.md` 「바퀴를 매번 새로 만들지 않는다」 위반이었다.

**동결 대상(고치지 않고 남긴다):** F-3(계획 §5 마지막 불릿의 두 키 중 어느 것이든 삭제 가능) ·
pytest 사슬의 표적 실행 우회(`conftest.py` 이관을 착수했다가 **되돌렸다**) · 반복 실패
(a) 커밋 전 측정 보고 · (c) 리더의 독립성 누출.

**동결하지 않는 것:** 제품 불변식 가드(마스킹 선행·소유권 은닉 404·AEAD·no-store·평문 미저장).
깨지면 사용자 데이터가 나가고, 그것은 Kotlin 테스트와 `migration-safety-gate` 가 진다.

**이 동결에 강제자는 없다** — 기계로 재는 장치가 0개이고 지키는 것은 리더의 규율이다.
아래 두 표는 R-2(branch protection 이관)로 소멸할 예정이므로 **그때까지만** 유효하다.

---

## 리뷰 커버리지 (codex high #2, 2026-08-22 신설)

**리뷰 회차가 실제로 덮은 커밋 구간을 여기 적는다.** 이 표가 커버리지 판정의 **입력**이고,
판정자는 `tests/test_review_coverage_reach.py` 다 — 기준 커밋 `0d632f9` 이후의 모든 커밋이
「이 표의 어느 범위에 든다 · 아래 이연 장부에 적혀 있다 · 바꾼 경로가 전부 `docs/` 다」 중
적어도 하나여야 하고, 어디에도 들지 않는 커밋이 있으면 그 검사가 실패한다.

| 회차 | 범위 | 산출물 |
|---|---|---|
| `xx_harness` | `5b226b4..cbf6e8d` | `reviews/xx_harness_{codex-reviewer,migration-reviewer,cross}.md` |
| `04_documents-c6` | `1fb5200..0075743` | `reviews/04_documents-c6_{codex-reviewer,migration-reviewer,cross}.md` |
| `04_documents-c6` | `1d42c5d..318bd36` | `reviews/04_documents-c6_{codex-reviewer,migration-reviewer,cross}.md` |

**이 표가 주장하는 것과 주장하지 않는 것.** 범위를 적는 것은 「그 구간을 리뷰했다」는
**선언**이며, **그 선언이 참인지는 이 장치가 재지 않는다.** 산출물 파일의 실재는 방증일 뿐
증명이 아니다 — 위 `xx_harness` 회차가 바로 그 반례다(산출물 3건이 남았는데 codex 원문은
0줄이었고, 그 사실은 `_cross.md` 머리에 경고로 박혀 있다). 기록 위치를 심판문(`reviews/**`)이
아니라 원장으로 정한 사유는 `tests/test_review_coverage_reach.py` 머리 주석이 정본이다
(여기에 값을 옮겨 적지 않는다 — 사본은 갈린다).

---

## 리뷰 이연 장부 (C-3, 2026-08-21 신설)

**4축에 닿지 않아 묶은 변경은 여기 한 줄로 적는다. 적지 않고 묶는 것은 면제와 같다.**
4축 정본과 판정 규약은 `.claude/skills/kotlin-migration/SKILL.md` 리뷰 게이트 절이다.

장부가 없으면 사후에 **「이연됨」과 「끝내 리뷰 안 됨」이 구분되지 않는다.** 그때 "면제 목록이
한 줄도 늘지 않았다"는 사실은 은폐가 없었다는 근거가 되지 못한다 — 은폐는 목록에서만
일어나지 않고 **장부 부재로도** 일어난다. 그리고 어느 축에 닿았는지 판정하는 주체가 리뷰 대상
본인(리더)이라는 문제는, 장부가 있으면 **사후 검증 가능**하고 없으면 **검증 불가능**하다.

| 커밋 | 무엇을 바꿨나 | 상태 | 왜 4축이 아닌가 | 리뷰할 회차 | 닫힘 |
|---|---|---|---|---|---|
| `ed3df31` | 4축 사본의 동기화 강제자 (M-1·M-2) — `CLAUDE.md` · `tests/test_harness_scope_reach.py` | **대기** | 필수 축③에 닿는다 — 이연이 아니라 미리뷰 | `xx_harness 2회차` | - |
| `7efc7c4` | codex 지적 medium 3건 — `.claude/skills/kotlin-migration/SKILL.md` · `tests/test_harness_scope_reach.py` · `tests/test_kotlin_class_snapshot_reach.py` | **대기** | 필수 축③에 닿는다 — 이연이 아니라 미리뷰 | `xx_harness 2회차` | - |
| `3ba7e04` | codex 지적 high #1 — 리뷰 축 4 → 7, 즉시 중단 기준 전부 매핑. `.claude/skills/{kotlin-migration,codex-review}/SKILL.md` · `CLAUDE.md` · `tests/test_harness_scope_reach.py` | **대기** | 필수 축③에 닿는다 — 이연이 아니라 미리뷰 | `xx_harness 2회차` | - |
| `e7faccc` | codex 지적 high #2 — 리뷰 커버리지 강제자 신설. `tests/test_review_coverage_reach.py` · 이 원장 | **대기** | 필수 축③에 닿는다 — 이연이 아니라 미리뷰 | `xx_harness-fixes` | - |
| `87457e6` | ruff E501 2건 수정 — 이 범위가 CI `quality` 잡 전체를 껐다(F-5). `tests/test_harness_scope_reach.py` | **대기** | 필수 축③에 닿는다 — 이연이 아니라 미리뷰 | `xx_harness-fixes 후속` | - |
| `73f9f4c` | F-2 차단 해소(장부 하한을 산문 → 표 행) + 커밋 게이트 사슬을 pytest 안으로(반복 실패 b) + `ci.yml` 신설 3파일 경로 명시(F-4 CI 부분) | **대기** | 필수 축③에 닿는다 — 이연이 아니라 미리뷰 | `xx_harness-fixes 후속` | - |
| `0fda906` | F-1 차단 해소 — 커버리지 표도 구조적 파싱 + 범위 양 끝 실재 + `reviews/<회차>_*.md` 최소 2건 요구. **F-2 만 닫는 것은 무의미했다**(가짜 장부 행 없이 커버리지 한 줄로 우회) | **대기** | 필수 축③에 닿는다 — 이연이 아니라 미리뷰 | **동결 (교체 계획 R-2 로 소멸 예정)** | - |
| `b4c2fda` | G-γ 차단 6건 조치 — K-1(하한선 인구조사 신설) · G-1 · G-2 · S-1 · S-2 · S-5 · 층 ⓑ · **DC-26·DC-27 구현**(P-4). `tests/test_kotlin_gate_reach.py` · `PrivateHeaderFloorCensusTest` · `ContractCheckOrder` · `DocumentService` 외 | **대기** | 필수 축③(게이트·탐지기 자신)과 축②(외부 HTTP 계약)에 닿는다 — 이연이 아니라 미리뷰 | `04_documents-c6 2회차` | - |
| `6a9ca8b` | G-γ 리더 판정 — 바닥 편입(`PrivateHeaderFloorCensusTest`) · `FLOOR_TEST_CLASSES` 를 이름 튜플 이력 축에 · 라쳇 둘 · 단언 하한 표. `tests/test_kotlin_gate_reach.py` · 이 원장 | **대기** | 필수 축③(게이트·탐지기 자신)에 닿는다 — 이연이 아니라 미리뷰. **이 커밋이 커버리지를 적은 커밋 자신이라 강제자가 자기를 지목했다**(설계대로. `docs/` 만 바꾸는 커밋은 면제이므로 사슬은 원장 커밋에서 끝난다) | `04_documents-c6 2회차` | - |
| `6c970b6` | stop-time 게이트가 잡은 C6 응답 계약 위반 둘 — ⑴ 「`done` 전에는 비어 있다」 규칙에 강제자 신설(`ConversionStatus.exposesResult` + 서비스 집행 + 매퍼 `require`) ⑵ 500 저장 문구를 계약 예시에 맞춤. `core/document`·`core/exceptions`·`application/document`·`api/document` + 테스트 | **대기** | 필수 축①(보안·개인정보 노출 범위)·②(외부 HTTP 계약)에 닿는다 — 이연이 아니라 미리뷰 | `04_documents-c6 2회차` | - |
| `4ac13ec` | 위 `6c970b6` 이 셋만 닫고 남긴 여섯을 stop-time 게이트가 다시 잡았다 — 완료 전 결과 필드를 **셋 → 아홉**으로 넓힘(`ConversionView.carriesResult` 신설이 아홉의 단일 정의, 대상 집합은 계약 `required` 에서 계산). 분모 결함도 고침(단위 팔 분모를 `exposesResult` → 상태 이름, 상수 자체를 계약 enum 과 대조). `core/document`·`application/document`·`api/document` + 테스트 | **대기** | 필수 축②(외부 HTTP 계약)에 닿는다 — 이연이 아니라 미리뷰. 축① 은 **아니다**: 여섯은 사용자 본문·개인정보를 담지 않는다(그 판단으로 지난 회차가 범위를 좁혔고, 계약 문면 축에서 틀렸다 — 축 판정과 범위 판정을 섞지 않는다) | `04_documents-c6 2회차` | - |

**`상태` 어휘는 둘이다.** `이연` = 필수 축에 닿지 않아 묶기로 판정한 것. `대기` = **필수 축에
닿는데 아직 리뷰를 못 받은 것** — 이연이 아니라 빚이다. 위 세 줄은 전부 `대기`다(축③ =
게이트·탐지기 자신). 거짓으로 「비필수」라고 적지 않는다. **`tests/test_review_coverage_reach.py`
는 이 둘을 구분하지 않는다** — 둘 다 「적혔다」로 세므로, 급한 것이 묻히지 않게 하는 것은 이
칸을 읽는 사람이다.

**부트스트랩 제약 — 자기 SHA 는 자기 커밋 안에 적을 수 없다** (2026-08-22 실측, stop-time
게이트가 잡았다). 커밋하기 전에는 SHA 를 모르므로, 비면제 변경을 담은 커밋은 **구조상 자기
자신을 이 표에 기록할 수 없고** 그 순간 `tests/test_review_coverage_reach.py` 가 빨개진다.
실제로 그 검사를 도입한 `e7faccc` 가 자기 자신 때문에 실패했다 — 커밋 **전에** 돌린 전체
스위트는 초록이었고, 커밋이 바로 그 조건을 깨뜨렸다.

풀이는 이 원장이 `docs/` 아래라는 사실에 있다 — **원장만 고치는 커밋은 「바꾼 경로가 전부
`docs/`」라 면제**다. 그래서 「작업 커밋(비면제) → 기록 커밋(`docs/` 전용, 면제)」 두 단계로
가면 자기참조가 풀리고 검사가 지속적으로 초록을 유지한다. 이 줄 자신이 그 두 번째 단계다.

> **이 풀이는 아직 검증되지 않았다.** `xx_harness-fixes` 회차에 두 리뷰 레인 모두에게 ⑴ 이
> 해법이 성립하는지 ⑵ 면제 규칙이 `docs/` **전부**를 덮는 것이 옳은지를 물었다 — 특히
> **`docs/plans/**` 는 계획 문서이고 그것을 고치는 것은 축④(Phase 종료·착수 판정)에 닿을 수
> 있는데 `docs/` 라서 면제된다.** 그것이 참이면 이 풀이가 새 구멍을 하나 만든 것이다.
> 판정이 나오기 전에는 위 문단을 **규약이 아니라 관측된 기제**로 읽어라.

**운용:** 회차를 열 때 이 표에서 이연분을 꺼내 리뷰 대상에 넣고, 넣은 줄의 `닫힘` 칸에 회차
번호(`{phase}_{scope}`)를 적는다. **강제자의 한계 — 지우지 마라:** 이 표 자체의 규약 위반은
`tests/test_harness_scope_reach.py` 가 이 파일을 읽는 범위 안이라 잡히지만, **「묶었는데 적지
않았다」는** 이 표만으로는 **잡히지 않는다**(적지 않은 것은 표에 나타나지 않는다). 그 공백을
2026-08-22부터 `tests/test_review_coverage_reach.py` 가 커밋 전수 대조로 메운다 — 남은 공백은
그 파일 머리 주석의 「한계」 세 줄이다.

---

## 역사 원장 — `00_progress-archive.md`

**2026-08-21 2단 분리.** 이 파일이 3,123줄까지 자라 위임마다 전량이 읽히고 있었다(그중 한 세션 일지 절 하나가 1,209줄 = 39%). 판정 장부와 세션 일지를 갈랐다.

| | 무엇이 있는가 |
|---|---|
| **이 파일 (현행)** | 재개발 전환 masthead · **Phase 종료 조건 표 전건** · 아직 돌리지 않은 검증 게이트 · 진행 중 판정 |
| **`00_progress-archive.md` (역사)** | 종결된 게이트 후속 회차 · 종결된 세션 일지 · 인수인계 |

**규약 세 줄.**

1. **Phase 종료 조건 표는 옮기지 않는다** — 그것이 판정 근거다. 아카이브에는 표가 아니라 서술이 간다.
2. **아카이브는 append-only** 다. 옮긴 뒤 고치지 않는다(죽은 포인터를 만들지 않기 위해 문면도 지우지 않는다).
3. **도달은 줄지 않는다** — `tests/test_harness_scope_reach.py` 가 두 파일을 합본으로 읽는다(`read_progress_markdown`). 분리 전 이 검사기는 한 파일만 읽었고, 그 상태로 표를 옮기면 근거 #5(`mypy .` 가 점 디렉터리를 건너뜀)와 같은 형태로 도달이 조용히 줄었다. 그래서 검사기 확장이 분리의 **선행 조치**였다.
