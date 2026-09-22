package kr.easydoc.infrastructure.document

import kr.easydoc.application.auth.TransactionRunner
import kr.easydoc.application.crypto.ContentCipher
import kr.easydoc.application.dictionary.ReviewedDefinitionSource
import kr.easydoc.application.document.ConversionRepository
import kr.easydoc.application.document.DocumentRepository
import kr.easydoc.application.document.ExplanationsService
import kr.easydoc.core.dictionary.ExplanationDefinitionSource
import kr.easydoc.core.dictionary.ReviewedDefinition
import kr.easydoc.infrastructure.crypto.MIGRATE_PROFILE
import kr.easydoc.infrastructure.dictionary.DictionaryIndexHolder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile

/**
 * R6 「근거 있는 추가 설명」 조립 지점 - `DocumentConfiguration` 을 건드리지 않고 새 파일로 둔다.
 *
 * [ReviewedDefinitionSource] 구현은 프로필로 갈린다 - 운영·개발은 실제 사전 색인을 쓰고
 * (`dictionaryReviewedDefinitionSource`), `e2e` 프로필은 유료 LLM 호출 없이 패널·엔드포인트
 * 경로 전체(조회 -> 파생 -> 응답)를 실제로 exercise 하기 위한 고정 응답 fake를 쓴다. 이 갈림은
 * `ActionGuideConfiguration` 의 `@Profile("worker & action-guide-fake")` /
 * `@Profile("worker & !action-guide-fake")` 짝과 같은 관례를 그대로 따른 것이다 - 다만 R6 의
 * fake 는 별도 스위치 프로필이 필요 없어 `compose.e2e.yml` 이 이미 켜는 `e2e` 프로필 하나로
 * 충분하다.
 *
 * `migrate` 프로필에서는 이 클래스가 서지 않는다 - [explanationsService] 가
 * [ConversionRepository]·[DocumentRepository]·[ContentCipher] 를 요구하는데, 스키마만
 * 적용하는 `migrate` 컨텍스트에는 그 셋이 없다(키 재료를 들지 않는 것이 그 프로필의 요점이다).
 * 형제 조립 지점(`DocumentConfiguration`·`ReviewHistoryConfiguration`·
 * `DocumentExportConfiguration`·`ActionGuideConfiguration`)이 모두 같은 게이트를 두고,
 * 컨트롤러도 같은 이유로 `@Profile("!$MIGRATE_PROFILE")` 다.
 */
@Configuration(proxyBeanMethods = false)
@Profile("!$MIGRATE_PROFILE")
class ExplanationsConfiguration {
    @Bean
    @Profile("!e2e")
    fun dictionaryReviewedDefinitionSource(holder: DictionaryIndexHolder): ReviewedDefinitionSource =
        DictionaryReviewedDefinitionSource(holder)

    /**
     * e2e 전용 고정 응답 - 실제 사전 색인이나 LLM을 부르지 않는다.
     *
     * `frontend/e2e/explanations.spec.ts` 의 `TERM`("서류")·`EXPLANATION`
     * ("신청할 때 기관에 내는 종이 문서입니다.") 두 상수에 그대로 고정돼 있다 - 이 값을 바꾸면
     * 그 e2e 스펙이 깨지므로 함께 바꾸지 않는 한 건드리지 않는다. `body.contains(TERM)` 로
     * 게이트를 두는 것은 실제 사전 조회가 "본문에 용어가 있어야만 정의를 낸다"는 성질을
     * 흉내 내기 위해서다 - 무조건 한 건을 돌려주면 "본문과 무관하게 항상 뜬다"는 실제로는
     * 없는 동작을 e2e 가 통과시켜 버린다.
     */
    @Bean
    @Profile("e2e")
    fun fakeReviewedDefinitionSource(): ReviewedDefinitionSource =
        ReviewedDefinitionSource { body ->
            if (body.contains(E2E_TERM)) {
                listOf(
                    ReviewedDefinition(
                        term = E2E_TERM,
                        surface = E2E_TERM,
                        definitionSource = ExplanationDefinitionSource.DICTIONARY_REVIEWED,
                        explanation = E2E_EXPLANATION,
                    ),
                )
            } else {
                emptyList()
            }
        }

    @Suppress("LongParameterList") // Spring 조립점의 포트 수이며 도메인 입력 복잡도가 아니다.
    @Bean
    fun explanationsService(
        properties: ExplanationsProperties,
        conversions: ConversionRepository,
        definitions: ReviewedDefinitionSource,
        documents: DocumentRepository,
        cipher: ContentCipher,
        transaction: TransactionRunner,
    ): ExplanationsService =
        ExplanationsService(
            enabled = properties.enabled,
            conversions = conversions,
            definitions = definitions,
            documents = documents,
            cipher = cipher,
            transaction = transaction,
        )

    private companion object {
        const val E2E_TERM = "서류"
        const val E2E_EXPLANATION = "신청할 때 기관에 내는 종이 문서입니다."
    }
}
