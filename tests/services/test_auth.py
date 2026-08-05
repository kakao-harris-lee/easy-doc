"""인증 서비스 단위 테스트 — fake 저장소로 DB 없이 검증한다.

핵심 관심사는 넷이다.
1. 실패 응답이 가입 여부를 드러내지 않는가 (메시지·처리 경로 모두).
2. 비밀번호·이메일·토큰이 예외 메시지로 새지 않는가.
3. argon2 계산이 이벤트 루프를 막지 않는가.
4. 커밋 시점을 서비스가 소유하는가.
"""

import threading
import uuid

import jwt
import pytest
from argon2 import PasswordHasher

from app.exceptions import (
    EmailAlreadyRegisteredError,
    InvalidCredentialsError,
    InvalidInputError,
)
from app.services import auth as auth_module
from app.services.auth import AuthService, hash_password, verify_password
from tests.fakes import FakeUserRepository

_SECRET = "test-secret-do-not-use-in-production"
# HMAC 키가 32바이트 미만이면 PyJWT가 경고를 낸다 — 테스트 출력을 깨끗하게 유지한다.
_ATTACKER_SECRET = "attacker-key-attacker-key-attacker-key"
_EMAIL = "user@example.com"
_PASSWORD = "sufficiently-long-password"


def _service(
    repository: FakeUserRepository | None = None,
    expire_minutes: int = 60,
) -> AuthService:
    """테스트용 서비스. 저장소를 넘기지 않으면 비어 있는 저장소를 쓴다."""
    return AuthService(
        repository=repository if repository is not None else FakeUserRepository(),
        jwt_secret=_SECRET,
        expire_minutes=expire_minutes,
    )


# --- 비밀번호 해싱 -------------------------------------------------------------


async def test_비밀번호는_argon2_해시로_저장된다() -> None:
    hashed = await hash_password(_PASSWORD)

    assert hashed.startswith("$argon2")
    # 평문이 해시 문자열 어디에도 남지 않아야 한다.
    assert _PASSWORD not in hashed
    assert await verify_password(hashed, _PASSWORD) is True


async def test_같은_비밀번호도_매번_다른_해시가_된다() -> None:
    """솔트가 붙지 않으면 레인보우 테이블·해시 일치 비교가 가능해진다."""
    assert await hash_password(_PASSWORD) != await hash_password(_PASSWORD)


async def test_틀린_비밀번호와_깨진_해시는_False가_된다() -> None:
    """argon2 예외를 밖으로 흘리지 않고 불리언으로 정규화한다."""
    hashed = await hash_password(_PASSWORD)

    assert await verify_password(hashed, "wrong-password") is False
    assert await verify_password("not-a-hash", _PASSWORD) is False


def test_해시_파라미터가_명시적으로_고정돼_있다() -> None:
    """라이브러리 기본값이 업그레이드에서 바뀌어도 우리 비용은 그대로여야 한다."""
    hasher = auth_module._HASHER

    assert (hasher.time_cost, hasher.memory_cost, hasher.parallelism) == (3, 65536, 4)


class _ThreadRecordingHasher:
    """argon2 호출이 어느 스레드에서 일어나는지 기록하는 래퍼.

    PasswordHasher는 속성 변경이 막혀 있어 메서드를 직접 감쌀 수 없다 — 모듈의
    _HASHER 자체를 갈아끼운다. 위임 대상은 가장 가벼운 파라미터로 둔다(속도 목적).
    """

    def __init__(self, threads: list[int]) -> None:
        self._delegate = PasswordHasher(time_cost=1, memory_cost=8, parallelism=1)
        self._threads = threads

    def hash(self, password: str) -> str:
        self._threads.append(threading.get_ident())
        return self._delegate.hash(password)

    def verify(self, password_hash: str, password: str) -> bool:
        self._threads.append(threading.get_ident())
        return self._delegate.verify(password_hash, password)


@pytest.mark.parametrize("target", ["hash", "verify"])
async def test_argon2_계산은_이벤트_루프_스레드_밖에서_돈다(
    monkeypatch: pytest.MonkeyPatch, target: str
) -> None:
    """argon2는 한 번에 ~23ms를 쓴다 — 루프에서 돌리면 그동안 모든 요청이 멈춘다.

    시간 측정은 머신 부하에 흔들리므로, 계산이 호출자와 다른 스레드에서 일어났는지로
    오프로드 여부를 확인한다.
    """
    threads: list[int] = []
    monkeypatch.setattr(auth_module, "_HASHER", _ThreadRecordingHasher(threads))

    if target == "hash":
        await hash_password(_PASSWORD)
    else:
        await verify_password(await hash_password(_PASSWORD), _PASSWORD)

    assert threads, "argon2가 호출되지 않았다"
    assert threading.get_ident() not in threads


# --- 가입 ---------------------------------------------------------------------


async def test_가입하면_해시만_저장된다() -> None:
    repository = FakeUserRepository()

    user = await _service(repository).signup(email=_EMAIL, password=_PASSWORD)

    assert user.email == _EMAIL
    assert user.password_hash != _PASSWORD
    assert await verify_password(user.password_hash, _PASSWORD) is True


async def test_가입은_커밋까지_마친다() -> None:
    """트랜잭션 경계는 서비스가 소유한다 — 저장소는 flush만 하고 확정하지 않는다."""
    repository = FakeUserRepository()

    await _service(repository).signup(email=_EMAIL, password=_PASSWORD)

    assert repository.commits == 1


@pytest.mark.parametrize(
    ("email", "password"),
    [("not-an-email", _PASSWORD), (_EMAIL, "short")],
)
async def test_입력이_틀리면_커밋하지_않는다(email: str, password: str) -> None:
    repository = FakeUserRepository()

    with pytest.raises(InvalidInputError):
        await _service(repository).signup(email=email, password=password)

    assert repository.commits == 0


async def test_중복_가입은_커밋하지_않는다() -> None:
    repository = FakeUserRepository()
    service = _service(repository)
    await service.signup(email=_EMAIL, password=_PASSWORD)

    with pytest.raises(EmailAlreadyRegisteredError):
        await service.signup(email=_EMAIL, password=_PASSWORD)

    assert repository.commits == 1


async def test_이메일은_소문자로_정규화된다() -> None:
    """대소문자만 다른 주소로 계정이 두 개 생기지 않게 한다."""
    repository = FakeUserRepository()
    service = _service(repository)

    user = await service.signup(email="  User@Example.COM  ", password=_PASSWORD)

    assert user.email == _EMAIL
    # 정규화가 로그인 경로에도 똑같이 적용되어야 가입한 계정으로 들어갈 수 있다.
    assert await service.login(email="USER@EXAMPLE.COM", password=_PASSWORD)


@pytest.mark.parametrize(
    "email",
    ["없는골뱅이", "@example.com", "user@", "user@example", "us er@example.com", ""],
)
async def test_이메일_형식이_아니면_거부한다(email: str) -> None:
    with pytest.raises(InvalidInputError):
        await _service().signup(email=email, password=_PASSWORD)


async def test_너무_긴_이메일은_거부한다() -> None:
    """컬럼 상한(255자)을 넘기면 DB가 터진다 — 도메인 예외로 먼저 막는다."""
    long_email = f"{'a' * 250}@example.com"

    with pytest.raises(InvalidInputError):
        await _service().signup(email=long_email, password=_PASSWORD)


async def test_비밀번호가_8자_미만이면_거부한다() -> None:
    with pytest.raises(InvalidInputError):
        await _service().signup(email=_EMAIL, password="1234567")


async def test_비밀번호_8자는_통과한다() -> None:
    """경계값 — 최소 길이 자체는 허용되어야 한다."""
    user = await _service().signup(email=_EMAIL, password="12345678")

    assert user.email == _EMAIL


async def test_중복_가입은_도메인_예외가_된다() -> None:
    repository = FakeUserRepository()
    service = _service(repository)
    await service.signup(email=_EMAIL, password=_PASSWORD)

    with pytest.raises(EmailAlreadyRegisteredError):
        await service.signup(email=_EMAIL, password=_PASSWORD)


async def test_입력_검증_예외에_이메일과_비밀번호가_들어가지_않는다() -> None:
    """예외 메시지는 그대로 로그·HTTP 응답에 실린다 — 개인정보를 담으면 안 된다."""
    with pytest.raises(InvalidInputError) as invalid_email:
        await _service().signup(email="broken-email", password=_PASSWORD)
    with pytest.raises(InvalidInputError) as short_password:
        await _service().signup(email=_EMAIL, password="short")

    assert "broken-email" not in str(invalid_email.value)
    assert "short" not in str(short_password.value)
    assert _PASSWORD not in str(invalid_email.value)


# --- 로그인 -------------------------------------------------------------------


async def test_로그인에_성공하면_토큰을_돌려준다() -> None:
    repository = FakeUserRepository()
    service = _service(repository)
    user = await service.signup(email=_EMAIL, password=_PASSWORD)

    token = await service.login(email=_EMAIL, password=_PASSWORD)

    assert service.resolve_token(token) == user.id


async def test_없는_사용자와_틀린_비밀번호는_구분되지_않는다() -> None:
    """실패 사유가 다르면 응답만으로 가입 여부를 알아낼 수 있다(계정 열거)."""
    repository = FakeUserRepository()
    service = _service(repository)
    await service.signup(email=_EMAIL, password=_PASSWORD)

    with pytest.raises(InvalidCredentialsError) as unknown_user:
        await service.login(email="ghost@example.com", password=_PASSWORD)
    with pytest.raises(InvalidCredentialsError) as wrong_password:
        await service.login(email=_EMAIL, password="wrong-password-here")

    assert str(unknown_user.value) == str(wrong_password.value)


async def test_로그인_실패_예외에_이메일과_비밀번호가_들어가지_않는다() -> None:
    with pytest.raises(InvalidCredentialsError) as error:
        await _service().login(email=_EMAIL, password=_PASSWORD)

    assert _EMAIL not in str(error.value)
    assert _PASSWORD not in str(error.value)


async def test_사용자가_없어도_비밀번호_검증_비용을_똑같이_치른다(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """더미 해시 검증을 건너뛰면 응답 시간 차이로 가입 여부가 새어 나간다.

    시간 측정은 불안정하므로, 검증 함수가 실제로 호출됐는지로 대신 확인한다.
    """
    verified_hashes: list[str] = []
    original = auth_module.verify_password

    async def spy(password_hash: str, password: str) -> bool:
        verified_hashes.append(password_hash)
        return await original(password_hash, password)

    monkeypatch.setattr(auth_module, "verify_password", spy)

    with pytest.raises(InvalidCredentialsError):
        await _service().login(email="ghost@example.com", password=_PASSWORD)

    assert len(verified_hashes) == 1
    assert verified_hashes[0].startswith("$argon2")


async def test_예전_파라미터의_해시는_로그인할_때_다시_저장된다() -> None:
    """파라미터를 올린 뒤 일괄 마이그레이션 없이 로그인마다 조금씩 이관된다."""
    weak_hash = PasswordHasher(time_cost=1, memory_cost=8, parallelism=1).hash(_PASSWORD)
    repository = FakeUserRepository()
    await repository.create(email=_EMAIL, password_hash=weak_hash)
    service = _service(repository)

    await service.login(email=_EMAIL, password=_PASSWORD)

    stored = await repository.get_by_email(_EMAIL)
    assert stored is not None
    assert stored.password_hash != weak_hash
    assert await verify_password(stored.password_hash, _PASSWORD) is True
    # 재해시는 저장까지 끝나야 의미가 있다.
    assert repository.commits == 1


async def test_최신_파라미터면_재해시하지_않는다() -> None:
    """멀쩡한 해시를 매 로그인마다 다시 쓰면 불필요한 UPDATE가 쌓인다."""
    repository = FakeUserRepository()
    service = _service(repository)
    user = await service.signup(email=_EMAIL, password=_PASSWORD)
    original_hash = user.password_hash

    await service.login(email=_EMAIL, password=_PASSWORD)

    assert user.password_hash == original_hash
    assert repository.commits == 1  # 가입 때 한 번뿐


# --- 토큰 ---------------------------------------------------------------------


async def test_토큰_클레임은_sub_exp_typ뿐이다() -> None:
    """이메일 등 개인정보를 토큰에 싣지 않는다 — JWT 본문은 누구나 디코딩할 수 있다."""
    repository = FakeUserRepository()
    service = _service(repository)
    user = await service.signup(email=_EMAIL, password=_PASSWORD)

    token = await service.login(email=_EMAIL, password=_PASSWORD)
    claims = jwt.decode(token, _SECRET, algorithms=["HS256"])

    assert set(claims) == {"sub", "exp", "typ"}
    assert claims["sub"] == str(user.id)
    assert claims["typ"] == "access"
    assert _EMAIL not in token


def test_typ가_없는_토큰은_거부한다() -> None:
    """용도 클레임이 없으면 우리가 발급한 액세스 토큰이 아니다."""
    no_type = jwt.encode({"sub": str(uuid.uuid4()), "exp": 1 << 40}, _SECRET, "HS256")

    with pytest.raises(InvalidCredentialsError):
        _service().resolve_token(no_type)


def test_다른_용도의_토큰은_액세스_토큰으로_쓸_수_없다() -> None:
    """같은 시크릿으로 서명된 비밀번호 재설정 토큰 등이 인증을 통과하면 안 된다."""
    reset_token = jwt.encode(
        {"sub": str(uuid.uuid4()), "exp": 1 << 40, "typ": "password_reset"}, _SECRET, "HS256"
    )

    with pytest.raises(InvalidCredentialsError):
        _service().resolve_token(reset_token)


async def test_만료된_토큰은_거부한다() -> None:
    repository = FakeUserRepository()
    service = _service(repository, expire_minutes=-1)
    await service.signup(email=_EMAIL, password=_PASSWORD)
    expired = await service.login(email=_EMAIL, password=_PASSWORD)

    with pytest.raises(InvalidCredentialsError):
        service.resolve_token(expired)


async def test_다른_비밀키로_서명한_토큰은_거부한다() -> None:
    forged = jwt.encode(
        {"sub": str(uuid.uuid4()), "exp": 1 << 40, "typ": "access"}, _ATTACKER_SECRET, "HS256"
    )

    with pytest.raises(InvalidCredentialsError):
        _service().resolve_token(forged)


def test_서명이_없는_토큰은_거부한다() -> None:
    """alg=none 공격 — 서명 검증을 끄도록 유도하는 고전 우회 경로."""
    unsigned = jwt.encode(
        {"sub": str(uuid.uuid4()), "exp": 1 << 40, "typ": "access"}, key="", algorithm="none"
    )

    with pytest.raises(InvalidCredentialsError):
        _service().resolve_token(unsigned)


def test_exp가_없는_토큰은_거부한다() -> None:
    """만료 없는 토큰은 영구 자격증명이 된다."""
    eternal = jwt.encode({"sub": str(uuid.uuid4()), "typ": "access"}, _SECRET, "HS256")

    with pytest.raises(InvalidCredentialsError):
        _service().resolve_token(eternal)


def test_sub가_UUID가_아닌_토큰은_거부한다() -> None:
    """우리가 발급하지 않은 형태 — 조회 단계로 넘기지 않고 인증 단계에서 끊는다."""
    malformed = jwt.encode({"sub": "not-a-uuid", "exp": 1 << 40, "typ": "access"}, _SECRET, "HS256")

    with pytest.raises(InvalidCredentialsError):
        _service().resolve_token(malformed)


@pytest.mark.parametrize("token", ["", "garbage", "a.b.c"])
def test_토큰이_아닌_문자열은_거부한다(token: str) -> None:
    with pytest.raises(InvalidCredentialsError):
        _service().resolve_token(token)


def test_토큰_거부_예외에_토큰값이_들어가지_않는다() -> None:
    """토큰은 그 자체가 자격증명이다 — 로그에 남으면 탈취 경로가 된다."""
    forged = jwt.encode(
        {"sub": str(uuid.uuid4()), "exp": 1 << 40, "typ": "access"}, _ATTACKER_SECRET, "HS256"
    )

    with pytest.raises(InvalidCredentialsError) as error:
        _service().resolve_token(forged)

    assert forged not in str(error.value)


def test_토큰_유효기간을_초로_알려준다() -> None:
    """클라이언트가 토큰을 디코딩하지 않고도 재발급 시점을 계산할 수 있어야 한다."""
    assert _service(expire_minutes=60).access_token_lifetime_seconds == 3600
