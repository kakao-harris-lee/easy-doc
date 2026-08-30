# 테스트 실행 방법

```bash
PYTHONPATH=src python3 -m unittest discover -s tests -v
```

`src/easydict/*` 모듈이 아직 구현되지 않은 경우 관련 테스트는 실패가 아니라 `skipped`로 표시됩니다. 실행 결과 마지막 줄의 `skipped=N`을 확인하세요.
