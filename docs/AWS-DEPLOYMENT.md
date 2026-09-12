# Deploying to AWS

How to run this engine on one EC2 instance for one or more users, with each user's broker traffic
leaving from an address of their own.

## Why the network is the hard part

SEBI's static-IP rule for retail algorithmic trading registers every broker API key to **one**
public IP, and Kite refuses orders arriving from anywhere else. One user on one machine satisfies
that by accident. Several users do not: each holds a key registered to a different address, and yet
every call would leave the box from its single default interface — so at most one of them could
ever trade.

The answer is to give the instance several addresses and have each user's traffic leave from theirs:

```
user A  ─►  private 10.0.1.4  ─►  Elastic IP 13.200.x.1  ─►  Kite   (key A registered to 13.200.x.1)
user B  ─►  private 10.0.1.5  ─►  Elastic IP 13.200.x.2  ─►  Kite   (key B registered to 13.200.x.2)
```

The engine binds each user's sockets to their private address (`BoundSocketFactory`, chosen per
call from `KiteCredentials.sourceIp`) and can create the addresses itself (`IpAllocationService`).
Registering the public IP against the key in the Kite developer console has no API and is always
done by hand — the users screen shows it as the last step and holds the user as "not ready" until
an operator ticks it.

**Every user must also be on the Google allow-list.** Set `GOOGLE_AUTH_ENABLED=true` on the instance
— with it off every endpoint is open to anyone who can reach the port, which is acceptable on a
laptop and nowhere else.

---

## 1. One-time AWS setup

Do this once per instance. Nothing here stores a credential anywhere: the engine takes temporary
ones from the instance's IAM role via IMDSv2.

### 1.1 IAM policy — eight EC2 actions and nothing else

Save as `equity-provisioning-policy.json`:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "ReadNetworkState",
      "Effect": "Allow",
      "Action": ["ec2:DescribeNetworkInterfaces", "ec2:DescribeAddresses"],
      "Resource": "*"
    },
    {
      "Sid": "ManagePerUserAddresses",
      "Effect": "Allow",
      "Action": [
        "ec2:AssignPrivateIpAddresses", "ec2:UnassignPrivateIpAddresses",
        "ec2:AllocateAddress",          "ec2:ReleaseAddress",
        "ec2:AssociateAddress",         "ec2:DisassociateAddress"
      ],
      "Resource": "*"
    }
  ]
}
```

`Describe*` cannot be resource-scoped. `AllocateAddress` cannot be tag-scoped on create. So the
blast radius is controlled by attaching this policy **only** to the trading instance's role — a
role that can allocate an address must not also be able to terminate the instance.

```bash
aws iam create-policy --policy-name EquityProvisioning \
  --policy-document file://equity-provisioning-policy.json
```

### 1.2 Role and instance profile

If the instance already has a role, attach the policy to it:

```bash
aws iam attach-role-policy --role-name <existing-role> \
  --policy-arn arn:aws:iam::<account-id>:policy/EquityProvisioning
```

Otherwise create one and attach it to the instance:

```bash
cat > trust.json <<'JSON'
{"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"Service":"ec2.amazonaws.com"},"Action":"sts:AssumeRole"}]}
JSON
aws iam create-role --role-name EquityInstanceRole --assume-role-policy-document file://trust.json
aws iam attach-role-policy --role-name EquityInstanceRole \
  --policy-arn arn:aws:iam::<account-id>:policy/EquityProvisioning
aws iam create-instance-profile --instance-profile-name EquityInstanceProfile
aws iam add-role-to-instance-profile --instance-profile-name EquityInstanceProfile --role-name EquityInstanceRole
aws ec2 associate-iam-instance-profile --instance-id <instance-id> \
  --iam-instance-profile Name=EquityInstanceProfile --region ap-south-1
```

### 1.3 Require IMDSv2

The token-less v1 metadata service is the one every SSRF write-up uses. Turn it off:

```bash
aws ec2 modify-instance-metadata-options --instance-id <instance-id> \
  --http-tokens required --http-endpoint enabled --region ap-south-1
```

Verify from the box that the role is visible:

```bash
TOKEN=$(curl -s -X PUT http://169.254.169.254/latest/api/token -H "X-aws-ec2-metadata-token-ttl-seconds: 60")
curl -s -H "X-aws-ec2-metadata-token: $TOKEN" http://169.254.169.254/latest/meta-data/iam/security-credentials/
# prints: EquityInstanceRole
```

### 1.4 Elastic IP quota

The default is **5 per region**, and the instance's own address is one of them. Check, and raise
before it runs out — the users screen shows remaining capacity, and provisioning fails cleanly with
`AddressLimitExceeded` when it is gone, but a support ticket takes a day or two.

```bash
aws service-quotas get-service-quota --service-code ec2 --quota-code L-0263D0A3 --region ap-south-1
aws service-quotas request-service-quota-increase --service-code ec2 --quota-code L-0263D0A3 \
  --desired-value 10 --region ap-south-1
```

Set `elastic-ip-quota` in the configuration to match.

### 1.5 Addresses per network interface

The instance type also limits how many private IPs one interface can hold. A `t3.medium` holds 6
(one primary, five secondary); a `t3.large` holds 12. That is the real ceiling on users per
instance without a second interface.

### 1.6 sudoers — the OS step

AWS assigning an address to the interface is not enough; the guest OS has to be told with
`ip addr add`, or every `bind()` to it fails with `EADDRNOTAVAIL`. The engine runs that under
`sudo`, and the service user needs exactly that and nothing more. Via `visudo`:

```
equity ALL=(root) NOPASSWD: /usr/sbin/ip addr add *, /usr/sbin/ip addr del *
```

Confirm the interface name — `ens5` on Amazon Linux 2023, `eth0` on Amazon Linux 2 — and set
`network-interface` to match.

### 1.7 Record what is already there

Before turning the automation on, note every address that exists today. The automation adopts
addresses it did not create as read-only and never releases them, but the list is what you check
against if anything looks wrong later.

```bash
aws ec2 describe-network-interfaces --network-interface-ids <eni-id> --region ap-south-1 \
  --query 'NetworkInterfaces[0].PrivateIpAddresses[*].{private:PrivateIpAddress,primary:Primary,public:Association.PublicIp}'
aws ec2 describe-addresses --region ap-south-1 \
  --query 'Addresses[*].{public:PublicIp,private:PrivateIpAddress,alloc:AllocationId,assoc:AssociationId}'
```

---

## 2. The service

### 2.1 Environment

Everything secret comes from the environment, never from a file in the repository:

```bash
# /etc/equity/environment  (mode 0600, owner equity)
GOOGLE_AUTH_ENABLED=true
GOOGLE_CLIENT_ID=...
GOOGLE_CLIENT_SECRET=...
EQUITY_SEED_EMAIL=you@example.com          # created as ADMIN on first start; the allow-list's first entry
KITE_API_KEY=...                            # the shared app, if users do not bring their own
KITE_API_SECRET=...
EQUITY_SECRET_KEY=...                       # encrypts stored broker credentials; unset means they are refused, not stored plain
EQUITY_AUTH_PUBLIC_BASE_URL=https://<host>  # what Google redirects back to; must match the OAuth client exactly
KITE_REDIRECT_URL=https://<host>/api/broker/kite/callback   # must match the Kite app, character for character

PROVISIONING_ENABLED=true                   # ONLY on the production instance
PROVISIONING_NIC=ens5
```

Leave `PROVISIONING_INSTANCE_ID`, `PROVISIONING_ENI_ID`, `PROVISIONING_SUBNET_CIDR`,
`PROVISIONING_REGION` and `PROVISIONING_PRIMARY_IP` unset on the instance — they are read from IMDS.
They exist to run the provisioning code off-box.

### 2.2 systemd unit

```ini
# /etc/systemd/system/equity.service
[Unit]
Description=Equity trading engine
After=network-online.target
Wants=network-online.target

[Service]
User=equity
Group=equity
WorkingDirectory=/opt/equity
EnvironmentFile=/etc/equity/environment
ExecStart=/usr/bin/java -Xms512m -Xmx1g -jar /opt/equity/equity.jar
Restart=on-failure
RestartSec=10
# The engine writes data/, logs/ and the H2 file under its working directory.
ReadWritePaths=/opt/equity
NoNewPrivileges=false   # sudo for `ip addr` needs this off; everything else is locked down by sudoers
ProtectSystem=strict
ProtectHome=true
PrivateTmp=true

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now equity
sudo journalctl -u equity -f
```

### 2.3 Build and ship

```bash
mvn -q package -DskipTests           # builds the console into the jar; one artefact
scp target/equity-*.jar equity@<host>:/opt/equity/equity.jar
ssh equity@<host> sudo systemctl restart equity
```

### 2.4 After every restart

Entries are **disarmed** on startup by design — a process that comes back up must not start trading
on its own. Arm each user from the dashboard. The order-tag sequence resumes from the database, so
a restart mid-session is safe.

---

### 2.5 Log retention — ten days

Three places hold logs, each bounded on its own:

| Where | What bounds it |
|---|---|
| `/opt/equity/logs/equity.log` | Logback: rolled daily, `max-history: 10`, `total-size-cap: 500MB`, pruned on start-up (`application.yml`) |
| systemd journal (`journalctl -u equity`) | `/etc/systemd/journald.conf.d/equity.conf`: `MaxRetentionSec=10day`, `SystemMaxUse=300M` |
| Anything Logback does not own | `equity-log-retention.timer`, daily 09:05 IST, runs `/usr/local/sbin/equity-log-retention.sh` |

The files are in `deploy/`. `/opt/equity/data` is deliberately not touched: the database, the
decision journal and stored candles are trade evidence with their own retention
(`equity.candles.retention-days`), not logs.

```bash
systemctl list-timers equity-log-retention.timer     # next run
journalctl -u equity-log-retention -n 3              # what the last run deleted
```

## 3. Adding a user

From the **Users** screen, as an administrator:

1. **Add user** with their Google email. That is the allow-list entry; they can sign in now.
2. They (or you, on Settings) enter their Kite API key and secret.
3. **Provision** — the engine assigns a private IP, adds it to the OS, allocates an Elastic IP,
   associates them, and binds the user. Roughly ten seconds. Or **Record address** if the address
   already exists.
4. The row now shows the **public IP**. Register it against the user's API key at
   `developers.kite.trade` → the app → *Allowed IPs*. There is no API for this.
5. Tick **In Kite**. The row moves from "register 13.x.x.x in Kite" to "needs today's Kite login".
6. The user logs in to Kite through the dashboard, then arms entries.

The readiness column says which step is next at every point.

### Verifying the egress

From the instance, as the service user, confirm a call bound to the user's private address leaves
from their public one:

```bash
curl --interface 10.0.1.4 -s https://checkip.amazonaws.com     # prints the Elastic IP
```

If it prints the instance's primary public IP instead, the OS step did not take — check
`ip addr show ens5` and the sudoers entry.

---

## 4. Removing a user

**Disable** on the Users screen. That removes them from the allow-list and disarms them; open
positions are still managed to close. **Release** their address afterwards — for an automated
address that frees the Elastic IP (and stops billing for it); for a recorded one it only unbinds.

Users are never deleted. Their positions, ledger and audit rows are history a regulator can ask for.

---

## 5. Troubleshooting

| symptom | cause | fix |
|---|---|---|
| `BindException: Cannot assign requested address` on a user's calls | private IP not on the OS interface | `sudo ip addr add 10.0.1.4/24 dev ens5`; check sudoers |
| Kite returns `TokenException` / `InputException: Invalid IP` for one user | public IP not registered against that key | register it in the developer console; tick "In Kite" |
| provisioning fails `AddressLimitExceeded` | Elastic IP quota reached | §1.4; compensation already released what it created |
| provisioning fails `UnauthorizedOperation` | role missing an action, or instance profile not attached | §1.1–1.2; verify with §1.3 |
| provisioning fails `Address does not fall within the subnet` | stale private IP from another subnet on the row | Release and Provision again; the picker rejects out-of-subnet addresses |
| `instance metadata unavailable` at startup | IMDSv2 unreachable, or not on EC2 | expected off-box; on EC2 check §1.3 |
| an Elastic IP on the bill that no user shows | orphan from a failed compensation | `describe-addresses` (§1.7); release by hand if unassociated and not service-managed |

The engine's own log names every step with the AWS identifiers it received, which is what you
need to finish anything by hand.
