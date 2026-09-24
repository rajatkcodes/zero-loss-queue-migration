import { useMemo, useState } from 'react'
import { TierBadge, ModeBadge } from './Badge'

function breachClass(rate) {
  if (rate > 0.15) return 'sla-red'
  if (rate > 0.05) return 'sla-amber'
  return 'sla-green'
}

export function SlaDashboard({ snapshot }) {
  const [tierFilter, setTierFilter] = useState('all')
  const [modeFilter, setModeFilter] = useState('all')

  const merchantRows = useMemo(() => {
    const merchants = snapshot?.merchants ?? {}
    return Object.entries(merchants)
      .map(([id, stats]) => ({ id, ...stats }))
      .filter((r) => tierFilter === 'all' || r.tier === tierFilter)
      .filter((r) => modeFilter === 'all' || r.mode === modeFilter)
      .sort((a, b) => (b['breach-rate'] ?? 0) - (a['breach-rate'] ?? 0))
  }, [snapshot, tierFilter, modeFilter])

  const queueRows = useMemo(() => {
    const queues = snapshot?.queues ?? {}
    return Object.entries(queues).map(([id, stats]) => ({ id, ...stats }))
  }, [snapshot])

  if (!snapshot) {
    return <div className="view"><p className="view-caption">Waiting for live metrics…</p></div>
  }

  return (
    <div className="view">
      <h3 className="section-title">Per-Queue Aggregate</h3>
      <div className="table-wrap">
        <table className="data-table">
          <thead>
            <tr>
              <th>Queue</th><th>Tier</th><th>Mode</th><th>Samples</th>
              <th>p50</th><th>p95</th><th>p99</th><th>Error Rate</th><th>Breach Rate</th>
            </tr>
          </thead>
          <tbody>
            {queueRows.map((r) => (
              <tr key={r.id}>
                <td className="mono">{r.id}</td>
                <td><TierBadge tier={r.tier} /></td>
                <td><ModeBadge mode={r.mode} /></td>
                <td>{r['sample-count']}</td>
                <td>{r['p50-latency-ms']?.toFixed(0)}ms</td>
                <td>{r['p95-latency-ms']?.toFixed(0)}ms</td>
                <td>{r['p99-latency-ms']?.toFixed(0)}ms</td>
                <td>{((r['error-rate'] ?? 0) * 100).toFixed(1)}%</td>
                <td className={breachClass(r['breach-rate'] ?? 0)}>{((r['breach-rate'] ?? 0) * 100).toFixed(1)}%</td>
              </tr>
            ))}
            {queueRows.length === 0 && <tr><td colSpan={9} className="empty-row">no traffic in the last 60s</td></tr>}
          </tbody>
        </table>
      </div>

      <div className="filters-row">
        <h3 className="section-title" style={{ marginRight: 'auto' }}>Per-Merchant</h3>
        <select value={tierFilter} onChange={(e) => setTierFilter(e.target.value)} className="mini-select">
          <option value="all">all tiers</option>
          <option value="standard">standard</option>
          <option value="enterprise">enterprise</option>
        </select>
        <select value={modeFilter} onChange={(e) => setModeFilter(e.target.value)} className="mini-select">
          <option value="all">all modes</option>
          <option value="legacy">legacy</option>
          <option value="dual-write">dual-write</option>
          <option value="new">new</option>
        </select>
      </div>
      <div className="table-wrap">
        <table className="data-table">
          <thead>
            <tr>
              <th>Merchant</th><th>Tier</th><th>Queue</th><th>Mode</th><th>Samples</th>
              <th>p50</th><th>p95</th><th>p99</th><th>Error Rate</th><th>Breach Rate</th><th>DLQ</th>
            </tr>
          </thead>
          <tbody>
            {merchantRows.map((r) => (
              <tr key={r.id}>
                <td className="mono">{r.id}</td>
                <td><TierBadge tier={r.tier} /></td>
                <td className="mono">{r['queue-id']}</td>
                <td><ModeBadge mode={r.mode} /></td>
                <td>{r['sample-count']}</td>
                <td>{r['p50-latency-ms']?.toFixed(0)}ms</td>
                <td>{r['p95-latency-ms']?.toFixed(0)}ms</td>
                <td>{r['p99-latency-ms']?.toFixed(0)}ms</td>
                <td>{((r['error-rate'] ?? 0) * 100).toFixed(1)}%</td>
                <td className={breachClass(r['breach-rate'] ?? 0)}>{((r['breach-rate'] ?? 0) * 100).toFixed(1)}%</td>
                <td>{r.dlq > 0 ? <span className="badge badge-sev-p2">{r.dlq}</span> : '—'}</td>
              </tr>
            ))}
            {merchantRows.length === 0 && <tr><td colSpan={11} className="empty-row">no matching merchants</td></tr>}
          </tbody>
        </table>
      </div>
    </div>
  )
}
