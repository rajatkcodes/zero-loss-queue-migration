import { useEffect, useState } from 'react'
import { api } from '../api'
import { TierBadge, ModeBadge } from './Badge'

const MODES = ['legacy', 'dual-write', 'new']

export function MigrationControl({ snapshot }) {
  const [queues, setQueues] = useState([])
  const [merchants, setMerchants] = useState([])
  const [overrides, setOverrides] = useState({})
  const [busy, setBusy] = useState(null)

  const refresh = async () => {
    const [q, m] = await Promise.all([api.queues(), api.merchants()])
    setQueues(q.queues)
    setMerchants(m.merchants)
  }

  useEffect(() => { refresh() }, [])

  const migrated = snapshot?.['core-queues-migrated'] ?? queues.filter((q) => q.mode === 'new').length
  const total = snapshot?.['core-queues-total'] ?? queues.length

  const setQueueMode = async (id, mode) => {
    setBusy(id)
    try {
      await api.setQueueMode(id, mode)
      await refresh()
    } finally {
      setBusy(null)
    }
  }

  const setMerchantOverride = async (id, mode) => {
    if (mode === '') {
      await api.clearMerchantMode(id)
      setOverrides((o) => ({ ...o, [id]: undefined }))
    } else {
      await api.setMerchantMode(id, mode)
      setOverrides((o) => ({ ...o, [id]: mode }))
    }
  }

  return (
    <div className="view">
      <div className="progress-banner">
        <div className="progress-banner-text">
          <strong>{migrated}</strong> / {total} core queues migrated off Redis
        </div>
        <div className="progress-track">
          <div className="progress-fill" style={{ width: `${total ? (migrated / total) * 100 : 0}%` }} />
        </div>
      </div>

      <div className="queue-grid">
        {queues.map((q) => (
          <div className={`queue-card mode-${q.mode}`} key={q.id}>
            <div className="queue-card-header">
              <span className="queue-label">{q.label}</span>
              <TierBadge tier={q.tier} />
            </div>
            <div className="queue-card-mode">
              current: <ModeBadge mode={q.mode} />
            </div>
            <div className="queue-card-actions">
              {MODES.map((m) => (
                <button
                  key={m}
                  className={`toggle-btn ${q.mode === m ? 'active' : ''}`}
                  disabled={busy === q.id}
                  onClick={() => setQueueMode(q.id, m)}
                >
                  {m}
                </button>
              ))}
            </div>
          </div>
        ))}
      </div>

      <h3 className="section-title">Merchants ({merchants.length})</h3>
      <div className="table-wrap">
        <table className="data-table">
          <thead>
            <tr>
              <th>Merchant</th>
              <th>Tier</th>
              <th>Queue</th>
              <th>Effective Mode</th>
              <th>Override</th>
            </tr>
          </thead>
          <tbody>
            {merchants.map((m) => {
              const queueMode = queues.find((q) => q.id === m['queue-id'])?.mode ?? 'legacy'
              const override = overrides[m.id]
              return (
                <tr key={m.id}>
                  <td className="mono">{m.id}</td>
                  <td><TierBadge tier={m.tier} /></td>
                  <td className="mono">{m['queue-id']}</td>
                  <td><ModeBadge mode={override || queueMode} /></td>
                  <td>
                    <select
                      value={override || ''}
                      onChange={(e) => setMerchantOverride(m.id, e.target.value)}
                      className="mini-select"
                    >
                      <option value="">(queue default)</option>
                      {MODES.map((m2) => (
                        <option key={m2} value={m2}>{m2}</option>
                      ))}
                    </select>
                  </td>
                </tr>
              )
            })}
          </tbody>
        </table>
      </div>
    </div>
  )
}
