# 실측 원자료

각 실험 폴더의 invocation.json은 호출 전 설정과 소스 SHA-256, run.log는 예약·사용량·평가 결과, outputs/passes는 첫 변환·보정·최종 채택 결과와 실제 프롬프트를 보존한다. inputs는 기존 코퍼스에서 사용한 원문이다. 새 평가 엔진을 만들지 않고 기존 Kotlin GoldenCorpusLlmEvaluationTest로 실행했다.

이 자료에는 공개 안내문의 본문·이메일·전화번호가 포함된다. API 키와 환경 파일은 포함하지 않는다.

비교 순서: baseline → lean-rag → lean-no-rag → reference-rag → reference-sol → reference-sol-high → focused-rag → focused-final → reference-short → confirmed-short → reference-long → final-sol. baseline의 judge는 사용자 기준으로 먼저 정렬했고 이후 모든 조건에 동일하게 사용했다. 모델 judge는 진단이고 원문 대조 판정은 상위 보고서에 구분해 기록한다.

실제 Chrome 검증은 live-first-result.json과 live-second-plan.json에서 별도로 추적한다. 벤치마크와 서비스 worker의 구조 지시 차이도 실패 사례로 남긴다. 최종 전체 빌드 결과는 backend-build-final.log와 backend-build-final-counts.json, 최종 배포 소스는 deployed-source-hashes.json이다.

최종 실제 출력은 live-final-output.txt, 실제 사용량·관찰은 live-final-result.json, 무료 Kotlin 사실 재확인은 live-final-fact-recheck.txt, 최종 비용은 final-settlement.json이다. runtime-final.json과 image-build-final.log는 구조 충돌 수정까지 반영한 최종 실행 이미지다. 앞선 runtime.json과 image-build.log는 첫 서비스 확인 당시 상태로 보존했다.
