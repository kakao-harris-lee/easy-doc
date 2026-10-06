import { afterEach, expect, it, vi } from 'vitest'
import { adminBillingAction } from './subscriptions'

afterEach(() => vi.unstubAllGlobals())
it('accepts empty 204 responses for administrator recovery actions', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(null, { status: 204 })))
  await expect(adminBillingAction('w', 'orders/o/sync', 'op', '확인')).resolves.toBeUndefined()
})
