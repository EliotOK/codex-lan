# Codex Light

原生 Android 客户端与 Windows 局域网连接服务，手机和 Codex Desktop 共享已有聊天。支持消息与进度同步、Markdown、语音转文字输入、文件与图片上传、图片预览与缩放、历史消息定位、一键回到底部、执行状态、剩余用量、应用内更新、字号调整、主题颜色、模型与推理强度选择、草稿保存和断线恢复。

主要开发原因是我的右手大臂最近肌肉劳损，很不舒服，不想打字QAQ，所以想躺在床上动动嘴皮就能（指挥大模型）干活。ChatGPT自带的远程控制要开VPN，很麻烦且不稳定，我希望有一个只要维持在局域网内就能秒连的客户端。

## 手机安装

在手机浏览器打开本仓库的 [Releases](https://github.com/EliotOK/codex-lan/releases/latest)，下载 `codex-lan-android.apk` 并安装。需要 Android 8.0 或更新版本。下载无需登录 GitHub。

从 0.4.0 开始，右上角“⋮”→“检查更新”可查看新版说明、下载并安装；未配对时配对页也有入口。需要手机能够访问 GitHub，首次在安卓设置允许 Codex Light 安装应用，之后由系统确认覆盖安装。更新校验文件 SHA-256、包名、递增版本号与签名，保留现有连接和草稿。

手机与电脑连接同一局域网，填写电脑连接面板显示的 HTTPS 地址和 8 位配对码。选择已有会话后即可继续聊天。语音识别结果放进消息框，确认后发送，Codex 返回文字；识别服务由手机系统提供。

0.4.9 新增 Windows 一键安装与 Codex Light 插件。下载完整 Windows 包，解压后双击 BAT；自动安装运行环境、原生证书、连接来源、普通用户守护任务与插件。重复安装保留配对和附件。

0.4.8 按最近交互时间排列会话：组内会话与“未分组”均按更新时间降序，分组按最近更新的会话排序。搜索保留项目的整体最近更新时间。新用户部署与手机配对见 [首次使用](codex-lan/GETTING-STARTED.md)。

0.4.7 名称更新为 Codex Light，保持现有界面和外观设置。新增“会话互动”：在手机回答 Plan 与 MCP 表单提问，并可将 Desktop 的纯文字排队消息立即引导到当前运行轮次。需要电脑服务 0.4.7；附件、服务端队列和不支持的提问模式提供电脑处理入口。

修复 Desktop 重启后的端点发现，活跃配对自动续期。应用内更新沿用原包名与签名。外网连接方案见 [连接方案](codex-lan/REMOTE-ACCESS.md)，推荐应用内中继与端到端加密。

0.4.3 会话列表按 Desktop 的真实项目 ID 分组，显示保存的项目名称；无项目归属的会话统一放在“未分组”。支持折叠与搜索会话名称、项目名及路径；同一项目的子目录和工作树保持同组。项目名称需要电脑服务 0.4.3，更新服务代码时保留原目录的 `.runtime/` 并重启服务。

0.4.1 修复手机发送的消息显示：发送时立即保留正文，刷新或重启后继续显示；读取桌面转发记录时恢复历史输入，并与本地气泡合并。

## 电脑服务

使用 Windows 10 或更新版本（x64），同一用户登录并打开 Codex Desktop。从 [最新 Release](https://github.com/EliotOK/codex-lan/releases/latest) 下载 `codex-light-windows-x64.zip`，完整解压后双击 `install.bat`，允许专用网络的 Windows 防火墙提示。包内自带 Node，无需另装 OpenSSL。

电脑连接面板：`http://127.0.0.1:8788/`，显示自己的 HTTPS 地址、配对码和状态。BAT 同时安装 **Codex Light 插件**，可在插件列表管理，在新聊天中让它更新、修复或查询连接服务。安装后重启 Codex 加载插件。

也可从仓库来源安装插件：

```powershell
codex plugin marketplace add EliotOK/codex-lan
codex plugin add codex-light@codex-light
```

登录自启动守护任务使用普通用户权限，服务退出后通常约 3 秒恢复。再次双击 BAT 可升级和修复，保留 `.runtime/`。停止服务并禁用自启动：`./stop.ps1`。源码运行需要 Node.js 22 或更新版本：`./install-keepalive.ps1`。

发布 APK 内置公开电脑证书。换电脑或重新生成证书时，在安卓配对页导入该电脑的 `.runtime/cert.pem` 并核对指纹。电脑私钥、登录凭证、运行日志和 APK 签名密钥保留在本机。

## 源码与验证

- [`codex-lan/`](codex-lan/README.md)：电脑服务、网页连接面板与 29 项服务器测试。
- [`codex-lan-android/`](codex-lan-android/README.md)：Kotlin / Compose 客户端与 Android 构建说明。
- [`安卓验证记录`](codex-lan-android/VALIDATION.md)：0.4.9 的 40 项 JVM 和 12 项 Android 15 聊天设备测试，以及此前版本的更新与安装确认验证。

服务和守护任务读取已有记录，不主动发送消息或调用模型。前台约每 1.2 秒同步桌面快照；手机审批仍需在电脑完成。电脑关机、注销或休眠会中断连接。桌面桥接使用内部本地接口，Codex Desktop 更新可能影响兼容性。

下载文件的 SHA-256 在 Release 的 `SHA256SUMS.txt` 中。0.4.9 发布包采用同一项目签名，可覆盖安装已有版本。
