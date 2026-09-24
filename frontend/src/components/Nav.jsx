const TABS = [
  { id: 'control', label: 'Migration Control' },
  { id: 'architecture', label: 'Architecture' },
  { id: 'sla', label: 'SLA Dashboard' },
  { id: 'traces', label: 'Trace Explorer' },
  { id: 'incidents', label: 'Incidents' },
  { id: 'chaos', label: 'Chaos Control' },
]

export function Nav({ active, onChange, connected }) {
  return (
    <nav className="nav">
      <div className="nav-brand">swym / queue migration</div>
      <div className="nav-tabs">
        {TABS.map((t) => (
          <button
            key={t.id}
            className={`nav-tab ${active === t.id ? 'active' : ''}`}
            onClick={() => onChange(t.id)}
          >
            {t.label}
          </button>
        ))}
      </div>
      <div className="nav-conn">
        <span className={`status-dot ${connected ? 'status-ok' : 'status-bad'}`} />
        {connected ? 'live' : 'reconnecting'}
      </div>
    </nav>
  )
}
