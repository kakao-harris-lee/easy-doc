# 토스페이먼츠 「결제경로」 심사 자료 도구

토스페이먼츠 결제 계약 심사에 내는 **결제경로 PPT**의 초안을 만드는 운영 도구다.
제품 코드가 아니며 `frontend/`·`backend-kotlin/`과 아무 관계가 없다.

이 도구가 만드는 것은 **초안**이다. 실제로 제출하는 그림은 운영자가 자기 PC에서
전체 화면으로 다시 찍어야 한다 — 카드사가 주소창 도메인과 작업표시줄 시계로 조작
여부를 확인하기 때문이다. 무엇을 어떤 순서로 찍어야 하는지는 [`CHECKLIST.md`](./CHECKLIST.md)에 있다.

## 1. 준비

```bash
# 파이썬 (PPT 생성용)
cd ops/toss-billing-review
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt

# 노드 (캡처용) — playwright 는 frontend 의 devDependency 를 그대로 쓴다
cd ../../frontend && npm install && npx playwright install chromium
```

## 2. 화면 캡처

저장소 루트에서 실행한다. `capture.mjs`가 `<repo>/frontend/node_modules`에서
playwright를 찾는다.

```bash
# 공개 화면만 (02 하단 정보 · 03 환불 규정 · 04a 로그인 화면)
node ops/toss-billing-review/capture.mjs

# 로그인 이후 단계까지 (04b · 05a · 05b · 06)
# 비밀번호를 명령줄에 쓰지 않는다 — 셸 히스토리와 `ps`에 그대로 남는다.
read -rsp 'PW: ' TOSS_REVIEW_PW && export TOSS_REVIEW_PW && node ops/toss-billing-review/capture.mjs; unset TOSS_REVIEW_PW
```

실행할 때마다 `out/`를 **통째로 지우고** 다시 만든다 — 앞서 만들어 둔
`out/결제경로_몬딱솔루션_빌링.pptx`도 함께 사라지므로, 남겨야 할 결과물은 미리 다른
곳으로 옮기고 캡처가 끝난 뒤 `build_pptx.py`를 다시 돌린다. 운영자 최종 캡처는
`final/`에 두므로 이 삭제의 영향을 받지 않는다.

| 환경변수 | 기본값 | 설명 |
| --- | --- | --- |
| `REVIEW_BASE_URL` | `https://easydoc.kr` | 캡처 대상 사이트 |
| `TOSS_REVIEW_ID` | `toss-review@easydoc.kr` | 심사용 계정 아이디 |
| `TOSS_REVIEW_PW` | (없음) | 심사용 계정 비밀번호. **없으면 공개 단계만 찍고 나머지는 `skipped`로 남긴다.** |
| `REVIEW_HEADED` | (없음) | `1`이면 브라우저 창을 띄워 눈으로 보며 실행한다 |
| `PLAYWRIGHT_NODE_MODULES` | (없음) | playwright가 다른 곳에 설치된 경우 그 `node_modules` 경로 |

비밀번호는 입력에만 쓰고 출력·manifest·예외 메시지 어디에도 남기지 않는다.
trace·video·HAR 기록은 켜지 않는다 — 입력 순간이 파일로 새지 않게 하기 위해서다.
06 단계는 토스 카드 등록창이 **열린 것까지만** 찍고 카드 정보는 입력하지 않는다.

결과는 `out/`에 쌓인다(gitignore 대상).

- `out/<단계>.png` — 1600×900 뷰포트 캡처
- `out/manifest.json` — 단계별 URL·상태·성공 여부·강조할 영역 좌표

## 3. PPT 생성

```bash
cd ops/toss-billing-review
.venv/bin/python build_pptx.py
# → out/결제경로_몬딱솔루션_빌링.pptx
```

16:9 슬라이드 6장(① 가맹점 정보 ② 하단 정보 ③ 환불 규정 ④ 로그인 ⑤ 상품 선택·구매
⑥ 정기결제 카드 입력창)이 나온다. 캡처가 없거나 실패·건너뜀인 자리에는 「최종 캡처
필요: <URL>」 회색 상자가 들어간다. 선택 인자는 `--out-dir`, `--final-dir`,
`--manifest`, `--output`이다.

## 4. 운영자 최종 캡처로 교체하기

운영자가 `CHECKLIST.md`대로 다시 찍은 그림을 `final/`에 **manifest와 같은 파일 이름**
으로 넣고 `build_pptx.py`를 다시 돌리면 그 그림이 자동 캡처보다 우선한다.
좌표계가 달라지므로 자동 강조 사각형은 그리지 않는다.

교체본에도 파란 강조 사각형이 필요하면 `final/highlights.json`에 직접 적는다.

```json
{
  "02-footer.png": {
    "image_size": { "width": 1920, "height": 1080 },
    "rects": [
      { "x": 40, "y": 820, "width": 1840, "height": 220, "label": "사업자 정보" }
    ]
  }
}
```

- 키는 `final/` 안의 파일 이름이다.
- `image_size`는 좌표를 잰 기준 크기다. 생략하면 그림의 실제 픽셀 크기로 본다.
- `rects`의 좌표 단위는 그 기준 크기 안의 픽셀, `label`은 캡션에 덧붙는 설명이다.

## 지켜야 할 선

- 그림 위에 **가짜 주소창·시계·작업표시줄을 합성하지 않는다.** 카드사는 캡처의 시각
  정보로 조작 여부를 확인한다. 이 도구는 강조 사각형과 캡션만 그린다.
- 심사 계정으로 **실제 카드 번호를 입력하지 않는다.** 테스트 키라 승인도 청구도 일어나지
  않지만, 카드 정보 자체를 넣을 이유가 없다.
