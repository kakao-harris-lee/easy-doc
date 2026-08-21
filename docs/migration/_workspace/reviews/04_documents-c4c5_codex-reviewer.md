# 게이트 덩어리 G-β · 1단계 · codex 독립 리뷰 — `04_documents-c4c5`

> 이 파일은 `codex-reviewer` 가 쓴 **1단계 산출물**이다. 3단계 교차 종합(`04_documents-c4c5_cross.md`)의 입력 하나이며,
> 나머지 입력은 같은 시각 독립으로 도는 `04_documents-c4c5_migration-reviewer.md` 다.
>
> **이 회차에 다른 리뷰어의 산출물을 열지 않았다.** `04_documents-c4c5_migration-reviewer.md`·`..._cross.md` 는 물론
> 직전 회차(`04_documents-c3_*`)의 `migration-reviewer`·`cross` 도 열지 않았다. 예외 하나를 정직하게 적는다 —
> 직전 회차의 **내 레인** 파일 `reviews/04_documents-c3_codex-reviewer.md` 의 **머리 40줄(호출 메타데이터 서식)만**
> 읽었다. 그 파일의 지적 본문·정리 구획은 열지 않았다.
>
> **이 에이전트는 판정하지 않는다.** codex 지적의 옳고 그름, 심각도 환산, 중복 병합, 오탐 여부는 전부 2단계 이후
> `migration-reviewer` 와 리더의 몫이다. 아래 §3 은 **무편집 원문**이고, §4 는 원문과 분리된 정리 구획이다.
>
> **심각도 라벨은 codex 원문 그대로 둔다**(`high` / `medium`). `codex-review` 스킬 §5 의 4단계
> (Critical①/Critical②/Major/Minor/제안)로의 **환산은 3단계 교차 종합에 넘긴다.** 이 문서 어디에도 환산값은 없다.

---

## 1. 호출 메타데이터

| 항목 | 값 |
|---|---|
| 실행 시각 | 2026-08-21 15:43 ~ 16:23 KST |
| 어간 | `04_documents-c4c5` (**리더가 1단계 호출에서 지정**. 이 에이전트가 짓지 않았다) |
| 산출물 경로 | `docs/migration/_workspace/reviews/04_documents-c4c5_codex-reviewer.md` |
| 회차 | 이 어간의 **1회차**. 직전 게이트는 `04_documents-c3` 어간을 썼고 그 파일은 덮지 않았다 |
| 리뷰 도구 | codex CLI (헬퍼 경유) |
| 헬퍼 경로 | `/Users/harris/.claude/plugins/cache/openai-codex/codex/1.0.6/scripts/codex-companion.mjs` |
| 헬퍼 버전 | `1.0.6` (plugins cache · 최신 버전 자동 선택) |
| 모드 | `adversarial-review` (2회 모두) |
| base | `81ba9fa` (리더 지정 범위 `81ba9fa..19062cc` 의 base) |
| scope | `auto(미지정)` — `--base` 가 주어져 무시됨 |
| 호출 횟수 | **2회.** 1회차 뒤 리더 지정 축 5개 중 축 4(리더 판정 오류)·축 5(장치 분류)의 커버리지가 얕아 그 두 축만 겨눈 2회차를 돌렸다(§1.2) |
| 종료 코드 | 호출 1 = **0** · 호출 2 = **0** (둘 다 리뷰 근거로 유효) |
| 출력 크기 | 호출 1 = 8,944바이트 · 호출 2 = 10,954바이트 |
| verdict | 호출 1 = `needs-attention` (본문 「NO-SHIP」) · 호출 2 = `needs-attention` (본문 「no-ship」) |
| codex 도구 호출 수 | 호출 1 = **114**회 · 호출 2 = **42**회 (헬퍼 stderr `Running command` 집계) |
| thread id | 호출 1 = `01a0230f-2e82-7c32-b952-e4a350fc9b50` · 호출 2 = `01a02325-b809-74e3-ad0d-5f7ca2b4601c`. 헬퍼 1.0.6 은 job id 를 stderr 에 찍지 않는다 — 사후 회수는 `node <헬퍼> status --all` |
| 재시도 | **0회** (실패 없음) |
| 잘림 | **없음** — 두 출력 모두 `Next steps:` 블록까지 정상 종결 |

### 1.1 스크립트가 stderr 에 찍은 대상 판정 두 줄 (2회 동일)

```
codex-review: 리뷰 대상 = branch diff vs 81ba9fa
codex-review: 대상 판정 = non-empty (merge-base=81ba9face155, 변경 파일 58개 (branch 모드는 커밋된 변경만 센다))
```

`--dry-run` 사전 확인도 같은 두 줄을 냈다(종료 코드 6). 즉 이 회차는 **리뷰 대상 0건이 아니었다.**

### 1.2 2회 호출로 나눈 사유

리더가 지정한 리뷰 축은 5개다(거짓 초록 / 보안 불변식 / 계약 준수 / 리더 판정 오류 4건 / 선언 범위 대 도달).
`codex-review` 스킬 §3.5 는 focus 를 「한 번에 3~5개 축까지」로 제한하고 그 이상이면 전부 얕게 본다고 적는다.
1회차에 5축을 한 번에 넣었더니 축 1·2·3 은 파일·줄 단위로 답이 왔으나 **축 4는 요약 한 문장(ruff 재측정)에
그쳤고 축 5(분류)는 지적 본문에 나타나지 않았다.** 그래서 2회차를 축 4·5 전용으로 다시 구성했다.
**1회차 결과를 2회차 프롬프트에 넣지 않았다** — 넣으면 1회차 결론을 확인하는 리뷰가 되어 독립성이 회수된다.

### 1.3 리뷰 도중 HEAD 가 움직였다 (사실 기록, 판정하지 않음)

리더가 지정한 심판 대상은 `81ba9fa..19062cc` 다. `--base 81ba9fa` 는 `merge-base(HEAD, 81ba9fa)..HEAD` 를 본다.

| 회차 | 실행 구간 (KST) | 그때의 HEAD | 실제 리뷰 범위 |
|---|---|---|---|
| 호출 1 | 15:43:03 ~ 16:06:17 | `19062cc` | `81ba9fa..19062cc` — **리더 지정과 일치** |
| 호출 2 | 16:07:47 ~ 16:23:14 | `94440d8` | `81ba9fa..94440d8` — **지정보다 커밋 1개 넓다** |

`94440d8`(`fix(harness): ruff format 을 통과시킨다 — CI quality 잡이 죽어 게이트 체인이 skip 됐다`)은
**2026-08-21 16:11:57 에, 즉 호출 2 가 도는 중에** 브랜치에 올라왔다. `tests/test_kotlin_gate_reach.py`
한 파일 +9/−3 이고, 그 파일은 호출 2 의 리뷰 대상 안이다. 호출 2 원문은 첫 줄에서 스스로
「HEAD 94440d8 기준」이라고 밝히고, 지적 [Q-A⒟] 가 그 커밋의 존재를 근거의 일부로 인용한다.
호출 1 의 stderr·출력에는 `94440d8` 이 나타나지 않는다(실측 0건).

### 1.4 독립성에 관한 사실 하나 (판정하지 않음)

호출 1 에서 codex 가 `/Users/harris/.codex/memories/MEMORY.md` 를 **2회 읽었다**(헬퍼 stderr 실측:
`rg -n -i "easy-doc|parity|stop-gate|false green|거짓 초록|R-10|DocumentDeleteReach|..."` 에 이어
`sed -n '408,480p' /Users/harris/.codex/memories/MEMORY.md`). 즉 이 리뷰어는 **저장소 맥락을 전혀 갖지
않은 백지 관점이 아니라, 이 저장소에 대한 자기 세션 간 메모리를 갖고 있다.** 호출 2 에서는 그 파일 접근이
0건이었다. 이 사실이 교차 대조에서 어떻게 쓰일지는 `migration-reviewer` 와 리더의 판단이다.

### 1.5 `{scope}` 정본 표와의 관계 (사실 기록)

`.claude/skills/kotlin-migration/SKILL.md` 의 `{scope}` 정본 표는 Phase 4 를
`upload · extract · crypto · documents · export` 로 열거하고 「표에 없는 값을 쓰지 않는다」고 적는다.
리더가 지정한 `documents-c4c5` 와 직전 회차의 `documents-c3` 는 **그 표에 없다.**
어간은 **리더 지정값을 그대로 썼다**(내 역할 정의가 그렇게 지시한다). 이 불일치를 여기 사실로만 남긴다.

### 1.6 민감 데이터

프롬프트에 사용자 문서 본문·실제 암호문·키·개인정보를 **싣지 않았다.** 프롬프트에 들어간 것은
계약 조항·파일 경로·판정 기록 요약뿐이다. codex 는 저장소를 직접 읽으므로 그 읽기 범위는 이 통제 밖이다.

---

## 2. 전달한 프롬프트 전문

### 2.1 호출 1 — 실행 명령

```bash
.claude/skills/codex-review/scripts/codex-review.sh adversarial --base 81ba9fa "$(cat focus_c4c5.txt)"
```

focus text 전문:

````text
[배경] Python/FastAPI 런타임을 Kotlin/Spring Boot 로 교체하는 전환의 Phase 4 다. 제품 동작과 개인정보 정책은 보존해야 하지만 Python 출력은 정답이 아니다(폐기 대상) — 기준은 `contracts/easy-doc-v1.yaml` 과 정책 불변식이다. 이 배치는 ⑴ 제품 코드 `GET /documents`(C4) · `DELETE /documents/{document_id}`(C5) ⑵ 그 판정을 지탱하는 하네스 강제자의 신설·개편(R-4~R-10)이다. 계약 파일은 이 배치에서 한 줄도 수정되지 않았다.

[지켜야 하는 조건 — 채점 기준]
C1 오류 본문은 `{"detail": ...}` 이고 `detail` 은 문자열 또는 객체 배열(항목 키 정확히 3개)이다. Spring 기본 ProblemDetail 노출 금지.
C2 JSON 필드는 snake_case.
C3 소유권 은닉 — 타인 자원은 403 이 아니라 404 이고, 「없는 것」과 「남의 것」이 상태 코드·본문 바이트·헤더 이름 집합·응답 시간 어느 축으로도 구별되지 않아야 한다.
C4 계약이 선언하지 않은 상태 코드가 나가면 안 된다. 계약 전체에 `'400'` 선언은 0건이다.
C5 사적 응답 헤더 2종(`Cache-Control: no-store` · `X-Content-Type-Options: nosniff`)이 대상 응답에서 빠지지 않는다.
C6 `DELETE /documents/{document_id}` 는 204 · 본문 0바이트이고, 문서와 그 변환 결과를 **표시가 아니라 실제로 파기**한다. 삭제 성공 직후 같은 식별자 재요청은 404(204 아님).
C7 로그·예외 메시지·메트릭에 문서 본문·개인정보가 0건. 마스킹 파이프라인을 통과하기 전의 원문이 LLM provider·로그로 나가는 경로가 없다. 로깅은 문서 id·길이·상태까지만.
C8 요청 필드 다섯(email · password · name · text · edited_text)에는 Bean Validation 제약을 달지 않는다 — 계약이 정한 것은 나가는 **바이트**와 **측정 축**(정규화 후 길이)이고, 스키마 층 제약은 그 축을 원시 길이로 바꿔 계약을 깬다. 이 배치가 `spring-boot-starter-validation` 을 새로 들였다.
C9 성공 응답은 요청이 지정한 값을 반영한다 — 반영할 것이 없으면 성공하지 못한다. 빈 값·공백뿐인 쿼리 값이나 경로 변수가 기본값·미지정으로 조용히 흡수되면 위반이다(범위의 조용한 확대).
C10 이 저장소의 「선언한 범위와 실제 도달을 대조한다」 규칙: 장치를 넷으로 분류한다 — 탐지형 / 은폐형(무시 패턴·억제·면제 조항) / 강제·표현형 / 범위 선언형. 은폐형은 넓히지 말고 탐지형으로 갈아탄다. 범위 선언형은 **빈 선언에서 실패**해야 한다. 대리 측정 금지(종료 코드 0 을 「검토했다」로, grep 개수를 실제 개수로, 테스트 통과를 「그 경로가 돌았다」로 바꿔 읽기).

[대상]
제품 코드 — `backend-kotlin/api/src/main/kotlin/kr/easydoc/api/document/{DocumentController.kt,DocumentDtos.kt,ListPageLimits.kt}` · `api/.../config/{TypedValueSlotInterceptor.kt,WebMvcConfig.kt}` · `api/.../auth/AuthenticatedEndpoints.kt` · `application/.../document/{DocumentService.kt,DocumentPorts.kt,DocumentMessages.kt}` · `infrastructure/.../document/JdbcDocumentRepository.kt` · `api/.../health/HealthController.kt` · `application/.../health/HealthDiagnosis.kt` · `infrastructure/.../health/HealthProbeConfiguration.kt` · `backend-kotlin/api/src/main/resources/application.yml` · `backend-kotlin/gradle/libs.versions.toml`
하네스 강제자 — `backend-kotlin/api/src/test/kotlin/kr/easydoc/api/{RequestFieldConstraintLayerTest,RequestFieldRejectionLayerTest,RequestFieldRejectionReachTest,ValueSlotInvariantReachTest,NamedReferenceGuardTest,DocumentDeleteReachTest,DocumentListReachTest,DocumentListContractTest,DocumentListHeaderFloorTest,DocumentContractNodeTest,HealthContractTest}.kt` · `api/src/test/.../support/{ConstraintMetadata,ContractSpec,RequestFieldProbes,DocumentSliceFakes,GeneratedToStringProbes}.kt` · `infrastructure/src/test/.../db/LiveSql.kt`(구 `SqlComments.kt`) · `tests/test_kotlin_gate_reach.py` · `.github/workflows/ci.yml`
계약 — `contracts/easy-doc-v1.yaml` (이 배치에서 무수정)
Python 참고 구현(정답이 아니다) — `app/api/documents.py` · `app/api/deps.py` · `app/repositories/` · `app/privacy/masking.py`

[질문]
Q1 거짓 초록. 위 하네스 강제자들이 **초록일 때 정확히 무엇을 증명하는가**, 그리고 무엇을 증명하지 못하는가. 이 배치에서 같은 종류가 여섯 번 잡혔고 여섯 번 다 처방의 논리는 맞았고 **도달이 선언보다 좁았다** — 같은 종류가 더 남아 있다고 전제하고 찾아라. 특히 ⑴ 관측이 살아 있음은 증명하나 자극이 실제로 처리됐음은 증명하지 못하는 축 ⑵ 대조 기준이 검사 대상 자신에게서 나오는 축(생성기가 만든 값을 그 생성기의 선언과 대조, 구현을 복사한 기대값) ⑶ 개수 하한·Gradle 리포트 대조·git 이력 라쳇이 한 줄 편집으로 우회되는 자리 ⑷ 선언이 비었을 때·분모가 0일 때 통과하는 축 ⑸ 테스트 태스크가 UP-TO-DATE 로 건너뛰어도 초록으로 읽히는 자리.
Q2 보안 불변식. C3·C6·C7 이 실제로 증명됐는가. 「없는 것」과 「남의 것」의 바이트·헤더 이름 집합·응답 시간 세 축이 정말 그것을 증명하는가(측정 하한, 거짓 양성 여유, 소유 조건이 SQL 을 떠난 변이). 「즉시 파기」가 표시(soft delete)가 아니라 파기인지, 파기 전/후 대조가 「애초에 0건」과 구별되는지, 변환 결과·큐 항목·봉투(암호화 키 자료)가 함께 파기되는지. `TypedValueSlotInterceptor` 를 인증 뒤에 등재한 것이 우회 경로를 만들지 않는지 — 인터셉터가 닿지 않는 요청 경로, 인터셉터보다 앞서 도는 필터·밸브, 예외 매퍼가 다른 상태 코드로 흘리는 경로를 데이터 흐름으로 따라가라.
Q3 계약 준수. 코드가 계약과 갈리는 자리, 계약이 표현하지 못하는 자리, 그리고 **계약이 선언하지 않은 상태 코드가 나가는 자리**(C4). 이 배치가 `PATCH`/`DELETE /workspaces/{공백뿐인 경로 변수}` 의 미선언 400 하나를 찾아 422 로 해소했다 — 같은 종류(프레임워크가 계약 밖 상태 코드를 만드는 경로)가 다른 엔드포인트·다른 파라미터 타입·다른 컨텐츠 협상 실패에 더 있는지 실제 코드 경로로 따라가라. `GET /documents` 의 페이지 파라미터 기본값·경계·다음 쪽 유무 필드가 계약 노드와 어긋나는 자리도 함께 본다.
Q4 리더가 스스로 신고한 판정 오류 넷을 **독립적으로 재평가**해라(리더를 봐주지 마라). ⑴ 정직하게 신고된 잔여(`@Disabled` 로 강제자를 끌 수 있다)를 원장 잔여로 처분했다가, 기준을 「정직성」에서 「악용 비용」으로 바꿔 다시 열었다 ⑵ 바닥 핀의 알갱이가 **클래스 이름**인데 보호 대상은 그 안의 **메서드 몇 개**였고, 그 상태로 「이 불변식의 유일한 강제자」라고 선언했다 ⑶ 테스트 개수를 `grep` 으로 세어(대리 측정) 틀린 값을 핀 상수에 넣었다 ⑷ 자기 커밋 뒤 ruff 를 재측정하지 않고 「전건 초록」이라고 보고했다(실제로는 E501 1건 빨강). 각각 판정이 옳았는가, **처분이 충분한가**, 같은 형태가 아직 남은 자리가 어디인가.
Q5 분류. 이 배치가 만들거나 고친 장치들의 분류(탐지형 / 은폐형 / 강제·표현형 / 범위 선언형)가 옳은가. 은폐형을 넓힌 자리(무시 패턴·면제 목록·억제 애너테이션 허용), 범위 선언형이 빈 선언에서 통과하는 자리, 그리고 「이 장치를 제거하면 정확히 무엇이 깨지는가」에 답할 수 없는 장치가 있는가.

지적마다 파일·줄·재현 방법을 붙여라. 확정하지 못한 추정은 「추정」임을 명시해라. 미결로 남는 것의 **악용 비용**(한 줄 편집인가, 큰 diff 인가)을 함께 적어라. 아무것도 찾지 못했으면 찾지 못했다고 적어라 — 없는 지적을 만들지 마라. 코드를 고치지 말고 리뷰만 해라.
````

### 2.2 호출 2 — 실행 명령

```bash
.claude/skills/codex-review/scripts/codex-review.sh adversarial --base 81ba9fa "$(cat focus_c4c5_2.txt)"
```

focus text 전문:

````text
[배경] 앞 호출과 같은 저장소·같은 diff(base `81ba9fa`)다. Python/FastAPI → Kotlin/Spring Boot 전환의 Phase 4 이고, 이 배치는 `GET /documents`(C4) · `DELETE /documents/{document_id}`(C5) 구현과 그것을 지탱하는 하네스 강제자 개편(R-4~R-10)이다. **이 호출은 앞 호출이 얕게 지난 두 축만 본다 — 코드 결함 일반 탐색이 아니라 「판정과 장치 분류」의 감사다.** 판정 기록은 `docs/migration/_workspace/00_progress.md` 의 L-⑳ ~ L-㉖ 절에 있고, 백로그는 `docs/migration/_workspace/04_kotlin-implementer_improvement-backlog.md`(B-12~B-23)다.

[지켜야 하는 조건 — 채점 기준]
D1 이 저장소의 「선언한 범위와 실제 도달을 대조한다」 규칙(정본: `.claude/skills/kotlin-migration/SKILL.md` 의 같은 이름 절). 요지 — 장치를 **넷으로 분류**한다: **탐지형**(위반을 드러낸다) / **은폐형**(무시 패턴·억제 애너테이션·면제 목록처럼 위반을 안 보이게 한다) / **강제·표현형**(규칙을 실행으로 강제하거나 사실을 표현한다) / **범위 선언형**(무엇이 대상인지 선언한다). 규칙: 은폐형은 근거가 재발해도 **넓히지 않고 탐지형으로 갈아탄다**. 범위 선언형은 **빈 선언에서 실패**해야 한다. 대리 측정 금지. 범위는 근거를 넘지 않는다. 판정 기준은 겪은 횟수가 아니라 **결함의 구조**다.
D2 잔여 처분 기준은 **「악용 비용」**이다 — 한 줄 편집으로 강제자를 무력화할 수 있으면 잔여로 미룰 수 없고, 큰 diff 가 필요하면 미룰 수 있다. 「레인이 정직하게 신고했다」는 처분 근거가 아니다.
D3 「이 장치를 제거하면 정확히 무엇이 깨지는가」에 답할 수 없는 장치는 장치가 아니다.
D4 리더(오케스트레이터)도 감사 대상이다. 리더가 스스로 신고한 오류라는 사실이 감형 사유가 아니다.

[대상]
판정 기록 — `docs/migration/_workspace/00_progress.md` (L-⑳ ~ L-㉖)
백로그 — `docs/migration/_workspace/04_kotlin-implementer_improvement-backlog.md` (B-12~B-23)
핀·게이트 — `tests/test_kotlin_gate_reach.py` (`FLOOR_TEST_CLASSES` · `MIN_TESTS_IN_FLOOR_CLASS` · `MIN_TEST_CLASSES` · `TEST_CLASS_COUNT` · `EXPECTED_SOURCE_DECLARATIONS` · `RATCHET_SCALAR_PINS` · `NON_RATCHET_PINS` · git 이력 라쳇 · Gradle JUnit XML 대조 · CI 배선 검사)
CI — `.github/workflows/ci.yml`
이 배치가 만든 Kotlin 강제자 — `backend-kotlin/api/src/test/kotlin/kr/easydoc/api/{RequestFieldConstraintLayerTest,RequestFieldRejectionLayerTest,RequestFieldRejectionReachTest,ValueSlotInvariantReachTest,NamedReferenceGuardTest,DocumentDeleteReachTest,DocumentListReachTest,DocumentListContractTest,DocumentListHeaderFloorTest}.kt` · `api/src/test/.../support/{ConstraintMetadata,ContractSpec,RequestFieldProbes}.kt` · `infrastructure/src/test/.../db/LiveSql.kt`
제품 — `backend-kotlin/api/src/main/kotlin/kr/easydoc/api/config/TypedValueSlotInterceptor.kt`

[질문]
Q-A 리더가 스스로 신고한 판정 오류 **넷**을 각각 독립적으로 재평가해라. 사실만 준다 — 판정은 네가 해라. 각 항목에 ⑴ 원래 판정이 옳았는가 ⑵ 처분(고친 방식)이 **충분한가** ⑶ **같은 형태가 지금 어디에 남아 있는가**를 파일·줄과 함께 답해라.
  ⒜ 레인이 「개수 하한은 `@Disabled` 를 구분하지 않는다」를 **스스로 신고**했는데 리더가 원장 잔여로 적고 넘어갔다. 뒤에 기준을 「정직성」에서 「악용 비용」으로 바꿔 다시 열고 `a9f71c2` 로 「소스에 선언된 것」 → 「실제로 돈 것」(Gradle JUnit XML)으로 갈았다. **지금 원장·백로그에 남아 있는 잔여 중 새 기준(한 줄 편집)에 걸리는데도 열려 있는 것을 열거해라.**
  ⒝ `FLOOR_TEST_CLASSES` 는 **클래스 이름**을 지키는데 보호 대상은 그 안의 **메서드 몇 개**였고, 리더는 그 상태로 `813c64f` 에서 「이 불변식의 유일한 강제자」라고 선언했다. `ea32728` 이 `ValueSlotInvariantReachTest` 추출 + `MIN_TESTS_IN_FLOOR_CLASS` 신설로 고쳤다. **인스턴스를 닫았는가 종류를 닫았는가.**
  ⒞ 리더가 `DocumentDeleteReachTest` 의 테스트 개수를 `grep` 으로 세어 15 를 핀에 넣었고 판정 장치에 물어 14 로 정정됐다. 같은 회차에 레인이 낡은 핀 값 넷(12→16 · 11→13 · 9→12 · 22→24)을 찾았다. **핀 값이 대리 측정으로 정해지는 자리가 지금 또 있는가. 낡거나 느슨한 핀을 잡는 장치가 있는가.**
  ⒟ 리더가 자기 커밋 뒤 ruff 를 재측정하지 않고 「전건 초록」이라고 보고했다(실제로는 `tests/test_kotlin_gate_reach.py` 583행 E501 — 한글 표시 폭 102 > 100). 이 세션에서 **두 번째**다. **「마지막 커밋 이후 재측정」을 강제하는 장치가 있는가. 로컬과 CI 의 린트·타입 검사 도달이 같은가**(`--isolated` 로 돌리면 E501 이 안 보인다는 사실 포함).
Q-B 분류 감사. 이 배치가 만들거나 고친 장치를 하나씩 **탐지형 / 은폐형 / 강제·표현형 / 범위 선언형**으로 분류하고, 다음을 지목해라 — ⑴ 리더·레인이 붙인 분류가 **틀린** 자리 ⑵ **은폐형을 넓힌** 자리(면제 목록·무시 패턴·억제 허용·오탐 회피용 예외) ⑶ **범위 선언형인데 빈 선언·분모 0 에서 통과**하는 자리 ⑷ 「제거하면 무엇이 깨지는가」에 답할 수 없는 장치(떼어도 아무 테스트가 빨개지지 않는 장치).
Q-C 이 배치는 하네스 강제자를 크게 늘렸다. **늘어난 장치들이 서로를 근거로 순환하는 자리**, 그리고 **선언(주석·KDoc·커밋 메시지·원장)이 주장하는 범위가 실제 도달보다 넓은 자리**를 찾아라. 특히 「세 축」·「유일한 강제자」·「모든 ~」·「전역」 같은 전칭이 실측을 넘어선 자리.

지적마다 파일·줄·재현 방법을 붙여라. 확정하지 못한 것은 「추정」임을 명시해라. **미결의 악용 비용**(한 줄 편집인가 큰 diff 인가)을 함께 적어라. 아무것도 찾지 못했으면 찾지 못했다고 적어라 — 없는 지적을 만들지 마라. 코드를 고치지 말고 리뷰만 해라.
````

---

## 3. codex 원문 출력 — **무편집**

> 아래 두 구획은 codex 가 낸 바이트 그대로다. 오탈자·표현·심각도 라벨·「추정」 표기·경로·줄번호를
> **하나도 손대지 않았다.** 사실과 다른 전제로 보이는 대목도 지우지 않았다 — 그 판정은 3단계의 몫이다.

### 3.1 호출 1 원문 (8,944바이트 · exit 0 · verdict `needs-attention`)

````text
# Codex Adversarial Review

Target: branch diff against 81ba9fa
Verdict: needs-attention

NO-SHIP. 계약 밖 400/505가 실제로 나가며, C3 측정은 필수 축을 충족하지 않는다. 여러 하네스는 한 줄 편집이나 이전 JUnit XML로 거짓 초록이 가능하다. 반면 현재 C6 구현은 실제 DELETE와 FK CASCADE를 사용하고, C7 평문·로그 유출이나 GET 페이지 경계 불일치는 찾지 못했다. 현재 ruff는 직접 재측정해 초록이지만, 읽기 전용 환경이라 Gradle은 재실행하지 않고 HEAD 직후 생성된 XML을 점검했다.

Findings:
- [high] Tomcat의 미선언 400·505가 그대로 외부로 나간다 (backend-kotlin/api/src/main/kotlin/kr/easydoc/api/error/ContractErrorReportValve.kt:67-69)
  `report()`는 본문만 계약 모양으로 바꾸고 Tomcat이 정한 상태는 보존한다. `ContainerRejectedRequest`는 요청 파손 5종을 400, 미지원 HTTP 버전을 505로 기대하며 현재 테스트도 그대로 통과하지만, 계약 `responses`에는 `'400'`·`'505'` 선언이 없다. 재현: `rg -n "'400':|'505':" contracts/easy-doc-v1.yaml` 후 `./gradlew :api:test --tests kr.easydoc.api.ContractErrorBodyReachTest --rerun-tasks`; 후자는 생성기 자체의 `expectedStatus`와 비교하므로 초록이다. 영향은 C4 위반이며 악용 비용은 코드 편집 0, 원시 malformed 요청 한 건이다.
  Recommendation: 컨테이너 오류 상태를 기계 판독 가능한 계약 허용 집합에 정식 선언하거나 선언된 상태로 변환하고, 테스트 기대 상태를 `RawHttp` 열거가 아니라 그 계약 노드에서 읽게 하라.
- [high] GET 목록의 소유권 은닉은 헤더·시간 축을 전혀 측정하지 않는다 (backend-kotlin/api/src/test/kotlin/kr/easydoc/api/DocumentListReachTest.kt:190-200)
  DL-9는 상태와 UTF-8로 디코딩된 `String` 본문만 비교한다. 헤더 이름 집합과 응답 시간은 측정하지 않으므로 C3의 네 축 중 둘이 비어 있고, 문자열 비교도 원시 바이트 동일성 증명이 아니다. DELETE 쪽도 `date` 이름을 면제하고(`DocumentDeleteReachTest.kt:491-535`) 1.5배 미만 시간차를 허용하며, 소유 조건을 SQL 밖으로 옮긴 변이가 통과한다고 스스로 기록한다. 추정: 현재 `SELECT ... WHERE id AND user_id`는 PK hit+heap 검사와 PK miss의 작업량이 달라질 수 있으나 원격 악용 가능성은 확정하지 못했다. 악용 비용은 하네스 편집 0이다.
  Recommendation: 두 엔드포인트 모두 원시 응답 바이트와 전체 헤더 이름을 비교하고, 충분한 표본과 신뢰구간·절대차 상한을 갖춘 교차 시간 측정을 추가하라. `(owner_id,id)` 접근 계획도 실제 EXPLAIN/변이로 고정하라.
- [high] 새 계약 강제자 일부는 클래스만 살아 있으면 핵심 메서드를 한 줄로 끌 수 있다 (tests/test_kotlin_gate_reach.py:446-475)
  메서드 하한 표는 28개 클래스만 보호하며 `DocumentListHeaderFloorTest`, `DocumentListContractTest`, `DocumentContractNodeTest`, `HealthContractTest`는 제외돼 있다. 예를 들어 `DocumentListHeaderFloorTest.kt:68`의 `@Test`를 `// @Test`로 바꾸면 개별 헤더 하한의 유일한 동작 단언이 사라져도 두 번째 전제 테스트가 실행되어 클래스 실행 검사는 초록이다. 기본 페이지값 검사(`DocumentListContractTest.kt:186`)도 같은 한 줄 우회가 가능하다. 악용 비용은 한 줄이다.
  Recommendation: 독립 계약 근거로 인용되는 모든 신규 강제자를 메서드 식별자 단위로 보호하거나, 각 불변식을 독립 클래스에 두고 바닥·실행 표에 등재하라.
- [high] 파라미터화 invocation이 실행 하한의 여유분으로 잘못 계산된다 (tests/test_kotlin_gate_reach.py:125-127)
  소스는 애너테이션 수를 세지만 XML은 JUnit `<testcase>` invocation 수를 센다. 현재 신선한 XML에서 `ContractErrorBodyReachTest`는 소스/하한 11 대 실행 16, `PrivateResponseHeadersReachTest`는 7 대 12다. `ContractErrorBodyReachTest.kt:304`의 `@Test`를 `// @Test`로 바꾸면 정규식은 주석도 애너테이션으로 세고 실제 실행은 15로만 줄어 11 하한을 넘으며 skipped도 0이다. 재현 비용은 한 줄이고 전체 도달 게이트가 초록으로 남는다.
  Recommendation: 주석·문자열을 배제하는 Kotlin 어휘 분석을 사용하고, XML invocation 개수가 아니라 고유 테스트 메서드/컨테이너 식별자를 소스 선언과 대조하라. 이 한 줄 주석 변이를 회귀 음성 대조로 추가하라.
- [high] CI 배선 검사가 명령 실행을 경로 문자열 포함으로 대리 측정한다 (tests/test_kotlin_gate_reach.py:1123-1130)
  `_steps_running_this_file()`은 `run` 문자열에 파일 경로가 들어 있는지만 본다. Kotlin 스텝을 `uv run pytest tests/test_kotlin_gate_reach.py || true`로 한 줄 변경하면 요구 모드·빌드 순서 검사는 모두 배선됐다고 판정하지만 실제 게이트 실패는 CI 성공으로 바뀐다. 이는 종료 코드 0을 실행·검토 증거로 읽는 C10 금지 형태이며 악용 비용은 한 줄이다.
  Recommendation: 허용된 argv를 정확히 고정하고 셸 제어 연산자·`continue-on-error`를 거부하라. 최종 신뢰 뿌리인 워크플로 파일에는 CODEOWNERS/필수 리뷰 같은 저장소 밖 보호도 적용하라.
- [medium] 이전 JUnit XML도 이번 실행 증거로 받아들인다 (.github/workflows/ci.yml:281-319)
  CI는 `./gradlew build --no-daemon` 뒤 XML을 읽지만 `--rerun-tasks`가 없고, XML 생성 시각이나 현재 실행 표식도 검증하지 않는다. 같은 작업공간에서 build와 게이트를 두 번 실행하면 두 번째 `test`가 UP-TO-DATE여도 이전 XML로 초록이 된다. GitHub의 새 러너에서 UP-TO-DATE 가능성은 낮다는 것은 추정이지만, 저장소는 Gradle 캐시도 활성화해 FROM-CACHE 결과 역시 현재 런타임 자극으로 오독할 수 있다. 악용 비용은 재사용 작업공간에서 편집 0이다.
  Recommendation: CI의 Test 태스크는 캐시·UP-TO-DATE를 끄거나 `--rerun-tasks`로 실행하고, 현재 run ID로 테스트 리스너가 기록한 표식을 XML 대조와 함께 요구하라.
- [medium] 요청 필드 경계 방향을 계약이 아니라 검사 대상 구현에서 추론한다 (backend-kotlin/api/src/test/kotlin/kr/easydoc/api/support/RequestFieldProbes.kt:203-223)
  `measure()`는 `limit-1`과 `limit+1` 중 구현이 거절한 쪽을 보고 `upperBound`를 정한 뒤 정규화 기대값을 만든다. 따라서 최대 길이 구현을 최소 길이로 뒤집어도 정확히 한 이웃을 거절하는 한 이 두 강제자는 그 방향을 정답으로 채택한다. 재현: 이메일 비교를 `length > MAX_EMAIL_LENGTH`에서 `<`로 바꾸고 두 `RequestFieldRejection*Test`만 실행하면 이 공용 판정은 초록이다. 현재 네 필드는 별도 endpoint 테스트가 방향을 잡아 전체 CI는 빨개지므로, 이 강제자 자체의 악용 비용은 한 줄이나 전체 우회 비용은 더 크다.
  Recommendation: 계약에 `bound: min|max` 같은 기계 판독 가능한 방향을 추가하고 그 값으로 양쪽 경계를 판정하라. 계약이 방향을 표현하지 못하면 구현에서 추론하지 말고 fail-closed하라.
- [medium] API 삭제 테스트는 고아 작업 행을 볼 수 없다 (backend-kotlin/api/src/test/kotlin/kr/easydoc/api/DocumentDeleteReachTest.kt:436-444)
  삭제 후 `jobRows()`는 `conversion_jobs`를 이미 삭제된 `conversions`에 INNER JOIN한다. 변환 행만 사라지고 작업 행이 고아로 남아도 조인 결과는 항상 0이라 DD-5가 초록이다. 재현은 작업 FK를 제거한 변이에서 문서를 삭제한 뒤 `conversion_jobs`를 직접 세면 된다. 별도 `JdbcDocumentStoreTest`가 현재 conversion ID로 직접 조회해 전체 suite에서는 이 결함을 잡으므로 현재 C6 제품 파기 실패는 확인되지 않았지만, 이 API 강제자가 주장하는 증명은 성립하지 않는다. 제품 변이 비용은 스키마 변경 규모다.
  Recommendation: 삭제 전에 conversion ID를 보존하고 삭제 후 `conversion_jobs WHERE conversion_id=:id`를 직접 조회하라. 완료된 변환의 암호문·봉투가 실제로 존재하는 사전 상태도 만든 뒤 파기를 검증하라.

Next steps:
- 미선언 컨테이너 상태 코드와 C3 측정 공백을 출하 차단 항목으로 먼저 닫는다.
- 메서드 식별자·현재 실행 표식·정확한 CI 명령으로 하네스 신뢰 뿌리를 교체한다.
- 요청 필드의 경계 방향을 계약에 명시하고 구현 독립 기대값으로 재검증한다.
- 수정 후 `./gradlew build --no-daemon --rerun-tasks`, 요구 모드 게이트, ruff를 같은 HEAD에서 다시 실행한다.
````

### 3.2 호출 2 원문 (10,954바이트 · exit 0 · verdict `needs-attention`)

````text
# Codex Adversarial Review

Target: branch diff against 81ba9fa
Verdict: needs-attention

HEAD 94440d8 기준 no-ship. R-8/R-9는 신고된 인스턴스 일부만 닫았고, 한 줄로 테스트나 CI 도달을 끄는 경로가 여전히 남는다. R-10의 DocumentDeleteReachTest ‘세 축’ 주장은 양성 대조로 현재 범위에서 뒷받침되므로 그 부분은 지적하지 않는다.

Findings:
- [high] [Q-A⒜] 악용 비용 기준을 세우고도 같은 한 줄 우회를 잔여로 남겼다 (docs/migration/_workspace/00_progress.md:2696-2713)
  ⑴ 원래 판정은 틀렸다. 레인의 `@Disabled` 관측은 옳았고, 이를 ‘정직하게 신고된 잔여’로 넘긴 리더의 처분이 D2 위반이다. ⑵ a9f71c2의 JUnit XML 대조는 바닥 클래스의 `@Disabled`·assumption 인스턴스에는 충분하지만, 한 줄 비활성화 종류 전체에는 불충분하다. ⑶ 같은 형태가 B-19 단언 무력화(백로그 327-354), B-21 Kotlin 라쳇 항목 삭제(398-420), B-22 산문 참조(424-447), B-23 소유 술어 fail-open(451-473), 원장의 조립 SQL·의미/멤버 참조·`--rerun-tasks` 제거(2873-2883)에 그대로 남는다. 모두 문서가 한 줄 비용이라고 인정한다. B-12·B-15~B-18처럼 대상 부재·큰 변경·즉시 실패하는 항목과 같은 처분을 할 수 없다. 재현: Kotlin 튜플 하나 삭제 또는 단언 본문 하나 무해화 후 전체 게이트를 실행하면 원장 자체가 ‘잡는 것 없음’으로 기록한다.
  Recommendation: 한 줄 잔여를 출하 차단 항목으로 재분류하고, B-19/B-21/B-23과 C5의 `--rerun-tasks` 우회에 각각 겨눈 음성 대조를 추가한다.
- [high] [Q-A⒝] R-7은 인스턴스와 단순 개수 감소만 닫았고 종류는 닫지 못했다 (docs/migration/_workspace/00_progress.md:2624-2660)
  ⑴ `813c64f`의 ‘클래스가 유일한 강제자’ 판정은 보호 단위가 메서드였으므로 틀렸다. ⑵ `ea32728`은 ValueSlot 메서드를 전용 클래스로 옮긴 인스턴스와 ‘바닥 클래스의 JUnit 선언 수가 감소한다’는 하위 종류는 닫았다. 그러나 `MIN_TESTS_IN_FLOOR_CLASS`는 `actual >= expected`만 보므로 단언 비우기, 무해한 단언으로 교체, 위험 케이스를 지우고 더미 케이스를 추가하는 B-19 유형은 전부 통과한다. 따라서 ‘보호 대상 메서드가 의미를 잃는다’는 종류는 열려 있다. ⑶ 같은 형태는 백로그 B-19와 비바닥 클래스 메서드의 태그 제외에 남는다. 재현 비용은 단언 한 줄 교체 또는 완전수식 `@org.junit.jupiter.api.Tag("llm")` 한 줄이다.
  Recommendation: ‘종류 종결’ 주장을 ‘바닥 클래스의 단순 테스트 선언 감소만 종결’로 좁히고, 핵심 제품 변이에 대한 표적 mutation test를 출하 게이트에 둔다.
- [high] CI 도달 검사가 한 줄 조건·종료 코드 은폐를 배선으로 승인한다 (tests/test_kotlin_gate_reach.py:1129-1177)
  `_steps_running_this_file`은 `run` 문자열에 경로가 있는지만 보고, 요구 모드는 env의 비어 있지 않은 값만 본다. 따라서 kotlin 요구 스텝에 `if: false`를 한 줄 추가하거나 명령에 `|| true`를 붙여도 검사 자체는 배선이 살아 있다고 판정한다. 읽기 전용 YAML 모의 입력으로 두 변이 모두 현재 predicate에서 `recognized=True`임을 확인했다. 전자는 XML 전건 대조를 실행하지 않고, 후자는 그 대조가 빨개져도 잡을 초록으로 만든다. 이는 D3의 외부 음성 대조를 없애며 악용 비용은 각각 한 줄이다.
  Recommendation: 정확한 `kotlin` 잡과 스텝을 지정해 조건 없는 실행, canonical 명령, 종료 코드 전파를 검증하고 `if: false`, `continue-on-error`, `|| true`, 외부 파이프에 대한 음성 대조를 추가한다.
- [high] JUnit XML 전환이 비바닥 메서드의 태그 제외를 보지 못한다 (backend-kotlin/build.gradle.kts:119-124)
  Gradle은 모든 Test 태스크에서 `llm` 태그를 발견 단계에서 제외한다. `DocumentListHeaderFloorTest`는 이름과 달리 FLOOR_TEST_CLASSES에 없고 테스트가 둘이다. 첫 메서드(파일 68-88)에 `@org.junit.jupiter.api.Tag("llm")` 한 줄을 추가하면 핵심 개별 헤더 하한 검사는 XML에 아예 나타나지 않지만 둘째 메서드가 실행돼 클래스 실행 대조는 통과한다. skipped=0 검사도 XML에 없는 케이스를 볼 수 없다. tests/test_kotlin_gate_reach.py 906-907은 이 부재를 정확히 기록하면서 922-923에서는 태그 제외가 skipped로 나타난다고 반대로 주장한다. 악용 비용은 한 줄이며 R-8의 ‘기제를 열거하지 않고 실제 실행을 본다’는 범위를 깨뜨린다.
  Recommendation: 계약·하네스 테스트를 태그 제외가 없는 전용 Gradle task로 실행하고 그 XML을 대조한다. 제외 애너테이션 목록을 더 스캔하는 은폐형 확장은 피한다.
- [high] [Q-C] 증거가 공통 판정기와 수기 핀을 순환하고 자동 범위 주장이 하드코딩보다 넓다 (backend-kotlin/api/src/test/kotlin/kr/easydoc/api/ValueSlotInvariantReachTest.kt:52-56)
  F3의 슬라이스 축과 컨테이너 축은 관측 지점만 다르고 둘 다 RequestFieldProbes.measure의 동일한 입력·판정을 사용한다(RequestFieldRejectionLayerTest 27-29·54-55, RequestFieldRejectionReachTest 31-63). 이 함수의 한 줄 오판은 두 축을 동시에 초록으로 만들므로 서로 독립한 근거가 아니다. 바닥 체계도 소스 애너테이션→수기 MIN_TESTS→XML 실행 수→같은 핀의 git 이력으로 순환하며 의미는 밖에서 검증하지 않는다. 동시에 이 파일은 ‘계약이 파라미터를 더하면 자동으로 덮는다’고 하지만 실제 쿼리 분모는 GET /documents 한 경로(77, 131), 경로 변수 분모는 `/workspaces/{workspace_id}`와 patch/delete 목록(166-169, 341-348)으로 하드코딩돼 있다. 다른 엔드포인트의 새 파라미터는 자동으로 테스트되지 않는다. 추가 비용은 기존 핸들러 파라미터 한 줄이며, 새 타입의 널화는 B-17처럼 미측정이다.
  Recommendation: 한 관측축은 RequestFieldProbes와 독립된 계약 oracle로 판정하고, 구현된 모든 계약 operation의 query/path 파라미터를 구조적으로 생성해 실행한다. 그렇지 않으면 KDoc 범위를 두 하드코딩 경로로 좁힌다.
- [medium] [Q-A⒞] 핀은 여전히 의미가 아닌 애너테이션 개수의 대리 측정이며 느슨함을 잡지 않는다 (tests/test_kotlin_gate_reach.py:721-742)
  ⑴ grep으로 15를 정한 원래 판정은 대리 측정이라 틀렸다. ⑵ `_declared_test_count`로 14를 다시 산출한 정정은 그 시점의 소스 선언 개수에는 충분하다. 현재 28개 표를 같은 파서로 재조회한 결과 불일치는 0건이었다. ⑶ 그러나 핀 값 전부가 여전히 `@Test` 애너테이션 수의 대리값이다. 소스 검사는 `actual >= expected`, XML 검사는 `executed < floor`만 보고, git 이력도 현재 핀이 과거 핀보다 낮은지만 본다. 테스트가 늘어 핀이 낡아져도 모두 초록이므로, 네 낡은 값은 사람이 발견했을 뿐 자동 장치가 발견한 것이 아니다. 한 줄로 기존 helper에 테스트 애너테이션을 붙이고 핀을 유지하면 느슨함이 재현된다.
  Recommendation: 소스 선언 수와 핀을 정확 일치시키고, 실행 수는 별도 의미로 비교한다. git 이력 라쳇은 감소 방지에만 사용하고 현재 값 산출 근거로 주장하지 않는다.
- [medium] [Q-A⒟] 마지막 커밋 이후 재측정을 증명하는 장치가 없다 (docs/migration/_workspace/00_progress.md:2718-2725)
  ⑴ `d816fb0` 뒤 재실행 없이 ‘전건 초록’이라고 한 판정은 거짓이었다. ⑵ 원장 정정과 뒤이은 포맷 수정 커밋은 현재 파일을 고쳤을 뿐, 같은 보고 오류를 막지 않는다. 실제로 제공된 19062cc 뒤 HEAD 94440d8이 `ruff format` 실패 수정으로 추가됐다. ⑶ 로컬 명령은 `ruff check --fix . && ruff format .`, CI는 `ruff check .`와 `ruff format --check .`; mypy 범위는 둘 다 `. .claude`이고 ruff도 정상 실행 시 같은 프로젝트 루트를 읽지만 동작 모드는 동일하지 않다. `--isolated` 한 플래그면 pyproject의 E501 선택과 100자 폭을 읽지 않아 해당 오류가 사라진다. CI는 체크아웃 SHA를 검사하지만 리더의 로컬 ‘전건 초록’ 보고를 그 SHA에 결속하지 않는다.
  Recommendation: 출하 근거를 정확한 HEAD SHA의 필수 CI check suite로 제한하고, 로컬 러너는 시작·종료 SHA가 같고 프로젝트 설정을 사용했음을 기록하도록 한다.
- [medium] [Q-B] 장치 분류가 틀렸고 은폐형과 0분모 통과가 남아 있다 (backend-kotlin/api/src/test/kotlin/kr/easydoc/api/support/RequestFieldProbes.kt:164-168)
  기능 기준 분류는 다음과 같다. 탐지형: 지정된 9개 Kotlin 테스트 클래스와 Python의 소스/XML/git/CI 대조. 강제·표현형: 런타임 `TypedValueSlotInterceptor`와 테스트를 실제 실행하는 CI 배선. 범위 선언형: TEST_CLASSES/TEST_CLASS_COUNT/MIN_TEST_CLASSES/FLOOR/MIN_TESTS, EXPECTED_SOURCE_DECLARATIONS, RATCHET_* 및 CI 대상 스텝. ConstraintMetadata·ContractSpec·RequestFieldProbes·LiveSql은 각각 탐지 helper/계약 표현/공유 판정 helper/SQL 판정 helper이며 독립 장치가 아니다; 제거하면 소비 테스트의 컴파일이 깨진다. 잘못 붙인 분류는 테스트를 ‘강제자’로 부르는 KDoc과 원장 2895의 파이프 ‘강제·표현형’이다. 실제 run_gate.sh 51-66은 자신을 탐지형이라고 하며 CI 배선 0을 인정한다. 은폐형 확장은 여기의 `@Suppress`와 tests/test_kotlin_gate_reach.py 540-548의 NON_RATCHET_PINS 면제표다. 또한 보고서 검사는 비요구 모드에서 XML 분모 0을 출력 후 통과하고(816-917), 이력 검사도 비요구 모드에서 이력 분모 0을 통과한다(995-998). 제거해도 외부가 빨개지지 않는 장치는 B-21의 Kotlin RATCHET 항목과 원장상의 파이프 규칙이다.
  Recommendation: 중앙 판정 함수를 분할해 suppression을 제거하고, NON_RATCHET 면제를 실행 성질 대조로 교체한다. 원장 분류를 실제 동작에 맞추고 0분모는 모든 실행 모드에서 실패시킨다.

Next steps:
- 한 줄 우회인 CI 조건/종료코드, 비바닥 `llm` 태그, B-21 라쳇 항목 삭제, B-23 소유 술어 반례를 출하 차단 음성 대조로 고정한다.
- 정확한 HEAD SHA에서 전체 CI가 완료되기 전에는 ‘전건 초록’을 수락하지 않는다.
- 수기 개수 핀과 공유 판정기 대신 핵심 제품 변이에 대한 독립·표적 mutation gate를 우선 도입한다.
````

---

## 4. 정리(가공) — 원문과 분리된 구획

> **이 구획은 codex 의 말이 아니다.** 목록화·축 대응·중복 인접 표시만 한 것이고, **옳고 그름·심각도 환산·오탐
> 여부는 판정하지 않는다.** `전제 확인 필요` 표시는 codex 지적을 부정하는 것이 **아니라**, 그 지적이 인용한
> 경로·범위가 이 회차의 심판 대상 안인지에 관한 **기계적 대조 결과**다(§4.3).

### 4.1 호출 1 지적 목록 (8건 — high 5 · medium 3)

| # | codex 라벨 | 지적 머리 | codex 가 준 근거 위치 | codex 가 적은 악용 비용 | 리더 축 대응 |
|---|---|---|---|---|---|
| C1-1 | high | Tomcat 의 미선언 400·505 가 그대로 외부로 나간다 | `backend-kotlin/api/.../error/ContractErrorReportValve.kt:67-69` | 「코드 편집 0, 원시 malformed 요청 한 건」 | 축 3 (계약) |
| C1-2 | high | GET 목록의 소유권 은닉이 헤더·시간 축을 전혀 측정하지 않는다 | `DocumentListReachTest.kt:190-200` (+ `DocumentDeleteReachTest.kt:491-535`) | 「하네스 편집 0」 | 축 2 (보안) |
| C1-3 | high | 새 계약 강제자 일부는 클래스만 살아 있으면 핵심 메서드를 한 줄로 끌 수 있다 | `tests/test_kotlin_gate_reach.py:446-475` (+ `DocumentListHeaderFloorTest.kt:68`, `DocumentListContractTest.kt:186`) | 「한 줄」 | 축 1·4⑵ |
| C1-4 | high | 파라미터화 invocation 이 실행 하한의 여유분으로 잘못 계산된다 | `tests/test_kotlin_gate_reach.py:125-127` (+ `ContractErrorBodyReachTest.kt:304`) | 「한 줄」 | 축 1 |
| C1-5 | high | CI 배선 검사가 명령 실행을 경로 문자열 포함으로 대리 측정한다 | `tests/test_kotlin_gate_reach.py:1123-1130` | 「한 줄」 | 축 1·5 |
| C1-6 | medium | 이전 JUnit XML 도 이번 실행 증거로 받아들인다 | `.github/workflows/ci.yml:281-319` | 「재사용 작업공간에서 편집 0」 | 축 1 |
| C1-7 | medium | 요청 필드 경계 방향을 계약이 아니라 검사 대상 구현에서 추론한다 | `support/RequestFieldProbes.kt:203-223` | 「이 강제자 자체는 한 줄, 전체 우회는 더 크다」 | 축 1 (기준이 검사 대상에서 나옴) |
| C1-8 | medium | API 삭제 테스트는 고아 작업 행을 볼 수 없다 | `DocumentDeleteReachTest.kt:436-444` | 「제품 변이 비용은 스키마 변경 규모」 | 축 2 (즉시 파기) |

**호출 1 이 「찾지 못했다」고 명시한 것** (원문 요약 문장에서): ⑴ **C7 평문·로그 유출** ⑵ **GET 페이지 경계 불일치**.
그리고 **C6 에 대해서는 「현재 구현은 실제 DELETE 와 FK CASCADE 를 사용한다」**고 적었다(= 표시 아닌 파기 쪽 긍정 관찰).
`ruff` 는 「직접 재측정해 초록」이라고 적었고, Gradle 은 「읽기 전용 환경이라 재실행하지 않고 HEAD 직후 생성된 XML 을 점검」했다고 밝혔다.

### 4.2 호출 2 지적 목록 (8건 — high 5 · medium 3)

| # | codex 라벨 | 지적 머리 | codex 가 준 근거 위치 | codex 가 적은 악용 비용 | 리더 축 대응 |
|---|---|---|---|---|---|
| C2-1 | high | 악용 비용 기준을 세우고도 같은 한 줄 우회를 잔여로 남겼다 | `00_progress.md:2696-2713` · 백로그 `327-354`·`398-420`·`424-447`·`451-473` · 원장 `2873-2883` | 「모두 문서가 한 줄 비용이라고 인정한다」 | 축 4⑴ |
| C2-2 | high | R-7 은 인스턴스와 단순 개수 감소만 닫았고 종류는 닫지 못했다 | `00_progress.md:2624-2660` | 「단언 한 줄 교체 또는 완전수식 `@Tag("llm")` 한 줄」 | 축 4⑵ |
| C2-3 | high | CI 도달 검사가 한 줄 조건·종료 코드 은폐를 배선으로 승인한다 | `tests/test_kotlin_gate_reach.py:1129-1177` | 「각각 한 줄」(`if: false` / `\|\| true`) | 축 1·5 |
| C2-4 | high | JUnit XML 전환이 비바닥 메서드의 태그 제외를 보지 못한다 | `backend-kotlin/build.gradle.kts:119-124` · `DocumentListHeaderFloorTest.kt:68-88` · `tests/test_kotlin_gate_reach.py:906-907` 대 `922-923` | 「한 줄」 | 축 1 |
| C2-5 | high | 증거가 공통 판정기와 수기 핀을 순환하고 자동 범위 주장이 하드코딩보다 넓다 | `ValueSlotInvariantReachTest.kt:52-56`·`77`·`131`·`166-169`·`341-348` · `RequestFieldRejectionLayerTest.kt:27-29`·`54-55` · `RequestFieldRejectionReachTest.kt:31-63` | 「기존 핸들러 파라미터 한 줄」 | 축 5 (전칭이 실측을 넘음) |
| C2-6 | medium | 핀은 여전히 의미가 아닌 애너테이션 개수의 대리 측정이며 느슨함을 잡지 않는다 | `tests/test_kotlin_gate_reach.py:721-742` | 「한 줄」 | 축 4⑶ |
| C2-7 | medium | 마지막 커밋 이후 재측정을 증명하는 장치가 없다 | `00_progress.md:2718-2725` | (명시 없음 — 보고 관행의 문제로 적음) | 축 4⑷ |
| C2-8 | medium | 장치 분류가 틀렸고 은폐형과 0분모 통과가 남아 있다 | `support/RequestFieldProbes.kt:164-168` · `tests/test_kotlin_gate_reach.py:540-548`·`816-917`·`995-998` · `run_gate.sh:51-66` · 원장 `2895` | (항목별 명시 없음) | 축 5 |

**호출 2 가 「지적하지 않겠다」고 명시한 것**: **R-10 의 `DocumentDeleteReachTest` 「세 축」 주장** — 「양성 대조로
현재 범위에서 뒷받침되므로 그 부분은 지적하지 않는다」. **호출 1 의 C1-2 와 방향이 갈리는 대목이므로 지우지 않고 병기한다.**

또한 호출 2 는 축 4 네 항목에 **⑴ 원래 판정 ⑵ 처분의 충분성 ⑶ 남은 자리**를 각각 명시적으로 답했다. 그 답의 요지는
원문(§3.2)에 있고, **이 구획에서 재기술하지 않는다** — 재기술이 곧 요약이고 요약은 독립성을 덮는다.

### 4.3 `전제 확인 필요` — 인용 경로가 심판 대상 안인지의 기계적 대조

리더가 지정한 심판 대상은 `81ba9fa..19062cc` 다. 그 diff 의 변경 파일 목록과 codex 인용 경로를 대조했다.

| codex 지적 | 인용 파일 | 파일 실재 | 이 배치 diff 안 | 대조 결과 |
|---|---|---|---|---|
| C1-1 | `.../error/ContractErrorReportValve.kt` | 있다 (184줄) | **아니다** | 지적 대상 코드가 **이 배치가 만든 것이 아니다**(기존 코드). 계약 위반 주장 자체의 성립 여부와는 별개 |
| C2-4 | `backend-kotlin/build.gradle.kts` | 있다 (381줄) | **아니다** | 같음 — `llm` 태그 제외 설정은 이 배치의 변경이 아니다 |
| C2-8 | `.claude/skills/kotlin-migration/scripts/run_gate.sh` | 있다 (118줄) | **아니다** | 같음 |
| C2-7 | HEAD `94440d8` 을 근거의 일부로 인용 | 해당 | **아니다** | §1.3 의 그 커밋. 호출 2 가 지정 범위보다 1커밋 넓은 것을 본 결과다 |
| 그 외 전부 | `tests/test_kotlin_gate_reach.py` · `.github/workflows/ci.yml` · `DocumentListReachTest` · `DocumentDeleteReachTest` · `RequestFieldProbes` · `ValueSlotInvariantReachTest` · `DocumentListHeaderFloorTest` · `DocumentListContractTest` | 있다 | **그렇다** | 심판 대상 안 |

**이 표는 지적을 기각하지 않는다.** diff 밖의 파일을 짚은 지적은 「이 배치가 만든 결함」이 아닐 수 있고 동시에
「이 배치가 그 위에 판정을 쌓은 자리」일 수 있다 — 어느 쪽인지는 3단계의 판단이다. 대조 결과만 남긴다.

### 4.4 두 회차가 인접·상충하는 자리 (병기만 한다)

| 주제 | 호출 1 | 호출 2 | 표시 |
|---|---|---|---|
| `DocumentDeleteReachTest` 의 시간 축 | C1-2 — 「1.5배 미만 시간차를 허용하며, 소유 조건을 SQL 밖으로 옮긴 변이가 통과한다고 스스로 기록한다」 | 「양성 대조로 현재 범위에서 뒷받침되므로 그 부분은 지적하지 않는다」 | **회차 간 갈림 — 어느 쪽도 지우지 않는다** |
| `DocumentListHeaderFloorTest` 의 한 줄 무력화 | C1-3 — `@Test` 주석화 | C2-4 — 완전수식 `@Tag("llm")` 추가 | 같은 자리·**다른 기제**. 중복이 아니라 두 경로 |
| CI 배선 검사의 대리 측정 | C1-5 — `\|\| true` | C2-3 — `if: false` **와** `\|\| true` | 인접(2회차가 넓다) |
| 핀의 대리 측정 | C1-4 — 소스 애너테이션 수 대 XML invocation 수 | C2-6 — 핀 전체가 애너테이션 수의 대리값, 느슨함 미탐지 | 인접 |

---

## 5. 미실행·실패 항목

| 항목 | 상태 |
|---|---|
| 스크립트 실패 | **없음.** 2회 모두 exit 0 |
| 재시도 | **0회** |
| 출력 잘림 | **없음.** 두 출력 모두 `Next steps:` 까지 정상 종결 |
| ⚠ codex 리뷰 누락 | **해당 없음** — 이 회차는 codex 독립 리뷰를 실제로 받았다 |
| 리더 지정 범위와의 차이 | **호출 2 가 1커밋 넓다**(§1.3). 좁혀 다시 돌리지 않았다 — `--base 81ba9fa` 는 항상 현재 HEAD 를 보므로 지정 범위로 되돌리려면 `19062cc` 를 detach 해 돌려야 하고, 그것은 이 에이전트가 작업 트리를 움직이는 일이다(내 역할 밖) |
| codex 가 돌리지 못했다고 밝힌 것 | 호출 1 원문 — 「읽기 전용 환경이라 Gradle 은 재실행하지 않고 HEAD 직후 생성된 XML 을 점검했다」. 즉 **`./gradlew build --rerun-tasks` 를 codex 가 직접 돌린 관측은 없다** |
| 이 에이전트가 하지 않은 것 | 지적의 옳고 그름 판정 · 심각도 환산 · 중복 병합 · 표현 다듬기 · 오탐 표시 · 코드 수정. `docs/migration/_workspace/reviews/**` 중 이 파일 하나만 썼다 |

---

## 6. 수신자에게

- **`migration-reviewer`**(3단계 교차 종합): 입력은 이 파일과 `04_documents-c4c5_migration-reviewer.md` 다.
  §3 의 두 원문이 정본이고 §4 는 목록화일 뿐이다. §4.4 의 **회차 간 갈림 1건**(삭제 시간 축)은 codex 내부에서
  갈린 것이므로 Claude 리뷰와의 상충과 별도로 다뤄야 한다.
- **리더**: codex 의 견해는 두 회차 모두 `needs-attention` / 본문 「NO-SHIP」·「no-ship」이다.
  **이 에이전트는 Phase·게이트 종료 가능 여부를 판정하지 않는다.**
