import { useEffect, useState } from 'react'
import { api } from '../lib/api'
import { istTime } from '../lib/format'
import { Pill } from '../components/Pill'

/**
 * Users, and whether each one can trade.
 *
 * The screen leads with one line per user — "needs an address", "register 13.x.x.x in Kite",
 * "trading" — because an administrator's real question is never "what is this user's row" but
 * "why can this person not trade yet". Everything else on the row is the evidence for that line.
 *
 * Adding a user here is what admits them: sign-in checks the email against this list. They arrive
 * unable to trade in every sense — no credentials, no address, entries disarmed — and gain the
 * ability one step at a time, each step visible.
 */

interface Address {
  sourceIp: string | null
  status: string
  privateIp: string | null
  publicIp: string | null
  automated: boolean
  whitelistedWithBroker: boolean
  lastError: string | null
  updatedAt: string | null
  updatedBy: string | null
}

interface UserRow {
  tradingUserId: string
  email: string
  displayName: string
  role: string
  enabled: boolean
  createdAt: string | null
  lastLoginAt: string | null
  brokerCredentialsSet: boolean
  brokerSessionDate: string | null
  entriesArmed: boolean
  address: Address
  readiness: string
  hasExposure: boolean
}

interface UsersResponse {
  users: UserRow[]
  provisioningEnabled: boolean
  capacity: { allocated: number; quota: number; remaining: number; elasticIpsInUse: number; interfaceSlots: number }
}

export function Users({ meId }: { meId?: string }) {
  const [data, setData] = useState<UsersResponse | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState<string | null>(null)   // tradingUserId being acted on

  async function load() {
    try { setData(await api.get<UsersResponse>('/api/admin/users')); setError(null) }
    catch (e) { setError(e instanceof Error ? e.message : String(e)) }
  }
  useEffect(() => { load() }, [])

  async function act(id: string, run: () => Promise<unknown>) {
    setBusy(id); setError(null)
    try { await run(); await load() }
    catch (e) { setError(e instanceof Error ? e.message : String(e)) }
    finally { setBusy(null) }
  }

  if (error && !data) return <div className="card bad"><div className="error">{error}</div></div>
  if (!data) return <div className="muted">Loading…</div>

  return (
    <>
      {error && <div className="card bad"><div className="error">{error}</div></div>}

      <CapacityCard data={data} />
      <CreateUserCard onCreated={load} />

      <div className="card">
        <h3>Users</h3>
        <div className="scroll">
        <table>
          <thead>
            <tr>
              <th>User</th><th>Readiness</th><th>Address</th><th>In Kite</th><th></th>
            </tr>
          </thead>
          <tbody>
            {data.users.map(u => (
              <UserLine key={u.tradingUserId} user={u} isMe={u.tradingUserId === meId}
                        busy={busy === u.tradingUserId} provisioning={data.provisioningEnabled}
                        act={run => act(u.tradingUserId, run)} />
            ))}
          </tbody>
        </table>
        </div>
      </div>
    </>
  )
}

// ── Capacity ─────────────────────────────────────────────────────────────────

function CapacityCard({ data }: { data: UsersResponse }) {
  const { allocated, quota, remaining, elasticIpsInUse, interfaceSlots } = data.capacity
  const tone = remaining === 0 ? 'bad' : remaining === 1 ? 'warn' : 'good'
  const bound = interfaceSlots - allocated <= quota - elasticIpsInUse ? 'the instance type' : 'the Elastic IP quota'
  return (
    <div className="card">
      <h3>Addresses</h3>
      <dl className="facts">
        <dt>Provisioning</dt>
        <dd>
          {data.provisioningEnabled
            ? <Pill tone="warn">LIVE — allocates Elastic IPs, which are billed</Pill>
            : <Pill>off — addresses are recorded, not created</Pill>}
        </dd>
        <dt>User addresses</dt>
        <dd><Pill tone={tone}>{allocated} assigned · {remaining} remaining</Pill></dd>
        <dt>Elastic IPs</dt>
        <dd>{elasticIpsInUse} of {quota} in the account (the instance's own is one of them)</dd>
        <dt>Interface</dt>
        <dd>{allocated} of {interfaceSlots} user slots on this instance type</dd>
      </dl>
      <p className="note">
        Each user's broker API key is registered to one public IP (SEBI static-IP rule). A user needs
        their own Elastic IP, and their traffic leaves from the private address it maps to. Two limits
        apply and the smaller wins — right now that is {bound}. The Elastic IP quota is raised by AWS
        support ticket; the interface limit only by a bigger instance. Registering the public IP in
        the Kite developer console has no API — it is the last step, done by hand, and the user cannot
        trade until it is ticked here.
      </p>
    </div>
  )
}

// ── Create ───────────────────────────────────────────────────────────────────

function CreateUserCard({ onCreated }: { onCreated: () => void }) {
  const [email, setEmail] = useState('')
  const [name, setName] = useState('')
  const [role, setRole] = useState<'TRADER' | 'ADMIN'>('TRADER')
  const [busy, setBusy] = useState(false)
  const [message, setMessage] = useState<{ text: string; bad: boolean } | null>(null)

  async function create() {
    setBusy(true); setMessage(null)
    try {
      await api.post('/api/admin/users', { email, displayName: name, role })
      setMessage({ text: `${email} added. They can sign in now; they cannot trade until they have credentials, an address, and it is registered in Kite.`, bad: false })
      setEmail(''); setName(''); setRole('TRADER')
      onCreated()
    } catch (e) {
      setMessage({ text: e instanceof Error ? e.message : String(e), bad: true })
    } finally { setBusy(false) }
  }

  return (
    <div className="card">
      <h3>Add a user</h3>
      <p className="hint">
        The email is the allow-list entry — sign-in with any other Google account is refused.
      </p>
      <div className="grid two">
        <div>
          <label htmlFor="newEmail">Google email</label>
          <input id="newEmail" type="email" value={email} spellCheck={false}
                 placeholder="name@gmail.com" onChange={e => setEmail(e.target.value)} />
        </div>
        <div>
          <label htmlFor="newName">Display name</label>
          <input id="newName" type="text" value={name}
                 placeholder="optional" onChange={e => setName(e.target.value)} />
        </div>
      </div>
      <div className="row" style={{ marginTop: '.9rem', alignItems: 'center' }}>
        <label style={{ margin: 0 }}>
          <input type="checkbox" checked={role === 'ADMIN'}
                 onChange={e => setRole(e.target.checked ? 'ADMIN' : 'TRADER')} />
          {' '}Administrator — can add users and provision addresses
        </label>
        <span className="spacer" />
        <button className="primary" disabled={busy || !email.includes('@')} onClick={create}>
          Add user
        </button>
      </div>
      {message && <div className={`note ${message.bad ? 'error' : ''}`} style={{ marginTop: '.6rem' }}>{message.text}</div>}
    </div>
  )
}

// ── One row ──────────────────────────────────────────────────────────────────

function UserLine({ user, isMe, busy, provisioning, act }: {
  user: UserRow; isMe: boolean; busy: boolean; provisioning: boolean
  act: (run: () => Promise<unknown>) => void
}) {
  const a = user.address
  const id = user.tradingUserId
  const hasAddress = a.status === 'ACTIVE'

  return (
    <tr style={user.enabled ? undefined : { opacity: .55 }}>
      <td style={{ verticalAlign: 'top', whiteSpace: 'nowrap' }}>
        <div>
          {user.displayName || user.email}
          {user.role === 'ADMIN' && <>{' '}<Pill tone="warn">ADMIN</Pill></>}
        </div>
        <div className="muted" style={{ fontSize: '.78em', lineHeight: 1.5 }}>
          {user.email}{isMe ? ' · you' : ''}<br />
          {user.brokerCredentialsSet
            ? <span title={user.brokerSessionDate ? `Kite session for ${user.brokerSessionDate}` : 'no Kite session today'}>
                key ✓{user.brokerSessionDate ? ' · logged in' : ''}
              </span>
            : 'no broker key'}
          {' · '}last login {user.lastLoginAt ? istTime(user.lastLoginAt) : 'never'}
        </div>
        <div className="actions" style={{ marginTop: '.35rem' }}>
          <button className="small" disabled={busy || isMe}
                  title={isMe ? 'You cannot disable yourself' : undefined}
                  onClick={() => act(() => api.post(`/api/admin/users/${id}/enabled`, { enabled: !user.enabled }))}>
            {user.enabled ? 'Disable' : 'Enable'}
          </button>
          <button className="small" disabled={busy || isMe}
                  title={isMe ? 'You cannot change your own role' : undefined}
                  onClick={() => act(() => api.post(`/api/admin/users/${id}/role`, { role: user.role === 'ADMIN' ? 'TRADER' : 'ADMIN' }))}>
            {user.role === 'ADMIN' ? 'Make trader' : 'Make admin'}
          </button>
          <button className="small danger" disabled={busy || isMe || user.hasExposure || hasAddress}
                  title={isMe ? 'You cannot delete yourself'
                    : user.hasExposure ? 'Close their open position first'
                    : hasAddress ? 'Release their address first'
                    : 'Remove the account. Trading records are kept under the id.'}
                  onClick={() => { if (confirm(`Delete ${user.email}?

Their login, settings and broker credentials are removed. Positions and ledger history stay on record under their id.`))
                    act(() => api.del(`/api/admin/users/${id}`)) }}>
            Delete
          </button>
        </div>
      </td>
      <td style={{ verticalAlign: 'top', whiteSpace: 'nowrap' }}><Readiness text={user.readiness} /></td>
      <td style={{ verticalAlign: 'top', whiteSpace: 'nowrap' }}><AddressCell a={a} /></td>
      <td style={{ verticalAlign: 'top', whiteSpace: 'nowrap' }}>
        {hasAddress
          ? <label style={{ display: 'inline', fontSize: 'inherit', color: 'inherit' }}
                   title="Tick once the public IP is registered against this user's API key at developers.kite.trade">
              <input type="checkbox" checked={a.whitelistedWithBroker} disabled={busy}
                     onChange={e => act(() => api.post(`/api/admin/users/${id}/address/whitelisted`, { whitelisted: e.target.checked }))} />
              {' '}{a.whitelistedWithBroker ? 'yes' : 'not yet'}
            </label>
          : <span className="muted">—</span>}
      </td>
      <td style={{ verticalAlign: 'top', whiteSpace: 'nowrap' }}>
        <RowActions user={user} busy={busy} provisioning={provisioning} act={act} />
      </td>
    </tr>
  )
}

function Readiness({ text }: { text: string }) {
  const tone = text === 'trading' ? 'bad'                       // money can move — the state to notice
    : text.startsWith('ready') ? 'good'
    : text === 'disabled' || text.includes('failed') ? 'bad'
    : 'warn'
  return <Pill tone={tone}>{text}</Pill>
}

function AddressCell({ a }: { a: Address }) {
  if (a.status === 'NONE') return <span className="muted">none</span>
  return (
    <div style={{ fontSize: '.85em', lineHeight: 1.4 }}>
      <div>
        <Pill tone={a.status === 'ACTIVE' ? 'good' : a.status === 'FAILED' ? 'bad' : 'warn'}>
          {a.status}{a.automated ? '' : ' · manual'}
        </Pill>
      </div>
      {a.publicIp && <div>public <code>{a.publicIp}</code></div>}
      {a.privateIp && <div className="muted">private <code>{a.privateIp}</code></div>}
      {a.lastError && <div className="error" title={a.lastError}>{a.lastError.slice(0, 80)}…</div>}
    </div>
  )
}

function RowActions({ user, busy, provisioning, act }: {
  user: UserRow; busy: boolean; provisioning: boolean
  act: (run: () => Promise<unknown>) => void
}) {
  const id = user.tradingUserId
  const a = user.address
  const [manual, setManual] = useState(false)
  const [privateIp, setPrivateIp] = useState('')
  const [publicIp, setPublicIp] = useState('')

  const hasAddress = a.status !== 'NONE' && a.status !== 'RELEASED'

  return (
    <div className="actions">
      {!hasAddress && provisioning && (
        <button className="small" disabled={busy} title="Assign a private IP, allocate an Elastic IP, associate, bind"
                onClick={() => { if (confirm(`Allocate an Elastic IP for ${user.email}? This is billed from the moment it exists.`))
                  act(() => api.post(`/api/admin/users/${id}/address/provision`)) }}>
          Provision
        </button>
      )}
      {!hasAddress && !manual && (
        <button className="small" disabled={busy} onClick={() => setManual(true)} title="Record an address that already exists">
          Record address
        </button>
      )}
      {!hasAddress && manual && (
        <>
          <input type="text" placeholder="private IP" value={privateIp} spellCheck={false}
                 onChange={e => setPrivateIp(e.target.value)} />
          <input type="text" placeholder="public IP" value={publicIp} spellCheck={false}
                 onChange={e => setPublicIp(e.target.value)} />
          <button className="small primary" disabled={busy || !privateIp}
                  onClick={() => act(async () => {
                    await api.post(`/api/admin/users/${id}/address/manual`, { privateIp, publicIp })
                    setManual(false); setPrivateIp(''); setPublicIp('')
                  })}>Save</button>
          <button className="small" disabled={busy} onClick={() => setManual(false)}>Cancel</button>
        </>
      )}
      {hasAddress && a.status === 'FAILED' && provisioning && (
        <button className="small" disabled={busy} onClick={() => act(() => api.post(`/api/admin/users/${id}/address/provision`))}>
          Retry
        </button>
      )}
      {hasAddress && (
        <button className="small danger" disabled={busy}
                onClick={() => { if (confirm(a.automated
                    ? `Release ${user.email}'s Elastic IP? Their broker key will need re-registering if they are given a new one.`
                    : `Unbind ${user.email} from ${a.privateIp}? The address itself is left in place.`))
                  act(() => api.del(`/api/admin/users/${id}/address`)) }}>
          {a.automated ? 'Release' : 'Unbind'}
        </button>
      )}
    </div>
  )
}
