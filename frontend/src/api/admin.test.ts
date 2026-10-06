import { afterEach, expect, it, vi } from 'vitest'
import {
  listAdminErrorEvents,
  listAdminFeedback,
  listAdminInvoiceRequests,
  listAdminNotifications,
  listAdminOperations,
  resolveAdminNotification,
  retryAdminNotification,
} from './admin'
import { adminBillingAction, getAdminBillingRequest } from './subscriptions'

afterEach(() => vi.unstubAllGlobals())

function captureRequests() {
  const fetch = vi.fn<typeof globalThis.fetch>().mockImplementation(() =>
    Promise.resolve(
      new Response(JSON.stringify({ items: [], page: 1, size: 20, total: 0 }), {
        headers: { 'Content-Type': 'application/json' },
      }),
    ),
  )
  vi.stubGlobal('fetch', fetch)
  return fetch
}

it('sends feedback and error filters to the server and retains pagination', async () => {
  const fetch = captureRequests()
  await listAdminFeedback({
    publish_intent: 'not_usable',
    max_quality_score: 2,
    from: '2026-10-01T00:00:00Z',
    to: '2026-10-07T00:00:00Z',
    page: 3,
  })
  const feedback = new URL(String(fetch.mock.calls[0]![0]), 'http://localhost')
  expect(Object.fromEntries(feedback.searchParams)).toEqual({
    publish_intent: 'not_usable',
    max_quality_score: '2',
    from: '2026-10-01T00:00:00Z',
    to: '2026-10-07T00:00:00Z',
    page: '3',
    size: '20',
  })
  await listAdminErrorEvents({
    failure_code: 'MODEL_TIMEOUT',
    from: '2026-10-01T00:00:00Z',
    page: 2,
  })
  const errors = new URL(String(fetch.mock.calls[1]![0]), 'http://localhost')
  expect(errors.pathname).toBe('/admin/errors/events')
  expect(errors.searchParams.get('failure_code')).toBe('MODEL_TIMEOUT')
  expect(errors.searchParams.get('page')).toBe('2')
})

it('preserves queue environment and direct notification and invoice target filters', async () => {
  const fetch = captureRequests()
  await listAdminOperations({
    environment: 'toss_test',
    kind: 'notification',
    state: 'manual_review',
    page: 2,
  })
  await listAdminNotifications({ id: 93 })
  await listAdminInvoiceRequests({ id: 'invoice-id' })
  expect(String(fetch.mock.calls[0]![0])).toContain('environment=toss_test')
  expect(String(fetch.mock.calls[1]![0])).toContain('/admin/notifications?id=93')
  expect(String(fetch.mock.calls[2]![0])).toContain('/admin/invoice-requests?id=invoice-id')
})

it('sends the same UUID and revision unchanged for notification recovery', async () => {
  const fetch = captureRequests()
  const command = { operation_id: 'operation-id', expected_revision: 7, reason: '미전달 확인' }
  await resolveAdminNotification(42, { ...command, resolution: 'not_delivered' })
  await retryAdminNotification(42, command)
  await retryAdminNotification(42, command)
  expect(JSON.parse(fetch.mock.calls[0]![1]!.body as string)).toEqual({
    ...command,
    resolution: 'not_delivered',
  })
  expect(fetch.mock.calls[1]![1]!.body as string).toBe(fetch.mock.calls[2]![1]!.body as string)
  expect(String(fetch.mock.calls[2]![0])).toContain('/admin/notifications/42/retry')
})

it('scopes financial request lookup to workspace and includes observed billing revision', async () => {
  const fetch = captureRequests()
  await getAdminBillingRequest('workspace-id', 'operation-id')
  await adminBillingAction('workspace-id', 'stop-renewal', 'operation-id', '고객 요청', 9)
  expect(String(fetch.mock.calls[0]![0])).toContain(
    '/admin/workspaces/workspace-id/billing/requests/operation-id',
  )
  expect(JSON.parse(fetch.mock.calls[1]![1]!.body as string)).toEqual({
    operation_id: 'operation-id',
    reason: '고객 요청',
    expected_revision: 9,
  })
})
