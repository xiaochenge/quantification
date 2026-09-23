# quantification 项目交接与约定

> 这个文件会被 Codex 在每次新会话开始时自动读取。项目约定有变化时，请同步更新这里。
> 最后更新：2026-09-22

## 项目是什么

资金费率套现（funding rate arbitrage）。在 Bitget 单家交易所做 delta 中性仓位（现货 + 永续对冲）吃资金费，按费率变化动态调仓，目标长期年化约 10%（弹性目标，非保底）。目前处于**需求设计**阶段。

**详细设计见 `docs/architecture.md`，那份是权威来源**；本文件只记录环境、约定、进度和待办。

部署形态：**当前这台 Mac mini 只作开发机**，将来会再单独买一台 Mac mini 当服务器部署运行。
所以代码要按"开发机上开发和验证、服务器上跑正式"来写——配置靠 profile 区分，不要写死本机路径。

## 技术栈与版本

| 项目 | 当前状态 |
| --- | --- |
| JDK | 24（Oracle OpenJDK，`~/Library/Java/JavaVirtualMachines/openjdk-24.0.2+12-54`） |
| 构建 | Maven 3.9.16，项目自带 `./mvnw`（系统里也装了 `~/Library/Maven/current/bin/mvn`） |
| 应用框架 | Spring Boot 4.x（parent 4.1.1），入口 `com.quantification.Application` |
| Web | `spring-boot-starter-web`（后台只读 REST 接口） |
| 持久层 | MyBatis（`mybatis-spring-boot-starter` 4.1.0），原生 Mapper，可读性优先 |
| 数据库 | MySQL 8.4.11（LTS），驱动 `mysql-connector-j`——**还没安装**，见下文 |
| 测试 | `spring-boot-starter-test`（JUnit 5） |
| 交易所 | Bitget 官方 Java SDK（v3），所有调用收敛到 `exchange` 层的 `ExchangeGateway` |
| 前端 | Vue 3 + TypeScript + Vite + Element Plus + ECharts（后台看板，尚未开工） |
| 结构迁移 | Flyway（建议采用，待最终确认） |

常用命令：

```
./mvnw clean test     # 编译并跑测试（合并前必须通过）
./mvnw compile
```

## 代码结构（规划）

单体应用，按包分层，包根 `com.quantification`：

- `exchange` —— Bitget SDK 封装 + `ExchangeGateway` 接口，所有交易所调用只经过这一层
- `strategy` —— 多币种费率扫描、仓位计算、调仓决策与执行
- `risk` —— 风控阈值、异常熔断、保证金保护
- `persistence` —— MyBatis Mapper、实体、Flyway 迁移脚本
- `admin` —— 后台只读 REST 接口
- `schedule` —— 策略循环、资金费结算触发、对账任务
- `config` —— `@ConfigurationProperties` + profile 配置

目前只建了启动类，其余包待建。几条硬约束：后台不直接连交易所（只读数据库）；交易所读写只能走 `exchange` 层；程序启动时必须先对账真实持仓，避免重启后重复开仓。

## 数据库

**状态：还没装，随时可以装**（大概 10 分钟）。

安装方式：MySQL 官方 tarball（macOS ARM 版）解压到用户目录，**不需要管理员密码**。
安装脚本在 `scripts/setup-mysql.sh`，注意它**尚未在真机上验证过**。

```
# 默认装到 ~/Library/MySQL
DB_PASSWORD='...' ./scripts/setup-mysql.sh
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

**数据库直接装在开发机内置盘**，不使用外接硬盘：内置 NVMe 快、TRIM 开着、永远在线，而 MySQL 每次事务提交都要 fsync，对磁盘延迟敏感；外接盘会休眠、可能被误拔，反而更容易出问题。磁盘写入量本来也不是瓶颈（系统换页次数极低）。

本地这两个库的分工：`quantification_test` 是日常开发用的；`quantification_prod` 现阶段只是本机的一份"演练环境"。等服务器就位后，`prod` 的配置改成指向服务器上的库，本机这份可以留着做对照。

开发机上的数据丢了主要影响开发进度；**真正需要认真备份的是将来那台服务器**。

## 安全红线（铁律）

⚠️ **这个仓库是公开的（public），且 API Key 权限很大、关联资金较多。**

**铁律：密钥在任何场景下都不许进仓库、不许打包、不许提供给外部，只能在本机使用。**

- 密钥（数据库密码、Bitget 的 api-key / secret-key / passphrase）只放**仓库外**的：
  `~/.quantification/application-local.yml`（权限 600），由 `application.yml` 的
  `spring.config.import` 自动加载。
- **永远不要**在 `src/main/resources/` 下再建 `application-local.yml`，也不要把密码写进 `application.yml`。
- 仓库有两道自动防护，别绕过（提交时不要加 `--no-verify`）：
  - **构建校验**：`pom.xml` 里的 exec-maven-plugin 在 validate / package 阶段执行
    `scripts/check-secrets.sh`，命中密钥直接构建失败；
  - **提交钩子**：`.githooks/pre-commit` 在提交前拦一遍（已执行
    `git config core.hooksPath .githooks` 启用；新克隆的仓库要再做一次）。
- 提交前仍然用 `git status` 自查。把整个项目目录拷给别人是安全的——里面没有密钥。
- `~/.quantification/application-local.yml` 是密钥的唯一副本，**注意备份到安全位置**。

## 分支与协作约定

- 主干是 **`master`**（不是 main），默认分支已设为它
- 流程：从 master 拉 `feature/xxx` → 开发 → 推送 → PR → 合并回 master
- 合并前必须 `./mvnw clean test` 通过
- 远程走 SSH：`git@github.com:xiaochenge/quantification.git`（`~/.ssh/config` 里配了 ssh.github.com:443）

和用户协作时的偏好：**用中文沟通**；动手改代码前先给出方案再执行；一次只做一件事；不确定的地方直接问，不要猜。

**编码规范**：见 `docs/coding-standards.md`（注释、时间类型、包结构、迁移规则等），写代码前必读。

## 待办与遗留问题

1. **当前分支名有拼写错误**：`feautre/init_Requirement_Design` 应为 `feature/init_Requirement_Design`
2. `.idea/` 已加进 .gitignore，但之前提交的 `.idea/*.xml` 还在版本控制里，需要 `git rm -r --cached .idea` 清理
3. 装 MySQL：跑 `scripts/setup-mysql.sh` → 确认 `application-local.yml` 里的密码一致 → 验证两个库都能连上
4. Flyway 是否采用，待最终确认（见架构文档）
5. **部署方案待定**：服务器上怎么跑——打成可执行 jar 用 launchd 托管，还是用 Docker。开发阶段不用急着决定，但代码里别依赖本机特有路径
6. 服务器到位后要处理：关闭自动休眠、配 UPS 防断电、数据库定期备份（mysqldump 导出到另一块盘或别处）

## 机器与部署规划

**当前这台——开发机**

- Mac mini（Mac16,11），Apple M4 Pro，24GB 内存，512GB SSD，macOS 27
- 环境已配好：JDK 24、Maven 3.9.16（PATH 写在 `~/.zshrc`）、Git 身份 `xiaochenge`
- 按开发机定位使用，不需要 24 小时开机，也不需要外接硬盘

**将来那台——服务器**

- 计划再买一台 Mac mini，专门部署正式运行
- 到那时才需要考虑：UPS 防断电、关闭自动休眠、数据库备份、开机自启
- 现在不用操心这些，但写代码时要避免依赖开发机的专有路径和手工步骤
