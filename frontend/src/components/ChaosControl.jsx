import { useState } from 'react'
import { api } from '../api'
import { Sparkline } from './Sparkline'

export function ChaosControl({ snapshot, history }) {
  const [busy, setBusy] = useState(false)
  const [lastReplay, setLastReplay] = useState(null)
  const legacyDown = !!snapshot?.['legacy-down']

  const toggle = async (down) => {
    setBusy(true)
    try { await api.setChaos(down) } finally { setBusy(false) }
  }

  const replay = async () => {
    setBusy(true)
    try {
      const res = await api.replayDlq()
      setLastReplay(res.replayed)
    } finally { setBusy(false) }
  }

  return (
    <div className="view">
      <p className="view-caption">
        Kill the legacy path to prove the migration's safety property: messages queue durably in Redis and
        drain with zero loss once restored. Merchants already cut over to Kafka feel nothing.
      </p>

      <div className="chaos-controls">
        <button className={`chaos-btn ${legacyDown ? 'chaos-btn-active-danger' : 'chaos-btn-danger'}`} disabled={busy || legacyDown} onClick={() => toggle(true)}>
          Kill Legacy Path
        </button>
        <button className="chaos-btn chaos-btn-safe" disabled={busy || !legacyDown} onClick={() => toggle(false)}>
          Restore Legacy Path
        </button>
        <button className="chaos-btn chaos-btn-neutral" disabled={busy} onClick={replay}>
          Replay DLQ Now
        </button>
        {lastReplay !== null && <span className="chaos-replay-result">replayed {lastReplay} message(s)</span>}
      </div>

      <div className={`chaos-status ${legacyDown ? 'chaos-status-down' : 'chaos-status-up'}`}>
        Legacy path is currently {legacyDown ? 'DOWN' : 'up'}
      </div>

      <div className="chaos-sparklines">
        <Sparkline points={history.map((h) => h.legacyDepth)} color="#f0a030" label="legacy queue depth" />
        <Sparkline points={history.map((h) => h.dlqDepth)} color="#e05252" label="DLQ depth" />
      </div>
    </div>
  )
}
