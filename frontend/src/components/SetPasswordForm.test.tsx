import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { setPassword } from '../api/auth'
import { ApiError } from '../api/client'
import { SetPasswordForm } from './SetPasswordForm'

vi.mock('../api/auth', () => ({
  setPassword: vi.fn(),
}))

beforeEach(() => {
  vi.mocked(setPassword).mockReset()
})

describe('SetPasswordForm — 비밀번호 만들기', () => {
  it('처음에는 「비밀번호 만들기」 버튼만 보이고 입력 폼은 없다', () => {
    render(<SetPasswordForm />)

    expect(screen.getByRole('button', { name: '비밀번호 만들기' })).toBeInTheDocument()
    expect(screen.queryByLabelText('새 비밀번호')).not.toBeInTheDocument()
  })

  it('버튼을 누르면 새 비밀번호·확인 입력 폼이 펼쳐진다', async () => {
    const user = userEvent.setup()
    render(<SetPasswordForm />)

    await user.click(screen.getByRole('button', { name: '비밀번호 만들기' }))

    expect(screen.getByLabelText('새 비밀번호')).toBeInTheDocument()
    expect(screen.getByLabelText('새 비밀번호 확인')).toBeInTheDocument()
  })

  it('일치하는 비밀번호를 제출하면 설정하고 성공 문구를 보여주며 onCreated를 부른다', async () => {
    const user = userEvent.setup()
    vi.mocked(setPassword).mockResolvedValue(undefined)
    const onCreated = vi.fn()
    render(<SetPasswordForm onCreated={onCreated} />)

    await user.click(screen.getByRole('button', { name: '비밀번호 만들기' }))
    await user.type(screen.getByLabelText('새 비밀번호'), 'brand-new-password')
    await user.type(screen.getByLabelText('새 비밀번호 확인'), 'brand-new-password')
    await user.click(screen.getByRole('button', { name: '만들기' }))

    expect(vi.mocked(setPassword)).toHaveBeenCalledWith('brand-new-password')
    expect(await screen.findByText('비밀번호를 만들었습니다')).toBeInTheDocument()
    expect(onCreated).toHaveBeenCalledTimes(1)
    // 성공 뒤 폼은 접힌다 — 트리거 버튼이 다시 보인다.
    expect(screen.getByRole('button', { name: '비밀번호 만들기' })).toBeInTheDocument()
  })

  it('두 비밀번호가 다르면 요청을 보내지 않고 오류를 보여준다', async () => {
    const user = userEvent.setup()
    render(<SetPasswordForm />)

    await user.click(screen.getByRole('button', { name: '비밀번호 만들기' }))
    await user.type(screen.getByLabelText('새 비밀번호'), 'brand-new-password')
    await user.type(screen.getByLabelText('새 비밀번호 확인'), 'different-password')
    await user.click(screen.getByRole('button', { name: '만들기' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('비밀번호가 서로 다릅니다')
    expect(vi.mocked(setPassword)).not.toHaveBeenCalled()
  })

  it('8자 미만 비밀번호는 서비스를 부르지 않고 필드 오류를 보여준다', async () => {
    const user = userEvent.setup()
    render(<SetPasswordForm />)

    await user.click(screen.getByRole('button', { name: '비밀번호 만들기' }))
    await user.type(screen.getByLabelText('새 비밀번호'), 'short')
    await user.type(screen.getByLabelText('새 비밀번호 확인'), 'short')
    await user.click(screen.getByRole('button', { name: '만들기' }))

    expect(screen.getByText('비밀번호는 8자 이상이어야 합니다')).toBeInTheDocument()
    expect(vi.mocked(setPassword)).not.toHaveBeenCalled()
  })

  it('서버가 409(이미 비밀번호 있음)를 주면 그 문구를 보여준다', async () => {
    const user = userEvent.setup()
    vi.mocked(setPassword).mockRejectedValue(
      new ApiError(
        409,
        '이미 비밀번호가 있는 계정입니다. 비밀번호를 바꾸려면 재설정을 이용하세요.',
      ),
    )
    render(<SetPasswordForm />)

    await user.click(screen.getByRole('button', { name: '비밀번호 만들기' }))
    await user.type(screen.getByLabelText('새 비밀번호'), 'brand-new-password')
    await user.type(screen.getByLabelText('새 비밀번호 확인'), 'brand-new-password')
    await user.click(screen.getByRole('button', { name: '만들기' }))

    expect(
      await screen.findByText(
        '이미 비밀번호가 있는 계정입니다. 비밀번호를 바꾸려면 재설정을 이용하세요.',
      ),
    ).toBeInTheDocument()
  })

  it('취소를 누르면 요청 없이 폼이 접힌다', async () => {
    const user = userEvent.setup()
    render(<SetPasswordForm />)

    await user.click(screen.getByRole('button', { name: '비밀번호 만들기' }))
    await user.type(screen.getByLabelText('새 비밀번호'), 'brand-new-password')
    await user.click(screen.getByRole('button', { name: '취소' }))

    expect(screen.queryByLabelText('새 비밀번호')).not.toBeInTheDocument()
    expect(vi.mocked(setPassword)).not.toHaveBeenCalled()
  })
})
