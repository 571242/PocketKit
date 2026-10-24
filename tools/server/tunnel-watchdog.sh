#!/usr/bin/env bash
# Health gate for the InstaBox-01 quick tunnel.
#
# tunnel-manager.py restarts cloudflared on its own, but it can only see its
# own stdout. This timer-level check catches the cases it cannot: the unit
# wedged, the python supervisor dead, or a hostname that resolves yet serves
# nothing. Restarting is safe because render() rewrites the subscription with
# whatever hostname cloudflared is assigned next.
set -uo pipefail

UNIT=tunnel-sub.service
DOMAIN_FILE=/var/www/html/domain.txt
SUB_FILE=/var/www/html/sub.yaml
LOG_TAG=instabox-tunnel-watchdog

log() { logger -t "$LOG_TAG" -- "$*"; echo "$*"; }

if ! systemctl is-active --quiet "$UNIT"; then
    log "unit not active; restarting"
    systemctl restart "$UNIT"
    exit 0
fi

if [ ! -s "$DOMAIN_FILE" ]; then
    log "no hostname recorded; restarting"
    systemctl restart "$UNIT"
    exit 0
fi

domain=$(tr -d '[:space:]' < "$DOMAIN_FILE")

# The subscription must be regenerated for the hostname currently in use.
if ! grep -q "$domain" "$SUB_FILE" 2>/dev/null; then
    log "subscription stale for $domain; restarting"
    systemctl restart "$UNIT"
    exit 0
fi

code=$(curl -sS -o /dev/null -m 25 -w '%{http_code}' "https://$domain/sub" 2>/dev/null || echo 000)
if [ "$code" != "200" ]; then
    log "https://$domain/sub returned $code; restarting"
    systemctl restart "$UNIT"
    exit 0
fi

log "healthy: $domain"
