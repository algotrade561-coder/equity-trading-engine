import { useEffect, useState } from 'react'
import { NavLink, Navigate, Route, Routes } from 'react-router-dom'
import { api, ApiError, type Me, type Status } from './lib/api'
import { StatusPills } from './components/StatusPills'
import { Dashboard } from './pages/Dashboard'
import { Positions } from './pages/Positions'
import { Rejections } from './pages/Rejections'
import { Universe } from './pages/Universe'
import { Settings } from './pages/Settings'
import { Users } from './pages/Users'

/**
 * The shell: sign-in gate, side navigation, and the one poll everything reads from.
 *
 * Status is fetched here rather than in each page so the header, the nav and whichever page is open
 * all describe the same instant. Independent polls would let the header say the market is open while
 * the page below it renders a closed one.
 */

const NAV = [
  { to: '/',           label: 'Dashboard',  icon: '◴' },
  { to: '/positions',  label: 'Positions',  icon: '◧' },
  { to: '/rejections', label: 'Rejections', icon: '⊘' },
  { to: '/universe',   label: 'Universe',   icon: '◎' },
  { to: '/settings',   label: 'Settings',   icon: '⚙' },
  { to: '/users',      label: 'Users',      icon: '⚇', adminOnly: true },
]

export function App() {
  const [me, setMe] = useState<Me | null>(null)
  const [status, setStatus] = useState<Status | null>(null)
  const [loadError, setLoadError] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false

    async function poll() {
      try {
        const identity = await api.get<Me>('/api/auth/me')
        if (cancelled) return
        setMe(identity)

        if (identity.authenticated) {
          setStatus(await api.get<Status>('/api/status'))
          setLoadError(null)
        }
      } catch (e) {
        if (cancelled) return
        // A 401 is not an error to display — it means "show the sign-in screen", which the render
        // below already does from `me`.
        if (e instanceof ApiError && e.isUnauthenticated) setMe({ authEnabled: true, authenticated: false })
        else setLoadError(e instanceof Error ? e.message : String(e))
      }
    }

    poll()
    const timer = setInterval(poll, 5000)
    return () => { cancelled = true; clearInterval(timer) }
  }, [])

  if (!me) return <div className="center-screen muted">Loading…</div>
  if (!me.authenticated) return <SignIn me={me} />

  return (
    <div className="shell">
      <nav className="nav">
        <div className="nav-brand">
          {/* Two-tone rather than one weight: at 1.05rem a single-colour compound word reads as
              one long token, and the eye has nothing to land on. */}
          <h1><span className="mark">Equity</span>Engine</h1>
          <div className="sub">NSE Intraday Momentum</div>
        </div>

        <div className="nav-links">
          {NAV.filter(item => !item.adminOnly || me.admin).map(item => (
            <NavLink
              key={item.to}
              to={item.to}
              end={item.to === '/'}
              className={({ isActive }) => `nav-link${isActive ? ' active' : ''}`}
            >
              <span className="icon">{item.icon}</span>
              {item.label}
            </NavLink>
          ))}
        </div>

        <div className="nav-foot">
          <div className="who">{me.email ?? 'local'}</div>
          <div>{me.role}{me.admin ? ' · admin' : ''}</div>
          {me.authEnabled && (
            <button
              style={{ marginTop: '.6rem', width: '100%' }}
              onClick={async () => { await api.post('/api/auth/logout'); location.reload() }}
            >
              Sign out
            </button>
          )}
        </div>
      </nav>

      <div className="main">
        <header className="topbar">
          <h2>{NAV.find(n => n.to === location.pathname)?.label ?? 'Dashboard'}</h2>
          <span className="spacer" />
          <StatusPills status={status} />
        </header>

        <div className="content">
          {loadError && <div className="card bad"><div className="error">{loadError}</div></div>}
          <Routes>
            <Route path="/"           element={<Dashboard status={status} />} />
            <Route path="/positions"  element={<Positions />} />
            <Route path="/rejections" element={<Rejections />} />
            <Route path="/universe"   element={<Universe />} />
            <Route path="/settings"   element={<Settings />} />
            {me.admin && <Route path="/users" element={<Users meId={me.tradingUserId} />} />}
            <Route path="*"           element={<Navigate to="/" replace />} />
          </Routes>
        </div>
      </div>
    </div>
  )
}

/**
 * The sign-in screen.
 *
 * It says plainly that being in Google is not enough — the allow-list is the actual gate, and
 * somebody refused should understand they need to be added rather than that sign-in is broken.
 */
function SignIn({ me }: { me: Me }) {
  return (
    <div className="center-screen">
      <div className="card" style={{ maxWidth: '26rem' }}>
        <h3><span className="mark">Equity</span>Engine</h3>
        <p className="hint">NSE intraday momentum · live trading console</p>
        <a href={me.loginUrl ?? '/oauth2/authorization/google'}>
          <button className="primary" style={{ width: '100%' }}>Sign in with Google</button>
        </a>
        <p className="note">
          A Google account alone is not access. Your address has to be on this engine's user list —
          if sign-in is refused, ask an admin to add it rather than trying again.
        </p>
      </div>
    </div>
  )
}
