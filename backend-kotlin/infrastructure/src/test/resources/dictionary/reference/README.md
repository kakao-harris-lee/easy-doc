# Kotlin 사전 컨텍스트 회귀 픽스처

Kotlin `DictionaryIndexJsonReader`와 `DictionaryIndex.buildPromptContext()`로 생성한 출력이다. 원문은 `data/golden/documents/`에 있고 파일명은 문서 ID다. 고정 52건은 `DictionaryReferenceContextTest`에서 전건 대조한다. 기존 추가 6건도 보관하며, 고정 대조 목록은 변경하지 않았다.

2026-09-12에는 생성 컨텍스트의 구역을 참고 자료로 바꾸고 다른 사업의 조건이 섞인 `caution`을 제외했다. 정의는 남기고, `시술`·`환수`·`중위소득`의 검수 결정도 반영했다. 이 변경으로 항목 길이와 예산 안에 들어가는 항목 수가 달라졌다. 매칭·위험도·예약석·중복 제거 규칙은 유지했다.

정책은 `maxTerms=40`, `maxChars=4000`, `maxCharsRatio=1.0`, `minSubstitute=5`, `maxExamples=3`이다. GLOSS 안내 줄의 길이는 기존 결정대로 문자 예산에서 제외한다. 빈 컨텍스트와 예산 경계는 core 단위 테스트로 확인한다.

재생성은 컴파일된 Kotlin 제품 클래스로 원문과 위 정책을 전달해 수행한다. 이번 변경은 Gradle test runtime classpath에서 JVM 호출로 58개 기존 파일을 갱신했다. 생성된 텍스트의 변경을 검토하고 다음을 실행한다.

```sh
cd backend-kotlin
./gradlew :core:test :infrastructure:test --tests '*Dictionary*'
```

이 픽스처는 문자열·선별 회귀를 검사한다. 실제 쉬운 글 품질의 근거는 별도의 유료 레인 결과다. Python 출력과의 동등성을 목표로 하지 않는다.
