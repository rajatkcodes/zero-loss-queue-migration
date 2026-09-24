import { useEffect, useState } from 'react'
import { api } from '../api'
import { SeverityBadge } from './Badge'

const RUNBOOK = [
  { sev: 'P1', title: 'Page immediately', body: 'Enterprise SLA breach or the legacy path is down. Customer-visible impact on a priority tier.' },
  { sev: 'P2', title: 'Investigate same day', body: 'DLQ growth or a standard-tier SLA breach. No enterprise impact, but reliability is degrading.' },
  { sev: 'P3', title: 'Monitor', body: 'Backlog building on the legacy queue. Not yet breaching SLA, but trending the wrong way.' },
  { sev: 'P4', title: 'Informational', body: 'A migration/cutover event, e.g. a queue mode changed. No action needed, kept for audit trail.' },
]

function timeAgo(ts) {
  const s = Math.max(0, (Date.now() - ts) / 1000)
  if (s < 60) return `${s.toFixed(0)}s ago`
  return `${(s / 60).toFixed(1)}m ago`
}

export function Incidents() {
  const [incidents, setIncidents] = useState([])
  const [expanded, setExpanded] = useState(null)

  useEffect(() => {
    let cancelled = false
    const tick = async () => {
      try {
        const res = await api.incidents()
        if (!cancelled) setIncidents(res.incidents)
      } catch { /* ignore */ }
    }
    tick()
    const timer = setInterval(tick, 3000)
    return () => { cancelled = true; clearInterval(timer) }
  }, [])

  return (
    <div className="view incidents-view">
      <div className="incidents-feed">
        <h3 className="section-title">Incident Feed</h3>
        <div className="trace-list-scroll">
          {incidents.map((inc) => (
            <div key={inc.id} className="incident-row" onClick={() => setExpanded(expanded === inc.id ? null : inc.id)}>
              <div className="incident-row-top">
                <SeverityBadge severity={inc.severity} />
                <span className="incident-title">{inc.title}</span>
                <span className="incident-ago">{timeAgo(inc.ts)}</span>
              </div>
              {expanded === inc.id && (
                <pre className="incident-detail">{JSON.stringify(inc.detail, null, 2)}</pre>
              )}
            </div>
          ))}
          {incidents.length === 0 && <p className="empty-row">no incidents — all quiet</p>}
        </div>
      </div>

      <div className="runbook-panel">
        <h3 className="section-title">Runbook Reference</h3>
        {RUNBOOK.map((r) => (
          <div key={r.sev} className="runbook-entry">
            <SeverityBadge severity={r.sev} />
            <div>
              <div className="runbook-entry-title">{r.title}</div>
              <div className="runbook-entry-body">{r.body}</div>
            </div>
          </div>
        ))}
      </div>
    </div>
  )
}
