package kr.easydoc.core.dictionary

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * `dictionary/DESIGN.md` §6.7 이 "이식에서 빠지면 안 된다"고 못 박은 규칙들의 회귀 테스트.
 *
 * 중첩 클래스 이름은 영문 PascalCase 다 — ktlint/detekt 의 class-naming 은 함수와 달리
 * @Test 예외가 없다. 사람이 읽는 이름은 @DisplayName 이 낸다.
 */
class DictionaryIndexTest {
    private fun termsFoundIn(
        index: DictionaryIndex,
        text: String,
    ): List<String> = index.findAll(text).map { it.entry.term }

    @Nested
    @DisplayName("§6.7 (0) 표면형 소유권이 priority 보다 먼저다")
    inner class SurfaceOwnership {
        // '내방'(명사, p=120)과 '내방하다'(동사, p=140)가 표면형 '내방'을 공유한다.
        // priority 만 보면 표제어가 긴 동사가 언제나 이겨, 명사 엔트리는 사전에 있는데도
        // 절대 뽑히지 않는 죽은 데이터가 된다.
        private val index =
            DictionaryFixture()
                .add(
                    DictionaryEntry(
                        term = "내방",
                        easyTerm = "방문",
                        strategy = ReplaceStrategy.SUBSTITUTE,
                        risk = RiskLevel.NONE,
                        priority = 120,
                    ),
                ).add(
                    DictionaryEntry(
                        term = "내방하다",
                        easyTerm = "찾아오다",
                        strategy = ReplaceStrategy.SUBSTITUTE,
                        risk = RiskLevel.NONE,
                        priority = 140,
                    ),
                    "내방",
                ).build()

        @Test
        @DisplayName("표면형이 자기 표제어인 엔트리가 priority 가 더 높은 동사를 이긴다")
        fun `내방을 은 명사 내방으로 잡힌다`() {
            assertThat(termsFoundIn(index, "내방을 하실 때")).containsExactly("내방")
        }
    }

    @Nested
    @DisplayName("§6.7 (3) 조사 경계는 조사 뒤까지 확인한다")
    inner class JosaBoundary {
        private val index =
            DictionaryFixture()
                .add(
                    DictionaryEntry(
                        term = "급여",
                        easyTerm = "지원금",
                        strategy = ReplaceStrategy.SUBSTITUTE,
                        risk = RiskLevel.NONE,
                        priority = 120,
                    ),
                ).build()

        @Test
        @DisplayName("조사 뒤에 한글이 이어지면 매칭하지 않는다 (급여과장 → 지원금과장 방지)")
        fun `급여과장에게 는 매칭되지 않는다`() {
            assertThat(termsFoundIn(index, "급여과장에게 문의하세요.")).isEmpty()
        }

        @Test
        @DisplayName("조사 연쇄를 다 소비한 뒤가 어절 경계면 매칭한다")
        fun `급여에서는 은 매칭된다`() {
            assertThat(termsFoundIn(index, "급여에서는 제외됩니다.")).containsExactly("급여")
        }

        @Test
        fun `조사 하나만 붙어도 매칭된다`() {
            assertThat(termsFoundIn(index, "급여는 월 30만 원입니다.")).containsExactly("급여")
        }

        @Test
        @DisplayName("조사 없이 표제어가 그대로 끝나도 매칭한다")
        fun `조사가 없어도 매칭된다`() {
            assertThat(termsFoundIn(index, "급여 지급 안내")).containsExactly("급여")
        }
    }

    @Nested
    @DisplayName("§6.7 (4) 로마자·숫자 경계")
    inner class LatinDigitBoundary {
        private val index =
            DictionaryFixture()
                .add(
                    DictionaryEntry(
                        term = "CT",
                        easyTerm = "전류 변성기",
                        strategy = ReplaceStrategy.GLOSS,
                        risk = RiskLevel.LOW,
                        priority = 120,
                    ),
                ).add(
                    DictionaryEntry(
                        term = "TF",
                        easyTerm = "특별 전담 조직",
                        strategy = ReplaceStrategy.GLOSS,
                        risk = RiskLevel.LOW,
                        priority = 120,
                    ),
                ).add(
                    DictionaryEntry(
                        term = "개월",
                        easyTerm = "달",
                        strategy = ReplaceStrategy.SUBSTITUTE,
                        risk = RiskLevel.NONE,
                        priority = 120,
                    ),
                ).build()

        @Test
        @DisplayName("왼쪽이 로마자면 매칭하지 않는다 (CCTV 의 CT)")
        fun `CCTV 에서 CT 를 잡지 않는다`() {
            assertThat(termsFoundIn(index, "CCTV를 설치합니다.")).isEmpty()
        }

        @Test
        @DisplayName("오른쪽이 로마자면 매칭하지 않는다 (TFT 의 TF)")
        fun `TFT 에서 TF 를 잡지 않는다`() {
            assertThat(termsFoundIn(index, "TFT 구성 후 운영합니다.")).isEmpty()
        }

        @Test
        @DisplayName("뒤가 로마자·숫자가 아니면 로마자 경계는 통과한다")
        fun `TF를 은 매칭된다`() {
            assertThat(termsFoundIn(index, "TF를 구성합니다.")).containsExactly("TF")
        }

        @Test
        @DisplayName("§6.7 (4) 표의 'TF팀 허용'은 로마자 경계 한정이다 — 조사 경계가 따로 거절한다")
        fun `TF팀 은 조사 경계에서 걸린다`() {
            // 참조 구현(`lookup.py`)으로 대조 확인: `TF팀을 만듭니다.` -> 매칭 없음.
            // `_boundary_ok` 는 로마자 검사를 통과해도 조사 연쇄 뒤가 한글 음절이면 실패한다.
            assertThat(termsFoundIn(index, "TF팀을 만듭니다.")).isEmpty()
        }

        @Test
        @DisplayName("한글 표제어에는 로마자·숫자 경계를 걸지 않는다")
        fun `3개월 의 개월은 숫자 뒤에서도 매칭된다`() {
            assertThat(termsFoundIn(index, "3개월 이내에 신청하세요.")).containsExactly("개월")
        }
    }

    @Nested
    @DisplayName("§6.7 (5) 길이 1 한글 표제어 전용 경계 규칙")
    inner class SingleHangulHeadword {
        private val index =
            DictionaryFixture()
                .add(
                    DictionaryEntry(
                        term = "자",
                        easyTerm = "사람",
                        strategy = ReplaceStrategy.SUBSTITUTE,
                        risk = RiskLevel.NONE,
                        priority = 110,
                    ),
                ).build()

        @Test
        @DisplayName("① 수량 단위: 바로 앞이 ASCII 숫자면 거부한다")
        fun `200자 의 자는 매칭되지 않는다`() {
            assertThat(termsFoundIn(index, "200자 이내로 작성하세요.")).isEmpty()
        }

        @Test
        @DisplayName("② 가나다 목록 기호: 문서 시작에 단독으로 나오고 뒤가 마침표면 거부한다")
        fun `줄머리 자 마침표는 매칭되지 않는다`() {
            assertThat(termsFoundIn(index, "자. 학교 밖 청소년 지원")).isEmpty()
        }

        @Test
        @DisplayName("② 개행 직후 공백·탭만 앞선 목록 기호도 거부한다")
        fun `개행 뒤 들여쓴 자 괄호도 매칭되지 않는다`() {
            assertThat(termsFoundIn(index, "안내입니다.\n  자) 신청 방법")).isEmpty()
        }

        @Test
        @DisplayName("법률문투 '자는' 은 두 조건 다 아니므로 계속 허용된다")
        fun `교부받은 자는 은 매칭된다`() {
            assertThat(termsFoundIn(index, "부정한 방법으로 교부받은 자는 처벌됩니다."))
                .containsExactly("자")
        }

        @Test
        @DisplayName("왼쪽 경계: 복합어 중간의 한 글자는 매칭하지 않는다")
        fun `신청자 의 자는 매칭되지 않는다`() {
            assertThat(termsFoundIn(index, "신청자는 오세요.")).isEmpty()
        }
    }

    @Nested
    @DisplayName("최장일치와 겹침 정리")
    inner class LongestMatch {
        private val index =
            DictionaryFixture()
                .add(
                    DictionaryEntry(
                        term = "차상위계층",
                        easyTerm = "기초생활수급자 바로 위의 저소득층",
                        strategy = ReplaceStrategy.GLOSS,
                        risk = RiskLevel.HIGH,
                        priority = 150,
                    ),
                ).add(
                    DictionaryEntry(
                        term = "차상위",
                        easyTerm = "형편이 조금 나은",
                        strategy = ReplaceStrategy.GLOSS,
                        risk = RiskLevel.LOW,
                        priority = 130,
                    ),
                ).add(
                    DictionaryEntry(
                        term = "내방",
                        easyTerm = "방문",
                        strategy = ReplaceStrategy.SUBSTITUTE,
                        risk = RiskLevel.NONE,
                        priority = 120,
                    ),
                ).build()

        @Test
        @DisplayName("겹치는 후보 중 긴 표면형이 이긴다")
        fun `차상위계층이 차상위를 이긴다`() {
            assertThat(termsFoundIn(index, "차상위계층 지원 안내")).containsExactly("차상위계층")
        }

        @Test
        @DisplayName("가장 긴 후보가 경계 검사에 걸리면 매칭 자체가 없다")
        fun `내방객은 매칭되지 않는다`() {
            assertThat(termsFoundIn(index, "내방객이 많습니다.")).isEmpty()
        }

        @Test
        @DisplayName("반환 순서는 문서 등장 순서다")
        fun `등장 순서로 돌려준다`() {
            val found = index.findAll("차상위계층 안내입니다. 내방 시 확인하세요.")
            assertThat(found.map { it.entry.term }).containsExactly("차상위계층", "내방")
            assertThat(found.map { it.start }).isSorted()
        }
    }

    @Nested
    @DisplayName("매칭 결과")
    inner class MatchShape {
        private val index =
            DictionaryFixture()
                .add(
                    DictionaryEntry(
                        term = "명기하다",
                        easyTerm = "쓰다",
                        strategy = ReplaceStrategy.SUBSTITUTE,
                        risk = RiskLevel.NONE,
                        priority = 140,
                    ),
                    "명기하여",
                ).build()

        @Test
        @DisplayName("변형형으로 걸리면 isInflected 가 참이다")
        fun `활용형 매칭을 표시한다`() {
            val match = index.findAll("주소를 명기하여 주십시오.").single()
            assertThat(match.surface).isEqualTo("명기하여")
            assertThat(match.entry.term).isEqualTo("명기하다")
            assertThat(match.isInflected).isTrue()
        }
    }

    @Nested
    @DisplayName("색인 구성 검증")
    inner class IndexConstruction {
        @Test
        @DisplayName("표면형이 가리키는 엔트리가 없으면 즉시 거절한다")
        fun `끊어진 표면형 참조를 fail-fast 로 막는다`() {
            assertThatIllegalArgumentException()
                .isThrownBy {
                    DictionaryIndex.of(
                        entries = emptyMap(),
                        surfaceIndex = mapOf("내방" to listOf(1)),
                        josa = TEST_JOSA,
                    )
                }.withMessageContaining("내방")
        }
    }
}
