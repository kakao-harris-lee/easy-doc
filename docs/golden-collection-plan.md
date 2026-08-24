# Kotlin 골든 데이터 수집 계획

## 목적

`data/golden/documents/`에 Kotlin 평가기가 읽을 수 있는 언어 독립 JSON을 축적한다. 수집과 사람 검수는 평가 실행과 분리한다.

## 수집 원칙

1. 기관 공개 문서 또는 사용 허가가 확인된 자료만 사용한다.
2. 원문 출처, 기관, 문서 날짜, 라이선스, 수집일을 기록한다.
3. 주민등록번호·카드번호가 없어야 한다.
4. 현재 자동 마스킹 범위 밖인 전화번호·이메일·계좌번호도 사람이 확인해 제거하거나 합성값으로 바꾼다.
5. `required_facts`는 원문에서 검증 가능한 사실 3~6개로 작성한다.
6. 변환 기대문을 만들 때 원문에 없는 혜택·조건·기한을 추가하지 않는다.

## 디렉터리

```text
docs/golden/            검토할 공개 원문
docs/golden-drafts/     사람 검수 전 JSON 초안
data/golden/documents/  승인된 언어 독립 fixture
```

초안에서 승인 경로로 이동할 때 파일명과 `id`를 고정하고, 기존 fixture를 덮어쓰지 않는다.

## 현재 검증

`backend-kotlin` Gradle 테스트가 `data/golden/documents/`를 읽어 다음을 검사한다.

- JSON 구문과 평가 입력 스키마(id·title·category·source_text·required_facts)
- 중복 `id` 없음
- `required_facts`가 원문에 실제로 존재
- 스타일 규칙은 외부 API 없이 `StyleRules.checkStyle`로 채점
- 커밋된 기준선(`core/src/test/resources/kr/easydoc/core/quality/golden-baseline.json`)과 문서·사실 건수 일치. 일반 테스트는 이 파일을 다시 쓰지 않는다.

LLM-as-judge는 `@Tag("llm")` opt-in이다. 기본 `./gradlew build`는 제외하고, 비밀값이 없으면 레인을 skip한다. 기준선 파일은 일반 테스트가 다시 쓰지 않으며, 건수 변경은 리뷰 승인 후에만 커밋한다.

## 완료 체크

- [ ] 출처·라이선스 기록
- [ ] 개인정보·연락처 사람 검수
- [ ] `required_facts` 3~6개 검증
- [ ] 중복 ID 확인
- [x] Kotlin 정적 평가 통과(평가기 구현 후)
