# 技术选型与架构（Architecture）

> 状态：需求设计阶段 · 本文件随讨论持续更新
> 最后更新：2026-09-22

## 1. 项目概述

资金费率套现策略。只在 Bitget 一家交易所操作：同时监控多个主流币种的资金费率，当某个币种费率处于高位时建立 delta 中性仓位（现货 + 永续对冲）吃资金费，并根据费率变化动态调仓。目标长期年化约 10%（弹性目标，非保底）。

本地单机部署：当前 Mac mini 作为开发机，将来另购一台服务器跑正式。管理后台仅本机访问，无公网入站，故不设鉴权。

## 2. 技术栈（已确认）

| 层面 | 选择 |
| --- | --- |
| 语言 / 运行时 | Java 24（Oracle OpenJDK） |
| 构建 | Maven 3.9.16（`./mvnw`） |
| 应用框架 | Spring Boot 4.x（当前主线 4.1.x） |
| 持久层 | MyBatis（原生 Mapper，可读性优先） |
| 数据库 | MySQL 8.4.11（utf8mb4，时区 Asia/Shanghai） |
| 交易所接入 | 自建轻量 Bitget 客户端（HTTP + Jackson）+ `ExchangeGateway` 隔离层；官方 SDK 仅作接口参考 |
| 后台前端 | Vue 3 + TypeScript + Vite + Element Plus + ECharts |
| 数据库结构迁移 | Flyway（已确认） |
| 测试 | JUnit 5 |

## 3. 系统架构

单体应用，按包分层（monolith；单人维护，暂不拆多模块）：

- `exchange` —— Bitget SDK 封装 + `ExchangeGateway` 接口，所有交易所调用只经过这一层
- `strategy` —— 多币种费率扫描、仓位计算、调仓决策与执行
- `risk` —— 风控阈值、异常熔断、保证金保护
- `persistence` —— MyBatis Mapper、实体、Flyway 迁移脚本
- `admin` —— 后台只读 REST 接口
- `schedule` —— 策略循环、资金费结算触发、对账任务
- `config` —— `@ConfigurationProperties` + profile 配置

数据流：

Bitget ⇄ exchange 层 → strategy / risk → persistence（MySQL）→ admin REST → Vue 看板

要点：后台不直接连交易所，只读数据库；交易所读写只经 `exchange` 层。

## 4. 关键设计决策

- **交易所隔离**：所有 Bitget 调用收敛到 `ExchangeGateway`，便于 mock 测试、纸面交易、未来换所。
- **不引入官方 Java SDK**：官方 Java SDK 未发布到任何 Maven 仓库（Central / Sonatype 均 404），且是 Java 8 + 老依赖（lombok 1.16.20、fastjson 1.2.70 等），在 JDK 24 下无法编译。改为自建轻量客户端，签名算法参考 SDK 实现。账户类型为统一账户（UTA），走 `/api/v3` 接口。
- **幂等下单**：下单携带 `clientOrderId`，失败重试不重复成交；订单维护状态机。
- **启动对账（最高优先级）**：策略阈值通过 `application-local.yml` + 重启生效，因此程序重启时必须先从交易所拉取真实持仓 / 余额，恢复本地状态并接管既有持仓，绝不能重复开仓。
- **配置与密钥**：`application.yml` 可提交（无密钥）；真实密码 / API key 只放**仓库外**的 `~/.quantification/application-local.yml`（权限 600，由 `spring.config.import` 自动加载）。构建与提交各有一道自动校验，防止密钥被打进 jar 或误提交。策略参数暂走配置文件 + 重启（一期）。
- **后台只读 + 无鉴权**：本机无公网入站，后台不做登录；一期仅查询，不做操作命令。
- **调度同进程**：策略循环用 Spring 调度能力 / 独立线程，与 Web 层解耦，本地单机同进程运行。
- **时区与结算去重**：统一 Asia/Shanghai；资金费结算点触发，需防重复结算。

## 5. 部署形态

- 开发：开发机 `./mvnw` 运行，前端开发期 `npm run dev`。
- 生产：前端构建为静态资源由 Spring Boot 托管，运行 `--spring.profiles.active=prod`。
- profile：`local`（连测试库）、`prod`（连生产库，将来指向服务器）。
- 不写死本机路径；将来服务器落地后再定 launchd 或 Docker。

## 6. 待定（业务轮再细化）

- 策略腿结构与开平仓 / 调仓规则（多币种动态调仓，细节未定）
- 成本模型：手续费、滑点、价差对收益率的影响与调仓阈值
- 历史资金费率 / 行情数据与回测方案
- 告警渠道（本地无外网，先本地日志 + 后台异常列表，服务器上线后再定）
