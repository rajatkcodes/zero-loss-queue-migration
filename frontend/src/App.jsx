import { useState } from 'react'
import { Nav } from './components/Nav'
import { MigrationControl } from './components/MigrationControl'
import { ArchitectureView } from './components/ArchitectureView'
import { SlaDashboard } from './components/SlaDashboard'
import { TraceExplorer } from './components/TraceExplorer'
import { Incidents } from './components/Incidents'
import { ChaosControl } from './components/ChaosControl'
import { useLiveSnapshot } from './hooks'

function App() {
  const [tab, setTab] = useState('control')
  const { snapshot, connected, history } = useLiveSnapshot()

  return (
    <div className="app">
      <Nav active={tab} onChange={setTab} connected={connected} />
      <main className="main">
        {tab === 'control' && <MigrationControl snapshot={snapshot} />}
        {tab === 'architecture' && <ArchitectureView snapshot={snapshot} />}
        {tab === 'sla' && <SlaDashboard snapshot={snapshot} />}
        {tab === 'traces' && <TraceExplorer />}
        {tab === 'incidents' && <Incidents />}
        {tab === 'chaos' && <ChaosControl snapshot={snapshot} history={history} />}
      </main>
    </div>
  )
}

export default App
