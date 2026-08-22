package kr.easydoc.api

import kr.easydoc.api.document.DocumentCreatedResponse
import kr.easydoc.api.document.DocumentTextRequest
import kr.easydoc.api.support.ProductClasses
import kr.easydoc.application.document.AcceptedUpload
import kr.easydoc.core.crypto.EncryptedContent
import kr.easydoc.core.crypto.PlainBody
import kr.easydoc.core.document.ConversionStatus
import kr.easydoc.core.privacy.CONTENT_MASK
import kr.easydoc.core.privacy.MaskedText
import kr.easydoc.core.privacy.ModelDraft
import kr.easydoc.core.privacy.ReviewedBody
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.reflect.KClass
import kotlin.reflect.full.primaryConstructor

/** 문서 DTO 의 두 축 — `toString()` 유출과 X2(저장·평문 타입이 웹 표현에 실리지 않음). */
class DocumentDtoLeakTest {
    @Test
    @DisplayName("요청 DTO 의 toString 이 본문도 제목도 **작업 공간 원문도** 노출하지 않는다")
    fun `요청 DTO 가 본문을 가린다`() {
        val rendered = DocumentTextRequest(BODY, TITLE, RAW_WORKSPACE_ID).toString()

        assertThat(rendered).doesNotContain(BODY)
        assertThat(rendered).doesNotContain(TITLE)
        // 파싱하지 않은 원문이라 UUID 라는 보장이 없다 — 사용자가 준 임의 문자열이 로그로 간다.
        assertThat(rendered).doesNotContain(RAW_WORKSPACE_ID)
        assertThat(rendered).contains(CONTENT_MASK)

        assertThat(rendered).contains("${BODY.length}자")
    }

    @Test
    @DisplayName("가리는 것은 toString 뿐이다 — 바인딩된 값은 그대로다")
    fun `요청 값 자체는 본문을 그대로 담는다`() {
        val request = DocumentTextRequest(BODY, TITLE, null)

        assertThat(request.text).isEqualTo(BODY)
        assertThat(request.title).isEqualTo(TITLE)
    }

    @Test
    @DisplayName("응답 DTO 에는 가릴 것이 없다 — 식별자·상태·문자 수뿐이다")
    fun `응답 DTO 가 본문을 담지 않는다`() {
        // `of` 가 유일한 조립 지점이다 — 주 생성자가 `private` 이라 테스트도 우회하지 못한다.
        val response =
            DocumentCreatedResponse.of(
                AcceptedUpload(
                    documentId = UUID.randomUUID(),
                    conversionId = UUID.randomUUID(),
                    status = ConversionStatus.PENDING,
                    charCount = BODY.length,
                ),
            )

        assertThat(response.toString()).doesNotContain(BODY)
        assertThat(response.toString()).doesNotContain(TITLE)
    }

    @Test
    @DisplayName("X2 api 의 어떤 타입도 주 생성자에 저장·평문 타입을 들지 않는다 (분모 비어 있지 않음 포함)")
    fun `웹 표현 타입이 평문 타입을 들지 않는다`() {
        val apiTypes = apiTypes()
        assertThat(apiTypes)
            .withFailMessage("api 모듈 타입을 하나도 찾지 못했다 — 이 대조는 아무것도 재지 않는다")
            .isNotEmpty()

        val undecidable = apiTypes.filter { runCatching { parameterTypesOf(it) }.isFailure }
        assertThat(undecidable.map { it.qualifiedName })
            .withFailMessage("주 생성자를 읽지 못한 타입이 있다 — **판정 불가는 통과가 아니다**: %s", undecidable.map { it.qualifiedName })
            .isEmpty()

        val violations =
            apiTypes.flatMap { type ->
                forbiddenIn(type).map { "${type.qualifiedName} 의 주 생성자가 ${it.simpleName} 를 든다" }
            }

        assertThat(violations)
            .withFailMessage(
                "웹 요청·응답 타입이 저장·평문 타입을 들고 있다 — value class 는 Jackson 이 **그냥 문자열로** 내보낸다:\n%s",
                violations.joinToString("\n") { "  - $it" },
            ).isEmpty()
    }

    @Test
    @DisplayName("X2 판정 함수가 실제로 지목한다 — 합성 표본으로 확인한다 (「위반 0건」이 공허하지 않다)")
    fun `판정 함수가 위반을 지목한다`() {
        assertThat(forbiddenIn(ForbiddenProbe::class).map { it.simpleName })
            .withFailMessage("판정 함수가 명백한 위반을 지목하지 못했다 — 위 케이스의 초록은 아무 뜻이 없다")
            .containsExactlyInAnyOrder(PlainBody::class.simpleName, EncryptedContent::class.simpleName)

        assertThat(forbiddenIn(DocumentCreatedResponse::class)).isEmpty()
    }

    @Test
    @DisplayName("X2 금지 타입 목록이 실재하는 타입만 담는다 — 이름이 바뀌면 컴파일이 먼저 깨진다")
    fun `금지 목록이 실재한다`() {
        assertThat(FORBIDDEN_TYPES).hasSize(EXPECTED_FORBIDDEN_TYPES)
        assertThat(FORBIDDEN_TYPES.map { it.qualifiedName }).doesNotContainNull()
    }

    /** [type] 의 주 생성자가 드는 금지 타입들. 판정은 이 함수 하나다. */
    private fun forbiddenIn(type: KClass<*>): List<KClass<*>> =
        parameterTypesOf(type).filter { it in FORBIDDEN_TYPES }.distinct()

    /** 주 생성자 파라미터의 선언 타입. */
    private fun parameterTypesOf(type: KClass<*>): List<KClass<*>> =
        type.primaryConstructor
            ?.parameters
            .orEmpty()
            .mapNotNull { it.type.classifier as? KClass<*> }

    /** `api` 모듈이 선언한 타입 전부. DTO 로 좁히지 않는다 — 좁힘 자체가 검사받지 않는 제외가 된다. */
    private fun apiTypes(): List<KClass<*>> =
        ProductClasses.onTestRuntimeClasspath().filter { it.qualifiedName?.startsWith(API_PACKAGE) == true }

    /** 합성 위반 표본. 제품 코드가 아니라 이 파일 안에 있다 — 분모를 오염시키지 않는다. */
    private data class ForbiddenProbe(
        val body: PlainBody,
        val sealed: EncryptedContent,
        val id: UUID,
    )

    private companion object {
        const val API_PACKAGE = "kr.easydoc.api."

        const val BODY = "주민등록번호가 들어 있을 수도 있는 사용자 문서 본문"
        const val TITLE = "복지 안내문 초안"

        /** UUID 가 **아닌** 값을 쓴다 — 이 필드가 파싱되지 않은 원문임을 표본이 함께 말한다. */
        const val RAW_WORKSPACE_ID = "작업공간-원문-표본"

        /** 웹 표현에 실려서는 안 되는 타입들. */
        val FORBIDDEN_TYPES: Set<KClass<*>> =
            setOf(
                PlainBody::class,
                MaskedText::class,
                ModelDraft::class,
                ReviewedBody::class,
                EncryptedContent::class,
            )

        const val EXPECTED_FORBIDDEN_TYPES = 5
    }
}
