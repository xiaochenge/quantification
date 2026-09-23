#!/usr/bin/env bash
#
# 一键安装并初始化 MySQL（macOS / Apple Silicon），不需要管理员密码。
#
# 用法：
#   DB_PASSWORD='你的密码' ./scripts/setup-mysql.sh
#
# 外接盘的话，把 MYSQL_BASE 指过去，例如：
#   MYSQL_BASE=/Volumes/Data/mysql DB_PASSWORD='你的密码' ./scripts/setup-mysql.sh
#
# 这段脚本还没在真机上完整跑过（数据库还没装），跑之前建议先看一眼。

set -euo pipefail

# ===== 可调参数 =====
MYSQL_VERSION="${MYSQL_VERSION:-8.4.11}"
MYSQL_BASE="${MYSQL_BASE:-$HOME/Library/MySQL}"
MYSQL_HOME="$MYSQL_BASE/mysql-$MYSQL_VERSION-macos15-arm64"
DATA_DIR="${DATA_DIR:-$MYSQL_BASE/data}"
TARBALL="${TARBALL:-$MYSQL_BASE/mysql-$MYSQL_VERSION-macos15-arm64.tar.gz}"
PORT="${PORT:-3306}"
SOCKET="${SOCKET:-$MYSQL_BASE/mysql.sock}"

DB_APP_USER="${DB_APP_USER:-quant_app}"
DB_TEST="${DB_TEST:-quantification_test}"
DB_PROD="${DB_PROD:-quantification_prod}"
DB_PASSWORD="${DB_PASSWORD:-}"

DOWNLOAD_URL="https://cdn.mysql.com/Downloads/MySQL-8.4/mysql-$MYSQL_VERSION-macos15-arm64.tar.gz"

if [ -z "$DB_PASSWORD" ]; then
  echo "请先设置密码，例如：DB_PASSWORD='xxx' $0" >&2
  echo "这个值要和 src/main/resources/application-local.yml 里的一致。" >&2
  exit 1
fi

mkdir -p "$MYSQL_BASE"

# ===== 1. 下载并解压 =====
if [ ! -d "$MYSQL_HOME" ]; then
  if [ ! -f "$TARBALL" ]; then
    echo "==> 下载 MySQL ${MYSQL_VERSION}（约 170MB）"
    curl -L --fail -o "$TARBALL" "$DOWNLOAD_URL"
  fi
  echo "==> 解压到 $MYSQL_BASE"
  tar -xzf "$TARBALL" -C "$MYSQL_BASE"
fi

# ===== 2. 初始化数据目录（只需要一次）=====
if [ ! -d "$DATA_DIR/mysql" ]; then
  echo "==> 初始化数据目录 $DATA_DIR"
  "$MYSQL_HOME/bin/mysqld" --initialize-insecure \
    --basedir="$MYSQL_HOME" \
    --datadir="$DATA_DIR"
fi

# ===== 3. 启动服务 =====
echo "==> 启动 mysqld（端口 ${PORT}，仅监听本机）"
"$MYSQL_HOME/bin/mysqld" \
  --basedir="$MYSQL_HOME" \
  --datadir="$DATA_DIR" \
  --port="$PORT" \
  --socket="$SOCKET" \
  --pid-file="$MYSQL_BASE/mysqld.pid" \
  --log-error="$MYSQL_BASE/mysqld.err" \
  --bind-address=127.0.0.1 \
  --daemonize

# 等它把 socket 建出来
for _ in $(seq 1 30); do
  [ -S "$SOCKET" ] && break
  sleep 1
done

# ===== 4. 设密码、建两个库和应用账号 =====
echo "==> 建库、建账号"
"$MYSQL_HOME/bin/mysql" --socket="$SOCKET" -u root <<SQL
ALTER USER 'root'@'localhost' IDENTIFIED BY '${DB_PASSWORD}';

CREATE DATABASE IF NOT EXISTS ${DB_TEST} CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS ${DB_PROD} CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE USER IF NOT EXISTS '${DB_APP_USER}'@'localhost' IDENTIFIED BY '${DB_PASSWORD}';
CREATE USER IF NOT EXISTS '${DB_APP_USER}'@'127.0.0.1' IDENTIFIED BY '${DB_PASSWORD}';

GRANT ALL PRIVILEGES ON ${DB_TEST}.* TO '${DB_APP_USER}'@'localhost';
GRANT ALL PRIVILEGES ON ${DB_TEST}.* TO '${DB_APP_USER}'@'127.0.0.1';
GRANT ALL PRIVILEGES ON ${DB_PROD}.* TO '${DB_APP_USER}'@'localhost';
GRANT ALL PRIVILEGES ON ${DB_PROD}.* TO '${DB_APP_USER}'@'127.0.0.1';
FLUSH PRIVILEGES;
SQL

echo
echo "完成。连接信息："
echo "  地址:     127.0.0.1:$PORT"
echo "  测试库:   $DB_TEST"
echo "  生产库:   $DB_PROD"
echo "  账号:     $DB_APP_USER"
echo "  停止服务: $MYSQL_HOME/bin/mysqladmin --socket=$SOCKET -u root -p shutdown"
