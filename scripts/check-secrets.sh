#!/usr/bin/env bash
#
# 密钥泄露检查：构建时（Maven）和提交前（git hook）共用同一个脚本。
#
# 用法：
#   ./scripts/check-secrets.sh [路径 ...]
#
# 不带参数时默认检查 src/main/resources 和 target/classes。
# 命中任一条即非零退出：
#   1. 文件名像密钥文件：application-local*.yml、*.secret*、*.key
#   2. 配置类文件里出现"非空的密钥字段"：api-key / secret-key / passphrase / password
#      （值写成 ${...} 占位符的不算，空值也不算）
#
# 说明：这只是"防误操作"的兜底。真正的防线是密钥根本不进仓库目录——
# 它放在 ~/.quantification/application-local.yml，详见 AGENTS.md 的安全红线。

set -euo pipefail

# 只有这些后缀才做"内容检查"，避免文档里的示例文案被误报
CONTENT_EXTENSIONS='yml|yaml|properties|json|env|toml|ini|conf'
# 字段出现在行首（允许前置空格），注释行不会被匹配
CONTENT_PATTERN='^[[:space:]]*(api-key|secret-key|passphrase|password)[[:space:]]*:'

if [ "$#" -gt 0 ]; then
  TARGETS=("$@")
else
  TARGETS=("src/main/resources" "target/classes")
fi

fail=0

# 把目录展开成文件列表；传入的是文件就原样输出
list_files() {
  if [ -d "$1" ]; then
    find "$1" -type f -print
  elif [ -f "$1" ]; then
    printf '%s\n' "$1"
  fi
}

for target in "${TARGETS[@]}"; do
  while IFS= read -r file; do
    [ -n "$file" ] || continue

    # 检查 1：文件名
    base=$(basename "$file")
    case "$base" in
      application-local*.yml | *.secret* | *.key)
        printf '❌ 发现疑似密钥文件：%s\n' "$file" >&2
        fail=1
        ;;
    esac

    # 检查 2：内容（仅配置类文件）
    case "$file" in
      *.yml | *.yaml | *.properties | *.json | *.env | *.toml | *.ini | *.conf) ;;
      *) continue ;;
    esac

    # 逐行判断字段值是否"真的非空"：
    #   排除 空值、只有引号（"" / ''）、以及 ${...} 占位符
    hits=""
    while IFS= read -r matched; do
      [ -n "$matched" ] || continue
      value="${matched#*:}"          # 去掉行号
      value="${value#*:}"            # 去掉字段名
      value="$(printf '%s' "$value" | tr -d '[:space:]')"
      case "$value" in
        "" | '""' | "''") continue ;;   # 空值
        '$'*) continue ;;               # ${...} 占位符
      esac
      hits="${hits}${matched}
"
    done < <(grep -nIE "$CONTENT_PATTERN" "$file" 2>/dev/null || true)
    if [ -n "$hits" ]; then
      printf '❌ 文件里有非空密钥字段：%s\n' "$file" >&2
      printf '%s' "$hits" | head -3 >&2
      fail=1
    fi
  done < <(list_files "$target")
done

if [ "$fail" -ne 0 ]; then
  printf '\n密钥检查未通过。密钥只能放在仓库外的：\n  ~/.quantification/application-local.yml\n详见 AGENTS.md 的「安全红线」。\n' >&2
  exit 1
fi

printf '✅ 密钥检查通过（未发现密钥文件或非空密钥字段）\n'
