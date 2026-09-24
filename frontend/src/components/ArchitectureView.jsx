const WHY_CARDS = [
  {
    n: 1,
    title: 'Flag is read from Redis, per event',
    because:
      "Not baked in at deploy time → cutover and rollback are the same lever, flipped either direction, live, with zero restarts.",
  },
  {
    n: 2,
    title: 'One shared Redis list, on purpose',
    because:
      'This is the production bug being modeled — one FIFO line, one fixed pool, so a big merchant blocks a small one behind it.',
  },
  {
    n: 3,
    title: 'Separate Kafka topic per tier',
    because:
      "Structural isolation, not priority logic → there's no shared worker to contend over, so contention is impossible, not just unlikely.",
  },
  {
    n: 4,
    title: 'One dead-letter queue for every lane',
    because:
      'Whole-event failures (crash, bad payload) are rare and few → cheaper to centralize handling than triplicate it per lane.',
  },
  {
    n: 5,
    title: 'Replay uses the current flag, not the original',
    because:
      "A message that failed pre-cutover shouldn't loop back into the exact path that just broke it — it retries wherever the merchant is now.",
  },
  {
    n: 6,
    title: 'Delivery is lane-agnostic',
    because:
      'It only ever sees "send this." That\'s what makes the two lanes truly swappable — and the migration reversible.',
  },
]

export function ArchitectureView({ snapshot }) {
  const legacyDepth = snapshot?.['legacy-queue-depth'] ?? 0
  const dlqDepth = snapshot?.['dlq-depth'] ?? 0
  const legacyDown = !!snapshot?.['legacy-down']
  const migrated = snapshot?.['core-queues-migrated'] ?? 0
  const total = snapshot?.['core-queues-total'] ?? 5

  const legacyColor = 'var(--red)'
  const newColor = 'var(--green)'
  const warnColor = 'var(--amber)'

  return (
    <div className="view">
      <p className="view-caption">
        Not a toggle bolted onto one system — two independent delivery paths, a routing flag that reads live, and a
        failure lane that redispatches through whatever path is current. Diagram below is live: numbers update over
        the WebSocket feed.
      </p>

      <figure className="arch-figure">
        <svg
          viewBox="0 0 1000 600"
          role="img"
          aria-label="Event generator reads a routing flag and forks each event to the shared legacy Redis queue or an isolated Kafka topic per tier, each with its own consumer, converging on delivery. Failures from any lane drop into a shared dead-letter queue that redispatches back through the same flag."
        >
          <defs>
            <marker id="arch-arrow" viewBox="0 0 10 10" refX="8" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
              <path d="M0,0 L10,5 L0,10 z" fill="currentColor" />
            </marker>
          </defs>
          <g fontFamily="'SF Mono', 'Fira Code', Consolas, monospace" fontSize="12" fill="var(--text)">
            {/* Event generator */}
            <rect x="20" y="215" width="140" height="60" rx="8" fill="var(--bg-panel)" stroke="var(--border)" />
            <text x="90" y="240" textAnchor="middle" fontWeight="600">Event</text>
            <text x="90" y="257" textAnchor="middle" fontWeight="600">Generator</text>

            {/* branch */}
            <line x1="160" y1="245" x2="196" y2="245" stroke="var(--text-dim)" markerEnd="url(#arch-arrow)" />
            <circle cx="200" cy="245" r="6" fill="var(--text)" />
            <text x="200" y="205" textAnchor="middle" fill="var(--text-dim)" fontSize="11">reads mode from Redis</text>
            <circle cx="200" cy="280" r="12" fill="var(--bg-panel)" stroke="var(--text-dim)" />
            <text x="200" y="284" textAnchor="middle" fontWeight="700">1</text>

            {/* fan-out */}
            <line x1="200" y1="240" x2="248" y2="85" stroke={legacyColor} markerEnd="url(#arch-arrow)" />
            <line x1="206" y1="245" x2="248" y2="245" stroke={newColor} markerEnd="url(#arch-arrow)" />
            <line x1="200" y1="250" x2="248" y2="410" stroke={newColor} markerEnd="url(#arch-arrow)" />

            {/* LEGACY lane */}
            <g className={legacyDown ? 'arch-flash' : ''}>
              <rect x="250" y="55" width="190" height="70" rx="8" fill="var(--bg-panel)" stroke={legacyColor} />
              <text x="345" y="78" textAnchor="middle" fill={legacyColor} fontWeight="600">Redis List</text>
              <text x="345" y="95" textAnchor="middle" fill="var(--text-dim)" fontSize="11">shared · no isolation</text>
              <text x="345" y="112" textAnchor="middle" fill={legacyDown ? legacyColor : 'var(--text-dim)'} fontWeight="700">
                {legacyDown ? 'DOWN · ' : ''}depth: {legacyDepth}
              </text>
            </g>
            <circle cx="272" cy="42" r="12" fill="var(--bg-panel)" stroke={legacyColor} />
            <text x="272" y="46" textAnchor="middle" fill={legacyColor} fontWeight="700">2</text>

            <line x1="440" y1="90" x2="498" y2="90" stroke={legacyColor} markerEnd="url(#arch-arrow)" />
            <rect x="500" y="60" width="190" height="60" rx="8" fill="var(--bg-panel)" stroke={legacyColor} />
            <text x="595" y="85" textAnchor="middle" fill={legacyColor} fontWeight="600">Legacy Consumer</text>
            <text x="595" y="102" textAnchor="middle" fill="var(--text-dim)" fontSize="11">4 fixed workers</text>

            {/* STANDARD lane */}
            <rect x="250" y="215" width="190" height="60" rx="8" fill="var(--bg-panel)" stroke={newColor} />
            <text x="345" y="240" textAnchor="middle" fill={newColor} fontWeight="600">Kafka · standard</text>
            <text x="345" y="257" textAnchor="middle" fill="var(--text-dim)" fontSize="11">6 partitions</text>

            <line x1="440" y1="245" x2="498" y2="245" stroke={newColor} markerEnd="url(#arch-arrow)" />
            <rect x="500" y="215" width="190" height="60" rx="8" fill="var(--bg-panel)" stroke={newColor} />
            <text x="595" y="240" textAnchor="middle" fill={newColor} fontWeight="600">New Consumer</text>
            <text x="595" y="257" textAnchor="middle" fill="var(--text-dim)" fontSize="11">6 workers · own group</text>

            <circle cx="228" cy="330" r="12" fill="var(--bg-panel)" stroke={newColor} />
            <text x="228" y="334" textAnchor="middle" fill={newColor} fontWeight="700">3</text>
            <text x="595" y="185" textAnchor="middle" fill="var(--text-dim)" fontSize="11">
              {migrated}/{total} migrated
            </text>

            {/* ENTERPRISE lane */}
            <rect x="250" y="380" width="190" height="60" rx="8" fill="var(--bg-panel)" stroke={newColor} />
            <text x="345" y="405" textAnchor="middle" fill={newColor} fontWeight="600">Kafka · enterprise</text>
            <text x="345" y="422" textAnchor="middle" fill="var(--text-dim)" fontSize="11">3 partitions</text>

            <line x1="440" y1="410" x2="498" y2="410" stroke={newColor} markerEnd="url(#arch-arrow)" />
            <rect x="500" y="380" width="190" height="60" rx="8" fill="var(--bg-panel)" stroke={newColor} />
            <text x="595" y="405" textAnchor="middle" fill={newColor} fontWeight="600">New Consumer</text>
            <text x="595" y="422" textAnchor="middle" fill="var(--text-dim)" fontSize="11">4 workers · own group</text>

            {/* converge to delivery */}
            <line x1="690" y1="90" x2="768" y2="188" stroke="var(--text-dim)" markerEnd="url(#arch-arrow)" />
            <line x1="690" y1="245" x2="768" y2="245" stroke="var(--text-dim)" markerEnd="url(#arch-arrow)" />
            <line x1="690" y1="410" x2="768" y2="302" stroke="var(--text-dim)" markerEnd="url(#arch-arrow)" />

            <rect x="770" y="160" width="170" height="170" rx="10" fill="var(--bg-panel)" stroke="var(--border)" />
            <text x="855" y="238" textAnchor="middle" fontWeight="600" fontSize="14">Delivery</text>
            <text x="855" y="258" textAnchor="middle" fill="var(--text-dim)" fontSize="11">email · sms · push</text>
            <circle cx="787" cy="150" r="12" fill="var(--bg-panel)" stroke="var(--text-dim)" />
            <text x="787" y="154" textAnchor="middle" fontWeight="700">6</text>

            {/* hard failures -> DLQ */}
            <g stroke={warnColor} fill="none" strokeDasharray="4 4">
              <polyline points="595,120 595,155 455,155 455,488" />
              <polyline points="595,275 595,310 470,310 470,488" />
              <polyline points="595,440 595,460 485,460 485,488" />
            </g>
            <text x="700" y="195" fill={warnColor} fontSize="11">hard failure only</text>

            <rect x="390" y="490" width="210" height="55" rx="8" fill="var(--bg-panel)" stroke={warnColor} />
            <text x="495" y="513" textAnchor="middle" fill={warnColor} fontWeight="600">Dead-Letter Queue</text>
            <text x="495" y="530" textAnchor="middle" fill="var(--text-dim)" fontSize="11">depth: {dlqDepth}</text>
            <circle cx="404" cy="476" r="12" fill="var(--bg-panel)" stroke={warnColor} />
            <text x="404" y="480" textAnchor="middle" fill={warnColor} fontWeight="700">4</text>

            {/* replay loop */}
            <path d="M390,517 C 220,575 90,430 195,252" fill="none" stroke={warnColor} markerEnd="url(#arch-arrow)" />
            <text x="130" y="480" fill={warnColor} fontSize="11">redispatch →</text>
            <text x="130" y="496" fill={warnColor} fontSize="11">current mode</text>
            <circle cx="115" cy="450" r="12" fill="var(--bg-panel)" stroke={warnColor} />
            <text x="115" y="454" textAnchor="middle" fill={warnColor} fontWeight="700">5</text>
          </g>
        </svg>
        <figcaption className="arch-figcaption">
          One event, one fork. The flag (①) decides whether it takes the shared legacy lane (②, red) or an isolated
          Kafka lane (③, green). Whole-event failures from any lane land in one dead-letter queue (④) that replays
          through the flag again (⑤) — not back into the lane that failed it. Delivery (⑥) never knows which lane a
          message came from.
        </figcaption>
        <div className="arch-legend">
          <span><i className="arch-dot" style={{ background: 'var(--red)' }} /> legacy path</span>
          <span><i className="arch-dot" style={{ background: 'var(--green)' }} /> new path</span>
          <span><i className="arch-dot" style={{ background: 'var(--amber)' }} /> failure / retry lane</span>
        </div>
      </figure>

      {legacyDown && (
        <div className="arch-alert">
          Legacy path is currently DOWN (chaos test). Events for un-migrated merchants are queuing safely in
          Redis — durable, delayed, not lost. Migrated merchants are unaffected.
        </div>
      )}

      <p className="section-title">why → because</p>
      <div className="why-grid">
        {WHY_CARDS.map((c) => (
          <div className="why-card" key={c.n}>
            <div className="why-badge">{c.n}</div>
            <div>
              <p className="why-title">{c.title}</p>
              <p className="why-because">{c.because}</p>
            </div>
          </div>
        ))}
      </div>

      <p className="section-title">the actual mechanism this fixes</p>
      <figure className="arch-figure">
        <svg
          viewBox="0 0 900 260"
          role="img"
          aria-label="Comparison: on the legacy shared queue, a large merchant A event blocks a small merchant B event behind it in one FIFO line, so B waits several seconds. On the new isolated design, A and B each sit on their own partition with their own worker, so B ships in a fraction of a second regardless of A's size."
        >
          <g fontFamily="'SF Mono', 'Fira Code', Consolas, monospace" fontSize="12">
            <text x="20" y="24" fill="var(--red)" fontWeight="700">LEGACY — one shared line</text>
            <rect x="20" y="80" width="55" height="46" rx="6" fill="var(--bg-panel)" stroke="var(--red)" />
            <text x="47" y="107" textAnchor="middle" fill="var(--red)" fontSize="11">4 workers</text>
            <line x1="90" y1="103" x2="76" y2="103" stroke="var(--red)" markerEnd="url(#arch-arrow)" />

            <rect x="90" y="83" width="210" height="40" fill="rgba(224,82,90,0.12)" stroke="var(--red)" />
            <text x="195" y="107" textAnchor="middle" fill="var(--red)">merchant A · 4,000 events</text>

            <rect x="300" y="83" width="70" height="40" fill="var(--bg-panel-alt)" stroke="var(--border)" />
            <text x="335" y="107" textAnchor="middle" fill="var(--text-dim)">B · 40</text>

            <text x="20" y="160" fill="var(--red)" fontSize="12.5">→ B sits behind A in the same line — waits ~6s</text>

            <line x1="440" y1="10" x2="440" y2="230" stroke="var(--border)" />

            <text x="475" y="24" fill="var(--green)" fontWeight="700">NEW — isolated per partition</text>
            <rect x="475" y="60" width="55" height="40" rx="6" fill="var(--bg-panel)" stroke="var(--green)" />
            <text x="502" y="84" textAnchor="middle" fill="var(--green)" fontSize="11">worker</text>
            <line x1="595" y1="80" x2="533" y2="80" stroke="var(--green)" markerEnd="url(#arch-arrow)" />
            <rect x="595" y="60" width="90" height="40" fill="rgba(76,175,125,0.15)" stroke="var(--green)" />
            <text x="640" y="84" textAnchor="middle" fill="var(--green)">A · 4,000</text>

            <rect x="475" y="140" width="55" height="40" rx="6" fill="var(--bg-panel)" stroke="var(--green)" />
            <text x="502" y="164" textAnchor="middle" fill="var(--green)" fontSize="11">worker</text>
            <line x1="595" y1="160" x2="533" y2="160" stroke="var(--green)" markerEnd="url(#arch-arrow)" />
            <rect x="595" y="140" width="55" height="40" fill="rgba(76,175,125,0.15)" stroke="var(--green)" />
            <text x="622" y="164" textAnchor="middle" fill="var(--green)">B · 40</text>

            <text x="475" y="220" fill="var(--green)" fontSize="12.5">→ A and B never share a line — B ships in ~0.3s</text>
          </g>
        </svg>
        <figcaption className="arch-figcaption">
          Same two merchants, two topologies. The queue doesn't get "smarter" on the new path — the small merchant
          simply never enters the same line as the large one.
        </figcaption>
      </figure>

      <div className="takeaway">
        → <strong>Every arrow above is swappable at runtime.</strong> That's the actual system-design claim: not "we
        moved to Kafka," but "we built a pipeline where moving is reversible, observable, and doesn't require
        trusting a big-bang cutover."
      </div>
    </div>
  )
}
