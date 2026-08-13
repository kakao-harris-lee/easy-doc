package kr.easydoc.core.easyread

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * 치환 비문(뜻풀이 축자 삽입) 검출.
 *
 * 이 검출기의 설계 목표는 **완벽한 비문 검출이 아니다.** 과소 검출은 골든셋 judge 가
 * 보완하지만, 과잉 검출은 게이트 신뢰를 무너뜨리고 멀쩡한 문장에 보정을 유발한다.
 * 그래서 아래 오탐 테스트가 검출 테스트만큼 중요하다.
 */
class GlossCollisionTest {
    @Test
    @DisplayName("패턴 ① 명사형 뜻풀이 + 용언")
    fun `내어 줌 받아 를 잡는다`() {
        assertThat(findGlossCollisions("신청서를 내어 줌 받아 주세요.")).containsExactly("내어 줌")
    }

    @Test
    @DisplayName("패턴 ② 한 낱말짜리 명사형 뜻풀이 + 체언")
    fun `뽑음 결과 를 잡는다`() {
        assertThat(findGlossCollisions("뽑음 결과를 알려 드립니다.")).containsExactly("뽑음")
    }

    @Test
    @DisplayName("패턴 ③ 복합어 앞자리 낱말 + 관형구 뜻풀이")
    fun `사용 정해진 날짜 를 잡는다`() {
        assertThat(findGlossCollisions("사용 정해진 날짜까지 내세요.")).containsExactly("정해진 날짜")
        assertThat(findGlossCollisions("납부 정해진 날짜가 지났습니다.")).containsExactly("정해진 날짜")
    }

    @Test
    @DisplayName("한 자리가 여러 패턴에 걸려도 가장 긴 매치 하나만 센다")
    fun `겹치는 매치를 하나로 접는다`() {
        // "정해진 날"('기일')과 "정해진 날짜"('기한')가 같은 자리에 걸린다. 같은 결함을
        // 두 건으로 세면 보정 채택 판정(위반 건수 비교)이 왜곡된다.
        val found = findGlossCollisions("사용 정해진 날짜까지 내세요.")
        assertThat(found).hasSize(1)
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(
        strings = [
            // 형태소로 일반화하면 걸리는 자연 표현 — 사전 값에 앵커링해야 하는 이유.
            "도움 받으실 수 있습니다.",
            "배움을 원하는 분은 신청하세요.",
            // LEXICALIZED_GLOSSES — 명사형이지만 낱말로 굳은 것.
            "알림 문자를 받으세요.",
            "돌봄 서비스를 신청하세요.",
            "이름 하나만 적으세요.",
            "걸림 없이 진행합니다.",
            // 앞 글자가 한글이면 더 긴 낱말의 일부다.
            "줄바꿈 기준으로 나눕니다.",
        ],
    )
    @DisplayName("정상 표현을 비문으로 잡지 않는다")
    fun `오탐이 없다`(sentence: String) {
        assertThat(findGlossCollisions(sentence)).isEmpty()
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(
        strings = [
            "매달 정해진 금액을 드립니다.",
            "미리 정해진 날짜에 오세요.",
            "학생에게 정해진 금액을 드립니다.",
        ],
    )
    @DisplayName("부사·시간명사가 관형구를 꾸미는 정상 문장은 통과한다")
    fun `복합어 앞자리 열거가 오탐을 막는다`(sentence: String) {
        // COMPOUND_HEAD_NOUNS 열거가 이 패턴의 주 방어선이다. "앞에 조사 없는 낱말이 오면
        // 비문"으로 잡으면 이 부류가 무더기로 걸린다.
        assertThat(findGlossCollisions(sentence)).isEmpty()
    }

    @Test
    fun `비문이 없는 문장은 빈 목록이다`() {
        assertThat(findGlossCollisions("오늘 안에 서류를 내세요.")).isEmpty()
    }

    @Test
    fun `빈 입력에서 예외를 던지지 않는다`() {
        assertThat(findGlossCollisions("")).isEmpty()
    }

    @Test
    @DisplayName("검출된 비문은 스타일 검사 결과에 재서술 지시로 실린다")
    fun `checkStyle 이 치환 비문을 지적한다`() {
        val result = checkStyle("뽑음 결과를 알려 드립니다.")
        val issue = result.issues.single { it.reason.startsWith("뜻풀이 축자 삽입") }

        assertThat(issue.reason).isEqualTo("뜻풀이 축자 삽입(뽑음) — 그 뜻이 통하게 문장을 자연스럽게 다시 쓸 것")
        // 처방이 사전값 치환이 아니라 재서술이라 word 를 비운다.
        assertThat(issue.word).isNull()
    }
}
