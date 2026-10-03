---
name: codex-light-setup
description: 安装、升级、修复或停止 Codex Light 的 Windows 电脑服务，配置当前 Desktop 聊天为连接来源，并核对手机局域网配对。用户请求 Codex Light 安装或连接修复时使用。
---

# Codex Light 电脑服务

按用户请求选择安装、升级、修复、状态查询或停止。状态问题只读取状态；安装与修复请求授权对应服务操作。电脑服务使用普通 Windows 守护任务保持运行，手机连接用户自己已登录的 Codex Desktop。

运行本插件工作流的 `scripts/Invoke-Setup.ps1`。它读取安装记录，必要时从项目 GitHub Release 下载 Windows 包并验证 SHA-256。`Install` 安装，`Update` 获取新版，`Repair` 修复已安装服务，`Status` 只读，`Stop` 停止服务并禁用自启动。

在当前 Codex 聊天中安装或修复时，将真实 `$env:CODEX_THREAD_ID` 传给 `-CallerThreadId`。若环境未提供 ID，让安装器自动读取本机 Desktop 会话目录；不要编造 ID。手机之后仍可选择任意已有本机聊天。

```powershell
& '<此工作流目录>/scripts/Invoke-Setup.ps1' -Mode Install -CallerThreadId $env:CODEX_THREAD_ID
```

脚本可安装到当前用户目录，生成独立 HTTPS 证书，注册当前用户的登录自启动任务。防火墙步骤单独请求 Windows 权限，仅允许该 Node 程序的专用网络、本地子网、TCP 8787 入站。遵循工具的执行权限；需要权限时说明具体目录或操作，不将拒绝或超时解释为同意。

更新和修复保留 `.runtime/` 的配对、证书、附件与配置。完整 Windows 包自带 Node，使用 Windows 原生证书能力；不需要另装 OpenSSL。不要创建新聊天或发送测试消息来验证安装。验证服务面板、Desktop 只读状态和当前用户的守护任务即可。

完成后告知安装位置、电脑面板 `http://127.0.0.1:8788/`、是否连接 Desktop，以及手机需要同一局域网、自己的 HTTPS 地址与配对码、导入公开证书 `.runtime/cert.pem`。私钥 `.runtime/key.pem` 留在电脑。凭证和完整聊天记录不要写入公共输出。若防火墙权限未获准、Desktop 未打开或用户还没有聊天，分别说明尚需完成的步骤。
