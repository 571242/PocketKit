#!/usr/bin/env bash
# 用新鉴权发布 APK / 更新版本元数据。
# 口令只在服务器上读取：APK 先 scp 到服务器 /tmp，再由服务器本地 publish-remote.py 发 HTTP。
# 客户端脚本、命令行参数、shell 历史里都不会出现口令。
set -euo pipefail

HUB_HOST="${HUB_HOST:-ibssh_lcg7ogpkswgbukqmp2qzb7woc4@api.instabox.sh}"
HUB_PORT="${HUB_PORT:-2222}"
BASE_URL="${BASE_URL:-https://9876-agqvov42zisdyuvnzmi23lrrw4.instabox.tech}"
SRC_DIR="$(cd "$(dirname "$0")" && pwd)"

usage() {
  cat <<'EOF'
用法: publish.sh <本地APK路径> [选项]

  --filename <名字.apk>  远端文件名，默认取本地文件名；须匹配 [A-Za-z0-9][A-Za-z0-9._-]*\.apk
  --set key=value        追加写入的元数据，可重复。合法 key:
                         versionName versionCode title changelog downloadUrl configVersion
  --point-download       自动把 downloadUrl 设为 <BASE_URL>/releases/<文件名>

不带 --set / --point-download 时只上传 APK，metadata 一字不动。
EOF
}

[ $# -ge 1 ] || { usage; exit 2; }
APK="$1"; shift
FILENAME="$(basename "$APK")"
META=()
POINT=""
while [ $# -gt 0 ]; do
  case "$1" in
    --filename) FILENAME="${2:?}"; shift 2;;
    --set) META+=("${2:?}"); shift 2;;
    --point-download) POINT=1; shift;;
    -h|--help) usage; exit 0;;
    *) echo "未知参数: $1" >&2; usage; exit 2;;
  esac
done
[ -f "$APK" ] || { echo "找不到 APK: $APK" >&2; exit 1; }
[ -n "$POINT" ] && META+=("downloadUrl=$BASE_URL/releases/$FILENAME")

SIZE=$(stat -c %s "$APK")
echo "上传 $(basename "$APK") ($SIZE bytes) -> releases/$FILENAME"

STAGE="/tmp/publish-$$-$FILENAME"
scp -P "$HUB_PORT" -o BatchMode=yes -q "$APK" "$HUB_HOST:$STAGE"
trap 'ssh -p "$HUB_PORT" -o BatchMode=yes "$HUB_HOST" "rm -f $STAGE" >/dev/null 2>&1 || true' EXIT
scp -P "$HUB_PORT" -o BatchMode=yes -q "$SRC_DIR/publish-remote.py" "$HUB_HOST:/tmp/publish-remote.py"
trap 'ssh -p "$HUB_PORT" -o BatchMode=yes "$HUB_HOST" "rm -f $STAGE /tmp/publish-remote.py" >/dev/null 2>&1 || true' EXIT

printf '%s\n' "${META[@]:-}" | ssh -p "$HUB_PORT" -o BatchMode=yes "$HUB_HOST" \
  "sudo python3 /tmp/publish-remote.py '$STAGE' '$FILENAME'"

echo
echo "下载地址: $BASE_URL/releases/$FILENAME"
