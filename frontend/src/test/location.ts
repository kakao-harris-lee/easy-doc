import { vi } from 'vitest'

/**
 * `window.location.assign`을 흉내 낸다.
 *
 * jsdom의 `Location.prototype.assign`은 설정 불가(non-configurable)라
 * `vi.spyOn(window.location, 'assign')`이 "Cannot redefine property" 로 죽는다.
 * `window.location` 프로퍼티 자체를 교체하면 우회할 수 있다 — 다른 필드(origin 등)는
 * 그대로 두고 `assign`만 모의로 바꾼다.
 */
export function mockLocationAssign(): ReturnType<typeof vi.fn> {
  const assign = vi.fn()
  const original = window.location
  Object.defineProperty(window, 'location', {
    configurable: true,
    value: { ...original, assign },
  })
  return assign
}
