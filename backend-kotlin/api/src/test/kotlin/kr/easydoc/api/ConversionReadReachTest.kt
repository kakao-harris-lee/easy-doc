package kr.easydoc.api

import kr.easydoc.api.support.ContractSpec
import kr.easydoc.api.support.MultipartBody
import kr.easydoc.api.support.OwnershipConcealment
import kr.easydoc.api.support.UploadFixtures
import kr.easydoc.application.crypto.ContentCipher
import kr.easydoc.core.crypto.EncryptedField
import kr.easydoc.core.crypto.PlainBody
import kr.easydoc.core.document.ConversionStatus
import kr.easydoc.core.document.FormatPreservationStatus
import kr.easydoc.core.document.SourceFormat
import kr.easydoc.core.privacy.MaskCategory
import kr.easydoc.core.privacy.MaskedItem
import kr.easydoc.core.security.Secret
import kr.easydoc.infrastructure.DatabaseHandle
import kr.easydoc.infrastructure.PostgresTestSupport
import kr.easydoc.infrastructure.document.MaskedItemCodec
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID

/** `GET /conversions/{conversion_id}` 의 실측 계약 — 명세 CR 표의 C-R·C-I 계층. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["easydoc.auth.jwt-secret=$CONVERSION_READ_TEST_SECRET"],
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConversionReadReachTest {
    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var cipher: ContentCipher

    private val json = ObjectMapper()

    @Test
    @DisplayName("CR-2 계약 `ConversionStatus.enum` 의 **각 값**을 실제로 밟고, 그 전부에서 키가 하나도 생략되지 않는다 (X-E2·X-E4)")
    fun `상태 네 값 전부에서 키 집합이 계약과 같다`() {
        val token = newAccount()

        val declaredStatuses = ContractSpec.schemaEnum(STATUS_SCHEMA)
        val declaredKeys = ContractSpec.schemaRequired(CONVERSION_SCHEMA)

        val observed =
            declaredStatuses.map { status ->
                val conversionId = createDocument(token).second
                if (status != PENDING_STATUS) forceStatus(conversionId, status)
                val body = bodyOf(read(token, conversionId))
                assertThat(body.keys.map { it.toString() }.toSet())
                    .withFailMessage("상태 %s 응답의 키 집합이 계약 %s 와 다르다: %s", status, CONVERSION_SCHEMA, body.keys)
                    .isEqualTo(declaredKeys)
                body[STATUS_PROPERTY].toString()
            }

        assertThat(observed.toSet()).isEqualTo(declaredStatuses.toSet())
    }

    @Test
    @DisplayName("CR-3 완료 전 상태에서 배열 필드 둘이 `null` 이 아니라 **빈 배열**이다 (X-E3)")
    fun `완료 전에는 빈 배열이다`() {
        val token = newAccount()
        val beforeDone = ContractSpec.schemaEnum(STATUS_SCHEMA).filterNot { it == DONE_STATUS }
        assertThat(beforeDone).describedAs("완료 전 상태가 계약에 하나도 없다 — 이 케이스가 성립하지 않는다").isNotEmpty()

        beforeDone.forEach { status ->
            val conversionId = createDocument(token).second
            if (status != PENDING_STATUS) forceStatus(conversionId, status)

            val body = bodyOf(read(token, conversionId))

            assertThat(body[MASKED_ITEMS_PROPERTY])
                .withFailMessage(
                    "상태 %s 의 %s 가 빈 배열이 아니다: %s",
                    status,
                    MASKED_ITEMS_PROPERTY,
                    body[MASKED_ITEMS_PROPERTY],
                ).isEqualTo(emptyList<Any>())
            assertThat(body[MISSING_PLACEHOLDERS_PROPERTY])
                .withFailMessage("상태 %s 의 %s 가 빈 배열이 아니다", status, MISSING_PLACEHOLDERS_PROPERTY)
                .isEqualTo(emptyList<Any>())
            assertThat(body[EASY_TEXT_PROPERTY]).describedAs("완료 전인데 초안이 실렸다").isNull()
            assertThat(body[EDITED_TEXT_PROPERTY]).describedAs("완료 전인데 검수본이 실렸다").isNull()
        }
    }

    /** CR-3 의 팔은 결과 열이 NULL 인 행만 밟아 **공허하게 통과한다**. 이 케이스가 메운다. */
    @Test
    @DisplayName("CR-3b 완료 전 상태가 결과 열을 **들고 있어도** 결과 필드 아홉이 비어 나가고 **키는 하나도 생략되지 않는다**")
    fun `완료 전 상태는 저장된 결과를 내보내지 않는다`() {
        val token = newAccount()
        val beforeDone = ContractSpec.schemaEnum(STATUS_SCHEMA).filterNot { it == DONE_STATUS }
        assertThat(beforeDone).describedAs("완료 전 상태가 계약에 하나도 없다 — 이 케이스가 성립하지 않는다").isNotEmpty()
        // 분모가 계약 enum 이라 구현 상수가 좁히지 못한다. 그 상수 자체는 여기서 계약과 대조한다.
        assertThat(ConversionStatus.entries.filter { it.exposesResult }.map { it.wireName })
            .withFailMessage("결과를 내보내는 상태가 계약 `%s` 하나가 아니다", DONE_STATUS)
            .containsExactly(DONE_STATUS)
        val label = ContractSpec.schemaPropertyEnum(MASKED_ITEM_SCHEMA, CATEGORY_PROPERTY).first()
        val category = MaskCategory.entries.first { it.label == label }
        val placeholder = "[[${label}1]]"
        val item = MaskedItem(category, placeholder, Secret(HIDDEN_ORIGINAL))
        val stored =
            DoneResult(STORED_DRAFT, STORED_EDITED, listOf(item), listOf(placeholder), reviewed = true)
        val emptyArrays = setOf(MASKED_ITEMS_PROPERTY, MISSING_PLACEHOLDERS_PROPERTY)

        beforeDone.forEach { status ->
            val (documentId, conversionId) = createDocument(token)
            markDone(conversionId, stored)
            forceStatus(conversionId, status)

            val response = read(token, conversionId)

            assertDeclaredStatus(response, ContractSpec.successStatus(CONVERSION_ITEM_PATH, GET))
            val body = bodyOf(response)
            // ① 완료 전에도 **나가는** 두 필드가 서로 뒤바뀌지 않았다. 둘 다 UUID 라 오배정이
            // 타입으로 드러나지 않고, 아래 ②③ 은 값이 아니라 키·비어 있음만 본다.
            assertThat(body[ID_PROPERTY])
                .withFailMessage("상태 %s 응답의 id 가 변환 식별자가 아니다 — 문서 식별자와 뒤바뀌었는지 보라", status)
                .isEqualTo(conversionId.toString())
            assertThat(body[DOCUMENT_ID_PROPERTY])
                .withFailMessage("상태 %s 응답의 document_id 가 문서 식별자가 아니다 — 변환 식별자와 뒤바뀌었는지 보라", status)
                .isEqualTo(documentId.toString())
            // ② `required` 를 깨지 않는다 — 생략이 아니라 `null`·빈 배열이어야 한다.
            assertThat(body.keys.map { it.toString() }.toSet())
                .withFailMessage("상태 %s 응답이 키를 생략했다 — 계약 required 위반이다: %s", status, body.keys)
                .isEqualTo(ContractSpec.schemaRequired(CONVERSION_SCHEMA))
            // 첫 위반에서 멈추지 않고 **전부 모은다** — 무엇이 남았는지 한 번에 드러난다.
            val carrying =
                (ContractSpec.schemaRequired(CONVERSION_SCHEMA) - BEFORE_DONE_FIELDS).filter { field ->
                    body[field] != (if (field in emptyArrays) emptyList<Any>() else null)
                }
            assertThat(carrying)
                .withFailMessage("상태 %s 에서 비어 있지 않은 결과 필드: %s", status, carrying)
                .isEmpty()
            assertThat(response.body())
                .withFailMessage("상태 %s 응답이 저장된 값을 담았다", status)
                .doesNotContain(HIDDEN_ORIGINAL)
                .doesNotContain(STORED_DRAFT)
                .doesNotContain(STORED_EDITED)
                .doesNotContain(STORED_MODEL)
                .doesNotContain(STORED_PROVIDER)
        }
    }

    @Test
    @DisplayName("CR-4 실패 변환의 실패 코드가 비어 있지 않고 계약 `maxLength` 안이며, **본문·모델 응답이 담기지 않는다**")
    fun `실패 코드가 본문을 담지 않는다`() {
        val token = newAccount()
        val body = "실패 재현용 안내문 본문 — 이 문장이 실패 코드에 실리면 안 된다"
        val conversionId = createDocument(token, body).second
        forceStatus(conversionId, FAILED_STATUS, failureCode = "ProviderUnavailable")

        val response = bodyOf(read(token, conversionId))

        val code = response[FAILURE_CODE_PROPERTY]?.toString()
        assertThat(code).describedAs("실패 상태인데 실패 코드가 비었다 — 사용자가 사유를 알 수 없다").isNotBlank()

        val maxLength =
            ContractSpec.number(
                "components",
                "schemas",
                CONVERSION_SCHEMA,
                "properties",
                FAILURE_CODE_PROPERTY,
                "maxLength",
            )
        assertThat(code!!.length).isLessThanOrEqualTo(maxLength)

        assertThat(code).doesNotContain("안내문").doesNotContain(body)
    }

    @Test
    @DisplayName("CR-5 마스킹 항목의 키 집합이 정확히 계약 required · 범주가 **2종 집합 안**이고 그 밖의 값 0건 · 자리표시자가 계약 pattern 과 맞다 (P-32)")
    fun `마스킹 항목이 실제 저장 형식을 거쳐 계약대로 나온다`() {
        val token = newAccount()
        val conversionId = createDocument(token).second

        val declaredCategories = ContractSpec.schemaPropertyEnum(MASKED_ITEM_SCHEMA, CATEGORY_PROPERTY)
        val items =
            declaredCategories.mapIndexed { index, label ->
                val category = MaskCategory.entries.first { it.label == label }
                MaskedItem(category, "[[$label${index + 1}]]", Secret("가려진값${index + 1}"))
            }
        markDone(conversionId, DoneResult(maskedItems = items))

        val body = bodyOf(read(token, conversionId))
        val responseItems = body[MASKED_ITEMS_PROPERTY] as List<*>

        assertThat(responseItems).hasSameSizeAs(items)
        val declaredKeys = ContractSpec.schemaRequired(MASKED_ITEM_SCHEMA)
        val pattern = Regex(ContractSpec.schemaPropertyPattern(MASKED_ITEM_SCHEMA, PLACEHOLDER_PROPERTY)).toPattern()
        responseItems.forEach { raw ->
            val item = raw as Map<*, *>
            assertThat(item.keys.map { it.toString() }.toSet()).isEqualTo(declaredKeys)
            assertThat(item[PLACEHOLDER_PROPERTY].toString()).matches(pattern)
        }

        assertThat(responseItems.map { (it as Map<*, *>)[CATEGORY_PROPERTY].toString() }.toSet())
            .withFailMessage("범주 값이 계약 enum 과 다르다 — 저장 키가 화면 문구 자리로 샜을 수 있다")
            .isEqualTo(declaredCategories.toSet())

        assertThat(responseItems.map { (it as Map<*, *>)[ORIGINAL_PROPERTY].toString() })
            .containsExactlyInAnyOrderElementsOf(items.map { it.original.reveal() })
    }

    @Test
    @DisplayName("CR-6 유실 자리표시자의 각 원소가 계약 `items.pattern` 과 맞다")
    fun `유실 라벨이 계약 형식을 지킨다`() {
        val token = newAccount()
        val conversionId = createDocument(token).second
        val labels = ContractSpec.schemaPropertyEnum(MASKED_ITEM_SCHEMA, CATEGORY_PROPERTY).map { "[[${it}1]]" }
        markDone(conversionId, DoneResult(missingPlaceholders = labels))

        val body = bodyOf(read(token, conversionId))

        val pattern =
            Regex(
                ContractSpec.schemaPropertyPattern(CONVERSION_SCHEMA, MISSING_PLACEHOLDERS_PROPERTY),
            ).toPattern()
        val observed = (body[MISSING_PLACEHOLDERS_PROPERTY] as List<*>).map { it.toString() }
        assertThat(observed).hasSameSizeAs(labels)
        observed.forEach { assertThat(it).matches(pattern) }
    }

    @Test
    @DisplayName("완료 변환의 초안·검수본이 **복호화되어** 그대로 나온다 — 봉인 왕복이 HTTP 표면에서 성립한다")
    fun `완료 변환의 본문이 왕복한다`() {
        val token = newAccount()
        val conversionId = createDocument(token).second
        markDone(conversionId, DoneResult(easyText = "쉬운 글 초안입니다.", editedText = "담당자가 다듬은 문장입니다."))

        val body = bodyOf(read(token, conversionId))

        assertThat(body[EASY_TEXT_PROPERTY]).isEqualTo("쉬운 글 초안입니다.")
        assertThat(body[EDITED_TEXT_PROPERTY]).isEqualTo("담당자가 다듬은 문장입니다.")
        // 심은 값이 **실제로 나온다**는 것을 여기서 못박는다. 이 단언이 없으면 CR-3b 의
        // `doesNotContain(STORED_MODEL)` 은 「그 열을 아무도 채우지 않아서」도 초록이다.
        assertThat(body[MODEL_PROPERTY])
            .withFailMessage("완료 변환이 심은 모델 이름을 내보내지 않는다 — CR-3b 의 미노출 단언이 공허해진다")
            .isEqualTo(STORED_MODEL)
        assertThat(body[PROVIDER_NAME_PROPERTY])
            .withFailMessage("완료 변환이 심은 provider 이름을 내보내지 않는다 — 위와 같은 이유로 공허해진다")
            .isEqualTo(STORED_PROVIDER)
    }

    /** 변조 팔. 문구는 코드에 박지 않고 **계약에서 읽어** 대조한다. */
    @Test
    @DisplayName(
        "변조된 암호문은 거절된다 — 계약이 선언한 상태 · 평문·암호문 미노출 · " +
            "저장 실패 문구가 바뀌면 계약 예시도 함께 갱신한다",
    )
    fun `변조된 암호문은 거절된다`() {
        val token = newAccount()
        val conversionId = createDocument(token).second
        val plaintext = "봉인 왕복 확인용 초안입니다."

        // 결속을 깬다 — AAD 의 행 식별자만 다른 암호문을 넣는다(바이트를 뒤집는 UPDATE 는
        // `EnvelopeColumnWriteGuardTest` 의 규약에 걸린다).
        markDone(conversionId, DoneResult(easyText = plaintext, sealAs = UUID.randomUUID()))
        val other = createDocument(token).second
        markDone(other, DoneResult(easyText = "$plaintext 다른 행", sealAs = UUID.randomUUID()))

        val response = read(token, conversionId)

        assertDeclaredStatus(response, INTERNAL_ERROR)
        assertThat(bodyOf(response)[DETAIL])
            .withFailMessage("변조 응답의 detail 이 문자열이 아니다 — 구현 수단이 응답으로 샐 자리다")
            .isInstanceOf(String::class.java)
        // 계약은 이 갈래의 값을 저장소에 위임했다 — 규범은 값이 아니라 성질(500·문자열·고정·단일 키)이다.
        // 그러므로 이 대조의 방향은 「예시가 코드를 따른다」이고, 갈리면 갱신 대상은 계약 예시다.
        // 읽을 예시가 없으면 대조가 공허해지므로 `responseExampleDetail` 이 그 자리에서 끊는다.
        assertThat(bodyOf(response)[DETAIL])
            .withFailMessage(
                "계약 예시 %s.%s 가 낡았다 — **예시를 갱신하라. 계약 위반이 아니다.** " +
                    "이 갈래의 값은 계약이 저장소에 위임한 것이고, 규범은 500·문자열·고정·단일 키라는 성질뿐이다",
                INTERNAL_ERROR_COMPONENT,
                STORAGE_EXAMPLE,
            ).isEqualTo(ContractSpec.responseExampleDetail(INTERNAL_ERROR_COMPONENT, STORAGE_EXAMPLE))
        assertThat(response.body())
            .withFailMessage("변조 응답에 평문이 실렸다 — 거절 경로가 본문을 흘린다")
            .doesNotContain(plaintext)
        assertThat(bodyOf(read(token, other))[DETAIL])
            .withFailMessage("두 변조 행의 detail 이 다르다 — 문구가 입력에 좌우된다는 뜻이다")
            .isEqualTo(bodyOf(response)[DETAIL])
    }

    @Test
    @DisplayName("CR-7 타인 소유 변환 → **404 이고 403 이 아니다** · detail 이 계약 404 예시와 같다 (X-B1)")
    fun `타인 변환 조회는 404 이고 403 이 아니다`() {
        val theirConversion = createDocument(newAccount()).second

        val response = read(newAccount(), theirConversion.toString())

        assertThat(response.statusCode()).isNotEqualTo(FORBIDDEN)
        assertDeclaredStatus(response, NOT_FOUND)
        assertThat(bodyOf(response)[DETAIL])
            .isEqualTo(ContractSpec.pathExampleDetail(CONVERSION_ITEM_PATH, GET, NOT_FOUND, NOT_FOUND_EXAMPLE))
    }

    @Test
    @DisplayName("CR-8 없는 식별자와 타인 식별자의 **상태·본문 원시 바이트·헤더 이름 집합이 완전히 같다** (X-B2)")
    fun `없는 것과 남의 것이 구분되지 않는다`() {
        val mine = newAccount()
        val theirConversion = createDocument(newAccount()).second

        val absent = readBytes(mine, UUID.randomUUID().toString())
        val others = readBytes(mine, theirConversion.toString())

        OwnershipConcealment.assertIndistinguishable("GET $CONVERSION_ITEM_PATH", absent, others)
    }

    @Test
    @DisplayName(
        "CR-10 Authorization 이 없으면 401 · `WWW-Authenticate` · 본문 키 집합 정확히 `ErrorResponse.required` (X-A1·X-C8)",
    )
    fun `토큰이 없으면 401 이다`() {
        val response = read(token = null, conversionId = UUID.randomUUID().toString())

        assertDeclaredStatus(response, UNAUTHORIZED)
        assertThat(response.headers().firstValue(WWW_AUTHENTICATE))
            .withFailMessage("401 에 WWW-Authenticate 가 없다 — 클라이언트가 재인증 방식을 알 수 없다")
            .hasValue(ContractSpec.headerConst(WWW_AUTHENTICATE_COMPONENT))
        assertThat(bodyOf(response).keys.map { it.toString() }.toSet())
            .withFailMessage("401 본문 키가 계약 %s 와 다르다 — 구현 수단이 응답으로 샌다", ERROR_SCHEMA)
            .isEqualTo(ContractSpec.schemaRequired(ERROR_SCHEMA))
    }

    /** DD-5 — 삭제 후 변환 조회가 404 다. C5 가 이 팔을 유보한 자리다. */
    @Test
    @DisplayName("DD-5 문서를 파기하면 그 변환 조회가 404 다 — **매핑 부재 404 와 본문이 다르다**")
    fun `삭제 후 변환 조회가 파기 404 를 낸다`() {
        val token = newAccount()
        val (documentId, conversionId) = createDocument(token)

        assertDeclaredStatus(
            read(token, conversionId.toString()),
            ContractSpec.successStatus(CONVERSION_ITEM_PATH, GET),
        )

        val deleted = send(deleteRequest(token, documentId.toString()))
        check(deleted.statusCode() == ContractSpec.successStatus(DOCUMENT_ITEM_PATH, DELETE)) {
            "문서 파기가 실패했다: ${deleted.statusCode()}"
        }

        val afterDelete = read(token, conversionId.toString())
        assertDeclaredStatus(afterDelete, NOT_FOUND)

        assertThat(bodyOf(afterDelete)[DETAIL])
            .isEqualTo(ContractSpec.pathExampleDetail(CONVERSION_ITEM_PATH, GET, NOT_FOUND, NOT_FOUND_EXAMPLE))

        val unmapped = send(getRequest(token, "$CONVERSION_PATH_PREFIX$conversionId/$UNMAPPED_SEGMENT"))
        assertThat(unmapped.statusCode()).isEqualTo(NOT_FOUND)
        assertThat(unmapped.body())
            .withFailMessage(
                "파기 404 와 **매핑 부재** 404 의 본문이 같다 — 이 케이스는 「핸들러가 없어서 404」를 " +
                    "「파기됐으니 404」로 읽고 있다. 파기: %s / 매핑 부재: %s",
                afterDelete.body(),
                unmapped.body(),
            ).isNotEqualTo(afterDelete.body())
    }

    /** 결과 열을 채워 완료 상태로 만든다. 워커가 할 일을 SQL 로 대신한다. */
    private fun markDone(
        conversionId: UUID,
        result: DoneResult = DoneResult(),
    ) {
        val sealAs = result.sealAs ?: conversionId
        val easy = sealed(result.easyText, sealAs, EncryptedField.CONVERSION_EASY_TEXT)
        val edited = sealed(result.editedText, sealAs, EncryptedField.CONVERSION_EDITED_TEXT)
        val table =
            result.maskedItems
                .takeIf { it.isNotEmpty() }
                ?.let { codec.encode(it).value }
        val masked = sealed(table, sealAs, EncryptedField.CONVERSION_MASKED_ITEMS)
        val labels = json.writeValueAsString(result.missingPlaceholders).replace("'", "''")

        // 규약: SQL 은 **companion 의 상수 리터럴**에 두고 조각만 채운다. 호출부에서 조립하면
        // 스캐너와 `EnvelopeColumnWriteGuardTest` 가 함께 눈을 감는다 — 근거는
        // `docs/migration/_workspace/04_kotlin-implementer_c6-test-sql-constraints.md`
        // (제거됨, git 태그 `pre-python-removal-20260824`에서 열람 가능).
        database.execute(
            MARK_DONE_SQL.format(
                easy,
                edited,
                masked,
                cipher.writeScheme,
                cipher.writeKeyVersion,
                labels,
                if (result.reviewed) "now()" else "NULL",
                STORED_MODEL,
                STORED_PROVIDER,
                conversionId,
            ),
        )
    }

    // ============================================================== 형식 셋 (§6.5)

    @Test
    @DisplayName("CF-1 붙여넣기 → `text` · `txt` · `not_applicable` — 원본 파일이 없으니 유지할 서식이 없다")
    fun `붙여넣기의 형식 셋이 계약대로 나온다`() {
        val token = newAccount()
        val conversionId = createDocument(token).second

        val body = bodyOf(read(token, conversionId))

        assertFormatTripleInContract(body)
        assertThat(body.getValue(SOURCE_FORMAT_PROPERTY)).isEqualTo(SourceFormat.TEXT.wireName)
        assertThat(body[EXPORT_FORMAT_PROPERTY])
            .describedAs("붙여넣기는 UTF-8 텍스트로 내려받는다(DESIGN.md §6.5 표)")
            .isEqualTo(ContractSpec.exportFormatDerivation()[SourceFormat.TEXT.wireName])

        val preservation = body[FORMAT_PRESERVATION_PROPERTY] as? Map<*, *>
        assertThat(preservation).describedAs("붙여넣기는 서버가 확실히 아는 갈래다 — `null` 로 접으면 안 된다").isNotNull()
        assertThat(preservation!![STATUS_PROPERTY])
            .isEqualTo(FormatPreservationStatus.NOT_APPLICABLE.wireName)
        assertThat(preservation[DETAILS_PROPERTY]).isEqualTo(emptyList<String>())
    }

    @Test
    @DisplayName(
        "CF-2 업로드한 문서는 형식이 그대로 나가고, 원본이 저장되는 형식은 **완료 전에는 서식 유지를 판정하지 않는다**(`null`)",
    )
    fun `업로드 문서의 형식 셋이 계약대로 나온다`() {
        val derivation = ContractSpec.exportFormatDerivation()
        val uploads =
            mapOf(
                SourceFormat.DOCX to ("안내문.docx" to UploadFixtures.sampleDocx()),
                SourceFormat.HWPX to ("안내문.hwpx" to UploadFixtures.sampleHwpx()),
                SourceFormat.PDF to ("안내문.pdf" to UploadFixtures.samplePdf()),
                // txt 는 원본을 저장하지 않는다(TXT-UPLOAD, `DocumentService`) — 그래서 완료 전에도
                // `null`(아직 판정 못함)이 아니라 붙여넣기와 같은 `not_applicable` 이 즉시 나간다.
                SourceFormat.TXT to ("안내문.txt" to UploadFixtures.sampleTxt()),
            )
        assertThat(uploads.keys)
            .describedAs("업로드 형식 전부를 지나지 않으면 `export_format` 의 `null` 갈래가 대조를 받지 않는다")
            .containsExactlyInAnyOrderElementsOf(SourceFormat.UPLOAD_FORMATS)

        uploads.forEach { (format, file) ->
            val token = newAccount()
            val conversionId = uploadDocument(token, file.first, file.second)

            val body = bodyOf(read(token, conversionId))

            assertFormatTripleInContract(body)
            assertThat(body.getValue(SOURCE_FORMAT_PROPERTY)).isEqualTo(format.wireName)
            assertThat(body[EXPORT_FORMAT_PROPERTY])
                .withFailMessage(
                    "원본 %s 의 `export_format` 이 계약 유도표와 다르다: %s",
                    format.wireName,
                    body[EXPORT_FORMAT_PROPERTY],
                ).isEqualTo(derivation[format.wireName])
            if (format == SourceFormat.TXT) {
                val preservation = body[FORMAT_PRESERVATION_PROPERTY] as? Map<*, *>
                assertThat(preservation)
                    .describedAs("txt 는 원본이 없어 판정이 영구히 참이다 — 완료를 기다릴 이유가 없다")
                    .isNotNull()
                assertThat(preservation!![STATUS_PROPERTY]).isEqualTo(FormatPreservationStatus.NOT_APPLICABLE.wireName)
            } else {
                assertThat(body[FORMAT_PRESERVATION_PROPERTY])
                    .withFailMessage(
                        "원본 %s 가 아직 변환 중인데 서버가 서식 유지 상태를 지어냈다 — 짝지을 검수본이 없다: %s",
                        format.wireName,
                        body[FORMAT_PRESERVATION_PROPERTY],
                    ).isNull()
            }
        }
    }

    @Test
    @DisplayName("CF-3 원본 바이트가 없는 업로드 문서는 `not_applicable` 이다 — `document_originals` 가 서기 전 문서")
    fun `원본이 없는 업로드 문서는 유지 대상이 아니다`() {
        val token = newAccount()
        val conversionId = uploadDocument(token, "안내문.docx", UploadFixtures.sampleDocx())
        // 표가 서기 전(2026-08-26 이전)에 올라온 문서 재현 — 바이트만 사라지고 형식은 남는다.
        database.execute(
            "DELETE FROM document_originals WHERE document_id IN " +
                "(SELECT document_id FROM conversions WHERE id = '$conversionId')",
        )

        val body = bodyOf(read(token, conversionId))

        assertThat(body.getValue(SOURCE_FORMAT_PROPERTY)).isEqualTo(SourceFormat.DOCX.wireName)
        val preservation = body[FORMAT_PRESERVATION_PROPERTY] as? Map<*, *>
        assertThat(preservation)
            .describedAs("되살릴 원본이 사라진 것은 서버가 아는 사실이다 — 영원히 판정되지 않을 `null` 로 두면 안 된다")
            .isNotNull()
        assertThat(preservation!![STATUS_PROPERTY]).isEqualTo(FormatPreservationStatus.NOT_APPLICABLE.wireName)
    }

    @Test
    @DisplayName("CF-5 완료된 DOCX 는 **원본을 실제로 열어** 판정한다 — 짝이 맞으면 `available`")
    fun `완료된 업로드 문서가 원본으로 판정된다`() {
        val token = newAccount()
        val conversionId = uploadDocument(token, "안내문.docx", UploadFixtures.sampleDocx())
        // `sample.docx` 의 본문 단위는 둘이다(추출 결과 두 줄, 머리글·바닥글 파트 없음).
        markDone(conversionId, DoneResult(easyText = "쉬운 제목입니다.\n쉬운 본문입니다."))

        val body = bodyOf(read(token, conversionId))

        val preservation = body[FORMAT_PRESERVATION_PROPERTY] as? Map<*, *>
        assertThat(preservation).describedAs("원본이 있고 검수본도 있는데 판정하지 않았다").isNotNull()
        assertThat(preservation!![STATUS_PROPERTY])
            .withFailMessage("원본 단위 수와 문단 수가 같은데 「유지 가능」이 아니다: %s", preservation)
            .isEqualTo(FormatPreservationStatus.AVAILABLE.wireName)
        assertThat(preservation[DETAILS_PROPERTY]).isEqualTo(emptyList<String>())
    }

    @Test
    @DisplayName("CF-6 문단 수가 원본과 다르면 `partial` 이고, 항목은 **개수만** 말한다")
    fun `문단 수가 다르면 일부 유지다`() {
        val token = newAccount()
        val conversionId = uploadDocument(token, "안내문.docx", UploadFixtures.sampleDocx())
        markDone(conversionId, DoneResult(easyText = "한 문단으로 합친 쉬운 글입니다."))

        val body = bodyOf(read(token, conversionId))

        val preservation = body.getValue(FORMAT_PRESERVATION_PROPERTY) as Map<*, *>
        assertThat(preservation[STATUS_PROPERTY]).isEqualTo(FormatPreservationStatus.PARTIAL.wireName)
        val details = preservation[DETAILS_PROPERTY] as List<*>
        assertThat(details).describedAs("「일부」라고 말해 놓고 무엇이 달라지는지 말하지 않았다").isNotEmpty()
        assertThat(details).allSatisfy { item ->
            assertThat(item.toString())
                .withFailMessage("영향 항목에 문서 본문 조각이 실렸다: %s", item)
                .doesNotContain("쉬운 글 변환 안내")
                .doesNotContain("추출 테스트용")
                .doesNotContain("한 문단으로 합친")
        }
    }

    @Test
    @DisplayName("CF-7 저장된 원본을 열 수 없으면 `failed` 다 — 사유를 항목으로 말한다")
    fun `열 수 없는 원본은 실패로 나간다`() {
        val token = newAccount()
        val conversionId = uploadDocument(token, "안내문.docx", UploadFixtures.sampleDocx())
        markDone(conversionId, DoneResult(easyText = "쉬운 제목입니다.\n쉬운 본문입니다."))
        breakStoredOriginal(token, conversionId)

        val body = bodyOf(read(token, conversionId))

        val preservation = body.getValue(FORMAT_PRESERVATION_PROPERTY) as Map<*, *>
        assertThat(preservation[STATUS_PROPERTY])
            .describedAs("열리지 않는 원본을 「유지 가능」이라 말하면 내려받기에서야 드러난다")
            .isEqualTo(FormatPreservationStatus.FAILED.wireName)
        assertThat(preservation[DETAILS_PROPERTY] as List<*>).isNotEmpty()
    }

    /**
     * 저장된 원본을 **열리지 않는 바이트로** 갈아 끼운다.
     *
     * 암호문 자체는 멀쩡하게 만든다(같은 결속·같은 세대) — 복호화가 실패하는 경로가 아니라
     * **연 바이트가 문서가 아닌** 경로를 재려는 것이다.
     */
    private fun breakStoredOriginal(
        token: String,
        conversionId: UUID,
    ) {
        val documentId = UUID.fromString(bodyOf(read(token, conversionId)).getValue(DOCUMENT_ID_PROPERTY).toString())
        database.execute(
            BREAK_ORIGINAL_SQL.format(
                sealed("zip 이 아니다", documentId, EncryptedField.DOCUMENT_ORIGINAL_BYTES),
                cipher.writeScheme,
                cipher.writeKeyVersion,
                documentId,
            ),
        )
    }

    @Test
    @DisplayName("CF-4 형식 셋은 **완료 전에도** 나간다 — 결과 필드가 아니라 문서 메타다")
    fun `완료 전에도 형식 셋이 나간다`() {
        val token = newAccount()
        val conversionId = uploadDocument(token, "안내문.docx", UploadFixtures.sampleDocx())
        forceStatus(conversionId, FAILED_STATUS, failureCode = "ProviderUnavailable")

        val body = bodyOf(read(token, conversionId))

        assertThat(body.getValue(STATUS_PROPERTY)).isEqualTo(FAILED_STATUS)
        assertFormatTripleInContract(body)
        assertThat(body.getValue(SOURCE_FORMAT_PROPERTY))
            .describedAs("변환이 실패해도 「이 문서는 DOCX 였다」는 사실은 그대로다")
            .isEqualTo(SourceFormat.DOCX.wireName)
        assertThat(body[EXPORT_FORMAT_PROPERTY]).isEqualTo(SourceFormat.DOCX.wireName)
    }

    /** 형식 셋의 **키가 있고** 값이 계약 값 집합 안인가. 값 자체는 케이스가 잰다. */
    private fun assertFormatTripleInContract(body: Map<*, *>) {
        val keys = body.keys.map { it.toString() }
        assertThat(keys)
            .describedAs("키는 항상 있고 값이 null 일 수 있다 — 생략되면 React 가 `undefined` 를 받는다")
            .contains(SOURCE_FORMAT_PROPERTY, EXPORT_FORMAT_PROPERTY, FORMAT_PRESERVATION_PROPERTY)

        assertThat(body.getValue(SOURCE_FORMAT_PROPERTY).toString())
            .isIn(ContractSpec.schemaEnum(SOURCE_FORMAT_SCHEMA))
        body[EXPORT_FORMAT_PROPERTY]?.let {
            assertThat(it.toString()).isIn(ContractSpec.schemaEnum(EXPORT_FORMAT_SCHEMA))
        }
        (body[FORMAT_PRESERVATION_PROPERTY] as? Map<*, *>)?.let { preservation ->
            assertThat(preservation.keys.map { it.toString() }.toSet())
                .isEqualTo(ContractSpec.schemaRequired(PRESERVATION_SCHEMA))
            assertThat(preservation[STATUS_PROPERTY].toString())
                .isIn(ContractSpec.schemaEnum(PRESERVATION_STATUS_SCHEMA))
        }
    }

    /** 파일을 올려 그 문서의 변환 식별자를 준다. **행은 제품이 쓴다** — 원본 행도 함께 선다. */
    private fun uploadDocument(
        token: String,
        filename: String,
        content: ByteArray,
    ): UUID {
        val body = MultipartBody().file(FILE_PART, filename, content)
        val request =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port$DOCUMENTS_PATH"))
                .header(CONTENT_TYPE, body.contentType())
                .header("Authorization", "Bearer $token")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.build()))
        val response = send(request)
        check(response.statusCode() == ContractSpec.successStatus(DOCUMENTS_PATH, POST)) {
            "업로드가 실패했다: ${response.statusCode()} ${response.body()}"
        }
        return UUID.fromString(bodyOf(response).getValue("conversion_id").toString())
    }

    /** 상태만 바꾼다. 결과 열은 건드리지 않으므로 「완료 전」 모양이 유지된다. */
    private fun forceStatus(
        conversionId: UUID,
        status: String,
        failureCode: String? = null,
    ) {
        // 규약: 문자열 템플릿 **안에** SQL 인용부호를 겹치지 않는다. 근거는 [markDone] 이 가리키는 문서.
        val escaped = failureCode?.replace(SINGLE_QUOTE, ESCAPED_QUOTE)
        val code = if (escaped == null) "NULL" else SINGLE_QUOTE + escaped + SINGLE_QUOTE
        database.execute(
            "UPDATE conversions SET status = '$status', failure_code = $code WHERE id = '$conversionId'",
        )
    }

    private fun sealed(
        plain: String?,
        record: UUID,
        field: EncryptedField,
    ): String {
        if (plain == null) return "NULL"
        val bytes = cipher.encrypt(PlainBody(plain), record, field).bytes
        return "decode('${bytes.joinToString("") { "%02x".format(it) }}', 'hex')"
    }

    // ================================================================ 요청 조립

    private fun newAccount(): String {
        val email = "conversionread${counter++}@example.test"
        val credentials = json.writeValueAsString(mapOf("email" to email, "password" to VALID_PASSWORD))
        send(post(null, credentials, "/auth/signup"))
        return bodyOf(send(post(null, credentials, "/auth/login")))
            .getValue("access_token")
            .toString()
    }

    /** 문서를 접수하고 `(문서 id, 변환 id)` 를 돌려준다. **행은 제품이 쓴다.** */
    private fun createDocument(
        token: String,
        text: String = "변환 조회 대상 안내문 본문",
    ): Pair<UUID, UUID> {
        val response = send(post(token, json.writeValueAsString(mapOf("text" to text)), DOCUMENTS_PATH))
        check(response.statusCode() == ContractSpec.successStatus(DOCUMENTS_PATH, POST)) {
            "문서 접수가 실패했다: ${response.statusCode()} ${response.body()}"
        }
        val body = bodyOf(response)
        return UUID.fromString(body.getValue("document_id").toString()) to
            UUID.fromString(body.getValue("conversion_id").toString())
    }

    private fun read(
        token: String?,
        conversionId: String,
    ): HttpResponse<String> = send(getRequest(token, itemPath(conversionId)))

    /** 식별자 갈래. 케이스가 `UUID` 를 들고 있을 때 `toString()` 을 흩뿌리지 않게 한다. */
    private fun read(
        token: String?,
        conversionId: UUID,
    ): HttpResponse<String> = read(token, conversionId.toString())

    /** 같은 요청을 **바이트로** 받는다 — CR-8 만 디코딩을 지나지 않는 팔을 쓴다. */
    private fun readBytes(
        token: String?,
        conversionId: String,
    ): HttpResponse<ByteArray> =
        HttpClient.newHttpClient().send(
            getRequest(token, itemPath(conversionId)).build(),
            HttpResponse.BodyHandlers.ofByteArray(),
        )

    /** 두 팔이 **같은 요청 조립**을 쓰게 한다 — 조립이 갈리면 두 팔의 차이가 요청 차이가 된다. */
    private fun getRequest(
        token: String?,
        path: String,
    ): HttpRequest.Builder {
        val builder = HttpRequest.newBuilder(URI.create("http://localhost:$port$path"))
        token?.let { builder.header("Authorization", "Bearer $it") }
        return builder
    }

    private fun deleteRequest(
        token: String,
        documentId: String,
    ): HttpRequest.Builder =
        HttpRequest
            .newBuilder(URI.create("http://localhost:$port$DOCUMENT_PATH_PREFIX$documentId"))
            .header("Authorization", "Bearer $token")
            .DELETE()

    private fun post(
        token: String?,
        body: String,
        path: String,
    ): HttpRequest.Builder {
        val builder =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port$path"))
                .header(CONTENT_TYPE, JSON_MEDIA_TYPE)
                .POST(HttpRequest.BodyPublishers.ofString(body, Charsets.UTF_8))
        token?.let { builder.header("Authorization", "Bearer $it") }
        return builder
    }

    private fun send(builder: HttpRequest.Builder): HttpResponse<String> =
        HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))

    /** P-21 — 경로 변수 이름을 계약에서 읽어 URL 을 조립한다. */
    private fun itemPath(conversionId: String): String =
        CONVERSION_ITEM_PATH.replace(
            "{${ContractSpec.pathVariable(CONVERSION_ITEM_PATH, GET).name}}",
            conversionId,
        )

    private fun assertDeclaredStatus(
        response: HttpResponse<String>,
        status: Int,
    ) {
        assertThat(response.statusCode())
            .withFailMessage("GET %s 가 %d 이 아니다: %s", CONVERSION_ITEM_PATH, status, response.body())
            .isEqualTo(status)
        assertThat(ContractSpec.responseStatuses(CONVERSION_ITEM_PATH, GET))
            .withFailMessage("계약이 GET %s 에 %d 를 선언하지 않는다", CONVERSION_ITEM_PATH, status)
            .contains(status.toString())
    }

    private fun bodyOf(response: HttpResponse<String>): Map<*, *> = json.readValue(response.body(), Map::class.java)

    private fun Map<*, *>.getValue(key: String): Any = this[key] ?: error("응답에 $key 가 없다: $this")

    /** 완료 행에 채울 값. [sealAs] 는 AEAD 결속의 행 식별자 — 다르면 결속이 깨진다. */
    private data class DoneResult(
        val easyText: String? = "쉬운 글 초안입니다.",
        val editedText: String? = null,
        val maskedItems: List<MaskedItem> = emptyList(),
        val missingPlaceholders: List<String> = emptyList(),
        val reviewed: Boolean = false,
        val sealAs: UUID? = null,
    )

    companion object {
        /** 저장 형식의 정본. 제품 클래스다 — 사유는 클래스 KDoc. */
        private val codec = MaskedItemCodec()

        private const val DOCUMENTS_PATH = "/documents"
        private const val DOCUMENT_ITEM_PATH = "/documents/{document_id}"
        private const val DOCUMENT_PATH_PREFIX = "/documents/"
        private const val CONVERSION_ITEM_PATH = "/conversions/{conversion_id}"
        private const val CONVERSION_PATH_PREFIX = "/conversions/"
        private const val GET = "get"
        private const val POST = "post"
        private const val DELETE = "delete"

        private const val UNAUTHORIZED = 401
        private const val FORBIDDEN = 403
        private const val NOT_FOUND = 404
        private const val INTERNAL_ERROR = 500

        private const val CONVERSION_SCHEMA = "ConversionResponse"
        private const val SOURCE_FORMAT_SCHEMA = "SourceFormat"
        private const val EXPORT_FORMAT_SCHEMA = "ExportFormat"
        private const val PRESERVATION_SCHEMA = "FormatPreservation"
        private const val PRESERVATION_STATUS_SCHEMA = "FormatPreservationStatus"
        private const val MASKED_ITEM_SCHEMA = "MaskedItemResponse"
        private const val STATUS_SCHEMA = "ConversionStatus"
        private const val ERROR_SCHEMA = "ErrorResponse"

        private const val ID_PROPERTY = "id"
        private const val DOCUMENT_ID_PROPERTY = "document_id"
        private const val STATUS_PROPERTY = "status"
        private const val SOURCE_FORMAT_PROPERTY = "source_format"
        private const val EXPORT_FORMAT_PROPERTY = "export_format"
        private const val FORMAT_PRESERVATION_PROPERTY = "format_preservation"

        /**
         * 규약: SQL 은 companion 의 상수 리터럴에 둔다 — 사유는 [markDone] 이 가리키는 문서.
         *
         * 암호문과 **봉투 두 값을 같은 문장에서** SET 한다. 행당 키 세대가 하나라 암호문만
         * 갈아 끼우면 「세대는 v1 인데 암호문은 v2」인 행이 남고, AAD 에 세대가 실리므로
         * 그 행은 영원히 열리지 않는다 — `EnvelopeColumnWriteGuardTest` 의 규약이다.
         */
        val BREAK_ORIGINAL_SQL =
            """
            UPDATE document_originals
            SET file_bytes_encrypted = %s,
                encryption_scheme = '%s',
                key_version = %s
            WHERE document_id = '%s'
            """.trimIndent()
        private const val DETAILS_PROPERTY = "details"
        private const val EASY_TEXT_PROPERTY = "easy_text"
        private const val EDITED_TEXT_PROPERTY = "edited_text"
        private const val MASKED_ITEMS_PROPERTY = "masked_items"
        private const val MISSING_PLACEHOLDERS_PROPERTY = "missing_placeholders"
        private const val MODEL_PROPERTY = "model"
        private const val PROVIDER_NAME_PROPERTY = "provider_name"
        private const val FAILURE_CODE_PROPERTY = "failure_code"
        private const val CATEGORY_PROPERTY = "category"
        private const val PLACEHOLDER_PROPERTY = "placeholder"
        private const val ORIGINAL_PROPERTY = "original"
        private const val DETAIL = "detail"

        /**
         * 계약 `ConversionStatus.enum` 의 값들. 분모로 쓰지 않는다 — 분모는 계약에서 읽고,
         * 이 상수들은 「그 값에 특별한 처분이 있는」 자리(대기는 SQL 을 쓰지 않는다 등)에만 쓴다.
         */
        private const val PENDING_STATUS = "pending"
        private const val DONE_STATUS = "done"
        private const val FAILED_STATUS = "failed"

        /** 계약이 이 경로 404 의 인라인 예시에 붙인 이름. 값이 아니라 이름이다. */
        private const val NOT_FOUND_EXAMPLE = "not_found"

        /** 500 문구를 읽을 좌표. 이름이지 값이 아니다. */
        private const val INTERNAL_ERROR_COMPONENT = "InternalError"
        private const val STORAGE_EXAMPLE = "storage"

        /** CR-3b 가 심는 값들. 응답에 **나타나면 안 된다.** 뒤의 둘은 [MARK_DONE_SQL] 이 채워 넣는다. */
        private const val HIDDEN_ORIGINAL = "900101-1234567"
        private const val STORED_DRAFT = "완료 전인데 저장돼 있던 초안입니다."
        private const val STORED_EDITED = "완료 전인데 저장돼 있던 검수본입니다."
        private const val STORED_MODEL = "stored-model-probe"
        private const val STORED_PROVIDER = "stored-provider"

        /**
         * 계약 `get.description` 이 완료 전에 나간다고 적은 **일곱** — 앞의 둘은 자원
         * 식별자이고, 뒤의 셋은 문서 메타에서 오는 **형식 셋**이라 완료 여부와 무관하다.
         */
        private val BEFORE_DONE_FIELDS =
            setOf(
                ID_PROPERTY,
                DOCUMENT_ID_PROPERTY,
                STATUS_PROPERTY,
                FAILURE_CODE_PROPERTY,
                SOURCE_FORMAT_PROPERTY,
                EXPORT_FORMAT_PROPERTY,
                FORMAT_PRESERVATION_PROPERTY,
            )

        private const val CONTENT_TYPE = "Content-Type"

        /** 업로드 파트 이름. 계약 `POST /documents` 의 multipart 본문이 정한다. */
        private const val FILE_PART = "file"
        private const val JSON_MEDIA_TYPE = "application/json"
        private const val WWW_AUTHENTICATE = "WWW-Authenticate"
        private const val WWW_AUTHENTICATE_COMPONENT = "WWWAuthenticateBearer"

        /** 계약에 없는 경로 조각. DD-5 의 근거 3 이 이것으로 「매핑 부재 404」를 만든다. */
        private const val UNMAPPED_SEGMENT = "no-such-subresource"

        private const val VALID_PASSWORD = "correct horse battery"

        /**
         * 결과 열 아홉을 채우는 UPDATE. **봉투 두 값을 같은 문장에서 함께 SET 한다** —
         * `EnvelopeColumnWriteGuardTest` 의 규약이다. `%s` 자리는 [markDone] 이 채운다.
         */
        val MARK_DONE_SQL =
            """
            UPDATE conversions
            SET status = 'done',
                easy_text_encrypted = %s,
                edited_text_encrypted = %s,
                masked_items_encrypted = %s,
                encryption_scheme = '%s',
                key_version = %s,
                missing_placeholders = '%s'::jsonb,
                reviewed_at = %s,
                model = '%s',
                provider_name = '%s',
                input_tokens = 11,
                output_tokens = 22
            WHERE id = '%s'
            """.trimIndent()

        /** SQL 리터럴 인용부호. 상수로 두는 사유는 [forceStatus] 의 주석. */
        private const val SINGLE_QUOTE = "'"
        private const val ESCAPED_QUOTE = "''"

        private var counter = 0

        /** 이 테스트만 쓰는 DB. 상태를 SQL 로 바꾸므로 다른 테스트의 행과 섞이면 안 된다. */
        val database: DatabaseHandle by lazy { PostgresTestSupport.createEmptyDatabase("conversion_read") }

        @JvmStatic
        @DynamicPropertySource
        fun datasourceProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { database.jdbcUrl }
            registry.add("spring.datasource.username") { database.username }
            registry.add("spring.datasource.password") { database.password }
        }
    }
}

/** 이 테스트가 쓰는 서명 키. 계약 `x-auth.min_secret_bytes` 이상이어야 한다. */
const val CONVERSION_READ_TEST_SECRET: String = "conversion-read-test-signing-key-0123456789"
