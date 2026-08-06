"""data.go.kr 복지서비스 Open API 어댑터 — 목록·상세 조회에서 골든셋 초안 본문 조립.

한국사회보장정보원이 공개하는 두 데이터셋(중앙부처·지자체 복지서비스)에서 서비스
하나의 서술 필드를 받아 "지원 대상 / 선정 기준 / 서비스 내용 / 신청 방법 …" 제목이 붙은
안내문 모양으로 잇는다. 여기까지가 이 모듈의 일이고, 그 뒤(마스킹→자동 분류→팩트
후보→초안 저장)는 `app/easyread/collection.py`의 기존 파이프라인을 그대로 쓴다.

두 API는 같은 기관이 만들었지만 필드 이름이 서로 다르다(중앙 `tgtrDtlCn` ↔ 지자체
`sprtTrgtCn`, 중앙 `servSeDetailNm` ↔ 지자체 `wlfareInfoReldNm`). 그래서 파싱 코드를
두 벌 두지 않고 차이를 `WelfareApi` 표에 데이터로 적어 둔다 — 필드가 바뀌면 표만 고친다.

보안:

- **인증키는 어디에도 남기지 않는다.** 로그를 남기지 않고, 예외 메시지에는 서버가
  돌려준 문구가 아니라 코드에 대응하는 우리 설명(`RESULT_MESSAGES`)만 담는다.
- 본문은 돌려주기만 하고 출력·로깅하지 않는다. 호출자가 마스킹을 거쳐 초안 파일에만
  쓴다(CLAUDE.md 보안·데이터 규칙).
- LLM을 호출하지 않는다 — 수집 도구는 오프라인 규칙 기반으로 유지한다.
"""

import xml.etree.ElementTree as ET
from dataclasses import dataclass
from types import TracebackType
from urllib.parse import unquote
from xml.parsers import expat

import httpx

from app.easyread.collection import FETCH_HEADERS, FETCH_TIMEOUT_SECONDS, normalize_text
from app.easyread.goldenset import GoldenSource
from app.exceptions import WelfareApiError

#: 두 데이터셋 모두 공공데이터포털에서 "이용허락범위 제한 없음"으로 배포된다.
#: 공공누리 유형 표시가 아니라 포털의 이용허락범위 표기를 그대로 옮긴다.
API_LICENSE = "이용허락범위 제한 없음(data.go.kr)"

#: 데이터 제공 기관. 개별 서비스의 소관 부처·지자체를 알 수 없을 때만 쓴다.
PROVIDER_ORGANIZATION = "한국사회보장정보원"

#: 정상 응답 코드. 두 API 모두 `resultCode`가 "0"이면 성공이다.
RESULT_CODE_SUCCESS = "0"

#: 오류 코드 설명 (활용가이드 2장 "OpenAPI 에러 코드정리").
#: 서버 문구를 그대로 옮기지 않고 이 표를 쓰는 이유는 예외 메시지에 인증키가 섞여 들어갈
#: 여지를 원천 차단하기 위해서다 — 우리가 쓴 문장만 나간다.
RESULT_MESSAGES: dict[str, str] = {
    "04": "HTTP 오류",
    "10": "잘못된 요청 파라미터",
    "12": "없거나 폐기된 서비스",
    "20": "서비스 접근 거부",
    "22": "요청 제한 횟수 초과 (일일 트래픽 상한)",
    "30": "등록되지 않은 인증키",
    "31": "인증키 활용기간 만료",
    "99": "기타 오류",
}

#: 인증키·트래픽 오류일 때 data.go.kr 게이트웨이가 돌려주는 다른 모양의 응답.
#: 정상 경로(`wantedList`/`wantedDtl`)와 루트 태그부터 다르다.
_GATEWAY_ROOT = "OpenAPI_ServiceResponse"


@dataclass(frozen=True)
class WelfareApi:
    """데이터셋 하나의 호출 규약과 필드 이름 표.

    detail_sections·list_sections의 **순서가 초안 본문의 섹션 순서**다. 요약을 먼저 두고
    대상→기준→내용→방법으로 잇는 것은 실제 복지 안내문이 쓰는 차례를 따른 것이다.
    """

    #: CLI `--api` 값.
    name: str
    #: 사람이 읽는 데이터셋 이름. `GoldenSource.dataset`에 그대로 들어간다.
    label: str
    endpoint: str
    list_operation: str
    detail_operation: str
    #: 기관명 후보 묶음을 **우선순위 순**으로 적는다. 앞 묶음에 값이 하나라도 있으면 그
    #: 묶음만 쓰고(묶음 안에서는 있는 값을 공백으로 잇는다) 뒤는 보지 않는다.
    #: 지자체 `bizChrDeptNm`이 이미 "인천광역시 동구 경제환경국 자원순환과"처럼 시도·
    #: 시군구를 품고 있어(실측), 앞에 ctpvNm·sggNm을 덧붙이면 시도가 두 번 나온다.
    organization_groups: tuple[tuple[str, ...], ...]
    #: (섹션 제목, 서술 필드 태그).
    detail_sections: tuple[tuple[str, str], ...]
    #: (섹션 제목, 반복 태그, 이름 태그, 값 태그). 값 태그가 None이면 이름만 줄로 적는다.
    list_sections: tuple[tuple[str, str, str, str | None], ...]


CENTRAL_API = WelfareApi(
    name="central",
    label="data.go.kr 중앙부처복지서비스",
    endpoint="https://apis.data.go.kr/B554287/NationalWelfareInformationsV001",
    list_operation="NationalWelfarelistV001",
    detail_operation="NationalWelfaredetailedV001",
    # 중앙부처는 부처명과 조직명이 따로라 이어 붙여야 한다("보건복지부 출산정책과").
    organization_groups=(("jurMnofNm", "jurOrgNm"),),
    detail_sections=(
        ("서비스 요약", "wlfareInfoOutlCn"),
        ("지원 대상", "tgtrDtlCn"),
        ("선정 기준", "slctCritCn"),
        ("서비스 내용", "alwServCn"),
    ),
    list_sections=(
        ("신청 방법", "applmetList", "servSeDetailNm", "servSeDetailLink"),
        ("문의처", "inqplCtadrList", "servSeDetailNm", "servSeDetailLink"),
        ("근거 법령", "baslawList", "servSeDetailNm", None),
        ("구비 서류", "basfrmList", "servSeDetailNm", None),
    ),
)

LOCAL_API = WelfareApi(
    name="local",
    label="data.go.kr 지자체복지서비스",
    endpoint="https://apis.data.go.kr/B554287/LocalGovernmentWelfareInformations",
    list_operation="LcgvWelfarelist",
    detail_operation="LcgvWelfaredetailed",
    # 담당 부서명이 이미 시도·시군구까지 담고 있으므로 그것만 쓰고, 없을 때만 시도·시군구.
    organization_groups=(("bizChrDeptNm",), ("ctpvNm", "sggNm")),
    detail_sections=(
        ("서비스 요약", "servDgst"),
        ("지원 대상", "sprtTrgtCn"),
        ("선정 기준", "slctCritCn"),
        ("서비스 내용", "alwServCn"),
        ("신청 방법", "aplyMtdCn"),
    ),
    list_sections=(
        ("문의처", "inqplCtadrList", "wlfareInfoReldNm", "wlfareInfoReldCn"),
        ("근거 법령", "baslawList", "wlfareInfoReldNm", None),
        ("구비 서류", "basfrmList", "wlfareInfoReldNm", None),
    ),
)

WELFARE_APIS: dict[str, WelfareApi] = {api.name: api for api in (CENTRAL_API, LOCAL_API)}


@dataclass(frozen=True)
class ServiceSummary:
    """목록 조회 결과 한 건.

    detail_link를 여기서 들고 다니는 이유는 **지자체 상세 응답에 상세 링크가 없기
    때문**이다(실측). 출처 URL은 목록에서 받은 값을 상세와 짝지어 남긴다.
    """

    serv_id: str
    serv_nm: str
    organization: str
    detail_link: str | None


@dataclass(frozen=True)
class ServiceDetail:
    """상세 조회 결과 한 건. sections는 (제목, 본문) 순서쌍이며 빈 필드는 이미 빠져 있다."""

    serv_id: str
    serv_nm: str
    organization: str
    sections: tuple[tuple[str, str], ...]


class _DtdNotAllowed(Exception):
    """XML에 DTD 선언이 있었다 (app/ingest/extractors.py와 같은 방어)."""


def parse_xml(data: bytes) -> ET.Element:
    """응답 XML을 ElementTree로 읽되 DTD 선언은 파서 수준에서 거부한다.

    `ET.fromstring`을 그대로 쓰지 않는 이유는 ElementTree가 내부 엔티티를 펼치기
    때문이다("billion laughs"). 반대로 expat만 쓰면 트리 API를 잃으므로, DTD를 막는
    expat 파서가 `ET.TreeBuilder`에 그대로 넣게 해 둘 다 취한다 — ElementTree의 C
    가속 파서는 `StartDoctypeDeclHandler`를 노출하지 않아 이 조합이 유일한 방법이다.
    본문을 `<!DOCTYPE` 바이트로 훑는 방식은 인코딩을 바꾸면 뚫린다(extractors.py 실측).

    Raises:
        WelfareApiError: XML이 아니거나 DTD 선언이 들어 있다.
    """

    def reject_doctype(name: str, sysid: str | None, pubid: str | None, subset: bool) -> None:
        raise _DtdNotAllowed

    builder = ET.TreeBuilder()
    parser = expat.ParserCreate()
    parser.StartDoctypeDeclHandler = reject_doctype
    parser.StartElementHandler = builder.start
    parser.EndElementHandler = builder.end
    parser.CharacterDataHandler = builder.data
    try:
        parser.Parse(data, True)
    except _DtdNotAllowed:
        raise WelfareApiError(
            "API 응답을 읽을 수 없습니다 (DTD 선언은 허용하지 않습니다)"
        ) from None
    except expat.ExpatError:
        # 오류 위치·조각을 메시지에 넣지 않는다 — 응답 본문이 새어 나갈 통로가 된다.
        raise WelfareApiError("API 응답이 XML이 아닙니다") from None
    return builder.close()


def _text(element: ET.Element, tag: str) -> str:
    """자식 태그의 텍스트를 정규화해 돌려준다 (없으면 빈 문자열)."""
    return normalize_text(element.findtext(tag) or "")


def check_result_code(root: ET.Element) -> None:
    """응답의 결과 코드를 확인한다.

    data.go.kr는 인증키·트래픽 오류를 **정상 경로와 다른 봉투**로 돌려준다
    (`OpenAPI_ServiceResponse` > `cmmMsgHeader` > `returnReasonCode`). 그래서 두 모양을
    모두 본다. 코드가 표에 없으면 코드만 남긴다 — 서버 문구는 옮기지 않는다.

    Raises:
        WelfareApiError: 결과 코드가 정상(0)이 아니다.
    """
    if root.tag == _GATEWAY_ROOT:
        code = (root.findtext(".//returnReasonCode") or "").strip() or "미상"
    else:
        code = (root.findtext("resultCode") or "").strip()
        if code == RESULT_CODE_SUCCESS:
            return
        if not code:
            raise WelfareApiError("API 응답에 결과 코드가 없습니다")
    description = RESULT_MESSAGES.get(code, "알 수 없는 오류")
    raise WelfareApiError(f"복지서비스 API 오류입니다 (코드 {code}: {description})")


def _organization(element: ET.Element, api: WelfareApi) -> str:
    """소관 기관명을 만든다 (값이 있는 첫 후보 묶음만 공백으로 이어 붙인다)."""
    for group in api.organization_groups:
        parts = [value for tag in group if (value := _text(element, tag))]
        if parts:
            return " ".join(parts)
    return ""


def parse_summary(element: ET.Element, api: WelfareApi) -> ServiceSummary:
    """목록 항목 하나를 `ServiceSummary`로 읽는다."""
    return ServiceSummary(
        serv_id=_text(element, "servId"),
        serv_nm=_text(element, "servNm"),
        organization=_organization(element, api),
        detail_link=_text(element, "servDtlLink") or None,
    )


def parse_detail(root: ET.Element, api: WelfareApi) -> ServiceDetail:
    """상세 응답을 `ServiceDetail`로 읽는다 (빈 섹션은 여기서 걸러진다).

    같은 본문이 두 필드에 그대로 들어 있는 경우가 있어(지자체 `sprtTrgtCn`과
    `slctCritCn`이 글자까지 같은 레코드가 실제로 있다) 뒤에 오는 중복 섹션은 버린다.
    남겨 두면 초안 본문이 같은 문단을 두 번 말하는 안내문이 된다.
    """
    sections: list[tuple[str, str]] = []
    seen: set[str] = set()
    for title, tag in api.detail_sections:
        body = _text(root, tag)
        if body and body not in seen:
            seen.add(body)
            sections.append((title, body))
    for title, container, name_tag, value_tag in api.list_sections:
        lines: list[str] = []
        for item in root.findall(container):
            name = _text(item, name_tag)
            value = _text(item, value_tag) if value_tag else ""
            entry = f"- {name}: {value}" if name and value else f"- {name or value}"
            if entry != "- " and entry not in lines:
                lines.append(entry)
        body = "\n".join(lines)
        if body and body not in seen:
            seen.add(body)
            sections.append((title, body))
    return ServiceDetail(
        serv_id=_text(root, "servId"),
        serv_nm=_text(root, "servNm"),
        organization=_organization(root, api),
        sections=tuple(sections),
    )


def build_source_text(detail: ServiceDetail) -> str:
    """상세 정보를 제목 달린 섹션으로 이어 붙여 초안 본문 후보를 만든다.

    첫 줄은 서비스명이다 — 초안의 제목이 되고(`build_draft`가 첫 줄에서 뽑는다) 안내문의
    모양과도 맞는다. 섹션 사이는 빈 줄로 띄워 문단 경계를 남긴다.
    """
    blocks = [detail.serv_nm] if detail.serv_nm else []
    blocks += [f"{title}\n{body}" for title, body in detail.sections]
    return "\n\n".join(blocks)


def build_golden_source(
    detail: ServiceDetail,
    api: WelfareApi,
    *,
    collected_at: str,
    detail_link: str | None = None,
) -> GoldenSource:
    """출처 메타를 만든다.

    기관명을 응답에서 못 얻으면 데이터 제공 기관(한국사회보장정보원)으로 적는다 —
    출처 표시 없이 초안이 나가는 것보다 낫고, 사람이 검수할 때 눈에 띈다.
    """
    return GoldenSource(
        url=detail_link,
        organization=detail.organization or PROVIDER_ORGANIZATION,
        license=API_LICENSE,
        collected_at=collected_at,
        dataset=api.label,
        record_id=detail.serv_id or None,
    )


class WelfareApiClient:
    """복지서비스 Open API 클라이언트 (데이터셋 하나에 하나).

    인증키를 생성자에서 한 번 디코드해 두는 것이 이 클래스의 핵심이다 — 아래 주석 참고.
    """

    def __init__(
        self,
        api: WelfareApi,
        service_key: str,
        *,
        timeout: float = FETCH_TIMEOUT_SECONDS,
        client: httpx.Client | None = None,
    ) -> None:
        self._api = api
        # data.go.kr가 주는 인증키는 Encoding 형태(`%2B`·`%2F` 포함)다. 이 값을 그대로
        # httpx의 params로 넘기면 `%`가 다시 인코딩되어 `%252B`로 나가고, 서버는 다른
        # 키로 읽어 30(등록되지 않은 인증키)을 돌려준다 — data.go.kr의 고전적 함정이다.
        # 그래서 여기서 한 번 풀어 두고 인코딩은 httpx에 맡긴다(퍼센트 인코딩 규칙을
        # 우리가 손으로 다시 구현하지 않는다). Decoding 형태 키에는 `%`가 없어
        # unquote가 아무것도 바꾸지 않으므로 두 형태를 모두 그대로 받는다.
        self._service_key = unquote(service_key)
        self._client = client or httpx.Client(
            timeout=timeout, headers=FETCH_HEADERS, follow_redirects=True
        )
        # 우리가 만든 연결만 우리가 닫는다 (주입받은 클라이언트는 호출자 것이다).
        self._owns_client = client is None

    def __enter__(self) -> "WelfareApiClient":
        return self

    def __exit__(
        self,
        exc_type: type[BaseException] | None,
        exc: BaseException | None,
        traceback: TracebackType | None,
    ) -> None:
        self.close()

    def close(self) -> None:
        if self._owns_client:
            self._client.close()

    def _request(self, operation: str, **params: str) -> ET.Element:
        """오퍼레이션 하나를 호출하고 결과 코드까지 확인한 루트 엘리먼트를 돌려준다.

        Raises:
            WelfareApiError: 통신 실패·비XML 응답·오류 결과 코드.
        """
        try:
            response = self._client.get(
                f"{self._api.endpoint}/{operation}",
                params={"serviceKey": self._service_key, **params},
            )
            response.raise_for_status()
        except httpx.HTTPStatusError as error:
            # 상태 코드만 남긴다. 요청 URL에는 인증키가 들어 있어 절대 담지 않는다.
            raise WelfareApiError(
                f"복지서비스 API 응답이 오류입니다 (HTTP {error.response.status_code})"
            ) from None
        except httpx.HTTPError as error:
            raise WelfareApiError(
                f"복지서비스 API를 호출하지 못했습니다 ({type(error).__name__})"
            ) from None
        root = parse_xml(response.content)
        check_result_code(root)
        return root

    def list_services(self, *, page: int = 1, rows: int = 10) -> list[ServiceSummary]:
        """복지서비스 목록 한 페이지를 조회한다."""
        root = self._request(
            self._api.list_operation,
            callTp="L",
            pageNo=str(page),
            numOfRows=str(rows),
            srchKeyCode="001",
        )
        return [parse_summary(item, self._api) for item in root.findall("servList")]

    def get_detail(self, serv_id: str) -> ServiceDetail:
        """서비스ID 하나의 상세 정보를 조회한다."""
        root = self._request(self._api.detail_operation, callTp="D", servId=serv_id)
        return parse_detail(root, self._api)
