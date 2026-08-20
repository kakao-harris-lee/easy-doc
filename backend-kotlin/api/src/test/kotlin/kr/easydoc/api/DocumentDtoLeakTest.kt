package kr.easydoc.api

import kr.easydoc.api.document.DocumentCreatedResponse
import kr.easydoc.api.document.DocumentTextRequest
import kr.easydoc.api.support.ProductClasses
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

/**
 * 문서 DTO 의 두 축 — **`toString()` 유출**과 **X2(저장·평문 타입이 웹 표현에 실리지 않음)**.
 *
 * ## 왜 두 축이 한 파일에 있는가
 *
 * 둘 다 「사용자 본문이 의도치 않은 통로로 나가는 것」을 막는다. 앞은 **로그**로, 뒤는
 * **응답 본문**으로 나가는 통로다. `AuthDtoLeakTest`·`WorkspaceDtoLeakTest` 가 앞 축만
 * 가진 것은 그 도메인에 감쌈 타입이 없기 때문이고, 문서 도메인에서 처음 둘이 만난다.
 *
 * ## X2 를 애너테이션이 아니라 **타입 부재**로 강제하는 이유
 *
 * `PlainBody` 는 `@JvmInline value class(String)` 이라 Jackson 이 **그냥 문자열로
 * 직렬화한다** — 응답 DTO 가 그것을 들면 봉인 전 평문이 그대로 나간다. `@JsonIgnore` 같은
 * 애너테이션으로 막으면 **새 DTO 가 안 붙이면 조용히 샌다**(`CLAUDE.md` 규칙 4 — 은폐형이
 * 아니라 탐지형). 타입 부재 단언은 반대 방향이다: 새 DTO 가 그 타입을 드는 순간 빨개진다.
 *
 * ## 판정 함수를 **실행으로** 확인한다
 *
 * 「위반 0건」은 판정 함수가 아무것도 못 보고 있어도 참이다. 그래서 합성 표본을 같은
 * 판정에 먹여 **지목되는지**를 함께 본다 — 이 저장소가 반복해 겪은 「재지 않은 초록」을
 * 막는 형태다.
 */
class DocumentDtoLeakTest {
    // ================================================================ toString 유출

    @Test
    @DisplayName("요청 DTO 의 toString 이 본문도 제목도 노출하지 않는다")
    fun `요청 DTO 가 본문을 가린다`() {
        val rendered = DocumentTextRequest(BODY, TITLE, UUID.randomUUID()).toString()

        // 역직렬화·검증 실패의 진단 로그가 요청 객체를 통째로 찍는 것이 가장 흔한 형태다.
        assertThat(rendered).doesNotContain(BODY)
        assertThat(rendered).doesNotContain(TITLE)
        assertThat(rendered).contains(CONTENT_MASK)
        // 길이는 남는다 — 로그에 허용된 것이 "문서 ID·길이·처리 상태"까지다.
        assertThat(rendered).contains("${BODY.length}자")
    }

    @Test
    @DisplayName("가리는 것은 toString 뿐이다 — 바인딩된 값은 그대로다")
    fun `요청 값 자체는 본문을 그대로 담는다`() {
        val request = DocumentTextRequest(BODY, TITLE, null)

        // 여기까지 가리면 업로드가 빈 본문으로 저장된다. 두 축을 섞지 않는다.
        assertThat(request.text).isEqualTo(BODY)
        assertThat(request.title).isEqualTo(TITLE)
    }

    @Test
    @DisplayName("응답 DTO 에는 가릴 것이 없다 — 식별자·상태·문자 수뿐이다")
    fun `응답 DTO 가 본문을 담지 않는다`() {
        val response =
            DocumentCreatedResponse(
                documentId = UUID.randomUUID().toString(),
                conversionId = UUID.randomUUID().toString(),
                status = ConversionStatus.PENDING.wireName,
                charCount = BODY.length,
            )

        // 계약 `DocumentCreatedResponse.required` 가 네 필드뿐이라는 사실의 다른 얼굴이다.
        assertThat(response.toString()).doesNotContain(BODY)
        assertThat(response.toString()).doesNotContain(TITLE)
    }

    // ================================================================ X2 — 저장·평문 타입 부재

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
        // 위 케이스의 「0건」은 판정이 아무것도 못 보고 있어도 참이다. 같은 함수에
        // 위반 표본을 먹여 **지목되는지**를 실행으로 확인한다.
        assertThat(forbiddenIn(ForbiddenProbe::class).map { it.simpleName })
            .withFailMessage("판정 함수가 명백한 위반을 지목하지 못했다 — 위 케이스의 초록은 아무 뜻이 없다")
            .containsExactlyInAnyOrder(PlainBody::class.simpleName, EncryptedContent::class.simpleName)

        // 정상 DTO 는 지목하지 않는다(과잉 탐지 0).
        assertThat(forbiddenIn(DocumentCreatedResponse::class)).isEmpty()
    }

    @Test
    @DisplayName("X2 금지 타입 목록이 실재하는 타입만 담는다 — 이름이 바뀌면 컴파일이 먼저 깨진다")
    fun `금지 목록이 실재한다`() {
        // 목록을 문자열로 두면 타입 이름이 바뀔 때 조용히 아무것도 안 겨눈다.
        // `KClass` 로 들면 그 변경이 **컴파일 오류**로 먼저 드러난다.
        assertThat(FORBIDDEN_TYPES).hasSize(EXPECTED_FORBIDDEN_TYPES)
        assertThat(FORBIDDEN_TYPES.map { it.qualifiedName }).doesNotContainNull()
    }

    // ================================================================ 판정

    /** [type] 의 주 생성자가 드는 금지 타입들. 판정은 **이 함수 하나**다. */
    private fun forbiddenIn(type: KClass<*>): List<KClass<*>> =
        parameterTypesOf(type).filter { it in FORBIDDEN_TYPES }.distinct()

    /**
     * 주 생성자 파라미터의 **선언 타입**.
     *
     * JVM 반사가 아니라 Kotlin 반사를 쓴다 — `@JvmInline value class` 파라미터는 JVM
     * 시그니처에서 **풀려 버려**(`PlainBody` 가 `String` 이 된다) 자바 반사로는 보이지 않는다.
     * 이 저장소가 같은 이유로 한 번 놓친 자리가 있다(게이트 24 privacy-gate A-3′).
     */
    private fun parameterTypesOf(type: KClass<*>): List<KClass<*>> =
        type.primaryConstructor
            ?.parameters
            .orEmpty()
            .mapNotNull { it.type.classifier as? KClass<*> }

    /** `api` 모듈이 선언한 타입 전부. DTO 로 좁히지 않는다 — 좁힘 자체가 검사받지 않는 제외가 된다. */
    private fun apiTypes(): List<KClass<*>> =
        ProductClasses.onTestRuntimeClasspath().filter { it.qualifiedName?.startsWith(API_PACKAGE) == true }

    /** 합성 위반 표본. **제품 코드가 아니라 이 파일 안에** 있다 — 분모를 오염시키지 않는다. */
    private data class ForbiddenProbe(
        val body: PlainBody,
        val sealed: EncryptedContent,
        val id: UUID,
    )

    private companion object {
        const val API_PACKAGE = "kr.easydoc.api."

        const val BODY = "주민등록번호가 들어 있을 수도 있는 사용자 문서 본문"
        const val TITLE = "복지 안내문 초안"

        /**
         * 웹 표현에 실려서는 안 되는 타입들.
         *
         * 앞 넷은 **본문을 감싼 value class** 라 Jackson 이 문자열로 펴 버리고, 마지막은
         * 암호문 바이트다(로그·응답 어디에도 나가면 안 된다 — `CLAUDE.md` 보안 규칙의
         * 허용목록은 "문서 ID·길이·처리 상태까지"다).
         */
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
