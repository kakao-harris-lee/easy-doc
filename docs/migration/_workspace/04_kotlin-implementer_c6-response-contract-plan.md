# C6 조회 경로 응답 계약 위반 2건 — 계획

대상: `GET /conversions/{conversion_id}`. HEAD `595ed3a`. 미커밋 5건은 c5 잔여이므로 손대지 않는다.

## 1. 라이브러리·프레임워크 리서치

새 의존성이 필요한 작업이 아니다. 두 자리 모두 **이미 있는 것으로** 닫는다.

- ① 상태 조건부 노출: 표준 라이브러리에 「상태에 따라 필드를 비운다」를 제공하는 것은 없다.
  Jackson 의 `@JsonInclude`·`JsonView` 는 **필드 존재 여부**를 바꾸는 장치이고 계약은
  `ConversionResponse.required` 에 13 키 전부를 요구한다(`CR-2` 가 그것을 잰다) — 키를 지우면
  그 케이스가 깨진다. 그러므로 값 수준의 판정이어야 하고, 그것은 도메인 조건이다.
  Bean Validation(`@AssertTrue`)도 후보였으나 응답 DTO 에 검증기를 붙이는 것은 이 저장소에
  선례가 없고, 위반 시점이 직렬화 시점으로 밀려 「어디서 새는가」를 가린다.
- ② 문구 일치: Kotlin `const val` 하나를 고치는 일이다.

## 2. 기구현 확인

| 이미 있는 것 | 위치 | 이 계획에서의 쓰임 |
|---|---|---|
| 계약에서 값을 읽는 하네스 | `api/src/test/.../support/ContractSpec.kt` — `responseExampleDetail`, `schemaEnum` | ② 의 문구를 값으로 박지 않고 계약에서 읽는다 |
| 완료 행을 SQL 로 만드는 팔 | `ConversionReadReachTest.markDone` / `forceStatus` | ① 의 음성 대조 상태(결과 열 채움 + 비완료 상태)를 **조합으로** 만든다 |
| Spring·DB 없는 조회 대역 | `application/.../ConversionQueryServiceTest.World` | ① 의 단위 팔 |
| 계약 문구를 만드는 상수 3개 | `ConversionStatus.UNKNOWN_STATUS_MESSAGE`, `MaskedItemCodec.STORAGE_FAILURE_MESSAGE`, `JdbcConversionRepository.UNREADABLE_RESULT_MESSAGE` | ② 의 판정 근거 — 리더 실측("계약 쪽 문구를 만드는 코드가 없다")과 다르다 |

새 테스트 클래스를 만들지 않는다 — `TEST_CLASSES`·개수 표·단언 하한 표를 건드리지 않기 위해서다.
추가하는 케이스는 전부 위 두 기존 클래스에 들어간다(하한 표는 하한이라 늘리기만 하면 통과한다).

## 3. 순서와 검증

### ① 「done 전에는 비어 있다」의 강제자

**강제 위치: `ConversionQueryService`(조립 경계) + `ConversionStatus`(규칙 정의) + `ConversionView`(구조적 가드).**
매퍼(`ConversionResponse.of`)가 아닌 사유:

1. 계약이 규정한 것은 **바이트**지만, 그 바이트를 만들려면 「이 상태는 결과를 내보내는가」라는
   도메인 판정이 필요하다. 이 저장소는 그 판정을 이미 유스케이스에 두고 있다 —
   `ConversionQueryService` 의 `maskedItems ... ?: emptyList()` 가 계약(`X-E3`)을 인용하며
   같은 종류의 정규화를 수행한다. 매퍼에 두면 같은 규칙이 두 계층으로 갈린다.
2. 매퍼는 **복호화가 끝난 뒤**다. 거기서 버리면 ⑴ 평문이 불필요하게 메모리에 생기고
   ⑵ 열 수 없는 암호문을 든 비완료 행이 **500** 이 된다 — 그 상태의 계약 응답은 200 이다
   (예: 키 회전이 반쯤 끝난 `failed` 행). 즉 매퍼 강제는 계약을 만족시키지 못한다.
3. 규칙 자체(`어느 상태가 결과를 내보내는가`)는 `ConversionStatus` 에 **한 번** 적는다. 열거형
   항목마다 값을 주므로 상태를 추가하는 사람이 결정을 건너뛸 수 없다.
4. `ConversionView.init` 에 `require` 를 둔다 — 조립 경계가 하나뿐인 지금은 발화하지 않지만,
   C7(PUT)·Phase 5(worker)가 새 조립 지점을 만들 때 **조용한 유출이 아니라 즉시 실패**가 된다.
   데이터 이상이 아니라 프로그래머 오류를 잡는 가드다(데이터 쪽은 ⑵ 의 사유로 서비스가 흡수한다).

**범위**: 계약이 명시적으로 열거한 세 필드(`easy_text`·`edited_text`·`masked_items`)만.
같은 문장의 후단("상태와 `failure_code`만 나간다")은 나머지 여섯 필드까지 함축하지만 그
여섯은 사용자 본문·개인정보를 담지 않으므로(모델명·토큰 수·시각·자리표시자 라벨 — 계약이
라벨을 "개인정보가 아니다"로 규정) **노출 범위 규칙은 세 필드로 닫힌다**. 후단의 규범성 판정은
계약 레인에 올린다. 근거를 넘지 않는다.

**실현 가능성 측정** (추정 금지 — 실측):
`conversions` 를 쓰는 제품 코드 전수 → `insertPending`(세 암호문 열 전부 NULL, status=pending)
과 `rewriteEnvelope`(암호문을 **있는 것끼리** 바꿔 쓰고 status 는 건드리지 않는다) 둘뿐이다.
`SET status` 를 하는 제품 SQL 은 0건이고 worker 모듈은 `WorkerApplication.kt` 한 파일뿐이다.
→ **오늘 제품 경로로는 그 조합이 만들어지지 않는다**. 만들 수 있는 것은 저장소 밖 SQL 과
테스트 하네스다. Phase 5 worker 가 오면 실현된다 — 마스킹이 LLM 호출 앞이므로 `failed` 행이
마스킹 대응표를 든다.

**음성 대조**: 결과 열을 채운 뒤(`markDone`) 상태만 비완료로 되돌려(`forceStatus`) HTTP 로 관측.
고치기 **전에** 세 값이 나가는지를 먼저 재고, 고친 뒤 상태·`failure_code` 만 남는지 확인.

### ② 500 저장 문구

**후보 ⒝(공용 문구를 계약 쪽으로) 를 고른다.** 사유는 보고에 적는다(핵심: 계약의
`InternalError` 는 13 오퍼레이션이 공유하고 storage 예시가 **하나**뿐이며 문서용 예시는 없다 —
⒜ 의 전제가 실측으로 거짓이다. 그리고 이미 셋이 그 문구를 낸다).

**음성 대조**: 변조 암호문 팔(이미 있다)에 「detail 이 계약 `InternalError.examples.storage` 와
같다」를 단언으로 추가. 고치기 전 빨강 → 고친 뒤 초록.

## 4. 검사

`uv run python .claude/skills/kotlin-migration/scripts/quality_gate_local.py` (커밋 전·후 각 1회),
Gradle 은 `--no-build-cache --rerun-tasks`, 요구 모드 게이트 앞에서
`KOTLIN_GATE_REACH_RUN_STARTED_AT` 를 박는다.
