"""골든셋 평가 패키지.

DOCUMENTS_DIR은 스키마 테스트와 LLM 평가 테스트가 공유한다.
벤치마크 스크립트(scripts/benchmark.py)는 같은 디렉터리를 리포 루트 기준으로 참조한다.
"""

from pathlib import Path

DOCUMENTS_DIR = Path(__file__).parent / "documents"
