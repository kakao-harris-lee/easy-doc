# kordoc 문서 파서 대체 검토

검토일: 2026-10-22. 대상: [kordoc](https://github.com/chrisryugj/kordoc) 4.24.1(2026-10-21 배포). 같은 질문이 다시 나오면 이 문서부터 읽고, 아래 「다시 볼 조건」에 해당할 때만 재조사한다.

## 결론

전면 대체는 하지 않는다. kordoc이 현행보다 나은 곳은 PDF와 레거시 .hwp 둘이다. .hwp는 정책을 바꿔야 쓸 수 있다. 지금은 제품에 넣지 않고 품질 비교 기준으로만 둔다.

| 형식 | 판정 | 결정적 근거 |
|---|---|---|
| HWPX | 현행 유지 | 본문 보존율은 같다(한글 재현율 둘 다 1.000). 교체하면 서식 유지 내보내기의 줄 정렬이 깨진다(아래 「내보내기 제약」) |
| DOCX | 현행 유지 | 골든 샘플이 0건이라 비교하지 못했다. kordoc에는 DOCX에 다시 써넣는 기능이 없다 |
| PDF | 조건부 후보 | 표 복원, 하드랩 이음, 반복 머리글·쪽번호 제거, PUA 정리는 우위다. 대신 3~11배 느리고 RSS가 최대 1.9GB다. 다단 읽기 순서는 미측정 |
| 레거시 .hwp | 제품 결정 필요 | 2/2건을 kordoc만 읽었다. 계약 `legacy_hwp_policy`는 422 거절이다. 열기로 하면 JVM 대안 hwplib/hwp2hwpx를 먼저 PoC한다 |

## 내보내기 제약 (HWPX·DOCX를 바꿀 수 없는 이유)

- 반영기는 추출 때 저장한 노드 ID를 쓰지 않는다. 원본 바이트를 다시 열어 `TextUnitWalk`로 텍스트 단위를 세고, 검수본 줄과 차례로 짝지어 `t` 요소를 제자리에서 바꾼다(`infrastructure/.../export/TextUnits.kt`, `ReflectionPlan.kt`).
- 그래서 "추출 줄 수 = 순회 단위 수"가 불변식이다. `SourceUnitExportAlignmentTest`(A7)가 이를 지킨다.
- kordoc 출력은 Markdown+HTML 표와 문단 단위 IR이다. 한 문단 안의 여러 `hp:t`를 합치고, 강제 줄바꿈 문단은 잠그고, 머리글·글상자·캡션은 매핑에서 빠진다. 이 줄 모델과 맞지 않는다.
- kordoc의 HWPX 패치(`HwpxSession`, `patchHwpxBlocks`)는 Java SDK와 parse-worker에 노출되지 않는다. 문단 치환 시 첫 run 서식으로 평탄화한다. 현행 `TextUnitWalk.rewrite`는 글자 수 비율로 run에 나눠 써서 서식을 더 보존한다.
- PDF는 내보내기가 TXT라 이 제약 밖이다. PDF만 교체하는 안은 출력 어댑터만 있으면 된다. 어댑터는 줄=단위, 줄마다 UnitKind, 표 셀→줄 색인 규칙을 지켜야 한다.

## 실측 (골든 27건: hwp 2, hwpx 13, pdf 12, docx 0)

현행 `DocumentExtractors().extract()`와 kordoc을 파일별 별도 프로세스로 돌렸다. 시간은 wall, 메모리는 최대 RSS(`/usr/bin/time -l`)로 쟀고, 모두 단일 실행이다.

| 형식 | kordoc 성공 | kordoc 시간 중앙/최대(초) | kordoc RSS 중앙/최대(MB) | 현행 성공 | 현행 시간 중앙/최대(초) | 현행 RSS 중앙/최대(MB) |
|---|---|---|---|---|---|---|
| hwp | 2/2 | 0.26 / 0.35 | 246 / 377 | 0/2(정책 거절) | — | — |
| hwpx | 12/13 | 0.25 / 1.32 | 153 / 1,627 | 12/13 | 0.26 / 0.31 | 96 / 101 |
| pdf | 12/12 | 4.53 / 21.19 | 539 / 1,925 | 9/12 | 0.95 / 2.18 | 477 / 660 |

- hwpx 실패 1건은 배포용 암호화 문서로, 둘 다 실패했다.
- 현행 PDF 실패 3건 중 2건은 추출 상한 500,000자 초과다. 변환 상한이 20,000자(`MAX_CONVERTIBLE_CHARS`)라 의미가 작다. 나머지 1건은 스캔 PDF 거절이다.
- 골든 PDF 중 12~15MB급은 업로드 상한 10MiB를 넘어 운영 경로에 들어오지 않는다.
- **kordoc 표:** 병합 셀과 중첩 표를 HTML로 보존한다. 하지만 hwpx 표를 쪼개거나 합친다(원문 12개 → 21개, 12개 → 8개). 현행 표 수는 원문과 12건 모두 일치한다.
- **kordoc PDF 노이즈:** 빈 표 행(한 문서에 108행)과 `#` 제목 과다(문서당 1,200~2,400개)가 있다.
- **반복 머리글 제거:** PDF 한글 재현율이 pdftotext 대비 0~3% 낮다. 반복 머리글 제거 때문으로 보이나 오삭제 여부는 확인하지 않았다.
- **현행 PDF:** 문장 중간 하드랩, `- 1 -` 쪽번호 줄, ▪→`§` 변환, 반복 머리글이 본문에 섞인다(한 문서에서 321회). PUA 문자도 최대 612개 남는다.
- **스캔 PDF:** kordoc은 기본 설정에서 한글 0자를 오류 없이 `success=true`로 돌려준다. `--ocr`(로컬 ONNX)을 켜면 40쪽에 28초, RSS 1.3GB가 들고 오인식이 있다.
- **이미지 큰 hwpx:** 3.9MB BMP가 든 문서에서 kordoc RSS가 1.6~1.7GB까지 오른다. 재실행해도 같았다.

## 운영

- **런타임:** kordoc은 TypeScript(Node ≥ 20) 라이브러리이고 MIT다. HTTP 서버와 Dockerfile은 없다.
- **필수 의존성:** jszip, @xmldom/xmldom, cfb, markdown-it, MCP SDK, zod, commander다.
- **PDF 의존성:** PDF는 선택 의존성 `pdfjs-dist`가 필요하다. `--omit=optional`로 설치하면 PDF와 OCR이 함께 빠진다.
- **Java SDK:** `app.gomdori.kordoc:kordoc:0.1.0`, `sdk/java`. Java 포팅이 아니라 `node dist/cli.js parse-worker --protocol 2`를 띄워 stdin/stdout NDJSON으로 통신하는 클라이언트다.
  - Maven Central에 없어서 소스로 빌드해 직접 게시해야 한다.
  - 워커 하나가 요청을 한 번에 하나만 처리한다. 기본 `maxWorkers`는 1이다.
  - `requestTimeout`이 시간 초과되면 워커를 강제 종료한다. 현행 추출 경로에는 타임아웃이 없으므로, 이건 얻는 점이다.
- **정책:** "제품 런타임은 Kotlin/Spring Boot 하나" 정책의 예외 승인이 전제다. 붙이는 방식은 둘이다.
  - 백엔드 이미지에 Node를 넣고 SDK로 호출하는 방식
  - 사이드카 컨테이너를 따로 두는 방식. 격리에는 유리하지만 HTTP 래퍼를 직접 짜야 한다.
- **MCP 모드:** 운영 파서로 부적합하다. 인증이 없고, `KORDOC_ROOT`를 정하지 않으면 허용 확장자 파일을 모두 읽는다.
- **거버넌스:**
  - 2026-03-28 생성, stars 약 2,600.
  - 커밋 1,302개 중 1,202개가 메인테이너 한 명의 것이다.
  - 7개월 동안 npm 버전 220개를 냈고, 하루 5~10회 릴리스한 날도 있다.
  - main에 직접 푸시한다.
  - 마이너 버전에서 출력이 바뀐 전례가 있다(#53, #121). PDF 힙 7GB 폭주(#137)와 암호 HWPX 무한 대기(#93)도 있었고, 둘 다 수정됐다.
  - 도입하면 정확한 버전 고정, 자체 회귀 코퍼스, 파서 버전 기록이 계속 들어간다.
- **외부 접속:** OCR 모델(약 18MB)을 huggingface.co에서 받는 것뿐이다. `KORDOC_OFFLINE=1`로 막을 수 있다.
- **자체 벤치 수치:** PDF 표 98.16%, HWPX 표 100% 같은 수치는 비공개 코퍼스 기준이라 재현할 수 없다.

## kordoc 없이 가져올 수 있는 것

PDF 반복 머리글·쪽번호 줄 제거, 하드랩 이음, PUA 글머리 기호(󰊱 → ①) 정리는 현행 JVM 추출기의 후처리로 할 수 있다. 각각 작은 과제다.

## 다시 볼 조건

아래 중 하나가 생기면 이 문서의 해당 행만 다시 판정한다.

1. **다단·카드형 PDF 읽기 순서 실측에서 kordoc(`--no-tables` 포함)이 현행보다 분명히 나을 때.** 이 경우 PDF 전용 격리 워커를 설계한다. 이번에 측정하지 못한 가장 큰 미확인 항목이다. 비교 대상 문서는 backlog 「레이아웃 인지 PDF 추출」의 2026-09-02 실측에 쓴 두 문서(채용공고 `107`, 기관 소개서)다.
2. **레거시 .hwp 지원을 열기로 제품 결정이 날 때.** hwplib/hwp2hwpx PoC를 먼저 하고, kordoc 출력은 비교 기준으로 쓴다.
3. **DOCX 실문서 코퍼스가 생길 때.**
4. **kordoc Java SDK가 Maven Central에 게시되거나, HWPX 패치가 SDK에 노출될 때.**

## 미확인 항목

- 동시 실행 처리량과 웜 프로세스 비교
- `--omit=optional` + `pdfjs-dist` 최소 설치의 PDF 동작
- 병합 셀 위치 정확도
- .hwp 출력 완전성. 참조본이 없다.
- 반복 머리글 제거의 오삭제 여부

## 문서 간 불일치

`docs/master-plan.md`는 구버전 hwp를 「텍스트 추출 베스트에포트」라고 적었다. 코드(`ExtractionLimits.kt`, `Ole2Diagnosis.kt`)와 계약 `legacy_hwp_policy`는 422 거절이다. 정본은 코드와 계약이다.

## 재현

```bash
npm install kordoc@4.24.1 --ignore-scripts          # 설치 스크립트는 onnxruntime-node 하나뿐
KORDOC_OFFLINE=1 node run1.mjs <파일> <출력접두> <kordoc dist/index.js>   # import { parse } 후 parse(ArrayBuffer)
```

- **현행 추출기 실행:** `./gradlew :infrastructure:classes`로 컴파일한다. Gradle init script로 `:infrastructure`의 `runtimeClasspath`를 출력해 클래스패스를 얻는다. 단일 파일 Java 하네스가 `DocumentExtractors().extract()`를 호출한다.
- **비교 기준 텍스트:** hwpx는 `Contents/section*.xml`의 `<hp:t>`, PDF는 `pdftotext`다.
- **로컬 자료:** 스크립트(`run1.mjs`, `runall.sh`, `runjvm.sh`, `compare.py`, `Base.java`, `cp.init.gradle`)와 집계 결과는 이 작업 기기의 `.omc/research/research_notes/kordoc 문서 파서 대체 검토/` 아래에 있다. 같은 폴더에 형식별 조사 노트 5종이 있고, 긴 보고서는 `.omc/research/reports/`에 있다. 모두 git 제외 경로라 다른 기기에는 없다.
