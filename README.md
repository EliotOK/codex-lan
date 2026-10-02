# Codex LAN

原生 Android 客户端与 Windows 局域网连接服务，手机和 Codex Desktop 共享已有聊天。支持消息与进度同步、Markdown、语音转文字输入、图片预览与缩放、历史消息定位、一键回到底部、执行状态、剩余用量、草稿保存和断线恢复。

## 手机安装

在手机浏览器打开本仓库的 [Releases](https://github.com/EliotOK/codex-lan/releases/latest)，下载 `codex-lan-android.apk` 并安装。需要 Android 8.0 或更新版本。下载无需登录 GitHub。

手机与电脑连接同一局域网，填写电脑连接面板显示的 HTTPS 地址和 8 位配对码。选择已有会话后即可继续聊天。语音识别结果放进消息框，确认后发送，Codex 返回文字；识别服务由手机系统提供。

## 电脑服务

需要 Windows、Node.js 22 或更新版本和 OpenSSL，并在同一 Windows 用户下打开 Codex Desktop。

```powershell
cd codex-lan
./install-keepalive.ps1
```

电脑打开 `http://127.0.0.1:8788/` 查看地址与配对码，“连接设置”可修改并保存配对码。安装脚本注册当前用户的 Windows 任务计划程序任务，登录后自动启动，服务退出后约 3 秒恢复。停止服务并禁用自启动：`./stop.ps1`。

发布 APK 内置公开电脑证书。换电脑或重新生成证书时，在安卓配对页导入该电脑的 `.runtime/cert.pem` 并核对指纹。电脑私钥、登录凭证、运行日志和 APK 签名密钥保留在本机。

## 源码与验证

- [`codex-lan/`](codex-lan/README.md)：电脑服务、网页连接面板与 14 项服务器测试。
- [`codex-lan-android/`](codex-lan-android/README.md)：Kotlin / Compose 客户端与 Android 构建说明。
- [`安卓验证记录`](codex-lan-android/VALIDATION.md)：0.3.0 的 JVM、Android 15 设备测试及真实桌面读取验证。

服务和守护任务读取已有记录，不主动发送消息或调用模型。前台约每 1.2 秒同步桌面快照；手机审批仍需在电脑完成。电脑关机、注销或休眠会中断连接。桌面桥接使用内部本地接口，Codex Desktop 更新可能影响兼容性。

下载文件的 SHA-256 在 Release 的 `SHA256SUMS.txt` 中。0.3.0 发布包采用同一项目签名，可覆盖安装已有 0.1.0 和 0.2.0。
