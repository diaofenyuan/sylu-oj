#!/bin/bash
# 首次部署管理员初始化：交互式设置（或 -a/-p 参数）并写入 var/oj-dev-admin.env。
# var/ 已被 .gitignore 排除，凭据只保存在部署机本地，绝不进入仓库。
# 后端启动时若系统中尚无该账号（首次部署）即自动创建；已有账号不会被覆盖。
# 用法: setup-admin.sh [-a 登录名] [-p 密码]
set -eu
REPO="${OJ_REPO_HOME:-$HOME/sylu-oj}"
MARK="$REPO/var/.admin-initialized"
ENVFILE="$REPO/var/oj-dev-admin.env"
mkdir -p "$REPO/var"

login=""
password=""
while getopts "a:p:" opt; do
  case "$opt" in
    a) login="$OPTARG" ;;
    p) password="$OPTARG" ;;
    *) echo "用法: setup-admin.sh [-a 登录名] [-p 密码]"; exit 2 ;;
  esac
done
shift $((OPTIND - 1))

if [ -z "$login" ]; then
  read -r -p "管理员登录名（回车默认 diaofenyuan）: " login
  login="${login:-diaofenyuan}"
fi
if [ -z "$password" ]; then
  read -r -s -p "管理员密码（至少 8 位）: " password
  echo
fi
[ -n "$login" ] || { echo "[setup] 登录名不能为空"; exit 1; }
[ "${#password}" -ge 8 ] || { echo "[setup] 密码长度必须至少 8 位"; exit 1; }

cat > "$ENVFILE" <<EOF
OJ_DEV_ADMIN_LOGIN=$login
OJ_DEV_ADMIN_PASSWORD=$password
EOF
chmod 600 "$ENVFILE" 2>/dev/null || true
touch "$MARK"
echo "[setup] 管理员凭据已写入 $ENVFILE（var/ 不入 git；系统无该账号时启动自动创建，已有账号不会覆盖）"
