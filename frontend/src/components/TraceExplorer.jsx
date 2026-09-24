import { useEffect, useState } from 'react'
import { api } from '../api'

function agoLabel(startNs) {
  const startMs = startNs / 1e6
  const deltaS = Math.max(0, (Date.now() - startMs) / 1000)
  if (deltaS < 60) return `${deltaS.toFixed(0)}s ago`
  return `${(deltaS / 60).toFixed(1)}m ago`
}

const KIND_COLOR = { PRODUCER: 'var(--accent)', CONSUMER: '#f0a030' }

export function TraceExplorer() {
  const [traces, setTraces] = useState([])
  const [selectedId, setSelectedId] = useState(null)
  const [detail, setDetail] = useState(null)
  const [expandedSpan, setExpandedSpan] = useState(null)

  useEffect(() => {
    let cancelled = false
    const tick = async () => {
      try {
        const res = await api.traces()
        if (!cancelled) setTraces(res.traces)
      } catch { /* ignore */ }
    }
    tick()
    const timer = setInterval(tick, 3000)
    return () => { cancelled = true; clearInterval(timer) }
  }, [])

  useEffect(() => {
    if (!selectedId) return
    let cancelled = false
    api.traceDetail(selectedId).then((d) => { if (!cancelled) setDetail(d) })
    return () => { cancelled = true }
  }, [selectedId])

  const spans = detail?.spans ?? []
  const traceStart = spans.length ? Math.min(...spans.map((s) => s.start_ns)) : 0
  const traceEnd = spans.length ? Math.max(...spans.map((s) => s.end_ns)) : 1
  const totalMs = Math.max(1, (traceEnd - traceStart) / 1e6)

  return (
    <div className="view trace-explorer">
      <div className="trace-list">
        <h3 className="section-title">Recent Traces</h3>
        <div className="trace-list-scroll">
          {traces.map((t) => (
            <button
              key={t['trace-id']}
              className={`trace-list-item ${selectedId === t['trace-id'] ? 'selected' : ''}`}
              onClick={() => { setSelectedId(t['trace-id']); setExpandedSpan(null) }}
            >
              <div className="trace-list-item-top">
                <span className="mono">{t['merchant-id'] ?? '—'}</span>
                <span className="trace-ago">{agoLabel(t['start-ns'])}</span>
              </div>
              <div className="trace-list-item-bottom">
                {t.names.join(' → ')} <span className="trace-span-count">({t['span-count']} spans)</span>
              </div>
            </button>
          ))}
          {traces.length === 0 && <p className="empty-row">no traces yet</p>}
        </div>
      </div>

      <div className="trace-waterfall">
        <h3 className="section-title">Waterfall {selectedId && <span className="mono trace-id-label">{selectedId}</span>}</h3>
        {!detail && <p className="view-caption">Select a trace to see its span timeline.</p>}
        {detail && spans.map((s) => {
          const offsetPct = ((s.start_ns - traceStart) / 1e6 / totalMs) * 100
          const widthPct = Math.max(0.5, (s.duration_ms / totalMs) * 100)
          const isExpanded = expandedSpan === s.span_id
          return (
            <div key={s.span_id} className="waterfall-row" onClick={() => setExpandedSpan(isExpanded ? null : s.span_id)}>
              <div className="waterfall-label">
                <span className={`waterfall-kind-dot`} style={{ background: KIND_COLOR[s.kind] ?? '#888' }} />
                {s.name}
                {s.status === 'ERROR' && <span className="badge badge-sev-p1" style={{ marginLeft: 6 }}>ERROR</span>}
              </div>
              <div className="waterfall-track">
                <div
                  className={`waterfall-bar ${s.status === 'ERROR' ? 'waterfall-bar-error' : ''}`}
                  style={{ marginLeft: `${offsetPct}%`, width: `${widthPct}%`, background: KIND_COLOR[s.kind] ?? '#888' }}
                  title={`${s.duration_ms.toFixed(2)}ms`}
                />
              </div>
              <div className="waterfall-duration">{s.duration_ms.toFixed(1)}ms</div>
              {isExpanded && (
                <div className="waterfall-attrs">
                  {Object.entries(s.attributes ?? {}).map(([k, v]) => (
                    <div key={k} className="waterfall-attr"><span className="mono">{k}</span>: {v}</div>
                  ))}
                </div>
              )}
            </div>
          )
        })}
      </div>
    </div>
  )
}
