const BASE = 'http://localhost:8901'

async function req(path, opts) {
  const res = await fetch(`${BASE}${path}`, {
    headers: opts?.body ? { 'Content-Type': 'application/json' } : undefined,
    ...opts,
  })
  if (!res.ok) throw new Error(`${opts?.method || 'GET'} ${path} -> ${res.status}`)
  return res.status === 204 ? null : res.json()
}

export const api = {
  health: () => req('/api/health'),
  merchants: () => req('/api/merchants'),
  queues: () => req('/api/queues'),
  setQueueMode: (id, mode) =>
    req(`/api/queues/${id}/mode`, { method: 'POST', body: JSON.stringify({ mode }) }),
  setMerchantMode: (id, mode) =>
    req(`/api/merchants/${id}/mode`, { method: 'POST', body: JSON.stringify({ mode }) }),
  clearMerchantMode: (id) => req(`/api/merchants/${id}/mode`, { method: 'DELETE' }),
  snapshot: () => req('/api/metrics/snapshot'),
  incidents: () => req('/api/incidents'),
  setChaos: (down) => req('/api/chaos/legacy', { method: 'POST', body: JSON.stringify({ down }) }),
  replayDlq: () => req('/api/dlq/replay', { method: 'POST' }),
  traces: () => req('/api/traces'),
  traceDetail: (id) => req(`/api/traces/${id}`),
}

export function wsUrl() {
  return BASE.replace('http', 'ws') + '/ws/live'
}
