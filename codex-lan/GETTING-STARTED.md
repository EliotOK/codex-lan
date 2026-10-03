# 第一次使用 Codex Light

Codex Light 包含 Windows 电脑连接服务、Codex 插件与 Android App。每位用户连接自己已登录的 Desktop，使用自己的账户与用量。

## 电脑：双击安装

1. 使用 64 位 Windows 10 或更新版本，登录并打开 Codex Desktop，打开至少一条已有聊天。
2. 从 [最新 Release](https://github.com/EliotOK/codex-lan/releases/latest) 下载 `codex-light-windows-x64.zip`，完整解压，双击其中的 `install.bat`。不要直接在压缩包中运行。
3. 按 Windows 提示允许专用网络的防火墙配置。整个安装器使用当前普通用户，只有防火墙步骤申请管理员权限。

安装包内置 Node.js，使用 Windows 原生证书能力，无需另装 Node 或 OpenSSL。首次默认安装到 `%LOCALAPPDATA%\CodexLight`；发现正在运行的本项目服务时复用原目录。自动生成独立 HTTPS 证书和随机八位配对码，自动识别已有 Desktop 聊天作为连接来源，注册当前用户的登录自启动守护任务。

重复双击可更新和修复，保留 `.runtime/` 中的配对、证书、私钥、附件及发送记录。地址改变或证书临近到期时会生成新证书，手机需重新导入。只打开 Codex Light 连接面板不会创建聊天或调用模型。

完成后打开 `http://127.0.0.1:8788/`，查看自己的局域网 HTTPS 地址、配对码和连接状态。在“连接设置”修改配对码。正常使用需要电脑保持运行、同一 Windows 用户登录并打开 Codex Desktop。

## Codex 插件

BAT 会登记个人插件来源，并通过本机 Codex 命令行安装 **Codex Light** 插件。若找不到命令行，重启 Codex 后在“插件 → Personal”安装 Codex Light。命令行安装成功后也建议重启 Codex，再打开新聊天加载插件。安装器不会自行关闭 Desktop。

在聊天中输入“使用 Codex Light 插件修复连接”“更新 Codex Light 电脑服务”或“查看 Codex Light 状态”。状态查询只读，停止操作会停止服务并禁用自启动；再次安装或修复可恢复。

也可先从 GitHub 安装插件，由插件下载完整 Windows 包：

```powershell
codex plugin marketplace add EliotOK/codex-lan
codex plugin add codex-light@codex-light
```

插件通过仓库和个人来源分发，支持在 Codex 插件列表安装和管理。插件内提供安装工作流与脚本，运行服务由 Windows 任务计划程序负责。

## 手机：证书与配对

从同一 Release 下载 `codex-lan-android.apk`，在 Android 8.0 或更新版本安装。在电脑连接面板下载公开证书，或取出服务目录的 `.runtime/cert.pem`，传到手机；App 配对页点击“导入电脑证书”，核对电脑面板的指纹后导入。每台电脑使用自己的证书。

`.runtime/key.pem` 是私钥，保留在电脑。手机仅需公开证书 `cert.pem`。让手机和电脑连接同一局域网，填写自己电脑的 HTTPS 地址和配对码，连接后选择已有聊天。

手机右上角“⋮ → 检查更新”可下载并覆盖安装新版本，保留连接、草稿和设置。电脑更新可重新下载 Windows 包双击 BAT，或让插件执行更新。

## 排查

电脑面板不可访问：运行 BAT 修复，检查端口 8787/8788 是否被其他程序占用。

面板可访问但手机连接失败：确认 Wi-Fi 同网、地址正确、已导入当前电脑公开证书、网络类型为专用网络、没有 Wi-Fi 客户端隔离。安装器只对捆绑 Node 程序放行专用网络、本地子网、TCP 8787；未允许防火墙权限时重复运行 BAT 完成该步骤。

Desktop 显示未连接：打开已有聊天后重试，也可让插件在当前聊天修复连接来源。电脑关机、注销、休眠或 Desktop 关闭会影响连接；服务退出时守护任务通常约 3 秒重启。守护任务闲置时不调用模型。

源码安装仍需 Node.js 22 或更新版本，Windows 不需要 OpenSSL：在 `codex-lan` 目录执行 `./install-keepalive.ps1`。源码包不含捆绑 Node，双击安装请使用完整 Windows 包。

外网连接方案见 [REMOTE-ACCESS.md](REMOTE-ACCESS.md)。
