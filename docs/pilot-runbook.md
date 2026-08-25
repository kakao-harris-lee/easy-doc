# Kotlin Lean MVP 파일럿 실행서

## 전제

- Docker Engine과 Docker Compose v2
- `.env.example`을 복사한 `.env`
- `EASYDOC_AUTH_JWT_SECRET`, `EASYDOC_ENCRYPTION_KEY_V1`, `EASYDOC_ENCRYPTION_KCV_V1`
- 실제 변환을 확인할 때만 선택한 provider API key. 유료 호출 없이 상태만 보려면 `EASYDOC_LLM_PROVIDER=fake`(Compose가 `local` 프로필을 켠다)

worker는 lease를 집어 마스킹 → LLM → 결과 저장까지 실행한다. 내보내기는
`GET /conversions/{conversion_id}/export?format=docx|txt|hwpx`다. `pdf`는 계약상 422다.

## 전체 스택

```bash
cp .env.example .env
docker compose -f compose.yml config
docker compose -f compose.yml up -d --build --wait
docker compose -f compose.yml ps
```

- 화면: `http://127.0.0.1:8080`
- API: `http://127.0.0.1:8100`
- health: `http://127.0.0.1:8100/health`

서비스 흐름은 다음과 같다.

```text
browser -> frontend/nginx -> backend-api -> PostgreSQL
                                |
                                +-> lease queue -> backend-worker
```

`backend-api`가 기동하면서 Flyway로 스키마를 적용한다. `backend-worker`는 API가 healthy 한 뒤 시작한다.

## 확인 순서

1. `/health`가 200인지 확인한다.
2. 가입·로그인·작업 공간 생성이 되는지 확인한다.
3. 텍스트와 지원 파일(DOCX/PDF/HWPX)의 등록 응답이 202인지 확인한다.
4. 변환 상태가 `pending`을 지나 `done` 또는 `failed`로 끝나는지 확인한다. fake provider면 본문은 고정 문장이다.
5. 로그에 문서 본문, 개인정보, API key가 없는지 확인한다.

## 로그와 종료

```bash
docker compose -f compose.yml logs -f backend-api backend-worker
docker compose -f compose.yml down
```

`down -v`는 로컬 DB 볼륨을 삭제하므로 데이터 폐기가 명시적으로 필요할 때만 사용한다.

## CI와 같은 독립 프로젝트 검증

```bash
docker compose -f compose.yml -f compose.ci.yml run --rm backend-check
docker compose -f compose.yml -f compose.ci.yml run --rm frontend-check
```

실제 LLM 호출, 외부 배포, 기관 데이터 사용은 별도 승인과 비밀값 관리 없이 실행하지 않는다.
