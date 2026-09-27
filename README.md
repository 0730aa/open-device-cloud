# open-device-cloud

开放云真机平台。目标：机主把闲置的旧手机接入平台，自己定每小时的价格；租户在浏览器里远程操控真机，按使用时长付费。

本仓库基于 [SonicCloudOrg](https://github.com/SonicCloudOrg) 的 Sonic 云真机平台。Sonic 原本是为"一家公司内网、所有人互相信任"设计的，这里在它之上补齐对陌生人开放所需的安全、归属和计量能力。

## 目录

| 目录 | 作用 |
|---|---|
| `sonic-server/` | 平台服务端：网关、controller、文件服务、注册中心（Spring Cloud），以及让家用宽带后面的 agent 也能接入的中继 |
| `sonic-agent/` | 装在机主电脑上的 agent，通过 USB 连接手机，负责画面和操作的转发 |
| `sonic-client-web/` | 租户和机主使用的网页前端（Vue 3） |
| `packaging/windows/` | Windows 本机试用包：服务端和 agent 在同一台电脑上运行，不需要 Docker |

## 进度

- **第 0 阶段（安全加固）**：设备放在平台自己的机房，给少量种子客户使用。改了什么、怎么部署、有哪些不兼容变更，见 [PLATFORM.md](PLATFORM.md)。
- **第 1 阶段（开放给外部机主）**：进行中。平台私钥签发的远控票据和中继网关已经完成，剩下的待办见 PLATFORM.md 的最后一节。
- 定价、计费和结算尚未开始。

## 构建与测试

命令见 PLATFORM.md 的"构建与测试"一节。每个 PR 都会由 CI 编译并测试 server、agent 和 client-web。

想在一台 Windows 电脑上直接试用，见 PLATFORM.md 的"在一台 Windows 电脑上试用"一节。

## 许可证

[AGPL-3.0](LICENSE)。三个组件各自保留上游的 LICENSE 文件，对上游的全部修改都记录在 git 历史里。部署修改后的版本对外提供服务时，AGPL 第 13 条要求向使用者提供对应的源代码。
