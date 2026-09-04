package kr.easydoc.core.easyread

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * 중첩 클래스 이름은 영문 PascalCase 다 — ktlint/detekt 의 class-naming 은 함수와 달리
 * @Test 예외가 없다. 사람이 읽는 이름은 @DisplayName 이 낸다.
 */
class StyleRulesTest {
    @Nested
    @DisplayName("문장 분리")
    inner class SentenceSplitting {
        @Test
        @DisplayName("마침표·물음표·느낌표 뒤 공백과 줄바꿈으로 나눈다")
        fun `문장 끝 기호와 줄바꿈으로 나눈다`() {
            val text = "첫 문장입니다. 두 번째 문장입니다!\n세 번째"
            assertThat(splitSentences(text))
                .containsExactly("첫 문장입니다.", "두 번째 문장입니다!", "세 번째")
        }

        @Test
        @DisplayName("개조식 항목 마커는 문장으로 세지 않는다")
        fun `항목 마커 조각을 버린다`() {
            val text = "안내입니다.\n1.\n가.\n①)\n본문입니다."
            assertThat(splitSentences(text)).containsExactly("안내입니다.", "본문입니다.")
        }

        @Test
        fun `양끝 공백을 다듬는다`() {
            assertThat(splitSentences("   공백 감싼 문장  ")).containsExactly("공백 감싼 문장")
        }

        @Test
        fun `빈 입력은 문장이 0개다`() {
            assertThat(splitSentences("")).isEmpty()
        }

        @Test
        @DisplayName("문장 끝 기호가 없어도 한 문장으로 센다")
        fun `종결 기호가 없는 텍스트도 문장이다`() {
            assertThat(splitSentences("가나다")).containsExactly("가나다")
        }
    }

    @Nested
    @DisplayName("어려운 표현 검출")
    inner class DifficultWordDetection {
        @Test
        @DisplayName("결과 순서가 사전 선언 순서를 따른다")
        fun `사전 선언 순서로 돌려준다`() {
            val found = findDifficultWords("금일 중으로 서류를 제출하고, 완납하고, 신청하고, 접수하세요.")
            assertThat(found).containsExactly("금일", "제출", "접수", "완납")
        }

        @Test
        @DisplayName("복합어 안쪽에 박힌 낱말은 위반이 아니다")
        fun `낱말 시작 위치에서만 센다`() {
            assertThat(findDifficultWords("소득인정액 기준입니다.")).isEmpty()
            assertThat(findDifficultWords("통장사본을 내세요.")).doesNotContain("사본")
        }

        @Test
        @DisplayName("낱말 첫머리에 오면 조사가 붙어도 잡는다")
        fun `조사가 붙어도 잡는다`() {
            assertThat(findDifficultWords("정액 지원을 받습니다.")).containsExactly("정액")
            assertThat(findDifficultWords("경감을 신청하세요.")).contains("경감")
        }

        @Test
        @DisplayName("문맥 판단이 필요한 낱말은 자동 채점에서 뺀다")
        fun `PROMPT_ONLY_WORDS 는 채점하지 않는다`() {
            assertThat(findDifficultWords("신청하기 위해 게시판을 보세요.")).isEmpty()
        }

        @Test
        @DisplayName("법령 이름·시스템 라벨·공식 명칭 안의 복합어는 위반이 아니다(backlog §1.3 ⑷)")
        fun `복합어 안에 박힌 사전 낱말은 잡지 않는다`() {
            assertThat(
                findDifficultWords(
                    "청소년복지 지원법 시행령 제4조제2항에 따라 실제로 합니다. " +
                        "시행령이란 법을 자세히 정한 대통령 규정을 말합니다.",
                ),
            ).isEmpty()
            assertThat(findDifficultWords("사례관리에서 [연계대상자의뢰등록]을 누릅니다.")).isEmpty()
            assertThat(findDifficultWords("신청 안내는 게시판에서 확인하세요.")).isEmpty()
            assertThat(
                findDifficultWords("질병으로 받는 연금인 상병보상연금을 받는 분도 마찬가지입니다."),
            ).isEmpty()
            assertThat(findDifficultWords("공동명의자 등록도 가능합니다.")).isEmpty()
        }

        @Test
        @DisplayName("이미 괄호로 뜻을 풀어 둔 용어는 위반이 아니다(backlog §1.3 ⑷)")
        fun `괄호 뜻풀이가 바로 붙은 낱말은 잡지 않는다`() {
            assertThat(findDifficultWords("명의(이름)가 개인인 리스 차량입니다.")).isEmpty()
        }

        @Test
        @DisplayName("조사·어미가 곧장 붙으면 복합어가 아니라 여전히 잡는다")
        fun `조사와 서술 어미가 붙으면 낱말 경계로 본다`() {
            assertThat(findDifficultWords("이 규정은 다음 달부터 시행을 시작합니다.")).contains("시행")
            assertThat(findDifficultWords("법령은 오늘부터 시행합니다.")).contains("시행")
            assertThat(findDifficultWords("새 규정이 다음 달에 시행됩니다.")).contains("시행")
            assertThat(findDifficultWords("이 계좌는 대표자 명의로 되어 있습니다.")).contains("명의")
        }

        @Test
        @DisplayName("-ㄴ/-ㄹ/-ㅁ 활용형(선어말 축약 음절)도 낱말 경계로 본다")
        fun `선어말 축약 어미가 붙어도 잡는다`() {
            assertThat(findDifficultWords("법령을 오늘 시행한다.")).contains("시행")
            assertThat(findDifficultWords("어제 시행된 규정을 확인하세요.")).contains("시행")
            assertThat(findDifficultWords("이미 시행한 규정입니다.")).contains("시행")
            assertThat(findDifficultWords("내년에 시행할 때 다시 안내합니다.")).contains("시행")
            assertThat(findDifficultWords("시행함으로써 효력이 생깁니다.")).contains("시행")
            assertThat(findDifficultWords("이 규정은 시행 중입니다.")).contains("시행")
            assertThat(findDifficultWords("업무를 위탁시킨 담당자에게 문의하세요.")).contains("위탁")
        }

        @Test
        @DisplayName("낱말 뒤에 문장부호가 오거나 문장 끝이어도 잡는다")
        fun `문장부호나 문장 끝 뒤에서도 낱말 경계로 본다`() {
            assertThat(findDifficultWords("서류를 오늘 안에 제출.")).contains("제출")
            assertThat(findDifficultWords("완납")).contains("완납")
        }

        @Test
        @DisplayName("괄호 안에 숫자가 있으면 뜻풀이로 보지 않는다")
        fun `숫자가 든 괄호는 뜻풀이로 보지 않는다`() {
            assertThat(findDifficultWords("이 규정은 시행(2026년 9월)됩니다.")).contains("시행")
            assertThat(findDifficultWords("이 규정은 시행(2026.9.)됩니다.")).contains("시행")
            assertThat(findDifficultWords("명의(이름)가 개인인 리스 차량입니다.")).isEmpty()
        }
    }

    @Nested
    @DisplayName("스타일 검사")
    inner class StyleChecking {
        @Test
        fun `쉼표가 상한을 넘으면 지적한다`() {
            val result = checkStyle("금일 중으로 서류를 제출하고, 완납하고, 신청하고, 접수하세요.")

            assertThat(result.totalSentences).isEqualTo(1)
            assertThat(result.passed).isFalse()
            assertThat(result.issues.map { it.reason })
                .containsExactly(
                    "쉼표 과다(한 문장 한 정보 위반 의심)",
                    "어려운 표현 잔존(금일)",
                    "어려운 표현 잔존(제출)",
                    "어려운 표현 잔존(접수)",
                    "어려운 표현 잔존(완납)",
                )
        }

        @Test
        @DisplayName("쉼표는 반각·전각·모점을 함께 센다")
        fun `전각 쉼표도 센다`() {
            val result = checkStyle("가나，다라、마바, 사아자")
            assertThat(result.issues.map { it.reason }).contains("쉼표 과다(한 문장 한 정보 위반 의심)")
        }

        @Test
        fun `문장이 상한보다 길면 지적한다`() {
            val long = "안".repeat(MAX_SENTENCE_CHARS + 1)
            assertThat(checkStyle(long).issues.map { it.reason }).containsExactly("문장 길이 초과")
        }

        @Test
        @DisplayName("상한과 정확히 같은 길이는 통과한다")
        fun `경계값은 위반이 아니다`() {
            val exact = "안".repeat(MAX_SENTENCE_CHARS)
            assertThat(checkStyle(exact).passed).isTrue()
        }

        @Test
        fun `이중 피동을 지적한다`() {
            val result = checkStyle("이 서류는 되어지고 있습니다.")
            assertThat(result.issues.map { it.reason }).containsExactly("이중 피동 표현(되어지)")
        }

        @Test
        @DisplayName("어려운 표현 위반만 word 를 채운다")
        fun `위반 종류에 따라 word 가 갈린다`() {
            val difficult = checkStyle("정액 지원을 받습니다.").issues.single()
            assertThat(difficult.word).isEqualTo("정액")

            val passive = checkStyle("이 서류는 되어지고 있습니다.").issues.single()
            assertThat(passive.word).isNull()
        }

        @Test
        fun `쉬운 문장은 위반이 없다`() {
            val result = checkStyle("오늘 안에 내세요.")
            assertThat(result.totalSentences).isEqualTo(1)
            assertThat(result.passed).isTrue()
        }

        @Test
        fun `빈 입력은 문장 0개에 위반 0건이다`() {
            val result = checkStyle("")
            assertThat(result.totalSentences).isZero()
            assertThat(result.passed).isTrue()
        }
    }
}
