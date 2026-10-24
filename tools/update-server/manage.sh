#!/usr/bin/env bash
# PocketKit 更新服务（机器 01）运维脚本。不保存任何凭证：
# 管理口令只存在服务器端 /data/pocketkit-hub/admin.token（0600）。
set -euo pipefail

HUB_HOST="${HUB_HOST:-ibssh_lcg7ogpkswgbukqmp2qzb7woc4@api.instabox.sh}"
HUB_PORT="${HUB_PORT:-2222}"
REMOTE_DIR=/data/pocketkit-hub
BASE_URL="${BASE_URL:-https://9876-agqvov42zisdyuvnzmi23lrrw4.instabox.tech}"
SRC_DIR="$(cd "$(dirname "$0")" && pwd)"

ssh_hub() { ssh -p "$HUB_PORT" -o BatchMode=yes "$HUB_HOST" "$@"; }

usage() {
  cat <<'EOF'
用法: manage.sh <install|rotate-token|status|verify|token-path>

  install       上传并安装 server.py，校验语法，重启 pocketkit-hub（保留现有口令）
  rotate-token  生成新的随机管理口令（0600，仅服务器本地），旧口令立即失效
  status        显示服务状态、自启、发布目录清单（不含口令）
  verify        匿名/错误/正确凭据的端到端验证
  token-path    打印口令文件路径（不打印口令）
EOF
}

case "${1:-}" in
  install)
    scp -P "$HUB_PORT" -o BatchMode=yes "$SRC_DIR/server.py" "$HUB_HOST:/tmp/pocketkit-hub-new.py"
    ssh_hub 'sudo bash -s' <<'EOS'
set -e
install -o user -g user -m 0644 /tmp/pocketkit-hub-new.py /data/pocketkit-hub/server.py
rm -f /tmp/pocketkit-hub-new.py
# 口令缺失时补建（不会覆盖已有口令）
if [ ! -s /data/pocketkit-hub/admin.token ]; then
  umask 077
  printf 'admin:%s\n' "$(openssl rand -base64 32 | tr -d '\n')" > /data/pocketkit-hub/admin.token
  chown user:user /data/pocketkit-hub/admin.token
fi
chmod 600 /data/pocketkit-hub/admin.token
python3 -m py_compile /data/pocketkit-hub/server.py
systemctl enable pocketkit-hub >/dev/null
systemctl restart pocketkit-hub
sleep 1
systemctl is-active pocketkit-hub
EOS
    ;;
  rotate-token)
    ssh_hub 'sudo bash -s' <<'EOS'
set -e
umask 077
printf 'admin:%s\n' "$(openssl rand -base64 32 | tr -d '\n')" > /data/pocketkit-hub/admin.token.new
chown user:user /data/pocketkit-hub/admin.token.new
chmod 600 /data/pocketkit-hub/admin.token.new
mv /data/pocketkit-hub/admin.token.new /data/pocketkit-hub/admin.token
# 每次请求读取口令文件，无需重启
systemctl is-active pocketkit-hub
EOS
    ;;
  status)
    ssh_hub 'systemctl is-enabled pocketkit-hub; systemctl is-active pocketkit-hub; ss -lntp 2>/dev/null | grep 9876 || true; ls -la /data/pocketkit-hub/; ls -la /data/pocketkit-hub/releases/' \
      | sed "s#$HUB_HOST#<hub>#"
    ;;
  verify)
    bash "$SRC_DIR/verify.sh"
    ;;
  token-path)
    echo "$REMOTE_DIR/admin.token"
    ;;
  *)
    usage; exit 2;;
esac
