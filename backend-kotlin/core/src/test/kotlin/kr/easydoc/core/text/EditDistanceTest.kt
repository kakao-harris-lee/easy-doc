package kr.easydoc.core.text

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 수정률의 분자다. **코드 포인트 단위**라는 것이 이 테스트의 핵심 축이다 — 서로게이트 쌍을
 * 두 글자로 세면 이모지 하나를 고친 것이 두 번 고친 것으로 집계돼 수정률이 부풀려진다.
 */
class EditDistanceTest {
    @Test
    fun `빈 문자열끼리는 거리가 0 이다`() {
        assertThat(editDistanceOf("", "")).isZero()
    }

    @Test
    @DisplayName("한쪽만 비면 거리는 다른 쪽의 코드 포인트 수다")
    fun `한쪽이 비면 다른 쪽 길이가 거리다`() {
        assertThat(editDistanceOf("", "안내문")).isEqualTo(3)
        assertThat(editDistanceOf("안내문", "")).isEqualTo(3)
    }

    @Test
    fun `같은 문자열은 거리가 0 이다`() {
        assertThat(editDistanceOf("이 안내문은 쉽습니다.", "이 안내문은 쉽습니다.")).isZero()
    }

    @Test
    @DisplayName("겹치는 문자가 하나도 없으면 긴 쪽 길이가 거리다 — 전면 교체")
    fun `전면 교체의 거리는 긴 쪽 길이다`() {
        assertThat(editDistanceOf("abc", "가나다라")).isEqualTo(4)
    }

    @Test
    fun `한 글자 치환은 거리가 1 이다`() {
        assertThat(editDistanceOf("주민센터", "주민센타")).isEqualTo(1)
    }

    @Test
    fun `한 글자 삽입은 거리가 1 이다`() {
        assertThat(editDistanceOf("안내", "안내문")).isEqualTo(1)
    }

    @Test
    fun `한 글자 삭제는 거리가 1 이다`() {
        assertThat(editDistanceOf("안내문", "안내")).isEqualTo(1)
    }

    @Test
    @DisplayName("고전 예시 kitten → sitting 은 3 이다")
    fun `널리 알려진 예시와 값이 같다`() {
        assertThat(editDistanceOf("kitten", "sitting")).isEqualTo(3)
    }

    @Test
    @DisplayName("좌우를 바꿔도 거리는 같다 — 삽입과 삭제의 비용이 같기 때문이다")
    fun `거리가 대칭이다`() {
        val left = "공공기관 안내문입니다"
        val right = "기관 안내 문서입니다"
        assertThat(editDistanceOf(left, right)).isEqualTo(editDistanceOf(right, left))
    }

    @Test
    @DisplayName("BMP 밖 문자 하나를 바꾼 거리는 1 이다 — 코드 단위로 세면 2 가 된다")
    fun `서로게이트 쌍을 한 글자로 센다`() {
        // U+1F600 GRINNING FACE / U+1F601 GRINNING FACE WITH SMILING EYES. 둘 다 서로게이트 쌍이다.
        assertThat(editDistanceOf("😀", "😁")).isEqualTo(1)
    }

    @Test
    @DisplayName("서로게이트 쌍 하나를 지운 거리는 1 이다")
    fun `서로게이트 쌍 삭제가 한 번으로 센다`() {
        assertThat(editDistanceOf("가😀나", "가나")).isEqualTo(1)
    }

    @Test
    @DisplayName("서로게이트 쌍만으로 이루어진 문자열의 거리는 코드 포인트 수로 잰다")
    fun `서로게이트 쌍 문자열의 길이가 코드 포인트 수다`() {
        assertThat(editDistanceOf("😀😁", "")).isEqualTo(2)
    }

    @Test
    @DisplayName("앞쪽 접두사가 같아도 뒤쪽 차이만큼만 센다")
    fun `공통 접두사는 거리에 들어가지 않는다`() {
        assertThat(editDistanceOf("민원 처리 안내", "민원 처리 방법")).isEqualTo(2)
    }
}
