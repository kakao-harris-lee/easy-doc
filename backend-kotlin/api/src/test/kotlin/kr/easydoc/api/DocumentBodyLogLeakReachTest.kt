package kr.easydoc.api

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.Appender
import kr.easydoc.api.support.CanaryProbe
import kr.easydoc.api.support.MultipartBody
import kr.easydoc.api.support.POSITIVE_CONTROL_MARKER
import kr.easydoc.api.support.RETRO_CANARY_VALUE
import kr.easydoc.api.support.RETRO_CONTROL_MARKER
import kr.easydoc.api.support.UploadFixtures
import kr.easydoc.infrastructure.DatabaseHandle
import kr.easydoc.infrastructure.PostgresTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.slf4j.LoggerFactory
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * **문서 본문·제목·자격증명이 로그에 남지 않는다** — 원장 조건 18(표 18).
 *
 * ## 이 케이스가 **강제 TRACE** 에서 도는 이유
 *
 * 종전 형태는 **제품 기본 로그 구성(root INFO) 그대로** 돌았다. 그것이 증명하는 것은
 * *"기본 레벨에서는 안 새어 나온다"* 뿐이고, **기본 레벨에서 안 새는 이유는 우리 장치가
 * 아니라 로거 레벨이 낮아 그 줄이 아예 방출되지 않기 때문**이다. 즉 억제를 탐지로 오인한
 * 상태였다(게이트 28 교차 종합 #1 — codex F-1 `critical` 과 Claude M-2 가 독립 합의).
 *
 * 원장 조건 18 이 겨눈 축은 **강제 TRACE** 다. 근거는 실측이다
 * (`reviews/03_security-workspaces-fixes_privacy-gate.md` 기록 ③) — 루트를 TRACE 로 올리자
 * 프레임워크 로거 **3종**이 이름·이메일·평문 비밀번호·PHC·토큰을 실제로 찍었다
 * (`Http11InputBuffer` = 원시 요청 바이트 · `StatementCreatorUtils` = JDBC 바인딩 파라미터 ·
 * `QueryExecutorImpl` = 와이어 프로토콜). 기본·DEBUG 에서는 0이었다.
 *
 * 그래서 이 케이스는 **루트 로거를 TRACE 로 올린 상태**에서 요청을 태운다. 강제 TRACE 는
 * 기본 구성의 **상위 집합**이다(어떤 로거의 레벨도 내려가지 않고 방출만 늘어난다) —
 * 그러므로 여기서 0이면 제품 기본 구성에서도 0이며, 종전이 재던 축은 이 축에 포함된다.
 *
 * ## 강제 + 탐지 병용 — 「레벨 고정 = 은폐형」은 오분류였다
 *
 * 종전 KDoc 은 *"로거 레벨을 못박는 것은 은폐형이므로 쓰지 않는다"* 고 적었다. **그 분류가
 * 틀렸다**(게이트 28 리더 판정 P-3). `CLAUDE.md` 규칙 4 의 은폐형은 *"신호를 줄인다 —
 * 무시 패턴·억제·예외 목록·면제 조항"* 인데, 로거를 INFO 로 못박는 것은 신호를 가리는 것이
 * 아니라 **방출 자체를 막는 강제·표현형**이다. 이웃 `application.yml` 이 이미
 * `org.springframework.web`·`org.springframework.security`·`org.flywaydb` 를 그렇게 고정하며
 * 강제와 탐지를 병용하고 있었다.
 *
 * 그래서 이 자리도 **두 겹으로 간다**:
 * - **강제** — `api/src/main/resources/application.yml` 이 그 세 로거를 **이름으로** INFO 에
 *   못박는다. 열거이므로 **네 번째 로거는 못 잡는다**(그 한계는 그 파일 주석에 적혀 있다).
 * - **탐지(이 파일)** — 열거의 빈자리를 이 케이스가 덮는다. **루트만** TRACE 로 올리고
 *   **로거 이름은 모른 채** 방출된 것 전부를 훑으므로, ⑴ 누가 그 고정을 지우면 ⑵ 판올림이
 *   네 번째 로거로 같은 것을 흘리기 시작하면 ⑶ 우리 방어가 사라지면 **그 커밋에서 빨개진다.**
 *   실패 메시지가 **어느 로거·어느 레벨·어느 축**인지 지목한다.
 *
 * 반대 방향의 처방 — *"불변식을 기본 운영 레벨로 축소한다"* — 은 **기각됐다**(같은 판정).
 * 그것이야말로 규칙 4 ⑵ 가 금지한 **면제 조항**이다.
 *
 * ## 「강제 TRACE 에서 오늘 유출이 있다」를 어떻게 다뤘는가
 *
 * 기록 ③ 의 실측이 그것이다. 그래서 이 케이스의 「유출 0」은 **⒝ 의 고정이 실제로 방출을
 * 막을 때만** 성립한다 — 그 조건 위에서 **0을 단언한다**. 유출을 낸 로거를 적어 두고
 * 넘기는 **허용 목록(특성화)을 두지 않았다**: 목록을 두면 그것이 규칙 4 ⑵ 의 면제 조항이
 * 되고, 목록에 든 로거가 다음에 무엇을 더 흘려도 초록이다. 음성 대조 N-A(고정 3줄을 지우면
 * 빨강)가 **0이 억제 덕이 아니라 강제 덕임을** 실행으로 보인다.
 *
 * ## 카나리를 네 축으로 나눈다
 *
 * **본문 · 제목 · 비밀번호 · 액세스 토큰**. 축을 나누지 않으면 「무엇이 샜는가」가 실패
 * 메시지에서 사라지고, 한 축만 막은 구현이 다른 축의 유출을 데리고 통과한다.
 *
 * 토큰은 **발급 전에는 값을 모른다**. 그래서 계정 생성 구간만 이벤트를 **보관**해 두고
 * 발급 직후 그 보관분을 **소급 대조**한다(`rescanRetained`) — 로그인 응답 바이트를 찍는
 * 로거가 있으면 그 자리에서 잡힌다. 문서 요청 구간은 보관 없이 흐름 대조만 한다(강제 TRACE
 * 의 방출량이 힙을 넘길 수 있다).
 *
 * **그 보관·소급 경로는 fail-closed 다.** 종전에는 보관 상한을 넘기면 토큰 축 커버리지를
 * 잃는데도 `hits()` 가 비어 초록이었고, 소급 대조가 빈 큐를 훑어도 초록이었다 — 즉 축이
 * **조용히 죽는** 경로가 둘 있었다(게이트 28 stop-time codex ①). 이제 ⑴ 잘림은 **실패**이고
 * ⑵ 소급 대조가 실제로 무언가를 봤음을 **양성 통제**로 단언한다.
 *
 * ## 실패 메시지에 카나리 값을 절대 싣지 않는다
 *
 * 지목은 CI 로그로 나간다. 그런데 **토큰 축의 카나리는 곧 발급된 액세스 토큰**이므로, 잘라낸
 * 조각을 그대로 실으면 **자격증명 유출을 막으려고 세운 이 케이스가 실패하는 순간 자격증명을
 * 로그에 쓴다** — 실패는 「누가 로거 레벨을 내렸을 때」 나므로 가장 나쁜 순간에 정확히 그
 * 일이 벌어진다(게이트 28 stop-time codex ②). 종전에는 그것뿐 아니라 **본문 축 조각의 앞
 * 문맥에 Bearer 토큰의 꼬리가 딸려 나오기까지** 했다(N-A 실측).
 *
 * 그래서 조각을 **자르기 전에 등록된 카나리 전부를** 축 이름 표식(`«축»`)으로 치환한다.
 * 자르고 나서 치환하면 창 경계에 걸린 **토막**은 그대로 남으므로 순서가 뒤바뀌면 안 된다.
 * 지목에 필요한 것은 로거·레벨·축·**문맥**이고 카나리 값 자체는 필요하지 않다.
 *
 * **축별 예외를 두지 않는다.** 본문·제목 카나리는 합성 문자열이라 실어도 무해하지만, 예외를
 * 두면 그것이 면제 목록이 되어 다음에 실제 비밀이 새 축으로 들어올 때 조용히 새어 나간다
 * (`CLAUDE.md` 규칙 4 ⑵).
 *
 * ## 실패 경로를 함께 태운다
 *
 * 유출은 성공 경로보다 **오류 경로**에서 난다(예외 메시지·스택트레이스가 입력을 담는다).
 * 그래서 거절되는 요청 셋 — 상한 초과 본문 · 손상 파일 · 저장할 수 없는 문자 — 도 같은
 * 캡처 구간에서 돌린다.
 *
 * ## 요청이 **의도한 층**까지 갔음을 단언한다
 *
 * 종전에는 요청 일곱 갈래를 태우고 **응답을 하나도 보지 않았다**(이 파일에 `statusCode()`
 * 적중 0건). 그래서 `POST /documents` 가 어떤 이유로든 이른 단계에서 거절하기 시작하면 —
 * 계약 변경 · 인증 회귀 · 미디어 타입 불일치 · 경로 변경 — 본문과 제목이 **저장 경로에 닿지
 * 않고**, 로그에 카나리가 없는 것은 **동어반복**이 된다. 양성 대조 ⑴⑵ 는 캡처와 레벨 상향을
 * 증명하지만 **트래픽이 처리됐다는 것은 증명하지 않는다**(게이트 28 P-2 다섯 번째 조치 —
 * 같은 종류 다섯 번째이고 blast radius 가 가장 크다: 다른 열두 성질이 전부 참이어도 결론이
 * 무의미해진다).
 *
 * 단언은 **「성공했음」이 아니다.** 일곱 중 셋은 거절이 정상이다(상한 초과 · 손상 파일 ·
 * 저장 불가 문자). 거절 자체는 문제가 아니고 **거절된 층이 의도한 층인지**가 문제다 — 상한
 * 초과가 서비스 층에서 422 로 끊기면 본문이 그 층까지 간 것이고, 같은 요청이 401(인증
 * 깨짐)이나 415(미디어 타입 협상)로 끊기면 본문은 아무 데도 가지 않았다. 그래서 못박는 것은
 * 「거절되지 않았음」이 아니라 **「그 요청이 낸 상태 코드가 의도한 층의 것」**이다.
 *
 * **일곱이다(다섯이 아니다).** 문서 요청은 다섯이지만 계정 생성·로그인 두 갈래도 함께
 * 못박는다 — **비밀번호 축**이 요청 바이트·Argon2·JDBC 를 지나는 자리가 그 둘이고, 거기서
 * 끊기면 그 축이 조용히 죽는다.
 *
 * ### 기대값을 계약에서 읽지 않은 이유
 *
 * [kr.easydoc.api.support.ContractSpec] 이 있고 이 저장소 규약은 「계약이 소유한 값을 옮겨
 * 적지 않는다」다. 그런데 여기서는 **코드 상수**를 골랐다. 사유 셋:
 *
 * 1. **목적이 다르다.** 이 핀이 재는 것은 계약 준수가 아니라 **자극 도달**이다. 계약 준수는
 *    DC-1(성공 상태)·DC-9(상한 초과 422 문자열)·컨테이너 거절 케이스가 이미 잰다. 계약에서
 *    읽으면 같은 것을 두 번 재면서 이 핀의 고유 기능은 오히려 얇아진다.
 * 2. **계약에는 이 일곱 갈래를 가릴 노드가 없다.** `POST /documents` 는
 *    202·401·404·413·415·422·500·503 을 **모두** 선언한다(실측). 「선언된 상태 코드 집합」과
 *    대조하면 401 도 그 집합에 있으므로 **음성 대조 NC-A(전건 401)가 잡히지 않는다** — 이
 *    조치의 목적을 정확히 못 이룬다.
 * 3. **계약 변경이 결함 원인 목록에 들어 있다.** 기대를 계약에서 읽는 핀은 계약 변경을
 *    **구조적으로** 탐지할 수 없다. 코드에 못박으면 계약이 성공 상태를 옮기는 순간 이 핀이
 *    빨개지고, 그 diff 가 「도달 기대가 바뀌었다」를 리뷰에 올린다(`TEST_CLASSES` 규율).
 *
 * 갈래마다 **허용 집합이 아니라 코드 하나**를 못박은 것도 같은 이유다 — 일곱 갈래 전부 층이
 * 하나로 정해져 있고, 집합을 두면 층이 바뀌어도 통과한다.
 *
 * ### 상태 코드만으로는 **422 안의 층**이 갈리지 않는다 — 그래서 `detail` 모양을 함께 잰다
 *
 * 첫 판(`3a3333e`)은 상태 코드만 못박고 이 한계를 KDoc 에 적어 두었다. **적은 것은 옳았고,
 * 그 한계가 성질 11 을 열어 두었다**(게이트 28 stop-time codex 판정). 일곱 중 **셋이 422**
 * 다. 앞단 검증이 그 셋을 전부 거절하기 시작하면 — 요청 스키마가 좁아지거나 컨트롤러에 앞단
 * 검증이 들어오면 — **본문은 도메인에 닿지 않는데 상태 코드는 여전히 422 이고 핀은 초록이다.**
 * 막으려던 동어반복이 **422 팔에서만 그대로 남아 있었다.**
 *
 * 판별자는 이 저장소가 이미 쓰는 것이다:
 *
 * | 거절 층 | `detail` 모양 | 만드는 자리 |
 * |---|---|---|
 * | 스키마·프레임워크 바인딩 | **배열** | `GlobalExceptionHandler.validationError` |
 * | 서비스·도메인 | **문자열** | `mappingFor(EasyDocException)` → `ErrorResponse` |
 *
 * 배열 쪽으로 오는 것은 Jackson 역직렬화 실패 · 누락 파트 · 누락 파라미터 · 타입 불일치 —
 * 곧 **요청이 도메인에 닿기 전에 끊긴 갈래 전부**다.
 *
 * 계약이 `detail` 을 union 으로 둔 이유가 그것이고, 그래서 **각 422 갈래가 낸 `detail` 의
 * 모양**이 도달 증거가 된다. 세 갈래의 의도 층은 **코드로 확정했다**(추측이 아니다):
 *
 * - ⑷ 상한 초과 → `DocumentService.store` 의 `charCount > MAX_CONVERTIBLE_CHARS`
 *   → `InvalidInputException` → **문자열**. 이 필드에 Bean Validation 을 달지 않는 것은
 *   계약 F3 이고 `RequestFieldConstraintLayerTest` 가 애너테이션 부재로 강제한다.
 * - ⑸ 손상 파일 → `DocxExtractor` → `DocumentExtractionException` → **문자열**.
 * - ⑹ 저장 불가 문자 → `PlainBody.init` → `InvalidInputException` → **문자열**.
 *
 * 셋 다 문자열이므로 **배열이 나오면 그 갈래는 도메인에 닿지 않았다.**
 *
 * ### 이것이 DC 케이스·F3 강제자와 같은 것을 두 번 재는 것인가
 *
 * 아니다 — **재는 대상이 같고 재는 장소가 다르다.** DC-9 는 계약 준수를(그리고 MockMvc
 * 슬라이스에서, 가짜 저장소로) 재고, `RequestFieldConstraintLayerTest` 는 **애너테이션 부재**
 * 라는 정적 상태를 잰다. 둘 다 **이 케이스의 트래픽이 도메인에 닿았는지**는 말하지 않는다.
 * 도달 증거는 **카나리를 실어 보낸 그 요청들에서** 나와야 하고, 다른 테스트의 결론을 빌리는
 * 것이 이 하네스가 반복해 겪은 「선언한 범위와 실제 도달」의 어긋남이다. 또 F3 강제자는
 * 금지 애너테이션 **열거**라 목록에 없는 앞단 검증(컨트롤러 가드·Jackson 설정·인터셉터)은
 * 보지 못한다 — 음성 대조 NC-A 가 그 빈자리를 통과해 들어온 변이다.
 *
 * ### 여전히 가리지 못하는 것 (성질 11 의 잔여)
 *
 * 모양은 **스키마 층과 도메인 층**을 가르지만 **도메인 안에서 어느 지점인지**는 가르지
 * 않는다. 컨트롤러가 던지는 `InvalidInputException`(`MISSING_FILE_PART_MESSAGE` ·
 * `INVALID_WORKSPACE_ID_MESSAGE`)도 문자열이므로, ⑸ 가 파서 대신 **컨트롤러**에서 끊기면
 * 바이트는 추출기에 닿지 않는데 모양은 그대로 문자열이다. `CanaryProbe` 성질 표에 그 잔여를
 * 적어 두었다 — 표가 거짓이면 그 표가 다음 빈 칸을 가린다.
 *
 * 실패 메시지에 응답 **본문**을 싣지 않는다 — 값이 흘러들 통로 자체가 없다. [ReachLog] 가
 * 보관하는 것은 `Int` 와 [DetailShape] 열거값이고 라벨은 컴파일 상수다. 본문은 모양을 뽑는
 * 즉시 버리며, **파싱 예외도 버린다** — Jackson 예외 메시지에는 문제가 된 입력 조각이 들어
 * 있다. `residualCanaryFragments()` 와 같은 요구를 다른 기제로 막는다(검사가 아니라 구조다).
 *
 * ## 성공 팔은 **응답이 아니라 저장 상태**로 잰다
 *
 * `202/NONE` 은 **「컨트롤러가 접수하기로 했다」는 응답 사실**이고 **「본문이 암호화돼
 * Postgres 행으로 써졌다」는 저장 사실이 아니다.** 지속화를 건너뛰는 변경(단축·스텁·플래그·
 * 트랜잭션 경계 이동)이 들어오면 카나리는 JDBC 에 닿지 않는데 응답은 그대로 202 이고,
 * 「로그에 카나리 0건」이 다시 **동어반복**이 된다.
 *
 * `88e55b1` 까지의 판단은 *"성공 상태는 이른 거절로 위조할 수 없으므로 ⑵·⑶ 는 저장 경로를
 * 진다"* 였다. **뒷문장은 참이고 앞문장으로 이어지지 않는다** — 「이른 거절이 아님」과
 * 「저장 경로를 지났음」은 다른 명제다. 실측이 그것을 확인했다: 서비스에서 삽입 세 건만
 * 건너뛰게 하자 두 팔 다 202 를 내고 행은 하나도 생기지 않았는데 **핀은 초록**이었다
 * (실패 0건). 같은 종류 일곱 번째이고, 앞의 여섯과 달리 **장치가 아니라 판단의 근거**가
 * 성질을 겨누지 못한 경우다.
 *
 * 그래서 [assertPersistedByStorageState] 가 **DB 쪽에서** 본다. 응답 본문의 식별자를 근거로
 * 쓰지 않는다 — 그것도 응답 사실이다. 이 저장소가 이미 쓰는 기법이다(DC-24 가 「저장 안 됨」을
 * `documents` 행 수로 잰다).
 *
 * | 저장 사실 | 왜 그것인가 |
 * |---|---|
 * | `documents` 를 `source_format` 별로 센다 | **팔이 1:1 로 갈린다** — 아래 ⑴ |
 * | `conversions`·`conversion_jobs` 도 2 씩 | **한 트랜잭션**인지 본다 — 아래 ⑵ |
 * | 암호문이 자리표시자가 아니다 | 아래 「평문을 꺼내지 않는다」 |
 * | 제목 카나리가 실린 행이 정확히 1 | 제목 축도 저장까지 갔는지 본다 — 아래 ⑶ |
 *
 * ⑴ `text`=⑵ 붙여넣기 · `docx`=⑶ 파일 업로드다. 한 팔만 빠지면 없어진 항목이 **그 팔을
 * 지목한다.** 총 2 라는 사실이 거절된 세 갈래가 저장되지 않았음도 함께 잰다.
 *
 * ⑵ 문서·변환·작업 세 행이 같은 트랜잭션에서 확정된다(계획 §4.4). 문서 행만 쓰고 큐 등록이
 * 빠지는 경계 이동은 ⑴ 로는 보이지 않는다.
 *
 * ⑶ ⑵ 의 주석이 *"본문·제목이 저장 경로 전체를 지난다"* 라고 적었으므로 **제목도** 재야 그
 * 문장이 참이 된다. ⑶ 은 제목을 주지 않아 대체 제목이므로 카나리가 실린 행은 1 이다.
 *
 * ### 평문을 꺼내 비교하지 않는다 — **성질**로 본다
 *
 * 행이 있어도 본문이 빈 값이면 카나리는 암호화 경로를 지나지 않았다. 그렇다고 평문을 꺼내
 * 대조하면 **이 장치가 막으려는 것과 같은 노출**이다. 그래서 두 열의 **관계**로 본다:
 *
 * > `octet_length(source_text_encrypted) > char_count` 이고 `char_count > 0`
 *
 * AEAD 는 길이를 줄이지 않고(nonce + 암호문 + 태그), 평문의 UTF-8 바이트 수는 코드 포인트
 * 수(`charCountOf`)보다 작을 수 없다. 그러므로 **진짜 암호문은 언제나 `char_count` 를
 * 넘는다.** 한 바이트 자리표시자나 빈 배열은 넘지 못한다. 위반 행 수를 세어 **0** 을
 * 단언하므로 실패 메시지에도 값이 실리지 않는다(마법 상수도 없다 — 두 열의 관계다).
 *
 * 제목은 `documents.title` 이 **평문 열**이라 값 대조가 가능한데, **needle 을 SQL 에 넣지
 * 않는다**: 제목을 읽어 와 **테스트 안에서** 세고 `Int` 만 남긴다. SQL 에 카나리를 실으면
 * 그 문장이 JDBC 로거를 타는 날 이 케이스가 **스스로** 빨개진다.
 *
 * ### DC 케이스와 같은 것을 두 번 재는 것인가
 *
 * 아니다 — 세 번째로 같은 답이다. DC-24 는 **거절된** 업로드가 저장되지 않음을 재고, 이쪽은
 * **접수된** 업로드가 저장됨을 잰다(반대 방향). 그리고 DC 케이스는 그 케이스의 트래픽에
 * 대해서만 말한다 — **카나리를 실어 보낸 이 요청들이 저장에 닿았는지**는 여기서만 잰다.
 *
 * ### 실행 시간 — **컨테이너가 새로 붙지는 않는다**
 *
 * 이 케이스는 저장 핀이 붙기 **전부터** Testcontainers 를 썼다(아래 `database` — 클래스마다
 * 빈 데이터베이스를 하나 만든다). 그래서 이번 변경으로 **컨테이너 기동이 늘지 않고**, 더해진
 * 것은 판정 구간의 `SELECT` **다섯 건**뿐이고, **그 다섯이라는 사실 자체가 단언으로 고정돼
 * 있다**(`EXPECTED_STORAGE_QUERIES`). `withFailMessage` 의 인자는 **성공 경로에서도**
 * 평가되므로 단언 안에서 질의를 호출하면 같은 질의가 두세 번 나간다 — 초판이 그래서 다섯 건이
 * 아니라 **열한 건**을 쏘고 있었다. 「한 번씩만 돌린다」를 관측으로만 두면 다시 늘어도 아무것도
 * 빨개지지 않으므로 계수기를 붙였다.
 *
 * **증분을 수치로 주장하지 않는다.** 실측이 핀 이전 1.012s · 이후 1.371s · 전체 빌드 안에서
 * 0.856s 였다 — **회차 간 흔들림이 더해진 비용보다 크다.** 다섯 건의 `SELECT` 를 「측정된
 * 증분」으로 적으면 없는 정밀도를 주장하는 것이므로, 확실한 것만 적는다: 컨테이너는 늘지
 * 않았고 질의 다섯 건이 늘었다.
 *
 * 개발용 `easydoc_kotlin` 은 건드리지 않는다 — 그 DB 를 쓰면 Flyway 가 무대를 바꾼다.
 *
 * ## 양성 대조 셋
 *
 * ⑴ 「0건」은 **캡처가 비어 있어도** 참이다. 요청 전에 표식을 직접 찍고 그것이 캡처에
 * 있는지 먼저 본다. ⑵ 강제 TRACE 축에서는 표식 하나로 부족하다 — **TRACE 이벤트가 실제로
 * 방출됐는지**(레벨 상향이 먹었는지)도 함께 단언한다. 레벨을 올리지 못한 채 「0건」을
 * 보고하는 것이 이 케이스의 가장 조용한 실패이고, 그것이 종전 형태의 결함이었다.
 * ⑶ 앞의 둘은 **캡처와 레벨 상향**만 증명하고 **보관·소급 대조 경로**는 증명하지 않는다.
 * 그래서 계정 생성 구간에 표식을 하나 더 방출해 두고 `rescanRetained()` 가 **그것을 찾는지**
 * 단언한다 — 「빈 큐를 훑고도 초록」이 불가능해진다.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["easydoc.auth.jwt-secret=$DOCUMENT_REACH_TEST_SECRET"],
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DocumentBodyLogLeakReachTest {
    @LocalServerPort
    private var port: Int = 0

    private val json = ObjectMapper()

    @Test
    @DisplayName("문서 본문·제목·자격증명 카나리가 **강제 TRACE** 의 어느 로그에도 남지 않는다 (양성 대조 2종 포함)")
    fun `문서 본문이 강제 TRACE 로그로도 새지 않는다`() {
        val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as ch.qos.logback.classic.Logger
        val probe = CanaryProbe(RETRO_CONTROL_MARKER)
        val reach = ReachLog(json)
        probe.addCanary(BODY_AXIS, BODY_CANARY)
        probe.addCanary(TITLE_AXIS, TITLE_CANARY)
        probe.addCanary(PASSWORD_AXIS, PASSWORD_CANARY)

        // 콘솔 appender 를 잠시 떼어 둔다 — 강제 TRACE 의 방출량이 빌드 로그를 덮는 것을
        // 막을 뿐이고, **판정은 probe 가 받은 전량으로 한다**(신호를 줄이지 않는다).
        val detached: List<Appender<ILoggingEvent>> = root.iteratorForAppenders().asSequence().toList()
        val restoreLevel: Level? = root.level
        try {
            detached.forEach { root.detachAppender(it) }
            root.addAppender(probe)
            root.level = Level.TRACE

            LoggerFactory.getLogger(javaClass).warn(POSITIVE_CONTROL_MARKER)
            // 보관 구간에만 있는 표식 — `rescanRetained()` 가 이것을 찾아야 그 경로가
            // 빈 큐를 훑지 않았음이 증명된다(양성 대조 ⑶).
            LoggerFactory.getLogger(javaClass).warn(RETRO_CONTROL_MARKER)
            // 보관 구간에만 방출하고 **구간이 끝난 뒤 토큰과 같은 방식으로** 등록한다.
            // 그래야 「늦게 등록된 카나리가 보관분에 대해 적중을 낸다」가 단언 가능해진다.
            LoggerFactory.getLogger(javaClass).warn("late canary $RETRO_CANARY_VALUE emitted")

            // ⑴ 계정 생성 — 평문 비밀번호가 요청 바이트·Argon2·JDBC 를 지난다. 이 구간만
            //    보관해 두고, 토큰을 손에 쥔 직후 소급 대조한다.
            val token = newAccount(reach)
            probe.addCanary(TOKEN_AXIS, token)
            // 토큰과 **같은 자리에서 같은 방식으로** 등록한다 — 이 줄이 토큰 등록과 함께
            // 움직이지 않으면 아래 통제 단언이 그 사실을 잡는다.
            probe.addControlCanary(RETRO_CONTROL_AXIS, RETRO_CANARY_VALUE)
            probe.rescanRetained()
            probe.stopRetaining()

            // ⑵ 성공 — 본문·제목이 저장 경로 전체(암호화·JDBC·트랜잭션)를 지난다.
            createFromText(reach, STEP_TEXT, token, textBody("$BODY_CANARY 안내 본문입니다", TITLE_CANARY))
            // ⑶ 파일 모드 — multipart 파싱과 파서까지 지난다.
            upload(reach, STEP_FILE, token, docxPart(UploadFixtures.sampleDocx()))
            // ⑷ 상한 초과 — 서비스 층 거절.
            createFromText(reach, STEP_OVER_LIMIT, token, overLimitBody())
            // ⑸ 손상 파일 — 파서 예외가 도메인 예외로 바뀌는 경로.
            upload(reach, STEP_BROKEN_FILE, token, docxPart(BODY_CANARY.toByteArray(Charsets.UTF_8)))
            // ⑹ 저장할 수 없는 문자 — `PlainBody` 거절 경로.
            createFromText(reach, STEP_UNSAVABLE, token, """{"text":"$BODY_CANARY\ud800"}""")
        } finally {
            root.level = restoreLevel
            root.detachAppender(probe)
            detached.forEach { root.addAppender(it) }
            probe.stop()
        }

        assertNoCanaryInLogs(probe, reach)
    }

    /**
     * 판정부. 트래픽을 태우는 부분과 분리해 둔다 — 단언이 일곱 축(**도달**·**저장**·캡처·
     * 레벨 상향·보관 잘림·소급 대조·적중)이라 한 함수에 두면 「무엇을 재는 함수인가」가
     * 흐려진다.
     *
     * **자극 축이 맨 앞이다.** 자극이 의도한 층에 닿지 않았거나 저장까지 가지 않았으면 나머지
     * 다섯 축의 결론이 전부 무의미하므로, 진단이 그 사실부터 말하게 한다. 앞 회차가 잔여
     * 단언을 지목 단언보다 앞에 둔 것과 같은 이유다. 둘 사이의 순서는 인과다 — 응답이 먼저
     * 어긋나면 저장 개수는 뒤따라 어긋나므로, 응답 쪽 진단이 먼저 나가야 원인이 보인다.
     *
     * **저장 단언은 캡처 구간이 닫힌 뒤에 돈다.** 이 함수는 `finally` 가 probe 를 떼어낸 뒤에
     * 불리므로 그 조건이 이미 성립한다 — **여기 있는 단언을 캡처 구간 안으로 옮기지 마라.**
     * 옮기면 조회 SQL 자체가 강제 TRACE 로 캡처되어 이 케이스가 스스로 빨개진다.
     */
    private fun assertNoCanaryInLogs(
        probe: CanaryProbe,
        reach: ReachLog,
    ) {
        assertReachedIntendedLayers(reach)
        assertPersistedByStorageState()
        assertMeasuredAxisInventory(probe)
        // 양성 대조 ⑴ — 캡처가 살아 있는가.
        assertThat(probe.sawPositiveControl())
            .withFailMessage("표식이 캡처에 없다 — 이 케이스는 아무 로그도 보고 있지 않다")
            .isTrue()
        // 양성 대조 ⑵ — 레벨 상향이 실제로 먹었는가. 이것이 없으면 「0건」은
        // 「TRACE 를 켜지 못했다」와 구분되지 않는다 — 그것이 종전 형태의 결함이다.
        assertThat(probe.traceEvents())
            .withFailMessage(
                "강제 TRACE 에서 TRACE 이벤트가 0건이다 — 루트 레벨 상향이 먹지 않았다. " +
                    "이 상태의 「유출 0」은 억제이지 탐지가 아니다(총 %d 이벤트).",
                probe.totalEvents(),
            ).isNotZero()

        // 양성 대조 ⑶-a — 보관이 잘렸으면 토큰 축을 다 재지 못한 것이다. 「다 재지 못했다」는
        // 통과 사유가 아니므로 **실패**로 만든다(종전에는 초록이었다).
        assertThat(probe.retainTruncated())
            .withFailMessage(
                "보관이 잘렸다 — 계정 생성 구간 %d자가 상한 %d자를 넘겨 소급 대조가 전량이 아니다. " +
                    "이 상태에서는 **토큰 축 커버리지를 잃는다**. 상한을 올려 미루기 전에 " +
                    "보관 구간이 왜 그만큼 커졌는지 답하라.",
                probe.retainedCharsSeen(),
                probe.retainCharLimit(),
            ).isFalse()
        assertRetroMatchIsMeasured(probe)

        // 이 케이스의 실패 메시지는 CI 로그로 나간다. 그 메시지에 카나리 **원문 조각**이
        // 실리면 자격증명 유출을 막으려던 장치가 유출 경로가 된다 — 실제 카나리(150자 JWT)와
        // 실제 프레임워크 로그 줄로 재는 자리는 여기뿐이다(단위 케이스는 합성 입력으로 잰다).
        // **한 번만 계산한다.** `residualCanaryFragments()` 는 등록된 카나리 × 길이 12 이상
        // 부분 문자열 전부를 지목 줄 전량과 대조하므로 이 파일에서 가장 비싼 호출이고, 종전에는
        // 단언 주체와 메시지 인자에서 **두 번** 돌았다(`withFailMessage` 인자는 성공 경로에서도
        // 평가된다). 저장 질의와 같은 결함이고 같은 처방을 쓴다.
        val residual = probe.residualCanaryFragments()
        assertThat(residual)
            .withFailMessage(
                "실패 메시지가 카나리 원문 조각을 실어 나른다 — 일치한 자리 %s. " +
                    "조각 값은 찍지 않는다. `CanaryProbe.snippet` 의 치환이 **자르기와 등록 " +
                    "양쪽보다 먼저**인지 보라(같은 결함이 두 번 났다).",
                residual.take(5),
            ).isEmpty()

        assertThat(probe.hits())
            .withFailMessage(
                "강제 TRACE 로그에 카나리가 실렸다 — 아래가 지목이다.%n%s%n" +
                    "고칠 방향: 지목된 로거를 `api/src/main/resources/application.yml` 의 명시 고정에 " +
                    "이름으로 더하고(강제), 이 케이스는 그대로 둔다(탐지). **기대값을 낮춰 덮지 마라** — " +
                    "허용 목록은 CLAUDE.md 규칙 4 ⑵ 가 금지한 면제 조항이다.",
                probe.report(),
            ).isEmpty()
    }

    /**
     * **도달 핀** — 요청 일곱 갈래가 각각 **의도한 층**에서 처리·거절됐다.
     *
     * 축이 둘이다: **상태 코드**와 **`detail` 모양**. 상태 코드만으로는 셋이 같은 422 라
     * 「422 안에서 어느 층인지」가 갈리지 않고, 그 빈자리가 성질 11 을 열어 두었다
     * (게이트 28 stop-time codex). 왜 그 형태인지·기대값을 계약에서 읽지 않은 이유·세 갈래의
     * 의도 층을 무엇으로 확정했는지·여전히 가리지 못하는 것은 클래스 KDoc 의 「요청이
     * **의도한 층**까지 갔음을 단언한다」에 있다.
     *
     * 형태는 재고 핀과 같은 **정확 열거 핀**이고 세 방향을 함께 잰다:
     *
     * - **비면** 0건 검사가 된다 → 선언 자체가 비었는지 먼저 본다(`CLAUDE.md` 규칙 4 ⑶).
     * - **부분집합만** 보면 요청이 사라져도 통과한다 → 없어진 요청을 잡는다.
     * - **상위집합만** 보면 요청이 조용히 늘어난다 → 새로 생긴 요청을 잡는다.
     *
     * 순서까지 못박는다(집합이 아니라 목록으로 대조한다) — 보관 구간이 계정 생성 두 갈래로
     * 한정되어 있어 **요청 순서가 바뀌면 토큰 축의 소급 대조 범위가 달라진다.**
     */
    private fun assertReachedIntendedLayers(reach: ReachLog) {
        val observed = reach.observed()
        // ⑴ 선언이 비어 있지 않은가 — 규칙 4 ⑶. 이것이 없으면 아래 대조가 0건 검사로 통과한다.
        assertThat(EXPECTED_REACH)
            .withFailMessage(
                "도달 기대의 선언이 비었다 — 아래 대조가 0건 검사가 된다(CLAUDE.md 규칙 4 ⑶). " +
                    "요청을 정말 다 뺐다면 이 케이스를 지워야 하고, 그 diff 가 신고다.",
            ).isNotEmpty()
        // ⑵ 개수도 함께 못박는다 — 한 갈래를 빼고 다른 갈래를 넣는 편집이 두 자리에서 난다.
        assertThat(EXPECTED_REACH)
            .withFailMessage(
                "선언 개수(%d)가 상수(%d)와 다르다 — 둘을 함께 고쳐라",
                EXPECTED_REACH.size,
                EXPECTED_REACH_COUNT,
            ).hasSize(EXPECTED_REACH_COUNT)
        // ⑶ 실제 관측과 **정확 일치**(순서 포함). 삭제·추가·층 변경이 모두 잡힌다.
        assertThat(observed)
            .withFailMessage(
                "카나리가 **의도한 층**에 도달하지 않았다 — 아래가 지목이다(상태/모양).%n%s%n" +
                    "이 상태의 「유출 0건」은 **동어반복**이다: 본문·제목이 그 층에 닿지 않았으니 " +
                    "로그에 없는 것이 당연하다. 다른 열두 성질이 전부 참이어도 결론이 없다.%n" +
                    "거절 자체는 결함이 아니다(셋은 거절이 정상이다) — **거절된 층이 다른 것**이 " +
                    "결함이다. 401 은 인증이, 415 는 미디어 타입 협상이, 404·405 는 경로·메서드가 " +
                    "끊었다는 뜻이고 그 어디서도 본문은 제품 경로를 지나지 않는다.%n" +
                    "**모양이 %s 로 바뀌었으면 스키마·프레임워크 바인딩 층이 먼저 물었다는 뜻**이고, " +
                    "그 갈래의 본문은 도메인에 닿지 않았다 — 앞단 검증·요청 스키마 제약이 들어왔는지 " +
                    "보라(계약 F3). **기대값을 실제에 맞춰 덮지 마라** — 그러면 이 핀이 아무것도 " +
                    "재지 않는다.",
                reachDiff(observed),
                DetailShape.ARRAY,
            ).isEqualTo(EXPECTED_REACH)
    }

    /**
     * 도달 지목. **라벨·정수·열거값만** 담는다 — 응답 본문은 [ReachLog] 가 애초에 들고 있지 않다.
     *
     * 한 갈래만 어긋나면 **그 한 줄만** 나오게 만든다. 뭉개지면 「어느 요청이 층을 잃었는지」가
     * 사라지고, 그것을 잃으면 이 핀은 「어딘가 틀렸다」밖에 말하지 못한다. 두 축을
     * `상태/모양` 한 쌍으로 찍는 이유도 같다 — 어느 축이 어긋났는지가 한눈에 보여야 한다.
     */
    private fun reachDiff(observed: List<Reached>): String {
        val actualByStep = observed.associateBy { it.step }
        val lines = mutableListOf<String>()
        EXPECTED_REACH.forEach { expected ->
            val actual = actualByStep[expected.step]
            when {
                actual == null -> lines += "  · ${expected.step} — 이 요청이 나가지 않았다(기대 ${expected.mark()})"
                actual != expected -> lines += "  · ${expected.step} — 기대 ${expected.mark()} · 실제 ${actual.mark()}"
            }
        }
        (actualByStep.keys - EXPECTED_REACH.map { it.step }.toSet()).sorted().forEach { step ->
            lines += "  · $step — 선언에 없는 요청이 늘었다(실제 ${actualByStep[step]?.mark()})"
        }
        if (lines.isEmpty()) {
            // 두 축이 다 맞는데 목록이 다르다 = 순서가 바뀌었거나 같은 갈래가 두 번 나갔다.
            lines += "  · 상태·모양은 전부 같고 **순서**가 다르다(또는 같은 갈래가 두 번 나갔다)."
            lines += "    기대 ${EXPECTED_REACH.map { it.step }}"
            lines += "    실제 ${observed.map { it.step }}"
        }
        return lines.joinToString(System.lineSeparator())
    }

    /**
     * **저장 핀** — 접수된 두 팔이 실제로 **Postgres 행**이 됐다.
     *
     * 왜 응답이 아니라 저장 상태인지 · 무엇을 저장 증거로 골랐는지 · 평문을 꺼내지 않는 방법은
     * 클래스 KDoc 의 「성공 팔은 **응답이 아니라 저장 상태**로 잰다」에 있다.
     *
     * 이 함수는 **캡처 구간이 닫힌 뒤에만** 불려야 한다([assertNoCanaryInLogs] 참고).
     */
    private fun assertPersistedByStorageState() {
        // 질의를 **먼저 한 번씩만** 돌린다. `withFailMessage` 의 인자는 **성공 경로에서도
        // 평가되므로**(AssertJ 는 지연 평가가 아니다) 단언 안에서 호출하면 같은 질의가 회차마다
        // 두세 번 나간다 — 초판이 그래서 다섯 건이 아니라 열한 건을 쏘고 있었다.
        val documentRows = documentRowsByFormat()
        val conversionRows = rowCount(CONVERSIONS_TABLE)
        val jobRows = rowCount(JOBS_TABLE)
        val placeholderRows = placeholderCipherRows()
        val titleCanaryRows = storedTitleCanaryRows()

        // ⑴ 선언이 비어 있지 않은가 — 규칙 4 ⑶.
        assertThat(EXPECTED_DOCUMENT_ROWS)
            .withFailMessage("저장 기대의 선언이 비었다 — 아래 대조가 0건 검사가 된다(CLAUDE.md 규칙 4 ⑶).")
            .isNotEmpty()
        // ⑵ 어느 팔이 저장됐는지 **정확 열거**. `source_format` 이 팔과 1:1 이라 지목이 된다.
        assertThat(documentRows)
            .withFailMessage(
                "저장된 문서 행이 선언과 다르다.%n  없어진 것(선언에만): %s%n  새로 생긴 것(실제에만): %s%n" +
                    "  `text` 는 ⑵ 붙여넣기, `docx` 는 ⑶ 파일 업로드다 — **없어진 쪽이 저장 경로에 닿지 " +
                    "않은 팔**이고, 그 팔의 카나리는 암호화·JDBC 를 지나지 않았다. 202 는 「접수하기로 " +
                    "했다」는 응답 사실이지 「행이 써졌다」는 저장 사실이 아니다.",
                (EXPECTED_DOCUMENT_ROWS - documentRows.toSet()).ifEmpty { listOf("없음") },
                (documentRows - EXPECTED_DOCUMENT_ROWS.toSet()).ifEmpty { listOf("없음") },
            ).isEqualTo(EXPECTED_DOCUMENT_ROWS)
        // ⑶ 세 행이 **한 트랜잭션**에서 확정됐는가(계획 §4.4). 문서만 쓰고 큐가 빠지는 경계
        //    이동은 위 ⑵ 로는 보이지 않는다.
        assertThat(listOf(conversionRows, jobRows))
            .withFailMessage(
                "변환·작업 행 수가 문서 행 수와 어긋난다(변환 %d · 작업 %d, 둘 다 %d 이어야 한다) — " +
                    "문서·변환·작업이 한 트랜잭션에서 확정된다는 계획 §4.4 가 깨졌다.",
                conversionRows,
                jobRows,
                EXPECTED_DOCUMENT_ROWS.size,
            ).containsOnly(EXPECTED_DOCUMENT_ROWS.size)
        // ⑷ 암호문이 자리표시자가 아닌가 — **평문을 꺼내지 않고 두 열의 관계**로 본다.
        assertThat(placeholderRows)
            .withFailMessage(
                "본문이 행에 실제로 들어가지 않은 문서 행이 %d 건이다 — `char_count <= 0` 이거나 " +
                    "암호문 길이가 `char_count` 이하다. AEAD 는 길이를 줄이지 않고 평문의 UTF-8 " +
                    "바이트 수는 코드 포인트 수보다 작을 수 없으므로, 진짜 암호문은 언제나 " +
                    "`char_count` 를 넘는다. 넘지 못하면 카나리가 암호화 경로를 지나지 않았다.",
                placeholderRows,
            ).isEqualTo(EXPECTED_PLACEHOLDER_ROWS)
        // ⑸ 제목 축도 저장까지 갔는가. ⑵ 의 주석이 「본문·제목이 저장 경로 전체를 지난다」라고
        //    적었으므로 제목을 재지 않으면 그 문장이 근거 없이 남는다.
        assertThat(titleCanaryRows)
            .withFailMessage(
                "제목 카나리가 실린 문서 행이 %d 건이다(%d 이어야 한다) — 제목이 저장 경로를 " +
                    "지나지 않았거나 다른 값으로 바뀌었다. **값은 찍지 않는다.**",
                titleCanaryRows,
                EXPECTED_TITLE_CANARY_ROWS,
            ).isEqualTo(EXPECTED_TITLE_CANARY_ROWS)
        assertStorageQueriesRanOnce()
    }

    /**
     * **질의 횟수 핀** — 판정 구간에서 저장 질의가 정확히 [EXPECTED_STORAGE_QUERIES] 번 나갔다.
     *
     * 「한 번씩만 돌린다」를 관측으로만 두면 다시 늘어도 아무것도 빨개지지 않는다 — 이 세션이
     * 일곱 번 겪은 「관측은 했는데 장치가 없다」다.
     *
     * **이 단언은 반드시 판정부의 맨 뒤여야 한다.** 처음에는 다섯 줄을 읽은 **직후**에 두었는데,
     * 그 자리에서는 계수기가 아직 5 라서 **되돌린 즉시 평가를 잡지 못했다**(음성 대조 NC-D 가
     * 초록으로 나와 그 사실을 드러냈다). 재-인라인된 질의는 **뒤 단언의 메시지 인자가 평가될
     * 때** 나가므로, 그때보다 뒤에서 세야 보인다.
     *
     * 맞바꾼 것: 앞 단언이 실패하면 이 단언은 평가되지 않는다. 그것을 받아들이는 이유는 이 핀이
     * 지키려는 것이 **초록 회차의 실행 시간**이기 때문이다 — 이미 빨간 회차에서는 앞의 진단이
     * 더 중요하다.
     */
    private fun assertStorageQueriesRanOnce() {
        assertThat(storageQueries)
            .withFailMessage(
                "저장 질의가 %d 번 나갔다(%d 이어야 한다). `withFailMessage` 의 인자는 **성공 " +
                    "경로에서도** 평가되므로 단언 안에서 질의를 호출하면 회차마다 두세 번 나간다 — " +
                    "값을 **먼저 한 번** 읽어 지역 변수로 써라. 실 DB 왕복이라 Testcontainers " +
                    "실행 시간에 그대로 실린다.",
                storageQueries,
                EXPECTED_STORAGE_QUERIES,
            ).isEqualTo(EXPECTED_STORAGE_QUERIES)
    }

    /**
     * 저장 질의가 나간 횟수. **「한 번씩만」을 단언으로 만드는 계수기**다.
     *
     * 네 헬퍼가 각자 증가시키고 [EXPECTED_STORAGE_QUERIES] 와 대조된다 — 누가 질의를 다시
     * `withFailMessage` 인자로 옮기면 그 회차에서 개수가 어긋난다.
     */
    private var storageQueries = 0

    /** `형식=행수` 목록. 형식 이름과 개수만 나오므로 값이 실리지 않는다. */
    private fun documentRowsByFormat(): List<String> {
        storageQueries++
        return database.queryFirstColumn(
            "SELECT source_format || '=' || count(*)::text FROM documents GROUP BY source_format ORDER BY 1",
        )
    }

    private fun rowCount(table: String): Int {
        storageQueries++
        return database.queryInt("SELECT count(*) FROM $table")
    }

    /**
     * 암호문이 자리표시자인 행 수. **평문을 꺼내지 않는다** — 두 열의 관계만 본다.
     */
    private fun placeholderCipherRows(): Int {
        storageQueries++
        return database.queryInt(
            "SELECT count(*) FROM documents " +
                "WHERE char_count <= 0 OR octet_length(source_text_encrypted) <= char_count",
        )
    }

    /**
     * 제목 카나리가 실린 행 수.
     *
     * **needle 을 SQL 에 넣지 않는다** — 제목을 읽어 와 테스트 안에서 센다. SQL 에 카나리를
     * 실으면 그 문장이 JDBC 로거를 타는 날 이 케이스가 스스로 빨개진다. 반환형이 `Int` 라
     * 읽어 온 값이 호출자에게 넘어가지 않는다([ReachLog] 와 같은 규율).
     */
    private fun storedTitleCanaryRows(): Int {
        storageQueries++
        return database.queryFirstColumn("SELECT title FROM documents").count { it == TITLE_CANARY }
    }

    /**
     * **재고 핀** — 재고 있는 축의 집합이 선언과 **정확히 일치**하고, 그 선언이 **비어 있지 않다**.
     *
     * 앞의 장치들이 지킨 것은 **기제**다(늦은 등록이 작동하는가 · 소급이 카나리 집합을 지나는가).
     * **재고**는 아무도 지키지 않았다 — 축 등록을 통째로 지우면 통제 카나리는 별개 값이라 그대로
     * 적중하고, `pendingRetroMatches()` 는 등록되지 않은 축에 항목을 만들지 않아 비울 것이 없다.
     * 그래서 토큰 축을 지워도 **전부 초록**이었다(게이트 28 stop-time codex, 같은 종류 네 번째).
     *
     * 등록부는 `CLAUDE.md` 규칙 4 의 **범위 선언형**이다 — 무엇을 검사할지 스스로 열거한다.
     * 규칙 4 ⑶: *"범위 선언형은 빈 선언에서 통과하면 안 된다. 최대 위험은 좁게 선언되는 것이
     * 아니라 아무것도 선언되지 않은 채 초록이 되는 것이다."* 그래서 두 방향을 함께 단언한다:
     *
     * - **부분집합만** 보면 축이 지워져도 통과한다 → 없어진 축을 잡는다.
     * - **상위집합만** 보면 축이 조용히 늘어난다 → 새로 생긴 축을 잡는다.
     * - **비면** 0개 검사가 된다 → 선언 자체가 비었는지 먼저 본다.
     *
     * 형태는 이 저장소가 이미 쓰는 정확 열거 핀이다(`TEST_CLASSES`·`EXPECTED_STATEMENTS`·
     * `EXPECTED_DEFINITION_ROWS`) — 핀이 늘거나 줄면 **그 diff 가 리뷰에 올라오는 것**이 작동
     * 방식이다. 패턴 면제(축 이름 접두 예외)로 만들지 않았다: 그것이 규칙 4 ⑵ 다.
     *
     * **통제 축도 함께 덮는다** — 그 축이 지워지면 다른 단언의 근거가 사라지므로 그것도 결함이다.
     * 축 **이름**만 다루고 needle **값**은 다루지 않는다(이름은 비밀이 아니고 값은 비밀이다).
     */
    private fun assertMeasuredAxisInventory(probe: CanaryProbe) {
        // ⑴ 선언이 비어 있지 않은가 — 규칙 4 ⑶. 이것이 없으면 아래 대조가 0건 검사로 통과한다.
        assertThat(EXPECTED_AXES)
            .withFailMessage(
                "재는 축의 선언이 비었다 — 아래 대조가 0건 검사가 된다(CLAUDE.md 규칙 4 ⑶). " +
                    "축을 정말 다 뺐다면 이 케이스를 지워야 하고, 그 diff 가 신고다.",
            ).isNotEmpty()
        // ⑵ 개수도 함께 못박는다 — 목록에서 하나 빼고 하나 넣는 편집이 집합 대조를 통과하지
        //    못하게 두 자리에서 diff 가 나게 한다(`TEST_CLASSES`/`TEST_CLASS_COUNT` 와 같은 규율).
        assertThat(EXPECTED_AXES)
            .withFailMessage("선언 개수(%d)가 상수(%d)와 다르다 — 둘을 함께 고쳐라", EXPECTED_AXES.size, EXPECTED_AXIS_COUNT)
            .hasSize(EXPECTED_AXIS_COUNT)
        // ⑶ 실제 재고와 **정확 일치**. 양방향이라 삭제도 추가도 잡힌다.
        assertThat(probe.registeredAxes())
            .withFailMessage(
                "재고 있는 축이 선언과 다르다.%n  없어진 축(선언에만): %s%n  새로 생긴 축(실제에만): %s%n" +
                    "  축 등록이 지워지면 그 축은 **조용히 안 재진다** — 통제 카나리도 " +
                    "`pendingRetroMatches()` 도 그것을 보지 못한다. 정말 뺐다면 EXPECTED_AXES 와 " +
                    "EXPECTED_AXIS_COUNT 를 함께 고쳐라(그 diff 가 신고다).",
                (EXPECTED_AXES - probe.registeredAxes()).ifEmpty { setOf("없음") },
                (probe.registeredAxes() - EXPECTED_AXES).ifEmpty { setOf("없음") },
            ).isEqualTo(EXPECTED_AXES)
    }

    /**
     * 소급 대조 축의 통제. **세 단언이 겨누는 것이 각각 다르다** — 하나로 줄이면 그 차이가
     * 사라지고, 그 차이를 못 본 것이 이 세션의 세 번째 같은 결함이었다.
     */
    private fun assertRetroMatchIsMeasured(probe: CanaryProbe) {
        // ⑴ 소급 **루프**가 보관분을 훑었는가. 이것만으로는 부족하다 — `canaries` 집합을
        //    우회해 직접 검사하므로, 루프가 돌면서 대조를 안 해도 참이다.
        assertThat(probe.sawRetroControl())
            .withFailMessage(
                "소급 루프가 보관 구간의 표식을 보지 못했다 — 보관이 비었거나 루프가 죽었다" +
                    "(보관 %d건 관측, %d자).",
                probe.retainedEvents(),
                probe.retainedCharsSeen(),
            ).isTrue()
        // ⑵ **늦게 등록된 카나리가 보관분에 대해 적중을 냈는가.** 이것이 지켜야 할 성질이고,
        //    ⑴ 과 달리 `canaries`/`controls` 집합과 `match()` 를 실제로 지난다.
        assertThat(probe.controlHitAxes())
            .withFailMessage(
                "늦게 등록된 통제 카나리가 보관분에 대해 적중을 내지 않았다 — 소급 대조가 " +
                    "카나리 집합을 지나지 않는다는 뜻이고, **토큰 축도 같이 죽어 있다**. " +
                    "`sawRetroControl()` 은 %s 였다 — 두 통제가 겨누는 것이 다른 이유가 이것이다.",
                probe.sawRetroControl(),
            ).contains(RETRO_CONTROL_AXIS)
        // ⑶ 늦은 등록이 **빠짐없이** 소급 대조를 지났는가. 등록이 `rescanRetained()` 뒤로
        //    밀리거나 보관이 이미 비워진 뒤면 여기 남는다.
        assertThat(probe.pendingRetroMatches())
            .withFailMessage(
                "소급 대조를 지나지 않은 늦은 등록이 있다: %s. 그 축은 보관 구간을 재지 못했다 — " +
                    "등록을 `rescanRetained()` **앞**으로 두거나, 등록 뒤에 한 번 더 대조하라.",
                probe.pendingRetroMatches(),
            ).isEmpty()
    }

    // ================================================================ 요청 조립

    /**
     * 계정을 만들고 토큰을 받는다. **두 요청 다 도달 핀에 기록한다** — 비밀번호 축이 요청
     * 바이트·Argon2·JDBC 를 지나는 자리가 이 둘이고, 여기서 끊기면 그 축이 조용히 죽는다.
     *
     * 로그인이 실패하면 `access_token` 이 없어 토큰 축의 needle 이 `"null"` 이 되고, 그 값은
     * 거의 모든 로그 줄에 들어 지목이 수천 건으로 번진다. 도달 단언을 판정부 **맨 앞**에 둔
     * 이유 하나가 이것이다 — 그 난장판보다 「로그인이 200 이 아니다」가 먼저 나간다.
     */
    private fun newAccount(reach: ReachLog): String {
        val credentials =
            json.writeValueAsString(mapOf("email" to "canary@example.test", "password" to PASSWORD_CANARY))
        reach.record(STEP_SIGNUP, send(post(null, credentials, SIGNUP_PATH)))
        val login = send(post(null, credentials, LOGIN_PATH))
        reach.record(STEP_LOGIN, login)
        return json.readValue(login.body(), Map::class.java)["access_token"].toString()
    }

    private fun createFromText(
        reach: ReachLog,
        step: String,
        token: String,
        body: String,
    ) {
        reach.record(step, send(post(token, body)))
    }

    private fun upload(
        reach: ReachLog,
        step: String,
        token: String,
        body: MultipartBody,
    ) {
        reach.record(
            step,
            send(
                HttpRequest
                    .newBuilder(URI.create("http://localhost:$port$DOCUMENTS_PATH"))
                    .header("Content-Type", body.contentType())
                    .header("Authorization", "Bearer $token")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body.build())),
            ),
        )
    }

    /** 파일 파트. **파일 이름이 제목 카나리를 나른다** — 이름이 새는지도 같이 잰다. */
    private fun docxPart(bytes: ByteArray): MultipartBody = MultipartBody().file(FILE_PART, "$TITLE_CANARY.docx", bytes)

    /** 상한을 확실히 넘기는 본문. 호출부를 한 줄에 두려고 뺐다 — 값은 그대로다. */
    private fun overLimitBody(): String = textBody(BODY_CANARY + "가".repeat(OVER_LIMIT_CHARS), TITLE_CANARY)

    private fun textBody(
        text: String,
        title: String,
    ): String = json.writeValueAsString(mapOf("text" to text, "title" to title))

    private fun post(
        token: String?,
        body: String,
        path: String = DOCUMENTS_PATH,
    ): HttpRequest.Builder {
        val builder =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port$path"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, Charsets.UTF_8))
        token?.let { builder.header("Authorization", "Bearer $it") }
        return builder
    }

    private fun send(builder: HttpRequest.Builder): HttpResponse<String> =
        HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))

    /**
     * 오류 본문 `detail` 의 **모양**. 값이 아니라 모양만 남기는 것이 이 열거의 존재 이유다.
     *
     * 이 저장소에서 모양은 **거절 층의 지문**이다 — 스키마·프레임워크 바인딩은 배열,
     * 서비스·도메인은 문자열(클래스 KDoc 의 표). [OTHER]·[UNREADABLE] 은 우리 일곱 갈래에
     * 나오지 않아야 하는 값이고, 나오면 「모양을 못 읽었다」가 초록으로 새는 것을 막는다.
     */
    private enum class DetailShape {
        /** `detail` 키가 없다(2xx 본문) — 또는 값이 JSON `null` 이다. 우리 계약에 후자는 없다. */
        NONE,

        /** 문자열 `detail` — 도메인 예외 매핑(`ErrorResponse`). */
        STRING,

        /** 배열 `detail` — 검증 경로(`ValidationErrorResponse`). */
        ARRAY,

        /** 숫자·객체·불리언. 계약에 없는 모양이다. */
        OTHER,

        /** 본문이 비었거나 JSON 이 아니다. */
        UNREADABLE,
    }

    /**
     * 요청 하나의 도달 지문. **본문을 담지 않는다** — 상태 코드와 모양 열거값뿐이다.
     *
     * `data class` 지만 `toString()` 을 손대지 않는다: [step] 은 컴파일 상수, 나머지는
     * `Int` 와 열거값이라 찍혀도 샐 것이 없다.
     */
    private data class Reached(
        val step: String,
        val status: Int,
        val detail: DetailShape,
    ) {
        /** 지목 줄에 쓰는 표기. 두 축을 한 쌍으로 붙여 어느 축이 어긋났는지 바로 보이게 한다. */
        fun mark(): String = "$status/$detail"
    }

    /**
     * 요청 하나가 낸 **상태 코드와 `detail` 모양만** 순서대로 모은다.
     *
     * 지켜야 할 성질이 타입에 들어 있다 — **응답 본문을 보관하지 않는다.** [record] 는
     * `HttpResponse` 를 받아 `Int` 와 [DetailShape] 만 남기므로 실패 메시지에 응답 본문이
     * 실릴 통로가 아예 없다. `CanaryProbe.residualCanaryFragments()` 가 지목 줄에 대해
     * **검사로** 지키는 것을 이쪽은 **구조로** 막는다.
     *
     * 동시 구조가 필요 없다 — 요청은 Tomcat 워커에서 처리되지만 기록은 요청을 **보낸 쪽**,
     * 곧 테스트 스레드에서 순차로 한다.
     */
    private class ReachLog(private val json: ObjectMapper) {
        private val steps = mutableListOf<Reached>()

        fun record(
            step: String,
            response: HttpResponse<String>,
        ) {
            steps += Reached(step, response.statusCode(), shapeOf(response.body()))
        }

        fun observed(): List<Reached> = steps.toList()

        /**
         * 본문에서 **모양만** 뽑고 본문은 즉시 버린다.
         *
         * **파싱 예외도 버린다.** Jackson 의 파싱 예외 메시지에는 문제가 된 입력 조각이 들어
         * 있어서, 그것을 들고 있으면 실패 메시지로 응답 본문이 흘러나가는 통로가 되살아난다.
         * 「읽지 못했다」는 사실만 [DetailShape.UNREADABLE] 로 남기고 이유는 남기지 않는다.
         */
        private fun shapeOf(body: String?): DetailShape {
            // 빈 본문과 「JSON 이 아니다」를 한 자리에서 접는다 — 둘 다 「모양을 못 읽었다」이고,
            // 갈래를 늘리면 detekt ReturnCount 를 넘기면서 얻는 것이 없다.
            val parsed =
                body
                    ?.takeIf { it.isNotBlank() }
                    ?.let { text -> runCatching { json.readValue(text, Map::class.java) }.getOrNull() }
                    ?: return DetailShape.UNREADABLE
            return when (parsed[DETAIL_KEY]) {
                null -> DetailShape.NONE
                is String -> DetailShape.STRING
                is List<*> -> DetailShape.ARRAY
                else -> DetailShape.OTHER
            }
        }
    }

    companion object {
        private const val DOCUMENTS_PATH = "/documents"
        private const val SIGNUP_PATH = "/auth/signup"
        private const val LOGIN_PATH = "/auth/login"

        /** 계약 `DocumentFileRequest.properties` 의 파일 파트 이름. */
        private const val FILE_PART = "file"

        /** 자연 발생하지 않는 값이어야 「없다」가 뜻을 갖는다. */
        private const val BODY_CANARY = "CANARY-DOCUMENT-BODY-7Q2XZ"
        private const val TITLE_CANARY = "CANARY-DOCUMENT-TITLE-4M8VW"
        private const val PASSWORD_CANARY = "CANARY-CREDENTIAL-9L3RT-correct-horse"

        /** 소급 대조 통제 축의 이름. 유출 축이 아니라 **통제 집합**에 등록된다. */
        private const val RETRO_CONTROL_AXIS = "소급 대조 통제"

        private const val BODY_AXIS = "본문"
        private const val TITLE_AXIS = "제목"
        private const val PASSWORD_AXIS = "자격증명(비밀번호)"
        private const val TOKEN_AXIS = "자격증명(액세스 토큰)"

        /**
         * **이 케이스가 재는 축의 정본 재고.** `CanaryProbe.registeredAxes()` 와 정확히 일치해야
         * 한다. 접두 `유출`/`통제` 는 등록 레지스트리를 뜻한다 — 통제 축은 유출 축의 예외가 아니라
         * 다른 집합이다(`CanaryProbe` KDoc 참고).
         */
        private val EXPECTED_AXES =
            setOf(
                "유출 $BODY_AXIS",
                "유출 $TITLE_AXIS",
                "유출 $PASSWORD_AXIS",
                "유출 $TOKEN_AXIS",
                "통제 $RETRO_CONTROL_AXIS",
            )

        /** 목록과 **함께** 고쳐야 하는 개수 핀. 한 축을 빼고 다른 축을 넣는 편집을 드러낸다. */
        private const val EXPECTED_AXIS_COUNT = 5

        /**
         * 도달 핀의 요청 라벨. **컴파일 상수이고 응답에서 온 값이 아니다** — 실패 메시지가
         * CI 로그로 나가므로 라벨에 런타임 문자열을 섞지 않는다.
         *
         * 번호는 테스트 본문의 주석 번호와 같다. `⑴` 은 HTTP 요청이 둘이라 `-a`·`-b` 로 나눴다.
         */
        private const val STEP_SIGNUP = "⑴-a 계정 생성"
        private const val STEP_LOGIN = "⑴-b 로그인"
        private const val STEP_TEXT = "⑵ 붙여넣기 성공"
        private const val STEP_FILE = "⑶ 파일 업로드 성공"
        private const val STEP_OVER_LIMIT = "⑷ 본문 상한 초과"
        private const val STEP_BROKEN_FILE = "⑸ 손상 파일"
        private const val STEP_UNSAVABLE = "⑹ 저장할 수 없는 문자"

        /** 오류 본문의 유일한 키. 계약 `{"detail": …}`. */
        private const val DETAIL_KEY = "detail"

        /**
         * **이 케이스가 태우는 요청과, 각 요청이 닿아야 하는 층의 지문(상태 코드 + `detail` 모양).**
         *
         * 계약에서 읽지 않았다 — 사유 셋은 클래스 KDoc 「기대값을 계약에서 읽지 않은 이유」에
         * 있다. 요약: ⑴ 이 핀의 목적은 계약 준수가 아니라 **자극 도달**이고 계약 준수는 DC
         * 케이스가 이미 잰다, ⑵ 계약은 이 경로에 202·401·404·413·415·422·500·503 을 **모두**
         * 선언해서 「선언된 집합」과 대조하면 전건 401 이 통과한다, ⑶ **계약 변경**이 도달
         * 상실의 원인 목록에 있으므로 기대를 계약에서 읽으면 그 원인을 구조적으로 못 본다.
         * 모양 축도 같은 사유로 코드에 둔다 — 계약은 `detail` 을 **union** 으로 선언하므로
         * 「계약이 허용하는 모양」과 대조하면 배열도 문자열도 통과한다.
         *
         * - `202/NONE` = 접수(저장 경로 전체를 지났다)
         * - `422/STRING` = **서비스·도메인 층**의 거절 — 본문이 그 층까지 갔다
         * - `422/ARRAY` = **스키마·프레임워크 바인딩** 층의 거절 — **도달 실패**다
         * - `401·415·404·405` = 도달 실패
         */
        private val EXPECTED_REACH: List<Reached> =
            listOf(
                Reached(STEP_SIGNUP, 201, DetailShape.NONE),
                Reached(STEP_LOGIN, 200, DetailShape.NONE),
                Reached(STEP_TEXT, 202, DetailShape.NONE),
                Reached(STEP_FILE, 202, DetailShape.NONE),
                Reached(STEP_OVER_LIMIT, 422, DetailShape.STRING),
                Reached(STEP_BROKEN_FILE, 422, DetailShape.STRING),
                Reached(STEP_UNSAVABLE, 422, DetailShape.STRING),
            )

        /** 목록과 **함께** 고쳐야 하는 개수 핀. 한 갈래를 빼고 다른 갈래를 넣는 편집을 드러낸다. */
        private const val EXPECTED_REACH_COUNT = 7

        private const val CONVERSIONS_TABLE = "conversions"
        private const val JOBS_TABLE = "conversion_jobs"

        /**
         * **저장 상태 기대.** 응답이 아니라 **DB 쪽에서** 보는 값들이다.
         *
         * `source_format` 이 팔과 1:1 이다 — `text`=⑵ 붙여넣기, `docx`=⑶ 파일 업로드. 그래서
         * 한 팔만 저장되지 않으면 없어진 항목이 **그 팔을 지목한다.** 총 2 라는 사실이 거절된
         * 세 갈래가 저장되지 않았음도 함께 잰다. `ORDER BY 1` 이라 사전순이다.
         */
        private val EXPECTED_DOCUMENT_ROWS = listOf("docx=1", "text=1")

        /** 암호문이 자리표시자인 행은 하나도 없어야 한다. */
        private const val EXPECTED_PLACEHOLDER_ROWS = 0

        /** 제목 카나리가 실린 행 — ⑵ 뿐이다(⑶ 은 제목을 주지 않아 대체 제목이다). */
        private const val EXPECTED_TITLE_CANARY_ROWS = 1

        /**
         * 판정 구간에서 나가야 하는 저장 질의 수.
         *
         * 형식별 문서 행 1 + 변환 행 1 + 작업 행 1 + 자리표시자 1 + 제목 1 = **5**. 질의를
         * 늘리는 것이 정당하면 이 값을 함께 고쳐라 — 그 diff 가 「실 DB 왕복이 늘었다」를
         * 리뷰에 올린다(Testcontainers 실행 시간에 직결된다).
         */
        private const val EXPECTED_STORAGE_QUERIES = 5

        /** 계약 상한을 확실히 넘기는 길이. 정확한 경계는 DC-9·DC-10 이 잰다. */
        private const val OVER_LIMIT_CHARS = 5_000

        val database: DatabaseHandle by lazy { PostgresTestSupport.createEmptyDatabase("document_log_leak") }

        @JvmStatic
        @DynamicPropertySource
        fun datasourceProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { database.jdbcUrl }
            registry.add("spring.datasource.username") { database.username }
            registry.add("spring.datasource.password") { database.password }
        }
    }
}
