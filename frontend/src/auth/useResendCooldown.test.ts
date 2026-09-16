import { act, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { useResendCooldown } from './useResendCooldown'

beforeEach(() => {
  vi.useFakeTimers()
})

afterEach(() => {
  vi.useRealTimers()
})

describe('useResendCooldown', () => {
  it('시작하면 1초마다 줄어들고 0에서 멈춘다', () => {
    const { result } = renderHook(() => useResendCooldown())

    act(() => {
      result.current.startCooldown(2)
    })
    expect(result.current.cooldown).toBe(2)

    act(() => {
      vi.advanceTimersByTime(1000)
    })
    expect(result.current.cooldown).toBe(1)

    act(() => {
      vi.advanceTimersByTime(1000)
    })
    expect(result.current.cooldown).toBe(0)

    act(() => {
      vi.advanceTimersByTime(1000)
    })
    expect(result.current.cooldown).toBe(0)
  })

  it('도는 중에 다시 시작하면 새 값으로 교체한다', () => {
    const { result } = renderHook(() => useResendCooldown())

    act(() => {
      result.current.startCooldown(5)
    })
    act(() => {
      vi.advanceTimersByTime(1000)
    })
    expect(result.current.cooldown).toBe(4)

    act(() => {
      result.current.startCooldown(10)
    })
    expect(result.current.cooldown).toBe(10)

    act(() => {
      vi.advanceTimersByTime(1000)
    })
    expect(result.current.cooldown).toBe(9)
  })

  it('언마운트 후에는 타이머가 더 이상 상태를 바꾸지 않는다', () => {
    const { result, unmount } = renderHook(() => useResendCooldown())

    act(() => {
      result.current.startCooldown(3)
    })
    unmount()

    expect(() => {
      act(() => {
        vi.advanceTimersByTime(5000)
      })
    }).not.toThrow()
  })
})
