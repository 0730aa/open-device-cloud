# 开放云真机平台 · 改动与部署说明

本仓库基于 [SonicCloudOrg](https://github.com/SonicCloudOrg) 的 Sonic 云真机平台，目标是做一个开放的设备出租平台：机主把旧手机接入平台，自己设定每小时的价格，租户按分钟付费使用。

Sonic 原本是按"一家公司内网、所有人互相信任"设计的。开放给陌生人之前，必须先修掉一批安全问题，这就是第 0 阶段做的事。第 0 阶段的运营前提是：设备都在平台自己的机房里，先给 5–10 个种子客户按固定价使用。第 1 阶段让机主用自己家里的电脑和宽带接入，见下文"第 1 阶段"一节。

## 目录与上游版本

| 目录 | 上游仓库 | 导入时的上游提交 |
|---|---|---|
| `sonic-server/` | SonicCloudOrg/sonic-server | `90d333c` (2025-03-05) |
| `sonic-agent/` | SonicCloudOrg/sonic-agent | `acae68d` (2024-10-16) |
| `sonic-client-web/` | SonicCloudOrg/sonic-client-web | `0ca245d` (2024-10-16) |

`e08109e` 是原样导入的提交。之后的每个提交都是本平台的修改，`git diff e08109e..` 可以看到对上游的全部改动。

## 第 0 阶段：修了什么

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

## 第 0 阶段的升级与部署须知（不兼容变更）

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

## 第 1 阶段：让外部机主接入（进行中）

目标是让机主用自己家里的电脑和宽带接入平台。第 1 阶段的票据格式与第 0 阶段不兼容，server、agent 和中继要一起升级。

### 远控票据改由平台私钥签发（`3aed761`）

第 0 阶段的票据用 agent 自己的密钥签发，第三方 agent 可以替任何用户伪造票据，进而伪造使用记录，让租户多付钱。现在：

- 票据是 ES256（P-256）签名的 JWT。私钥只在 controller 上；agent 连上 server 时只收到公钥，能验票，不能签发。
- 一张票据只能开启一次使用记录，agent 无法重放真实的票据来虚增时长。
- 密钥对通过 `TICKET_PRIVATE_KEY` / `TICKET_PUBLIC_KEY` 配置（base64 编码的 PKCS#8 / X.509）。不配置时，controller 首次启动会自动生成一对并存进 `conf_list` 表；部署多个 controller 或者使用中继时，必须显式配置同一对。生成方法：

```bash
openssl ecparam -name prime256v1 -genkey -noout -out ticket.pem
openssl pkcs8 -topk8 -nocrypt -in ticket.pem -outform DER | base64 -w0   # TICKET_PRIVATE_KEY
openssl ec -in ticket.pem -pubout -outform DER | base64 -w0              # TICKET_PUBLIC_KEY
```

macOS 上把 `base64 -w0` 换成 `base64`。`ticket.pem` 就是私钥本身，用完要妥善保管或删除。

### 中继网关（`56c67c0` `1245177` `cbfb1b0`）

家用宽带的电脑在路由器后面，浏览器连不进去。新增的 `sonic-server-relay` 服务解决这个问题：

1. agent 通过已认证的 server 连接申请一张**中继令牌**（平台私钥签发，10 分钟有效，只能用来在中继上注册），然后主动连到中继，保持一条控制连接。
2. 浏览器照常发起远控连接，只是地址换成中继：`wss://中继/websockets/...`，路径与直连 agent 完全相同，前端不需要改。
3. 中继用平台公钥验票，按票据里的 agent id 找到那条控制连接，请 agent 回连一条数据连接，然后双向转发。agent 把这条连接接到自己本机的同一个端点上，所以验票、设备独占等逻辑都和直连时一样。

中继只持有公钥，不连数据库，也不保存画面内容。每条连接结束时记一行日志（agent、用户、设备、时长、双向字节数，不含票据），可以用来和 `device_sessions` 里的使用记录对账。中继每 30 秒 ping 一次浏览器，agent 每 30 秒 ping 一次中继，免得反向代理、负载均衡和家用路由器把暂时没有数据的连接断开。

**部署中继**

- 环境变量：`TICKET_PUBLIC_KEY` 必填，与 controller 用同一对密钥（这时 controller 也必须配置 `TICKET_PRIVATE_KEY`）；`RELAY_PORT` 可选，默认 8095。其余参数见 `sonic-server/sonic-server-relay/src/main/resources/application.yml`。
- 运行：`mvn -f sonic-server/pom.xml install` 之后执行 `java -jar sonic-server/sonic-server-relay/target/sonic-server-relay.jar`，或者用 `sonic-server-relay/src/main/docker/Dockerfile` 构建镜像。
- 放在 TLS 反向代理之后，对外提供 `https://`（前端页面是 HTTPS 时必须这样）。nginx 示例：

```nginx
server {
    listen 443 ssl;
    server_name relay.example.com;
    # ssl_certificate 和 ssl_certificate_key 略
    location / {
        proxy_pass http://127.0.0.1:8095;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_read_timeout 1h;
    }
}
```

**机主这边**

- 在 agent 上配置 `sonic.agent.relay-url`（Docker 环境变量 `AGENT_RELAY_URL`），例如 `https://relay.example.com`。
- 没有设置 `public-url` 时，浏览器会自动改走中继，agent 的 7777 端口不用再对外开放。
- agent 自己不要开 `server.ssl`：中继是通过本机 `127.0.0.1` 上的明文 ws 访问 agent 端点的，浏览器到中继这一段由中继的 TLS 保护。开了 `server.ssl` 的 agent 会拒绝启用中继，并在日志里说明原因。

**目前的限制**

- 中继只能跑一个实例：agent 和浏览器必须连到同一个中继进程。要多实例，需要按 agent 分片路由。
- 只转发远控用的 WebSocket（画面、控制、终端、音频、WebView 调试）。远程 ADB/SIB/WDA/UIA2 和抓包仍然只能在可信网络里用 `remote-access.enable` 开启，不经过中继。

### 注册中心（Eureka）加上鉴权（`2120cdb`）

上游的 Eureka 配置把 `/eureka/**` 整个排除在鉴权之外，`SONIC_EUREKA_USERNAME` / `SONIC_EUREKA_PASSWORD` 只保护了管理页面；docker-compose 又把它的端口发布到了宿主机上。结果是：任何能访问这个端口的人，不需要密码就能注册一个假的 `sonic-server-controller`（测试里对原配置发起注册，返回的是 204，注册成功）。网关会把一部分用户的登录请求、token，以及 agent 连接（URL 里带着 agent 密钥）转给它，攻击者还能读取注册表、注销真实实例。现在：

- 注册中心的接口必须带上这组账号密码（HTTP Basic）才能访问，只有健康检查保持开放；管理页面仍然用登录页。
- `SONIC_EUREKA_PASSWORD` 少于 16 位或含有 URL 里不能出现的字符时，注册中心拒绝启动，所以 `.env` 自带的 `sonic` 不能再用。密码会被拼进各组件的注册中心 URL，只能用字母、数字和 `._~-`，可以用 `openssl rand -hex 24` 生成；所有服务端组件必须一致。
- docker-compose 不再对外发布注册中心的端口，只有同一个 Docker 网络里的服务端组件能访问。

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

目前的测试数量：server 端 common 9 个、controller 49 个、relay 20 个、eureka 7 个；agent 43 个。

有两点需要注意：

- **上游测试没有在跑**：上游原有的 JUnit 4 测试因为项目缺少 vintage 引擎，实际上一个都没有执行。本仓库新增的 server 测试用的是 JUnit 5。
- **agent 的依赖从哪里下载**：pom 里配的是阿里云镜像，而 ddmlib 31.0.1 只发布在 Google Maven（`dl.google.com`）。CI（`.github/workflows/ci.yml`）改为直接从 Google Maven 和 Maven Central 下载，用真实的 ddmlib 编译并运行 agent 的全部测试；每个 PR 都会跑 server、agent、client-web 三项检查。本地下载失败时，可以照搬 CI 里的 `settings.xml` 镜像配置。

## 在一台 Windows 电脑上试用

`.github/workflows/windows-bundle.yml` 把服务端、agent、nginx 加网页前端、Java 17 运行时和启动脚本打成一个压缩包 `open-device-cloud-windows`，不需要 Docker，只需要另外装 MySQL。用法见包里的 `README.txt`（源文件在 `packaging/windows/bundle/`）。修改 `packaging/windows/` 的 PR 会自动打包；要打包当前代码，可以在 Actions 页面手动运行这个 workflow。压缩包在运行结果页面保留 3 天。

- **打包后会先实际用一遍。** 同一个 workflow 在 Windows 机器上按 README 的步骤操作：用 `start-server.bat` 启动，注册 `sonic`，新建 agent，带着 key 启动 `start-agent.bat`，等它上线，然后重启服务端，确认 agent 能重连，最后停止。唯一没有覆盖的是真手机。
- **服务端各组件共用依赖。** 四个组件的 Spring Boot jar 大部分依赖相同，打包时把 jar 解开，依赖只保留一份（261 MB 减到 94 MB）。每个组件按它自己 jar 里 `classpath.idx` 的顺序启动：`java @apps/<组件>/java.args`。
- **只在本机可访问。** 所有端口只监听 127.0.0.1：网页 3000（nginx），网关 8094，注册中心 8761；controller 和 folder 用随机端口。
- **密钥在第一次启动时生成。** `SECRET_KEY` 和注册中心密码首次启动时随机生成，和 MySQL 密码一起保存在 `server\settings.json`。数据库由 `DbCheck` 显式按 utf8mb4 创建：MySQL 5.7 和 MariaDB 的默认字符集是 latin1，存不了中文，controller 会在初始化数据时失败（在 MariaDB 上实际遇到过）。
- **第三方程序固定版本。** nginx 1.28.0；adb r34.0.3；sas 0.1.12、sib 1.3.20、sgm 1.3.4，与 agent 检查的版本一致；Java 取 Temurin 17 的最新更新，并用 Adoptium 公布的校验和核对。

## 开放给外部机主之前还要解决的问题

非对称签名的票据、中继网关和注册中心的鉴权已经完成（见"第 1 阶段"一节），剩下这些：

1. **使用时长还没有对账。** 中继已经按连接记录流量日志，但还没有和 `device_sessions` 自动核对，时长仍以 agent 上报为准。
2. **测试管理仍然是单租户的。** 项目、用例、结果、全局参数对所有用户可见；`/projects/list` 在网关上免登录，而且还带着已废弃的机器人密钥字段。
3. **设备的"安装密码"对所有用户可见。** 机主应该在设备上只使用测试账号。
4. **设备以序列号作为全局唯一键。** 很多廉价机的序列号是重复的（比如 `0123456789ABCDEF`），不同机主之间会冲突。需要改用平台分配的设备身份，并结合 Key Attestation 校验真机。
5. **iOS 仍有暴露的端口。** sib 开启的 WDA 端口和绑定地址由 sib 本身决定。建议第 0、1 阶段只做 Android。
6. **测试套件不参与设备独占。** 运行测试套件时不会占用 `DeviceClaimMap`。
7. **网关和 controller 的鉴权还有漏洞。** 网关白名单是按子串匹配的；controller 在没有 token 时不做任何检查，完全依赖网关。所以 controller 绝不能直接对外暴露。按目前的接口，还没找到能绕过去的请求路径，但只要以后加一个接口，就可能被绕过。
8. **AGPL 第 13 条（网络服务的源码提供义务）。** 部署修改版时，要在界面上向用户提供源码下载途径，例如在页脚链接到本仓库。
9. **还没有自己的镜像。** `docker-compose.yml` 引用的仍是上游的 `sonicorg/*` 镜像，里面不包含本仓库的任何修改，中继也没有现成的镜像。上线前要用本仓库的代码构建并推送镜像，再改掉 compose 里的镜像地址。

## 修改声明 / Modification notice (AGPL-3.0 §5a)

This repository contains modified versions of sonic-server, sonic-agent and sonic-client-web by SonicCloudOrg, licensed under the GNU Affero General Public License v3.0 (see the `LICENSE` file in each directory). The modifications were made in September 2026. They are listed above and consist of every commit after `e08109e` (the unmodified import); `git diff e08109e..` shows all of them.
