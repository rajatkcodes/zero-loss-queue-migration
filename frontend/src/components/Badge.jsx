export function TierBadge({ tier }) {
  return <span className={`badge badge-tier-${tier}`}>{tier}</span>
}

export function ModeBadge({ mode }) {
  return <span className={`badge badge-mode-${mode}`}>{mode}</span>
}

export function SeverityBadge({ severity }) {
  return <span className={`badge badge-sev-${severity?.toLowerCase()}`}>{severity}</span>
}

export function StatusDot({ ok }) {
  return <span className={`status-dot ${ok ? 'status-ok' : 'status-bad'}`} />
}
