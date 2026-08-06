"""복지서비스 Open API 어댑터(app/easyread/bokjiro.py) 단위 테스트.

**네트워크를 쓰지 않는다.** `httpx.MockTransport`로 응답 XML을 직접 돌려주고, 실제 API의
응답 모양(2026-08-06 프로브로 확인한 태그명)만 재현한다. 본문·기관명·서비스ID는 전부
이 파일에서 만든 예시값이다.

여기서 반드시 지키는 두 가지:

- 인증키가 이중 인코딩되지 않는다 (전송된 쿼리의 serviceKey가 원본 디코드 값과 같다)
- 인증키가 예외 메시지에 새지 않는다
"""

import httpx
import pytest

from app.easyread.bokjiro import (
    CENTRAL_API,
    LOCAL_API,
    ServiceDetail,
    WelfareApi,
    WelfareApiClient,
    build_golden_source,
    build_source_text,
    parse_xml,
)
from app.exceptions import WelfareApiError

#: Encoding 형태 인증키(퍼센트 인코딩 포함)와 그것을 푼 값. 실제 키가 아닌 예시다.
ENCODED_KEY = "Zm9v%2Bbar%2Fbaz%3D%3D"
DECODED_KEY = "Zm9v+bar/baz=="

LOCAL_LIST_XML = """<?xml version="1.0" encoding="UTF-8"?>
<wantedList>
  <totalCount>2</totalCount>
  <pageNo>1</pageNo>
  <numOfRows>2</numOfRows>
  <resultCode>0</resultCode>
  <resultMessage>SUCCESS</resultMessage>
  <servList>
    <ctpvNm>예시도</ctpvNm>
    <sggNm>예시시</sggNm>
    <bizChrDeptNm>예시도 예시시 노인복지과</bizChrDeptNm>
    <servId>WLF00099001</servId>
    <servNm>어르신 교통비 지원</servNm>
    <servDgst>만 65세 이상 어르신에게 교통비를 지원합니다.</servDgst>
    <servDtlLink>https://example.go.kr/wlfare?wlfareInfoId=WLF00099001</servDtlLink>
  </servList>
  <servList>
    <ctpvNm>예시도</ctpvNm>
    <servId>WLF00099002</servId>
    <servNm>청년 월세 지원</servNm>
  </servList>
</wantedList>
"""

LOCAL_DETAIL_XML = """<?xml version="1.0" encoding="UTF-8"?>
<wantedDtl>
  <resultCode>0</resultCode>
  <resultMessage>SUCCESS</resultMessage>
  <servId>WLF00099001</servId>
  <servNm>어르신 교통비 지원</servNm>
  <ctpvNm>예시도</ctpvNm>
  <sggNm>예시시</sggNm>
  <bizChrDeptNm>예시도 예시시 노인복지과</bizChrDeptNm>
  <servDgst>만 65세 이상 어르신에게 교통비를 지원합니다.</servDgst>
  <sprtTrgtCn>만 65세 이상  예시시 거주 어르신</sprtTrgtCn>
  <slctCritCn>만 65세 이상  예시시 거주 어르신</slctCritCn>
  <alwServCn>월 30,000원의 교통비를 지원합니다.</alwServCn>
  <aplyMtdCn>주민등록상 주소지 행정복지센터에 방문 신청합니다.</aplyMtdCn>
  <inqplCtadrList>
    <wlfareInfoDtlCd>010</wlfareInfoDtlCd>
    <wlfareInfoReldNm>예시시청 노인복지과</wlfareInfoReldNm>
    <wlfareInfoReldCn>02-1234-5678</wlfareInfoReldCn>
  </inqplCtadrList>
  <baslawList>
    <wlfareInfoDtlCd>030</wlfareInfoDtlCd>
    <wlfareInfoReldNm>노인복지법</wlfareInfoReldNm>
  </baslawList>
  <basfrmList>
    <wlfareInfoDtlCd>040</wlfareInfoDtlCd>
    <wlfareInfoReldNm>신청서 서식</wlfareInfoReldNm>
  </basfrmList>
</wantedDtl>
"""

CENTRAL_DETAIL_XML = """<?xml version="1.0" encoding="UTF-8"?>
<wantedDtl>
  <servId>WLF00000061</servId>
  <servNm>예시 진료비 지원</servNm>
  <jurMnofNm>예시부</jurMnofNm>
  <jurOrgNm>기초의료보장과</jurOrgNm>
  <wlfareInfoOutlCn>임신이 확인된 사람에게 진료비를 지원합니다.</wlfareInfoOutlCn>
  <tgtrDtlCn>임신이 확인된 수급권자를 지원합니다.</tgtrDtlCn>
  <slctCritCn>임신 사실이 확인되어야 합니다.</slctCritCn>
  <alwServCn>1인당 100만원의 진료비를 지원합니다.</alwServCn>
  <applmetList>
    <servSeCode>070</servSeCode>
    <servSeDetailNm>신청기관연락처목록</servSeDetailNm>
    <servSeDetailLink>거주지 행정복지센터에서 신청</servSeDetailLink>
  </applmetList>
  <baslawList>
    <servSeCode>030</servSeCode>
    <servSeDetailNm>의료급여법</servSeDetailNm>
  </baslawList>
  <resultCode>0</resultCode>
  <resultMessage>SUCCESS</resultMessage>
</wantedDtl>
"""

TRAFFIC_LIMIT_XML = """<?xml version="1.0" encoding="UTF-8"?>
<wantedList>
  <resultCode>22</resultCode>
  <resultMessage>LIMITED_NUMBER_OF_SERVICE_REQUESTS_EXCEEDS_ERROR</resultMessage>
</wantedList>
"""

# 인증키 오류는 정상 경로와 다른 봉투로 온다. 서버가 돌려준 키 값이 그대로 들어 있는
# 모양을 일부러 재현한다 — 우리 예외 메시지에 이 값이 새지 않는지 보기 위해서다.
GATEWAY_ERROR_XML = f"""<?xml version="1.0" encoding="UTF-8"?>
<OpenAPI_ServiceResponse>
  <cmmMsgHeader>
    <errMsg>SERVICE ERROR</errMsg>
    <returnAuthMsg>SERVICE_KEY_IS_NOT_REGISTERED_ERROR</returnAuthMsg>
    <returnReasonCode>30</returnReasonCode>
    <requestUrl>https://apis.data.go.kr/x?serviceKey={DECODED_KEY}</requestUrl>
  </cmmMsgHeader>
</OpenAPI_ServiceResponse>
"""


def _client(
    body: str,
    *,
    api: WelfareApi = LOCAL_API,
    status: int = 200,
    key: str = ENCODED_KEY,
    seen: list[httpx.Request] | None = None,
) -> WelfareApiClient:
    """지정한 응답만 돌려주는 클라이언트 (네트워크 없음)."""

    def handler(request: httpx.Request) -> httpx.Response:
        if seen is not None:
            seen.append(request)
        return httpx.Response(status, content=body.encode())

    return WelfareApiClient(api, key, client=httpx.Client(transport=httpx.MockTransport(handler)))


def test_목록_응답을_요약으로_읽는다() -> None:
    목록 = _client(LOCAL_LIST_XML).list_services(page=1, rows=2)
    assert [항목.serv_id for 항목 in 목록] == ["WLF00099001", "WLF00099002"]
    assert 목록[0].serv_nm == "어르신 교통비 지원"
    assert 목록[0].organization == "예시도 예시시 노인복지과"
    assert 목록[0].detail_link == "https://example.go.kr/wlfare?wlfareInfoId=WLF00099001"


def test_상세_링크가_없는_항목은_None이다() -> None:
    """지자체 상세 응답에는 링크가 없어 목록에서만 온다 — 없으면 None으로 남긴다."""
    목록 = _client(LOCAL_LIST_XML).list_services()
    assert 목록[1].detail_link is None
    assert 목록[1].organization == "예시도"


def test_기관명에_시도가_두_번_들어가지_않는다() -> None:
    """지자체 bizChrDeptNm은 이미 "인천광역시 동구 경제환경국 자원순환과"처럼 시도를 품는다.

    앞에 ctpvNm·sggNm을 덧붙이면 "인천광역시 제물포구 인천광역시 동구 …"가 된다(실측).
    """
    목록 = _client(LOCAL_LIST_XML).list_services()
    assert 목록[0].organization == "예시도 예시시 노인복지과"
    assert 목록[0].organization.count("예시도") == 1


def test_담당_부서명이_없으면_시도_시군구로_적는다() -> None:
    목록 = _client(LOCAL_LIST_XML).list_services()
    assert 목록[1].organization == "예시도"


def test_지자체_상세를_섹션으로_조립한다() -> None:
    상세 = _client(LOCAL_DETAIL_XML).get_detail("WLF00099001")
    assert [제목 for 제목, _ in 상세.sections] == [
        "서비스 요약",
        "지원 대상",
        "서비스 내용",
        "신청 방법",
        "문의처",
        "근거 법령",
        "구비 서류",
    ]
    assert 상세.organization == "예시도 예시시 노인복지과"


def test_같은_내용이_두_필드에_들어_있으면_한_번만_담는다() -> None:
    """지자체 레코드에는 sprtTrgtCn과 slctCritCn이 글자까지 같은 것이 실제로 있다."""
    상세 = _client(LOCAL_DETAIL_XML).get_detail("WLF00099001")
    제목들 = [제목 for 제목, _ in 상세.sections]
    assert "선정 기준" not in 제목들
    본문들 = [본문 for _, 본문 in 상세.sections]
    assert len(본문들) == len(set(본문들))


def test_중앙부처_상세도_같은_섹션_제목으로_읽힌다() -> None:
    """필드 이름이 다른 두 API가 같은 모양의 초안으로 모인다."""
    상세 = _client(CENTRAL_DETAIL_XML, api=CENTRAL_API).get_detail("WLF00000061")
    assert [제목 for 제목, _ in 상세.sections] == [
        "서비스 요약",
        "지원 대상",
        "선정 기준",
        "서비스 내용",
        "신청 방법",
        "근거 법령",
    ]
    assert 상세.organization == "예시부 기초의료보장과"


def test_빈_필드는_섹션에서_빠진다() -> None:
    상세 = _client(
        "<wantedDtl><resultCode>0</resultCode><servId>A</servId><servNm>이름</servNm>"
        "<sprtTrgtCn>대상입니다.</sprtTrgtCn><alwServCn>  </alwServCn></wantedDtl>"
    ).get_detail("A")
    assert [제목 for 제목, _ in 상세.sections] == ["지원 대상"]


def test_초안_본문은_제목_달린_섹션으로_이어진다() -> None:
    본문 = build_source_text(_client(LOCAL_DETAIL_XML).get_detail("WLF00099001"))
    assert 본문.startswith("어르신 교통비 지원\n\n서비스 요약\n")
    assert "\n\n지원 대상\n만 65세 이상 예시시 거주 어르신" in 본문
    assert "\n\n신청 방법\n주민등록상 주소지 행정복지센터에 방문 신청합니다." in 본문
    assert "- 예시시청 노인복지과: 02-1234-5678" in 본문
    assert "- 노인복지법" in 본문


def test_출처_메타에_데이터셋과_서비스ID가_남는다() -> None:
    상세 = _client(LOCAL_DETAIL_XML).get_detail("WLF00099001")
    출처 = build_golden_source(
        상세, LOCAL_API, collected_at="2026-08-06", detail_link="https://example.go.kr/a"
    )
    assert 출처.url == "https://example.go.kr/a"
    assert 출처.organization == "예시도 예시시 노인복지과"
    assert 출처.license == "이용허락범위 제한 없음(data.go.kr)"
    assert 출처.dataset == "data.go.kr 지자체복지서비스"
    assert 출처.record_id == "WLF00099001"


def test_기관명을_못_얻으면_제공기관으로_적는다() -> None:
    출처 = build_golden_source(
        ServiceDetail(serv_id="A", serv_nm="이름", organization="", sections=()),
        LOCAL_API,
        collected_at="2026-08-06",
    )
    assert 출처.organization == "한국사회보장정보원"
    assert 출처.url is None


def test_인증키는_이중_인코딩되지_않는다() -> None:
    """`.env`의 Encoding 형태 키를 params로 그대로 넘기면 %2B가 %252B가 된다."""
    요청: list[httpx.Request] = []
    _client(LOCAL_LIST_XML, seen=요청).list_services()
    보낸_주소 = 요청[0].url
    assert 보낸_주소.params["serviceKey"] == DECODED_KEY
    assert "%25" not in str(보낸_주소)


def test_이미_디코드된_키는_그대로_쓴다() -> None:
    """Decoding 형태 키에는 %가 없어 한 번 더 풀어도 값이 달라지지 않는다."""
    요청: list[httpx.Request] = []
    _client(LOCAL_LIST_XML, key=DECODED_KEY, seen=요청).list_services()
    assert 요청[0].url.params["serviceKey"] == DECODED_KEY


def test_목록_요청에_페이지와_출력건수가_실린다() -> None:
    요청: list[httpx.Request] = []
    _client(LOCAL_LIST_XML, seen=요청).list_services(page=3, rows=7)
    보낸_주소 = 요청[0].url
    assert 보낸_주소.path.endswith("/LcgvWelfarelist")
    assert 보낸_주소.params["pageNo"] == "3"
    assert 보낸_주소.params["numOfRows"] == "7"
    assert 보낸_주소.params["callTp"] == "L"


def test_상세_요청에_서비스ID가_실린다() -> None:
    요청: list[httpx.Request] = []
    _client(LOCAL_DETAIL_XML, seen=요청).get_detail("WLF00099001")
    보낸_주소 = 요청[0].url
    assert 보낸_주소.path.endswith("/LcgvWelfaredetailed")
    assert 보낸_주소.params["servId"] == "WLF00099001"
    assert 보낸_주소.params["callTp"] == "D"


def test_트래픽_초과는_도메인_예외다() -> None:
    with pytest.raises(WelfareApiError) as 오류:
        _client(TRAFFIC_LIMIT_XML).list_services()
    assert "코드 22" in str(오류.value)
    assert "요청 제한 횟수 초과" in str(오류.value)


def test_인증키_오류_응답에서_키가_새지_않는다() -> None:
    """게이트웨이 오류 응답에는 요청 URL(=인증키)이 들어 있다. 옮겨 적지 않는다."""
    with pytest.raises(WelfareApiError) as 오류:
        _client(GATEWAY_ERROR_XML).list_services()
    메시지 = str(오류.value)
    assert "코드 30" in 메시지
    assert "등록되지 않은 인증키" in 메시지
    for 조각 in (DECODED_KEY, ENCODED_KEY, "requestUrl", "SERVICE ERROR"):
        assert 조각 not in 메시지


def test_알_수_없는_결과_코드도_예외로_올린다() -> None:
    with pytest.raises(WelfareApiError, match="코드 77"):
        _client("<wantedList><resultCode>77</resultCode></wantedList>").list_services()


def test_결과_코드가_없으면_예외다() -> None:
    with pytest.raises(WelfareApiError, match="결과 코드"):
        _client("<wantedList><servList/></wantedList>").list_services()


def test_http_오류는_상태_코드만_남긴다() -> None:
    with pytest.raises(WelfareApiError) as 오류:
        _client("서버 오류", status=500).list_services()
    메시지 = str(오류.value)
    assert "HTTP 500" in 메시지
    assert ENCODED_KEY not in 메시지 and DECODED_KEY not in 메시지


def test_통신_실패는_예외_종류만_남긴다() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectTimeout("보내려던 주소에 인증키가 들어 있다")

    client = WelfareApiClient(
        LOCAL_API, ENCODED_KEY, client=httpx.Client(transport=httpx.MockTransport(handler))
    )
    with pytest.raises(WelfareApiError) as 오류:
        client.list_services()
    assert "ConnectTimeout" in str(오류.value)
    assert "인증키" not in str(오류.value)


def test_xml이_아니면_예외다() -> None:
    with pytest.raises(WelfareApiError, match="XML이 아닙니다"):
        _client("<html><body>점검 중</body>").list_services()


def test_dtd_선언은_거부한다() -> None:
    with pytest.raises(WelfareApiError, match="DTD"):
        parse_xml(b'<!DOCTYPE a [<!ENTITY x "y">]><wantedList>&x;</wantedList>')


def test_응답_본문은_예외_메시지에_담기지_않는다() -> None:
    with pytest.raises(WelfareApiError) as 오류:
        parse_xml("<wantedDtl><servNm>비밀 본문</servNm>".encode())
    assert "비밀 본문" not in str(오류.value)
