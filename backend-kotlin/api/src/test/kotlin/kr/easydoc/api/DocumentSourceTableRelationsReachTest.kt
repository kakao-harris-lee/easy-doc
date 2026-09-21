package kr.easydoc.api

import kr.easydoc.api.support.ContractSpec
import kr.easydoc.application.crypto.ContentCipher
import kr.easydoc.core.crypto.EncryptedField
import kr.easydoc.core.crypto.PlainBody
import kr.easydoc.core.document.TableCellStructure
import kr.easydoc.core.document.TableStructure
import kr.easydoc.core.document.TableStructurePayloadCodec
import kr.easydoc.infrastructure.DatabaseHandle
import kr.easydoc.infrastructure.PostgresTestSupport
import org.assertj.core.api.Assertions.assertThat
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

/**
 * `easydoc.table-relations.enabled=true` 일 때 `GET /documents/{document_id}/source` 의
 * `tables` 실제 wire-shape을 잰다 — contract-keeper가 "확인 불가"로 남긴 두 가지: 지원 표가
 * 있을 때 배열이 채워지는지, 없을 때도 키 자체가 빈 배열로 남는지.
 *
 * [DocumentSourceReachTest] 에 합치지 않고 따로 둔 이유: 계약 `DocumentSourceResponse.required`
 * 에는 `tables` 가 없다(선택 필드, `contracts/easy-doc-v1.yaml`). 그 파일의
 * `` `응답 필드가 계약과 같다` `` 는 응답 키 집합이 required 와 정확히 같은지를 재는데, 클래스
 * 전체에 플래그를 켜면 `tables` 키 하나 때문에 그 테스트가 항상 깨진다. 별도 클래스 + 별도 Spring
 * 컨텍스트로 플래그를 격리해 기존 계약 테스트를 건드리지 않는다.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "easydoc.auth.jwt-secret=$DOCUMENT_SOURCE_TEST_SECRET",
        "easydoc.table-relations.enabled=true",
    ],
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DocumentSourceTableRelationsReachTest {
    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var cipher: ContentCipher

    private val json = ObjectMapper()

    @Test
    fun `표가 없는 문서도 tables 키가 빈 배열로 남는다`() {
        val token = newAccount()
        val documentId = createDocument(token, "표가 없는 평범한 본문입니다.")

        val response = readSource(token, documentId)

        assertThat(response.statusCode()).isEqualTo(200)
        // 문서 생성이 항상 표 구조 행을 넣으므로(빈 표 목록이라도), 플래그가 켜지면 그 빈
        // 목록이 null이 아니라 빈 배열로 노출돼야 한다.
        assertThat(response.body()).contains("\"tables\":[]")
    }

    @Test
    fun `지원 표는 tables 배열에 실린다`() {
        val token = newAccount()
        val documentId = createDocument(token, "표가 있는 긴 본문입니다.")
        seedSupportedTable(documentId)

        val response = readSource(token, documentId)

        assertThat(response.statusCode()).isEqualTo(200)
        assertThat(response.body())
            .contains("\"table_id\":\"table-0\"")
            .contains("\"support_status\":\"supported\"")
            .contains("\"row_count\":1")
            .contains("\"column_count\":2")
    }

    /**
     * 문서 생성이 이미 빈 표 구조 행을 넣어 뒀으므로(`document_table_structures`에 문서당
     * 정확히 한 행), 표가 있는 상태를 만들려면 그 행을 두 번째 INSERT가 아니라 UPDATE로
     * 덮어써야 한다 — 두 번째 INSERT는 `pk_document_table_structures`를 어긴다.
     */
    private fun seedSupportedTable(documentId: String) {
        val table =
            TableStructure(
                tableId = "table-0",
                sourceUnitIndexes = listOf(0, 1),
                rowCount = 1,
                columnCount = 2,
                cells =
                    listOf(
                        TableCellStructure(
                            row = 0,
                            column = 0,
                            sourceUnitIndexes = listOf(0),
                            headerRefs = emptyList(),
                        ),
                        TableCellStructure(
                            row = 0,
                            column = 1,
                            sourceUnitIndexes = listOf(1),
                            headerRefs = emptyList(),
                        ),
                    ),
            )
        val encoded = TableStructurePayloadCodec.encode(listOf(table))
        val sealed =
            cipher.encrypt(PlainBody(encoded), UUID.fromString(documentId), EncryptedField.DOCUMENT_TABLE_STRUCTURE)
        val hex = sealed.bytes.joinToString("") { "%02x".format(it) }
        database.execute(
            """
            UPDATE document_table_structures
            SET payload_encrypted = decode('$hex', 'hex'),
                encryption_scheme = '${sealed.scheme}',
                key_version = ${sealed.keyVersion}
            WHERE document_id = '$documentId'
            """.trimIndent(),
        )
    }

    private fun newAccount(): String {
        val email = "tablerelations${counter++}@example.test"
        val credentials = json.writeValueAsString(mapOf("email" to email, "password" to VALID_PASSWORD))
        send(post(null, credentials, "/auth/signup"))
        database.execute("UPDATE users SET email_verified_at = now() WHERE email = '$email'")
        return bodyOf(send(post(null, credentials, "/auth/login"))).required("access_token").toString()
    }

    private fun createDocument(
        token: String,
        text: String,
    ): String {
        val response = send(post(token, json.writeValueAsString(mapOf("text" to text)), DOCUMENTS_PATH))
        check(response.statusCode() == ContractSpec.successStatus(DOCUMENTS_PATH, POST)) {
            "문서 접수가 실패했다: ${response.statusCode()} ${response.body()}"
        }
        return bodyOf(response).required(DOCUMENT_ID_PROPERTY).toString()
    }

    private fun readSource(
        token: String?,
        documentId: String,
    ): HttpResponse<String> = send(sourceRequest(token, documentId))

    private fun sourceRequest(
        token: String?,
        documentId: String,
    ): HttpRequest.Builder {
        val builder = HttpRequest.newBuilder(URI.create("http://localhost:$port${sourcePath(documentId)}")).GET()
        token?.let { builder.header("Authorization", "Bearer $it") }
        return builder
    }

    private fun sourcePath(documentId: String): String = SOURCE_PATH.replace("{document_id}", documentId)

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

    private fun bodyOf(response: HttpResponse<String>): Map<*, *> = json.readValue(response.body(), Map::class.java)

    private fun Map<*, *>.required(key: String): Any = this[key] ?: error("응답에 $key 가 없다")

    companion object {
        private const val DOCUMENTS_PATH = "/documents"
        private const val SOURCE_PATH = "/documents/{document_id}/source"
        private const val POST = "post"
        private const val CONTENT_TYPE = "Content-Type"
        private const val JSON_MEDIA_TYPE = "application/json"
        private const val VALID_PASSWORD = "correct horse battery"
        private const val DOCUMENT_ID_PROPERTY = "document_id"

        private var counter = 0

        /** 이 테스트만 쓰는 DB — 플래그가 다른 Spring 컨텍스트라 [DocumentSourceReachTest] 와 공유하지 않는다. */
        val database: DatabaseHandle by lazy { PostgresTestSupport.createEmptyDatabase("document_source_tables") }

        @JvmStatic
        @DynamicPropertySource
        fun datasourceProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { database.jdbcUrl }
            registry.add("spring.datasource.username") { database.username }
            registry.add("spring.datasource.password") { database.password }
        }
    }
}
