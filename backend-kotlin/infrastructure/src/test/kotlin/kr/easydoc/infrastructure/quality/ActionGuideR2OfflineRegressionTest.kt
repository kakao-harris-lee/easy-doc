package kr.easydoc.infrastructure.quality

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kr.easydoc.core.actionguide.ActionGuideCandidateParser
import kr.easydoc.core.actionguide.ActionGuideCandidateValidator
import kr.easydoc.core.exceptions.InvalidInputException
import kr.easydoc.core.quality.GoldenDocument
import kr.easydoc.core.quality.GoldenDocumentLoader
import kr.easydoc.core.segment.splitUnits
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

/**
 * R2 유료 실행에서 보존한 20개 후보를 호출 없이 production parser/validator로 재생한다.
 *
 * production runner가 validator에 넘기는 값은 저장된 변환 본문이 아니라 원문을
 * [splitUnits]로 나눈 목록이다. 이 테스트는 같은 원문 fixture를 사용해 그 경로를
 * 재생하고, 변환 본문도 함께 고정된 쌍인지 확인한다. 본문은 prompt 입력의 일부라서
 * parser 검증에는 필요하지 않지만, source/body가 다른 문서로 조용히 섞이는 것을 막는다.
 */
class ActionGuideR2OfflineRegressionTest {
    @Test
    fun `round3 twelve frozen outputs preserve text cautions and source coverage`() {
        val directory = File(repositoryRoot(), "docs/reports/2026-09-21-r2-bounded-quality-artifacts/round3")
        val hashes =
            Json.parseToJsonElement(File(directory, "manifest.json").readText()).jsonObject["sha256"]!!.jsonObject
        val documents =
            GoldenDocumentLoader
                .loadDirectory(GoldenDocumentLoader.documentsDirectory())
                .documents
                .associateBy(GoldenDocument::id)
        val files =
            directory.listFiles { file -> file.name.matches(Regex("[0-9]{3}-run[12]\\.txt")) }!!.sorted()
        assertThat(files).hasSize(12)
        files.forEach { file ->
            assertThat(sha256(file)).isEqualTo(hashes[file.name]!!.jsonPrimitive.content)
            val original = Json.parseToJsonElement(file.readText()).jsonObject["sections"]!!.jsonArray
            val source = documents.getValue(file.name.substringBefore("-run")).sourceText
            val result = ActionGuideCandidateParser.parseAndValidate(file.readText(), splitUnits(source))
            result.sections.forEachIndexed { sectionIndex, section ->
                val originalItems = original[sectionIndex].jsonObject["items"]!!.jsonArray
                section.items.forEachIndexed { itemIndex, item ->
                    val originalItem = originalItems[itemIndex].jsonObject
                    assertThat(item.text).isEqualTo(originalItem["text"]!!.jsonPrimitive.content)
                    assertThat(item.cautions)
                        .containsExactlyElementsOf(
                            originalItem["cautions"]!!.jsonArray.map { it.jsonPrimitive.content },
                        )
                    val originalIndexes =
                        originalItem["source_anchors"]!!
                            .jsonArray
                            .flatMap { anchor ->
                                anchor.jsonObject["source_unit_indexes"]!!.jsonArray.map { it.jsonPrimitive.int }
                            }.toSet()
                    assertThat(item.sourceAnchors.flatMap { it.sourceUnitIndexes }.toSet()).isEqualTo(originalIndexes)
                }
            }
        }
    }

    @Test
    @DisplayName("R2 보존 후보 20개가 production parser와 validator를 모두 통과한다")
    fun `R2 frozen candidates all pass production validation`() {
        val fixtures = loadFixtures()
        val failures =
            fixtures.mapNotNull { fixture ->
                try {
                    ActionGuideCandidateParser.parseAndValidate(
                        fixture.candidateJson,
                        splitUnits(fixture.document.sourceText),
                    )
                    null
                } catch (failure: InvalidInputException) {
                    "${fixture.fileName}: ${failure.message ?: "invalid input"}"
                }
            }

        assertThat(failures)
            .withFailMessage {
                "R2 보존 후보가 production validation을 통과하지 못했다: " +
                    failures.joinToString("; ")
            }.isEmpty()
    }

    @Test
    @DisplayName("R2 재생은 원문 단위와 정확히 일치하지 않는 anchor를 거부한다")
    fun `R2 replay preserves exact anchor validation`() {
        val fixture = loadFixtures().first { it.fileName == "023-run2.txt" }
        val candidate = ActionGuideCandidateParser.decode(fixture.candidateJson)
        val sectionIndex =
            candidate.sections.indexOfFirst { section ->
                section.items.any { item -> item.sourceAnchors.isNotEmpty() }
            }
        check(sectionIndex >= 0) { "R2 fixture에 source anchor가 없다: ${fixture.fileName}" }
        val itemIndex =
            candidate.sections[sectionIndex].items.indexOfFirst { item ->
                item.sourceAnchors.isNotEmpty()
            }
        val originalAnchor =
            candidate.sections[sectionIndex]
                .items[itemIndex]
                .sourceAnchors
                .first()
        val tampered =
            candidate.copy(
                sections =
                    candidate.sections.mapIndexed { currentSectionIndex, section ->
                        if (currentSectionIndex != sectionIndex) {
                            section
                        } else {
                            section.copy(
                                items =
                                    section.items.mapIndexed { currentItemIndex, item ->
                                        if (currentItemIndex != itemIndex) {
                                            item
                                        } else {
                                            item.copy(
                                                sourceAnchors =
                                                    listOf(
                                                        originalAnchor.copy(quote = originalAnchor.quote + " (변조)"),
                                                    ) +
                                                        item.sourceAnchors.drop(1),
                                            )
                                        }
                                    },
                            )
                        }
                    },
            )

        assertThatThrownBy {
            ActionGuideCandidateValidator.validate(tampered, splitUnits(fixture.document.sourceText))
        }.isInstanceOf(InvalidInputException::class.java)
    }

    @Test
    @DisplayName("round3 023 후보는 인접한 source anchor만 합쳐서 통과한다")
    fun `round3 023 candidate compacts adjacent anchors without changing content`() {
        val source =
            GoldenDocumentLoader
                .loadDirectory(GoldenDocumentLoader.documentsDirectory())
                .documents
                .single { it.id == "023" }
                .sourceText
        val rawCandidate =
            requireNotNull(javaClass.getResourceAsStream("/kr/easydoc/infrastructure/quality/023-run2-round3.json")) {
                "round3 023 frozen candidate resource가 없다"
            }.bufferedReader().use { it.readText() }

        val candidate = ActionGuideCandidateParser.parseAndValidate(rawCandidate, splitUnits(source))
        val benefits = candidate.sections.single { it.kind.wireName == "benefits" }
        val item = benefits.items[1]
        val sourceUnits = splitUnits(source)

        assertThat(item.text)
            .isEqualTo(
                "서식4의 보조비목·세목 목록은 예산 작성 참고자료입니다. 목록의 항목을 각각 별도 혜택으로 안내하지 않습니다.",
            )
        assertThat(item.cautions).containsExactly(
            "참고자료에서 보수와 공공운영비는 법정운영운영비 보조에 한하며, 기간제근로자등보수는 보조사업 운영 등에 필요한 인건비입니다.",
            "행사운영비·행사실비지원금·행사관련시설비는 원칙적으로 민간행사보조에 한합니다. 행사실비지원금은 단순 참가자에게 지급할 수 없습니다.",
            "국내여비·국외업무여비는 공무원 여비규정을 준용합니다. 재료비와 자산 및 물품취득비는 원칙적으로 민간자본사업보조사업에 한합니다.",
            "포상금은 원칙적으로 자부담에 한하며, 지자체 승인 여부를 확인한 후 편성할 수 있습니다.",
        )
        assertThat(item.sourceAnchors).hasSize(9)
        assertThat(item.sourceAnchors.map { it.sourceUnitIndexes })
            .contains(
                (191..197).toList(),
                (203..209).toList(),
            )
        assertThat(item.sourceAnchors.single { it.sourceUnitIndexes == (191..197).toList() }.quote)
            .isEqualTo(sourceUnits.slice(191..197).joinToString("\n"))
        assertThat(item.sourceAnchors.single { it.sourceUnitIndexes == (203..209).toList() }.quote)
            .isEqualTo(sourceUnits.slice(203..209).joinToString("\n"))
    }

    private fun loadFixtures(): List<FrozenR2Fixture> {
        val repositoryRoot = repositoryRoot()
        val artifactDirectory = File(repositoryRoot, "docs/reports/2026-09-21-r2-model-evaluation-artifacts")
        val manifest = Json.parseToJsonElement(File(artifactDirectory, "manifest.json").readText()).jsonObject
        assertThat(manifest["calls"]?.jsonPrimitive?.int).isEqualTo(FROZEN_OUTPUTS.size)
        val manifestHashes = manifest["sha256"]?.jsonObject ?: error("R2 manifest에 sha256가 없다")

        val corpus = GoldenDocumentLoader.loadDirectory(GoldenDocumentLoader.documentsDirectory())
        val documents = corpus.documents.associateBy(GoldenDocument::id)
        val sourceFiles = sourceFilesById(GoldenDocumentLoader.documentsDirectory())
        val savedBodies = GoldenDocumentLoader.loadConversions(GoldenDocumentLoader.conversionsDirectory())

        SOURCE_FIXTURE_HASHES.forEach { (documentId, hashes) ->
            val sourceFile = sourceFiles[documentId] ?: error("골든 원문 fixture가 없다: $documentId")
            assertThat(sha256(sourceFile))
                .withFailMessage { "R2 원문 fixture가 바뀌었다: $documentId" }
                .isEqualTo(hashes.source)
            val bodyFile = File(GoldenDocumentLoader.conversionsDirectory(), "$documentId.txt")
            assertThat(savedBodies[documentId]).isNotNull
            assertThat(sha256(bodyFile))
                .withFailMessage { "R2 저장 본문 fixture가 바뀌었다: $documentId" }
                .isEqualTo(hashes.body)
        }

        return FROZEN_OUTPUTS.map { fileName ->
            val file = File(artifactDirectory, fileName)
            assertThat(Files.isRegularFile(file.toPath()))
                .withFailMessage { "R2 보존 후보가 없다: $file" }
                .isTrue()
            assertThat(sha256(file))
                .withFailMessage { "R2 보존 후보가 manifest와 다르다: $fileName" }
                .isEqualTo(manifestHashes[fileName]?.jsonPrimitive?.content)

            val documentId = fileName.substringBefore("-run")
            val document = documents[documentId] ?: error("R2 문서 fixture가 없다: $documentId")
            val savedBody = savedBodies[documentId] ?: error("R2 저장 본문 fixture가 없다: $documentId")
            check(savedBody.isNotBlank()) { "R2 저장 본문 fixture가 비어 있다: $documentId" }
            FrozenR2Fixture(
                fileName = fileName,
                document = document,
                candidateJson = file.readText(),
            )
        }
    }

    private fun sourceFilesById(directory: File): Map<String, File> {
        val files =
            directory.listFiles { file -> file.isFile && file.extension == "json" }
                ?: error("골든 원문 디렉터리를 읽을 수 없다: $directory")
        return files.associateBy { GoldenDocumentLoader.loadFile(it).id }
    }

    private fun repositoryRoot(): File {
        val sourceRoot =
            System.getProperty("easydoc.kotlin.source.root")
                ?: error("easydoc.kotlin.source.root가 없다")
        return File(sourceRoot).canonicalFile.parentFile
            ?: error("backend-kotlin의 부모 디렉터리를 찾을 수 없다: $sourceRoot")
    }

    private fun sha256(file: File): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(file.readBytes())
            .joinToString("") { byte -> "%02x".format(byte) }

    private data class FrozenR2Fixture(
        val fileName: String,
        val document: GoldenDocument,
        val candidateJson: String,
    )

    private data class SourceFixtureHashes(
        val source: String,
        val body: String,
    )

    private companion object {
        val FROZEN_OUTPUTS =
            listOf(
                "070-run1.txt",
                "070-run2.txt",
                "087-run1.txt",
                "087-run2.txt",
                "023-run1.txt",
                "023-run2.txt",
                "072-run1.txt",
                "072-run2.txt",
                "077-run1.txt",
                "077-run2.txt",
                "088-run1.txt",
                "088-run2.txt",
                "089-run1.txt",
                "089-run2.txt",
                "097-run1.txt",
                "097-run2.txt",
                "074-run1.txt",
                "074-run2.txt",
                "101-run1.txt",
                "101-run2.txt",
            )

        /** R0 기준선 §3의 원문·저장 본문 SHA-256. */
        val SOURCE_FIXTURE_HASHES =
            mapOf(
                "070" to
                    SourceFixtureHashes(
                        source = "7e7dfe5ce00757794641fa1dd0b586a789109197b132d6891aad20766118f049",
                        body = "84e999774841155f3e5a7e0573427dbd5e0fd2c080ca6c57fb391adc44a34d34",
                    ),
                "087" to
                    SourceFixtureHashes(
                        source = "86c30aa00eadab2ead330faf1cc4b7aa52d80c61ff5b7b4ce2db78b4db83e30b",
                        body = "5b029427aac5c60275c27384b9b1ec2c6afe5f329a8c04857152e6ee86b8f60b",
                    ),
                "023" to
                    SourceFixtureHashes(
                        source = "4d3a8a9cb01ffff348321b5fc64bc30637d6527fb96a584d566b7c7bf8dccf5a",
                        body = "35cbe750220280d71169c43959add44f1436569430942dec24bbbb9758ae3307",
                    ),
                "072" to
                    SourceFixtureHashes(
                        source = "9966531f2d8598413473418721db27d55baab54424f8546a125ed9d6dd888848",
                        body = "651f398bf6d17f72c3fbaf3bc8a2aeba6cd83f6017d1f8170e0ea597e8be911f",
                    ),
                "077" to
                    SourceFixtureHashes(
                        source = "28e2b649fef4033b34c061e85ee2f34160baa63894289e4263951316fc56b0f7",
                        body = "034f291c655bf8c063203982ef99e1b8748104a50f2fcf3dcede6c770fed7d8c",
                    ),
                "088" to
                    SourceFixtureHashes(
                        source = "2fcac76fc577603bd2deecabaa25426ea6b7c1bdbce2a6b493882b451aa90b5b",
                        body = "0c0f755f821419949e8d6ea8c840b1f38f32f482aa9eccf984f85d7fc949de94",
                    ),
                "089" to
                    SourceFixtureHashes(
                        source = "b2503daf37585414ae53516804444e503006d447e183cfdc037d041a95385e48",
                        body = "d1d7191e637692cd7c5ec497a4333b2e1b166f132f53bf478b8409622fe8c9a4",
                    ),
                "097" to
                    SourceFixtureHashes(
                        source = "3f79d4f402c5490e99628010acc8da49dbb828b936f62329e51449276839ccbb",
                        body = "7f873a677f79972ae39bd13ea50623b840645ba128faf19674686ae6c01f3a89",
                    ),
                "074" to
                    SourceFixtureHashes(
                        source = "a60ceff902a7357d6da40613242d19e629767330e23f4d784ee0386ebf7903b4",
                        body = "e395b6734002167c5c80814632269cb1265b09375d41c466b43edc42fba8c035",
                    ),
                "101" to
                    SourceFixtureHashes(
                        source = "eb25f6c6ced8acb37c4867ae02b2e52f641908efd93bc61969fbdd0d90564661",
                        body = "b191bd9c9f4d1f46528ed8e2a934840185f27026e267961db2a2156817b07585",
                    ),
            )
    }
}
