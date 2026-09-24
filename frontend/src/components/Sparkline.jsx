export function Sparkline({ points, color = 'var(--accent)', height = 48, width = 320, label }) {
  if (!points || points.length < 2) {
    return (
      <div className="sparkline-empty" style={{ height, width }}>
        collecting data…
      </div>
    )
  }
  const max = Math.max(1, ...points)
  const min = 0
  const step = width / (points.length - 1)
  const path = points
    .map((v, i) => {
      const x = i * step
      const y = height - ((v - min) / (max - min || 1)) * height
      return `${i === 0 ? 'M' : 'L'}${x.toFixed(1)},${y.toFixed(1)}`
    })
    .join(' ')
  const areaPath = `${path} L${width},${height} L0,${height} Z`
  const last = points[points.length - 1]

  return (
    <div className="sparkline">
      <svg viewBox={`0 0 ${width} ${height}`} width={width} height={height} preserveAspectRatio="none">
        <path d={areaPath} fill={color} opacity="0.12" />
        <path d={path} fill="none" stroke={color} strokeWidth="1.5" />
      </svg>
      <div className="sparkline-meta">
        {label && <span className="sparkline-label">{label}</span>}
        <span className="sparkline-value" style={{ color }}>{last}</span>
      </div>
    </div>
  )
}
