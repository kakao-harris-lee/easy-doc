# C6 테스트 SQL 이 지켜야 하는 두 규약 — 근거 (주석에서 옮김)

주석 예산(`tests/test_kotlin_comment_budget.py`, 상한 130,000자) 때문에
`backend-kotlin/api/src/test/kotlin/kr/easydoc/api/ConversionReadReachTest.kt` 의 서사 주석을
여기로 옮겼다. `.kt` 에는 규약 한 줄과 이 문서 포인터만 남긴다. **지운 것이 아니라 옮긴 것이다.**

## 규약 1 — 결과 열을 쓰는 SQL 은 companion 의 **상수 문자열 리터럴**에 둔다

`ConversionReadReachTest.MARK_DONE_SQL` 이 그 형태다(`JdbcConversionRepository.FIND_OWNED_SQL` 과
같은 모양). 호출부에서 여러 줄 문자열을 조립하거나 조각을 `+` 로 이어 붙이면 **두 게이트가 동시에
무력화된다.**

1. **`scan_privacy_invariants.py` 의 논리 줄 결합기.** 호출부에 놓인 여러 줄 문자열에서 문자열
   상태가 열린 채 40줄 상한에 닿고, 그 구간이 **미검사**로 남는다. 실측: BLOCK 이 났다.
2. **`EnvelopeColumnWriteGuardTest`.** 이 가드는 **문자열 리터럴로 읽히는 SQL** 만 본다. 조각을
   `+` 로 이어 붙이면 「암호문 열을 SET 하는 UPDATE」가 가드의 눈에서 사라진다. 실측: 첫 판이
   그것을 어겨 **그 파일이 인구조사에서 빠졌다**.

덧붙여 `EnvelopeColumnWriteGuardTest` 는 **봉투 두 값(`encryption_scheme`·`key_version`)을 암호문
열과 같은 문장에서 함께 SET** 하도록 요구한다. 첫 판이 이것도 어겨 실제로 빨개졌다. 워커(Phase 5)가
쓸 UPDATE 도 같은 모양이어야 한다.

## 규약 2 — 문자열 템플릿 **안**에 SQL 인용부호를 겹치지 않는다

금지 형태: `"'${x.replace("'", "''")}'"`. 스캐너의 어휘 분석기가 그 겹침에서 문자열 상태를 열린
채로 두고, 그 구간이 미검사로 남는다. 실측: **그 한 줄이 BLOCK 을 냈다.** 그래서
`ConversionReadReachTest.forceStatus` 는 인용부호를 `SINGLE_QUOTE`·`ESCAPED_QUOTE` 상수로 빼서
문자열 **밖에서** 잇는다.

## 왜 `.kt` 에 남기지 않았나

CLAUDE.md 의 주석 규칙 — 실측·기각한 대안·이력은 `docs/migration/_workspace/` 로 간다. 위 넷은
전부 「무엇을 실측했고 첫 판이 어떻게 틀렸나」라서 그 규칙의 정확한 대상이다. 코드에 남겨야 하는
것은 **규약 자체**(상수 리터럴에 둔다 / 템플릿 안에서 인용부호를 겹치지 않는다)뿐이다.
