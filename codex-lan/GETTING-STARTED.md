# 第一次使用 Codex Light

Codex Light 包含 Windows 电脑服务与 Android App。每位用户在自己的电脑部署服务，连接自己已登录的 Codex Desktop，使用自己的 Codex 账户与用量。GitHub 提供安装包与源代码。

## 1. 准备电脑

准备 Windows、Node.js 22 或更新版本、OpenSSL，以及已登录并打开的 Codex Desktop。确保 `node` 与 `openssl` 可从 PowerShell 调用。

从 [最新 Release](https://github.com/EliotOK/codex-lan/releases/latest) 下载 `codex-lan-source.zip`，解压到固定目录。打开 PowerShell，进入其中的 `codex-lan` 目录，然后执行：

```powershell
./install-keepalive.ps1
```

安装过程生成这台电脑独立的 HTTPS 证书、私钥与随机配对码，并注册当前 Windows 用户的登录自启动守护任务。电脑开机登录后服务可自动恢复；正常使用需要电脑保持运行并打开 Codex Desktop。

## 2. 设置连接来源

在电脑打开 `http://127.0.0.1:8788/`，查看自己的局域网地址与八位配对码。

服务需要一条本机 Codex 聊天的 ID 作为接口调用来源。在 Codex 聊天环境中运行安装脚本时，可自动读取当前聊天 ID；从普通 PowerShell 安装时，可能需要在电脑连接面板填写“连接来源聊天 ID”并保存。可以在已有 Codex 聊天中让 Codex 读取当前聊天 ID 并帮助填写。手机仍可从会话列表选择要继续的已有聊天。

使用 Codex 协助安装时，可以提供以下请求，并填写实际解压目录：

> 请在我解压的 Codex Light 电脑服务目录中检查 Node.js 和 OpenSSL，运行 install-keepalive.ps1，使用当前聊天 ID 配置连接来源，确认守护任务、电脑连接面板和 Desktop 接口正常，告诉我手机连接地址、配对方式与公开证书的位置。

## 3. 安装手机 App

从同一 Release 下载 `codex-lan-android.apk`，在 Android 8.0 或更新版本安装。首次允许安装下载的应用后，由 Android 确认安装。

发布 APK 当前内置开发电脑的公开证书。新用户需要在自己电脑的连接面板下载公开证书，或取出服务目录的 `.runtime/cert.pem`，将该文件传到手机。在 App 配对页点击“导入电脑证书”，选择这份文件，核对指纹后导入。

电脑可查看证书 SHA-256 指纹：

```powershell
openssl x509 -in .runtime/cert.pem -noout -fingerprint -sha256
```

`.runtime/key.pem` 是电脑私钥，必须留在电脑；手机仅需公开证书 `cert.pem`。

让手机和电脑连接同一局域网。在 App 输入自己电脑面板显示的 HTTPS 地址与配对码，点击“连接电脑”，再选择已有会话。默认地址需要改成自己的电脑地址。每台电脑的配对码由该服务独立生成；可在电脑“连接设置”修改。

## 4. 更新与排查

手机右上角“⋮ → 检查更新”下载并安装新版本，包名与签名一致时保留已保存的连接、草稿和设置。电脑更新时保留 `.runtime/`，替换服务源代码并重启服务。

若手机连不上，先确认电脑面板可访问、Desktop 已打开、电脑地址仍正确，再检查 Windows 防火墙是否允许 Node 服务的 TCP 8787 专用网络入站连接，以及 Wi-Fi 是否启用了客户端隔离。电脑地址或 HTTPS 证书变化后，按面板的新地址重新连接并导入新公开证书。

当前版本提供局域网连接。外网方案见 [REMOTE-ACCESS.md](REMOTE-ACCESS.md)。目前新用户仍需安装运行依赖、配置连接来源及手动导入证书；安装向导、自动配对和通用首次启动流程是后续产品化工作。
