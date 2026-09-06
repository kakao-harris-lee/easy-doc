import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { oauthUnlink } from '../api/auth'
import { ApiError } from '../api/client'
import { SocialLinkStatus } from './SocialLinkStatus'

vi.mock('../api/auth', () => ({
  oauthUnlink: vi.fn(),
  oauthLinkStart: vi.fn(),
}))

beforeEach(() => {
  vi.mocked(oauthUnlink).mockReset()
})

describe('SocialLinkStatus — 연결 해제', () => {
  it('연결된 계정에 「연결 해제」 버튼을 보여준다', () => {
    render(<SocialLinkStatus identities={[{ provider: 'google' }]} hasPassword={true} />)

    expect(screen.getByText('구글 계정 연결됨')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '연결 해제' })).toBeEnabled()
  })

  it('확인 대화상자에서 취소하면 요청을 보내지 않는다', async () => {
    const user = userEvent.setup()
    render(<SocialLinkStatus identities={[{ provider: 'google' }]} hasPassword={true} />)

    await user.click(screen.getByRole('button', { name: '연결 해제' }))
    expect(screen.getByRole('dialog')).toBeInTheDocument()
    expect(screen.getByText('구글 계정 연결 해제')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: '취소' }))

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(vi.mocked(oauthUnlink)).not.toHaveBeenCalled()
  })

  it('확인하면 해제 요청을 보내고 성공하면 안내 문구를 보여주며 onUnlinked를 부른다', async () => {
    const user = userEvent.setup()
    vi.mocked(oauthUnlink).mockResolvedValue(undefined)
    const onUnlinked = vi.fn()
    render(
      <SocialLinkStatus
        identities={[{ provider: 'google' }, { provider: 'kakao' }]}
        hasPassword={false}
        onUnlinked={onUnlinked}
      />,
    )

    await user.click(screen.getAllByRole('button', { name: '연결 해제' })[0]!)
    const dialog = screen.getByRole('dialog')
    await user.click(within(dialog).getByRole('button', { name: '연결 해제' }))

    expect(vi.mocked(oauthUnlink)).toHaveBeenCalledWith('google')
    expect(await screen.findByText('구글 연결을 해제했습니다')).toBeInTheDocument()
    expect(onUnlinked).toHaveBeenCalledTimes(1)
  })

  it('해제 안내는 목록 수준에 있어 그 신원이 응답 갱신으로 사라져도(연결 가지로 바뀌어도) 남는다', async () => {
    const user = userEvent.setup()
    vi.mocked(oauthUnlink).mockResolvedValue(undefined)
    const { rerender } = render(
      <SocialLinkStatus identities={[{ provider: 'google' }]} hasPassword={true} />,
    )

    await user.click(screen.getByRole('button', { name: '연결 해제' }))
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: '연결 해제' }))
    expect(await screen.findByText('구글 연결을 해제했습니다')).toBeInTheDocument()

    // `onUnlinked`가 부른 `refreshMe` 뒤 부모가 새 `identities`(그 신원이 빠졌다)를
    // 내려준다 — 구글 행이 "연결됨"에서 "연결" 버튼 가지로 바뀐다.
    rerender(<SocialLinkStatus identities={[]} hasPassword={true} />)

    expect(screen.getByRole('button', { name: '구글 계정 연결' })).toBeInTheDocument()
    expect(screen.getByText('구글 연결을 해제했습니다')).toBeInTheDocument()
  })

  it('404(이미 해제됨)를 받아도 onUnlinked를 불러 낡은 행을 새로고침한다', async () => {
    const user = userEvent.setup()
    vi.mocked(oauthUnlink).mockRejectedValue(
      new ApiError(404, '연결된 소셜 계정을 찾을 수 없습니다'),
    )
    const onUnlinked = vi.fn()
    render(
      <SocialLinkStatus
        identities={[{ provider: 'google' }]}
        hasPassword={true}
        onUnlinked={onUnlinked}
      />,
    )

    await user.click(screen.getByRole('button', { name: '연결 해제' }))
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: '연결 해제' }))

    await waitFor(() => expect(onUnlinked).toHaveBeenCalledTimes(1))
    expect(await screen.findByText('연결된 소셜 계정을 찾을 수 없습니다')).toBeInTheDocument()
  })

  it('비밀번호가 없고 신원이 하나뿐이면 해제 버튼을 비활성화하고 이유를 aria-describedby로 잇는다', () => {
    render(<SocialLinkStatus identities={[{ provider: 'google' }]} hasPassword={false} />)

    const button = screen.getByRole('button', { name: '연결 해제' })
    expect(button).toBeDisabled()
    const describedBy = button.getAttribute('aria-describedby')
    expect(describedBy).toBeTruthy()
    const reason = document.getElementById(describedBy as string)
    expect(reason).toHaveTextContent(
      '마지막 로그인 수단은 해제할 수 없습니다. 먼저 다른 소셜 계정을 연결하세요.',
    )
  })

  it('비밀번호가 없어도 신원이 여럿이면 해제 버튼은 활성 상태다', () => {
    render(
      <SocialLinkStatus
        identities={[{ provider: 'google' }, { provider: 'kakao' }]}
        hasPassword={false}
      />,
    )

    screen.getAllByRole('button', { name: '연결 해제' }).forEach((button) => {
      expect(button).toBeEnabled()
    })
  })

  it('서버가 409(마지막 로그인 수단)를 돌려주면 그 문구를 그대로 보여준다', async () => {
    const user = userEvent.setup()
    vi.mocked(oauthUnlink).mockRejectedValue(
      new ApiError(
        409,
        '마지막 로그인 수단은 해제할 수 없습니다. 먼저 다른 소셜 계정을 연결하세요.',
      ),
    )
    render(<SocialLinkStatus identities={[{ provider: 'google' }]} hasPassword={true} />)

    await user.click(screen.getByRole('button', { name: '연결 해제' }))
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: '연결 해제' }))

    expect(
      await screen.findByText(
        '마지막 로그인 수단은 해제할 수 없습니다. 먼저 다른 소셜 계정을 연결하세요.',
      ),
    ).toBeInTheDocument()
  })

  it('네트워크 등으로 실패하면 제공자별 대체 문구를 보여준다', async () => {
    const user = userEvent.setup()
    vi.mocked(oauthUnlink).mockRejectedValue(new Error('network down'))
    render(<SocialLinkStatus identities={[{ provider: 'kakao' }]} hasPassword={true} />)

    await user.click(screen.getByRole('button', { name: '연결 해제' }))
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: '연결 해제' }))

    expect(
      await screen.findByText('카카오 계정 연결 해제에 실패했습니다. 잠시 후 다시 시도해 주세요.'),
    ).toBeInTheDocument()
  })

  it('연결되지 않은 제공자는 「연결」 버튼을 보여준다', () => {
    render(<SocialLinkStatus identities={[]} hasPassword={true} />)

    expect(screen.getByRole('button', { name: '구글 계정 연결' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '연결 해제' })).not.toBeInTheDocument()
  })
})
