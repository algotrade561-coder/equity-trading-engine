#!/bin/bash
# Keep ten days of logs on the equity box and nothing older.
#
# Three places hold logs and each is bounded separately:
#   1. /opt/equity/logs — Logback rolls equity.log daily and keeps 10 (application.yml). This
#      script is the backstop for anything Logback does not own: a renamed pattern, a file left
#      by a crashed process, a copy someone took to inspect.
#   2. The systemd journal, which holds the same lines the console printed. Capped in
#      /etc/systemd/journald.conf.d/equity.conf; vacuumed here too so the two never disagree.
#   3. SSM agent logs, which grow with every remote command.
#
# Not touched: /opt/equity/data. The database, the decision journal and stored candles are trade
# evidence with their own retention (equity.candles.retention-days), not logs.
set -euo pipefail
DAYS=10
LOGDIR=/opt/equity/logs

before=$(du -sh "$LOGDIR" 2>/dev/null | cut -f1)
# Rolled files only — never the live equity.log, whatever its age looks like.
find "$LOGDIR" -type f -name 'equity.log.*' -mtime +"$DAYS" -print -delete
find "$LOGDIR" -type f -name '*.log' ! -name 'equity.log' -mtime +"$DAYS" -print -delete
journalctl --vacuum-time="${DAYS}d" --quiet
find /var/log/amazon/ssm -type f -name '*.log.*' -mtime +"$DAYS" -delete 2>/dev/null || true
after=$(du -sh "$LOGDIR" 2>/dev/null | cut -f1)
echo "log retention: ${DAYS} days kept; $LOGDIR $before -> $after"
