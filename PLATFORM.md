# 开放云真机平台 · 第 0 阶段加固

本仓库基于 [SonicCloudOrg](https://github.com/SonicCloudOrg) 的 Sonic 云真机平台，目标是做一个开放的设备出租平台：机主把旧手机接入平台，自己设定每小时的价格，租户按分钟付费使用。

Sonic 原本是按"一家公司内网、所有人互相信任"设计的。开放给陌生人之前，必须先修掉一批安全问题，这就是第 0 阶段做的事。第 0 阶段的运营前提是：设备都在平台自己的机房里，先给 5–10 个种子客户按固定价使用。

## 目录与上游版本

| 目录 | 上游仓库 | 导入时的上游提交 |
|---|---|---|
| `sonic-server/` | SonicCloudOrg/sonic-server | `90d333c` (2025-03-05) |
| `sonic-agent/` | SonicCloudOrg/sonic-agent | `acae68d` (2024-10-16) |
| `sonic-client-web/` | SonicCloudOrg/sonic-client-web | `0ca245d` (2024-10-16) |

`e08109e` 是原样导入的提交。之后的每个提交都是本平台的修改，`git diff e08109e..` 可以看到对上游的全部改动。

## 修了什么

### 原计划的 7 处

| # | 问题 | 改法 | 提交 |
|---|---|---|---|
| 1 | 浏览器从 `/agents?id=` 拿到 agent 的主密钥，拼进 WebSocket 地址；agent 只比对密钥，token 只查"非空"。租过一次的人永久持有这把钥匙，还能拿它冒充 agent | 服务端签发**远控票据**：2 分钟有效，绑定用户、agent 和设备，用 agent 密钥签名。agent 的所有远控 WebSocket 只认票据。用户的登录 JWT 也不再发给 agent | `7ddfe9d` |
| 2 | server 相信 agent 消息里自报的 `agentId`，一个 agent 能改写别人的地址，也能抢走别人的设备 | 连接建立时就绑定身份，消息里的 `agentId` 一律被覆盖。设备不能被其他机主的 agent 抢走 | `437be9d` `cbda149` |
| 3 | 浏览器用明文 `ws://` 直连 agent 的 IP，HTTPS 页面根本连不上，家庭宽带在 NAT 后面也连不进来 | agent 连 server 支持 `wss`（`sonic.server.ssl`）。agent 可以声明公网地址（`sonic.agent.public-url`），用于 TLS 反向代理或隧道。HTTPS 页面自动改用 `wss` | `b4d4b1a` |
| 4 | 远程 ADB、SIB、WDA、UIA2 和抓包都在 agent 主机上开无鉴权端口。`/uia` 代理还能转发到本机任意端口，甚至其他主机 | 统一由 `sonic.agent.remote-access.enable` 控制，**默认关闭**。开启后 `/uia` 也只转发到 occupy 登记过的端口。agent 没开时，server 的 occupy 会直接返回明确的错误 | `87fa683` `4589e30` |
| 5 | 用例里的 Groovy/Python 脚本步骤会在 agent 主机上执行任意代码 | 由 `sonic.agent.script.enable` 控制，**默认关闭** | `aa570da` |
| 6 | 没有"归属"：任何登录用户都能看所有 agent 的密钥、关停 agent、删除设备、强停别人的会话 | agent 增加机主（`owner_name`），管理操作只允许机主或超管。设备的当前使用人可以结束自己的会话 | `cbda149` |
| 7 | 没有独占，也没有计量 | 独占在 agent 端实现（`DeviceClaimMap`）：一台设备同一时间只服务一个用户，别人既不能操作也不能围观。新增 `device_sessions` 使用记录表和 `/deviceSessions/list` 查询接口 | `7ddfe9d` `fce909f` |

### 过程中发现的其他问题

| 问题 | 改法 | 提交 |
|---|---|---|
| `/exchange/send` 标了 `@WhiteUrl`，任何登录用户（包括自助注册的）都能借它向任意 agent 发送关机、占用、执行用例等指令 | 改为只接受 server 实例之间的内部 token（60 秒有效） | `accd0c2` |
| JWT 密钥默认值是 `sonic`，任何人都能伪造超管 token；controller 解析 token 时不验签；日志里会记下密码、token、票据和机器人密钥 | 没有设置 ≥32 字符的密钥就拒绝启动；controller 校验签名；日志只记录用户名，敏感接口不记录参数和返回值 | `66e2da1` |
| nginx 把 `/chrome/` 整个转发到前端容器里 Chrome 的远程调试端口，公网上的任何人都能操控这个浏览器访问内网 | 只放行 DevTools 前端文件，`/json/*` 和 `page/`、`browser/` 一律返回 403 | `ac75358` |
| agent 的 Docker 部署使用 privileged 加 host 网络 | 新增 `docker-compose-hardened.yml` | `ef7fce7` |
| 在 JDK 21 上无法编译（Lombok 版本过旧），Groovy 被降级导致脚本步骤无法运行 | 升级 Lombok，对齐 Groovy 版本 | `4535bee` `2a66dc6` |

## 升级与部署须知（不兼容变更）

- **必须设置 `SECRET_KEY`**：至少 32 个字符的随机串，所有 server 组件（gateway、controller、folder）要用同一个值，否则服务拒绝启动。可以用 `openssl rand -base64 48` 生成。
- **server 和 agent 必须一起升级。** 旧 agent 不认票据，旧 server 也不会签发票据。
- **数据库**：`agents` 表新增 `remote_access`、`owner_name`、`public_url` 三列，另有新表 `device_sessions`。这些由 actable 在启动时自动创建。
- **已有 agent**：`owner_name` 为空，视为平台自营，只有超管能管理。用户新建的 agent 归创建者所有。
- **HTTPS**：页面走 HTTPS 时，浏览器只能用 `wss` 连 agent。可以给 agent 配置 Spring Boot 的 `server.ssl.*`；也可以把 agent 放在反向代理或隧道（cloudflared、frp 等）之后，再设置 `public-url`。
- **agent 新配置项**（配置文件和 Docker 环境变量两种写法）：

| 配置项 | Docker 环境变量 | 默认值 | 作用 |
|---|---|---|---|
| `sonic.agent.script.enable` | `AGENT_SCRIPT_ENABLE` | `false` | 允许 Groovy/Python 脚本步骤 |
| `sonic.agent.remote-access.enable` | `AGENT_REMOTE_ACCESS_ENABLE` | `false` | 允许远程 ADB/SIB/WDA/UIA2 和抓包（这些都没有鉴权） |
| `sonic.agent.public-url` | `AGENT_PUBLIC_URL` | 空 | 浏览器访问本 agent 用的地址（TLS 代理或隧道） |
| `sonic.server.ssl` | `SONIC_SERVER_SSL` | `false` | agent 通过 `wss`/`https` 连接 server |

- **权限配置**：Sonic 的角色权限默认对所有登录用户开放全部接口。建议给租户单独建一个角色，只开放设备列表、远控票据和使用记录相关的接口。

## 构建与测试

```bash
# server：JDK 17 或 21
mvn -f sonic-server/pom.xml install

# agent：JDK 17 或 21；ddmlib 31.0.1 只发布在 Google Maven（dl.google.com）
mvn -f sonic-agent/pom.xml test

# client-web：Node 16/18；如果用 Node 22，需要关掉它的 ESM 检测
cd sonic-client-web && npm ci
NODE_OPTIONS="--no-experimental-require-module --no-experimental-detect-module" npm run build
```

目前的测试数量：server 端 common 9 个、controller 38 个；agent 29 个。

有两点需要注意：

- **上游测试没有在跑**：上游原有的 JUnit 4 测试因为项目缺少 vintage 引擎，实际上一个都没有执行。本仓库新增的 server 测试用的是 JUnit 5。
- **agent 构建请在正式环境复验一次**：开发环境访问不了 `dl.google.com`，所以 agent 的编译和测试用的是 Maven Central 上的 ddmlib 25.3.0 替身。`AndroidDeviceBridgeTool` 里有 3 处只存在于 31.x 的 API，只在本地副本里做了替换，仓库代码没有改动。正式发布前，请在能访问 `dl.google.com` 的环境里完整跑一次 `mvn test`。

## 开放给外部机主之前必须解决的问题（第 1 阶段）

1. **票据要改成非对称签名。** 现在票据用 agent 自己的密钥签发，一个恶意的第三方 agent 可以替任何用户伪造会话，让对方被计费。应改为由平台私钥签名，agent 只拿公钥做验证；用量时长还要用浏览器心跳或中继流量交叉核对。
2. **建设中继网关。** agent 主动出站连到平台，所有流量（包括 ADB、UIA2）都经过带票据鉴权的通道转发，这样才能取代第 4 项里"默认关闭"的临时方案。
3. **测试管理仍然是单租户的。** 项目、用例、结果、全局参数对所有用户可见；`/projects/list` 在网关上免登录，而且还带着已废弃的机器人密钥字段。
4. **设备的"安装密码"对所有用户可见。** 机主应该在设备上只使用测试账号。
5. **设备以序列号作为全局唯一键。** 很多廉价机的序列号是重复的（比如 `0123456789ABCDEF`），不同机主之间会冲突。需要改用平台分配的设备身份，并结合 Key Attestation 校验真机。
6. **iOS 仍有暴露的端口。** sib 开启的 WDA 端口和绑定地址由 sib 本身决定。建议第 0、1 阶段只做 Android。
7. **测试套件不参与设备独占。** 运行测试套件时不会占用 `DeviceClaimMap`。
8. **网关和 controller 的鉴权还有漏洞。** 网关白名单是按子串匹配的；controller 在没有 token 时不做任何检查，完全依赖网关。所以 controller 绝不能直接对外暴露。
9. **Eureka 端口在 docker-compose 里对外发布了。** 建议只在内网开放。
10. **AGPL 第 13 条（网络服务的源码提供义务）。** 部署修改版时，要在界面上向用户提供源码下载途径，例如在页脚链接到本仓库。

## 修改声明 / Modification notice (AGPL-3.0 §5a)

This repository contains modified versions of sonic-server, sonic-agent and sonic-client-web by SonicCloudOrg, licensed under the GNU Affero General Public License v3.0 (see the `LICENSE` file in each directory). The modifications were made in September 2026. They are listed above and consist of every commit after `e08109e` (the unmodified import); `git diff e08109e..` shows all of them.
