# quantification 项目交接与约定

> 这个文件会被 Codex 在每次新会话开始时自动读取。项目约定有变化时，请同步更新这里。
> 最后更新：2026-09-22

## 项目是什么

资金费率套现（funding rate arbitrage）。Java 项目，本地开发，最终长期跑在一台 Mac mini 上。
目前处于**需求设计**阶段，策略细节还没定。

## 技术栈与版本

| 项目 | 当前状态 |
| --- | --- |
| JDK | 24（Oracle OpenJDK，`~/Library/Java/JavaVirtualMachines/openjdk-24.0.2+12-54`） |
| 构建 | Maven 3.9.16，项目自带 `./mvnw`（系统里也装了 `~/Library/Maven/current/bin/mvn`） |
| 测试 | JUnit 5.11.4 |
| 应用框架 | **未定**。数据库配置是按 Spring Boot 的习惯写的，若改用其他框架需要调整 |
| 数据库 | MySQL 8.4.11（LTS）—— **还没安装**，见下文 |

常用命令：

```
./mvnw clean test     # 编译并跑测试（合并前必须通过）
./mvnw compile
```

## 数据库

**状态：还没装。** 等外接硬盘到位后再装。

安装方式：MySQL 官方 tarball（macOS ARM 版）解压到用户目录，**不需要管理员密码**。
安装脚本在 `scripts/setup-mysql.sh`，注意它**尚未在真机上验证过**。

```
# 默认装到 ~/Library/MySQL
DB_PASSWORD='...' ./scripts/setup-mysql.sh

# 若改路径
MYSQL_BASE=/Volumes/xxx/mysql DB_PASSWORD='...' ./scripts/setup-mysql.sh
```

| 用途 | 库名 | 账号 |
| --- | --- | --- |
| 生产 | `quantification_prod` | `quant_app` |
| 测试 | `quantification_test` | `quant_app` |
| 管理 | — | `root`（密码同 `quant_app`） |

端口 3306，只监听 `127.0.0.1`。字符集 utf8mb4，时区 Asia/Shanghai。

配置文件分工：

- `src/main/resources/application.yml` —— **会提交**，无明文密码，默认 profile 是 `local`（连测试库）
- `src/main/resources/application-local.yml` —— **不提交**（在 .gitignore 里），真实密码在这里
- 切到生产库：`--spring.profiles.active=prod`

**为什么数据库装内置盘、外接盘只做备份**：内置 NVMe 更快，而 MySQL 每次事务提交都要 fsync，对磁盘延迟敏感；外接盘还可能休眠或被误拔。内置盘的写入量本来就不是瓶颈（系统换页次数极低）。外接盘的用途是 Time Machine 备份 + 每晚 mysqldump 导出。

## 安全红线

⚠️ **这个仓库是公开的（public）。**

- 任何密码、密钥、API key 都不许提交。数据库密码只放 `application-local.yml`
- 以后接交易所 API 时，key/secret 同样只放本地文件或环境变量，绝不进仓库
- 提交前用 `git status` 确认没有敏感文件被带上

## 分支与协作约定

- 主干是 **`master`**（不是 main），默认分支已设为它
- 流程：从 master 拉 `feature/xxx` → 开发 → 推送 → PR → 合并回 master
- 合并前必须 `./mvnw clean test` 通过
- 远程走 SSH：`git@github.com:xiaochenge/quantification.git`（`~/.ssh/config` 里配了 ssh.github.com:443）

和用户协作时的偏好：**用中文沟通**；动手改代码前先给出方案再执行；一次只做一件事；不确定的地方直接问，不要猜。

## 待办与遗留问题

1. **包名有重复**：`com.App` / `com.AppTest`（示例代码）和 `com.quantification.Application` / `ApplicationTest`（空类）并存，需要统一到一个包里
2. **当前分支名有拼写错误**：`feautre/init_Requirement_Design` 应为 `feature/init_Requirement_Design`
3. `.idea/` 已加进 .gitignore，但之前提交的 `.idea/*.xml` 还在版本控制里，需要 `git rm -r --cached .idea` 清理
4. 外接硬盘到位后：装 MySQL → 跑 `scripts/setup-mysql.sh` → 确认 `application-local.yml` 里的密码一致 → 验证两个库都能连上
5. 引入 Spring Boot，还是用别的方案，待定

## 这台开发机

- Mac mini（Mac16,11），Apple M4 Pro，24GB 内存，512GB SSD，macOS 27
- 定位是**长期运行**：需要 UPS 防断电，外接盘做备份
- 环境已配好：JDK 24、Maven 3.9.16（PATH 写在 `~/.zshrc`）、Git 身份 `xiaochenge`
