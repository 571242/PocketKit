#!/usr/bin/env bash
# 端到端验证 PocketKit 更新服务鉴权。
# 匿名检查走公网；需要口令的检查全部在服务器本地执行——口令不离开服务器、不打印、不落盘。
set -uo pipefail

HUB_HOST="${HUB_HOST:-ibssh_lcg7ogpkswgbukqmp2qzb7woc4@api.instabox.sh}"
HUB_PORT="${HUB_PORT:-2222}"
BASE_URL="${BASE_URL:-https://9876-agqvov42zisdyuvnzmi23lrrw4.instabox.tech}"
RELEASES=/data/pocketkit-hub/releases
APK=PocketKit-0.9.2.apk

fail=0
ok()  { printf 'PASS  %s\n' "$1"; }
bad() { printf 'FAIL  %s\n' "$1"; fail=$((fail+1)); }
ssh_hub() { ssh -p "$HUB_PORT" -o BatchMode=yes "$HUB_HOST" "$@"; }
code() { curl -s -o /dev/null -w '%{http_code}' --max-time 30 "$@"; }
b64() { base64 -w0 2>/dev/null || base64 | tr -d '\n'; }

TOKEN=$(ssh_hub 'sudo cat /data/pocketkit-hub/admin.token' | tr -d '\r\n')
[ -n "$TOKEN" ] || { echo "FATAL: 读不到管理口令文件"; exit 1; }
AUTH_B64=$(printf '%s' "$TOKEN" | b64)
# 口令经 curl --config stdin 传入，绝不出现在 argv / ps / shell 历史里
auth_req() {
  local url="$1"; shift
  curl -s -o /dev/null -w '%{http_code}' --max-time 90 -K - "$@" "$url" <<EOF
header = "Authorization: Basic $AUTH_B64"
EOF
}
meta_hash() { ssh_hub 'sudo sha256sum /data/pocketkit-hub/config.json' | cut -d' ' -f1; }
apk_hash()  { ssh_hub "sudo sha256sum $RELEASES/$APK" | cut -d' ' -f1; }

echo "== 基线（远端）=="
META_BEFORE=$(meta_hash)
APK_BEFORE=$(apk_hash)
NAMES_BEFORE=$(ssh_hub "sudo ls -A $RELEASES" | sort)
echo "config.json    $META_BEFORE"
echo "$APK $APK_BEFORE"

echo
echo "== 公共只读接口仍然匿名开放 =="
[ "$(code "$BASE_URL/api/check-update")" = 200 ] && ok "匿名 GET /api/check-update 200" || bad "匿名 GET /api/check-update"
[ "$(code "$BASE_URL/api/proxy-config")" = 200 ] && ok "匿名 GET /api/proxy-config 200" || bad "匿名 GET /api/proxy-config"
HDR=$(curl -s -D - -o /dev/null --max-time 25 "$BASE_URL/releases/$APK" | tr -d '\r')
CL=$(printf '%s' "$HDR" | awk 'tolower($1)=="content-length:"{print $2}' | tail -1)
RSIZE=$(ssh_hub "stat -c %s $RELEASES/$APK")
# 300MB 包只核对响应头，不整包拉取
{ printf '%s' "$HDR" | head -1 | grep -q ' 200' && [ "$CL" = "$RSIZE" ]; } \
  && ok "匿名下载已有 APK 200，Content-Length=$CL 与磁盘一致" \
  || bad "匿名下载已有 APK（$(printf '%s' "$HDR" | head -1) len=$CL/$RSIZE）"

echo
echo "== 匿名 / 错误凭据的写操作必须 401，且不改动任何东西 =="
PROBE="probe-$(date +%s).apk"
WRONG=$(printf 'admin:wrong-password' | b64)
[ "$(code -X POST -H 'Content-Type: application/x-www-form-urlencoded' \
      --data 'versionName=HACK&versionCode=999&title=HACK&changelog=HACK&downloadUrl=http://evil/x.apk&configVersion=HACK' \
      "$BASE_URL/api/admin/save-all")" = 401 ] \
  && ok "匿名 POST /api/admin/save-all 401" || bad "匿名 POST /api/admin/save-all"
[ "$(code -X POST -H "X-Filename: $PROBE" --data-binary 'x' "$BASE_URL/api/upload-release")" = 401 ] \
  && ok "匿名 POST /api/upload-release 401" || bad "匿名 POST /api/upload-release"
[ "$(code -X POST -H "Authorization: Basic $WRONG" -H "X-Filename: $PROBE" --data-binary 'x' "$BASE_URL/api/upload-release")" = 401 ] \
  && ok "错误凭据 POST /api/upload-release 401" || bad "错误凭据 upload"
[ "$(code -X POST -H "Authorization: Basic $WRONG" --data 'versionName=HACK' "$BASE_URL/api/admin/save-all")" = 401 ] \
  && ok "错误凭据 POST /api/admin/save-all 401" || bad "错误凭据 save-all"
[ "$(code -X POST -H "Authorization: Basic $(printf 'admin' | b64)" --data 'x' "$BASE_URL/api/admin/save-all")" = 401 ] \
  && ok "畸形 Basic（无冒号）401" || bad "畸形 Basic 未被拒绝"
[ "$(code "$BASE_URL/")" = 401 ] && ok "匿名 GET 管理页 401" || bad "匿名 GET 管理页"
curl -s -D - -o /dev/null --max-time 20 "$BASE_URL/" | tr -d '\r' | grep -qi 'www-authenticate: *basic' \
  && ok "401 带 WWW-Authenticate: Basic（浏览器可弹登录框）" || bad "401 缺 WWW-Authenticate"
[ "$(meta_hash)" = "$META_BEFORE" ] && ok "匿名尝试后 config.json 未被修改" || bad "config.json 被匿名修改"
[ "$(ssh_hub "sudo test -e $RELEASES/$PROBE && echo exists || echo absent")" = absent ] \
  && ok "匿名上传未在磁盘产生文件" || bad "匿名上传产生了 $PROBE"

echo
echo "== 服务器本地回环同样必须鉴权（不因 peer 是 localhost 免检）=="
LOOP=$(ssh_hub 'curl -s -o /dev/null -w "%{http_code}" --max-time 10 -X POST -H "X-Filename: loop-probe.apk" --data-binary x http://127.0.0.1:9876/api/upload-release')
[ "$LOOP" = 401 ] && ok "127.0.0.1 匿名 upload 401" || bad "127.0.0.1 匿名 upload 返回 $LOOP"
LOOP=$(ssh_hub 'curl -s -o /dev/null -w "%{http_code}" --max-time 10 -X POST --data "versionName=LOOP" http://127.0.0.1:9876/api/admin/save-all')
[ "$LOOP" = 401 ] && ok "127.0.0.1 匿名 save-all 401" || bad "127.0.0.1 匿名 save-all 返回 $LOOP"
LOOP=$(ssh_hub 'curl -s -o /dev/null -w "%{http_code}" --max-time 10 http://127.0.0.1:9876/')
[ "$LOOP" = 401 ] && ok "127.0.0.1 匿名管理页 401" || bad "127.0.0.1 匿名管理页返回 $LOOP"

echo
echo "== 公网入口不剥离 Authorization（浏览器 Basic 可继续管理）=="
[ "$(auth_req "$BASE_URL/")" = 200 ] && ok "带凭据 GET 管理页 200" || bad "带凭据 GET 管理页"
[ "$(auth_req "$BASE_URL/api/upload-release" -X POST -H "X-Filename: $PROBE" --data-binary 'probe-payload')" = 200 ] \
  && ok "带凭据上传探针 200" || bad "带凭据上传探针"
[ "$(code "$BASE_URL/releases/$PROBE")" = 200 ] && ok "探针文件匿名可下载 200" || bad "探针下载"
[ "$(ssh_hub "sudo cat $RELEASES/$PROBE")" = 'probe-payload' ] && ok "探针内容逐字节一致" || bad "探针内容不一致"

echo
echo "== 授权写入的安全语义（服务器本地执行）=="
SERVER_OUT=$(ssh_hub 'sudo bash -s' <<'EOS'
set -uo pipefail
SF=0
ok()  { echo "PASS  $1"; }
bad() { echo "FAIL  $1"; SF=$((SF+1)); }
R=/data/pocketkit-hub/releases
T=$(cat /data/pocketkit-hub/admin.token)
A=$(printf '%s' "$T" | base64 -w0)
req() { local u="$1"; shift; curl -s -o /dev/null -w '%{http_code}' -K - "$@" "$u" <<EOF
header = "Authorization: Basic $A"
EOF
}
meta() { sha256sum /data/pocketkit-hub/config.json | cut -d' ' -f1; }
M0=$(meta)

# 1. 原值回写必须逐字节不变
BODY=$(python3 - <<'PY'
import json, urllib.parse
c = json.load(open("/data/pocketkit-hub/config.json", encoding="utf-8"))
print(urllib.parse.urlencode({
    "versionName": c["versionName"], "versionCode": str(c["versionCode"]),
    "title": c["title"], "changelog": c["changelog"],
    "downloadUrl": c["downloadUrl"], "configVersion": c["configVersion"]}))
PY
)
[ "$(req http://127.0.0.1:9876/api/admin/save-all -X POST -H 'Content-Type: application/x-www-form-urlencoded' --data "$BODY")" = 303 ] \
  && ok "授权 save-all 原值回写 303" || bad "授权 save-all 原值回写"
[ "$(meta)" = "$M0" ] && ok "原值回写后 metadata 逐字节未变" || bad "原值回写改动了 metadata"

# 2. 表单缺字段不得清空其它 metadata
ORIG_TITLE=$(python3 -c 'import json;print(json.load(open("/data/pocketkit-hub/config.json",encoding="utf-8"))["title"])')
req http://127.0.0.1:9876/api/admin/save-all -X POST -H 'Content-Type: application/x-www-form-urlencoded' --data 'title=only-title' >/dev/null
KEPT=$(python3 -c 'import json;c=json.load(open("/data/pocketkit-hub/config.json",encoding="utf-8"));print(c["versionName"],c["versionCode"],c["configVersion"]=="2026.10.24.5")')
[ "$KEPT" = "0.9.2 20 True" ] && ok "缺失字段保留原值 ($KEPT)" || bad "缺失字段被清空 ($KEPT)"
python3 - "$ORIG_TITLE" <<'PY'
import json, sys
p = "/data/pocketkit-hub/config.json"
c = json.load(open(p, encoding="utf-8"))
c["title"] = sys.argv[1]
with open(p, "w", encoding="utf-8") as f:
    json.dump(c, f, ensure_ascii=False, indent=2)
PY
[ "$(meta)" = "$M0" ] && ok "title 还原后 metadata 与基线一致" || bad "title 还原后与基线不一致"

# 3. 文件名/路径穿越与上传大小校验
for evil in "../evil.apk" "../../etc/cron.d/evil.apk" "sub/dir.apk" "evil.txt" ".hidden.apk" "a b.apk"; do
  c=$(req http://127.0.0.1:9876/api/upload-release -X POST -H "X-Filename: $evil" --data-binary x)
  [ "$c" = 400 ] && ok "拒绝文件名 '$evil' (400)" || bad "文件名 '$evil' 返回 $c"
done
c=$(req http://127.0.0.1:9876/api/upload-release -X POST -H "X-Filename: okname.apk" -H 'Content-Length: 0' --data-binary '')
[ "$c" = 413 ] && ok "空上传 413" || bad "空上传返回 $c"
c=$(req http://127.0.0.1:9876/api/admin/save-all -X POST -H 'Content-Type: text/plain' --data 'x')
[ "$c" = 415 ] && ok "非表单编码 415" || bad "非表单编码返回 $c"

# 4. 服务自启/自愈与口令文件权限
systemctl is-active pocketkit-hub | grep -qx active && ok "pocketkit-hub active" || bad "pocketkit-hub 非 active"
systemctl is-enabled pocketkit-hub | grep -qx enabled && ok "pocketkit-hub enabled（开机自启 + Restart=always）" || bad "pocketkit-hub 未自启"
stat -c '%a %U:%G' /data/pocketkit-hub/admin.token | grep -qx '600 user:user' \
  && ok "口令文件 0600 user:user" || bad "口令文件权限异常：$(stat -c '%a %U:%G' /data/pocketkit-hub/admin.token)"
echo "SERVERFAIL:$SF"
EOS
)
printf '%s\n' "$SERVER_OUT" | grep -v '^SERVERFAIL:'
SF=$(printf '%s\n' "$SERVER_OUT" | sed -n 's/^SERVERFAIL://p' | tail -1)
fail=$((fail + ${SF:-99}))

echo
echo "== 探针清理与收尾一致性 =="
ssh_hub "sudo rm -f $RELEASES/$PROBE $RELEASES/loop-probe.apk $RELEASES/.upload-*.part"
[ "$(ssh_hub "sudo test -e $RELEASES/$PROBE && echo exists || echo absent")" = absent ] && ok "探针已清理" || bad "探针残留"
[ "$(meta_hash)" = "$META_BEFORE" ] && ok "config.json 与基线一致" || bad "config.json 偏离基线"
[ "$(apk_hash)" = "$APK_BEFORE" ] && ok "$APK 未被改动" || bad "APK 被改动"
[ "$(ssh_hub "sudo ls -A $RELEASES" | sort)" = "$NAMES_BEFORE" ] && ok "releases/ 清单与基线一致" || bad "releases/ 清单变化"
[ "$(code "$BASE_URL/api/check-update")" = 200 ] && ok "收尾：匿名 check-update 仍 200" || bad "收尾 check-update"

echo
if [ "$fail" -eq 0 ]; then echo "RESULT: ALL PASS"; else echo "RESULT: $fail FAILED"; fi
exit "$fail"
