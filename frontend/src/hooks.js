import { useEffect, useRef, useState } from 'react'
import { api, wsUrl } from './api'

export function useLiveSnapshot() {
  const [snapshot, setSnapshot] = useState(null)
  const [connected, setConnected] = useState(false)
  const [lastEvent, setLastEvent] = useState(null)
  const historyRef = useRef([])
  const [history, setHistory] = useState([])

  useEffect(() => {
    let ws
    let pollTimer
    let closedByUs = false

    const pushHistory = (snap) => {
      const point = {
        t: snap['generated-at'],
        legacyDepth: snap['legacy-queue-depth'] ?? 0,
        dlqDepth: snap['dlq-depth'] ?? 0,
      }
      const next = [...historyRef.current, point].slice(-180)
      historyRef.current = next
      setHistory(next)
    }

    const startPolling = () => {
      if (pollTimer) return
      pollTimer = setInterval(async () => {
        try {
          const snap = await api.snapshot()
          if (snap && snap['generated-at']) {
            setSnapshot(snap)
            pushHistory(snap)
          }
        } catch {
          // control-plane unreachable; keep retrying silently
        }
      }, 1500)
    }

    const stopPolling = () => {
      if (pollTimer) clearInterval(pollTimer)
      pollTimer = null
    }

    const connect = () => {
      try {
        ws = new WebSocket(wsUrl())
      } catch {
        startPolling()
        return
      }
      ws.onopen = () => {
        setConnected(true)
        stopPolling()
      }
      ws.onmessage = (evt) => {
        try {
          const msg = JSON.parse(evt.data)
          if (msg.type === 'snapshot') {
            setSnapshot(msg.data)
            pushHistory(msg.data)
          } else {
            setLastEvent(msg)
          }
        } catch {
          /* ignore malformed frame */
        }
      }
      ws.onclose = () => {
        setConnected(false)
        if (!closedByUs) {
          startPolling()
          setTimeout(connect, 3000)
        }
      }
      ws.onerror = () => ws.close()
    }

    connect()
    return () => {
      closedByUs = true
      stopPolling()
      ws?.close()
    }
  }, [])

  return { snapshot, connected, lastEvent, history }
}

export function usePolling(fn, intervalMs, deps = []) {
  const [data, setData] = useState(null)
  useEffect(() => {
    let cancelled = false
    let timer
    const tick = async () => {
      try {
        const result = await fn()
        if (!cancelled) setData(result)
      } catch {
        /* keep last good data on transient failure */
      }
      timer = setTimeout(tick, intervalMs)
    }
    tick()
    return () => {
      cancelled = true
      clearTimeout(timer)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps)
  return data
}
