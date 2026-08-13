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
            // 버리지 않으면 문장 수가 부풀고 평균 길이가 낮게 나와, 길이 규칙이 실제보다
            // 잘 지켜지는 것처럼 보인다.
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
            // 이 순서가 보정 프롬프트의 지적 순서가 된다. 정렬된 자료구조로 바꾸면 조용히 달라진다.
            val found = findDifficultWords("금일 중으로 서류를 제출하고, 납부하고, 신청하고, 접수하세요.")
            assertThat(found).containsExactly("금일", "제출", "접수", "납부")
        }

        @Test
        @DisplayName("복합어 안쪽에 박힌 낱말은 위반이 아니다")
        fun `낱말 시작 위치에서만 센다`() {
            // "소득인정액"은 제도 이름이다. 여기서 '정액'을 위반으로 세면 모델이 옳게 지킨
            // 것을 벌하게 되고, 게이트 신뢰가 무너진다.
            assertThat(findDifficultWords("소득인정액 기준입니다.")).isEmpty()
            assertThat(findDifficultWords("통장사본을 내세요.")).doesNotContain("사본")
        }

        @Test
        @DisplayName("낱말 첫머리에 오면 조사가 붙어도 잡는다")
        fun `조사가 붙어도 잡는다`() {
            assertThat(findDifficultWords("정액 지원을 받습니다.")).containsExactly("정액")
            assertThat(findDifficultWords("감면을 신청하세요.")).contains("감면")
        }

        @Test
        @DisplayName("문맥 판단이 필요한 낱말은 자동 채점에서 뺀다")
        fun `PROMPT_ONLY_WORDS 는 채점하지 않는다`() {
            // "신청하기 위해"의 '하기', "게시판"의 '게시'처럼 정상 표현과 기계적으로 구분되지
            // 않는다. 넣으면 오탐이 압도적이라 문맥 판단은 LLM 몫으로 남긴다.
            assertThat(findDifficultWords("신청하기 위해 게시판을 보세요.")).isEmpty()
        }
    }

    @Nested
    @DisplayName("스타일 검사")
    inner class StyleChecking {
        @Test
        fun `쉼표가 상한을 넘으면 지적한다`() {
            val result = checkStyle("금일 중으로 서류를 제출하고, 납부하고, 신청하고, 접수하세요.")

            assertThat(result.totalSentences).isEqualTo(1)
            assertThat(result.passed).isFalse()
            assertThat(result.issues.map { it.reason })
                .containsExactly(
                    "쉼표 과다(한 문장 한 정보 위반 의심)",
                    "어려운 표현 잔존(금일)",
                    "어려운 표현 잔존(제출)",
                    "어려운 표현 잔존(접수)",
                    "어려운 표현 잔존(납부)",
                )
        }

        @Test
        @DisplayName("쉼표는 반각·전각·모점을 함께 센다")
        fun `전각 쉼표도 센다`() {
            // hwpx/pdf 추출본에 섞여 들어온다. 반각만 세면 같은 문장이 입력 경로에 따라
            // 다르게 판정된다.
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
            // 보정 프롬프트가 뜻풀이를 함께 실으려면 사전 키가 필요하다. reason 문자열을
            // 되파싱하면 사유 문구를 손댈 때마다 조용히 깨지므로 값으로 들고 다닌다.
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
