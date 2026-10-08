# 官方 Remote 协议实验

Windows 电脑端验证工具：通过官方重新验证、独立 P-256 设备密钥和 WebSocket 中继，尝试读取正在运行的 Desktop 原会话。尚未接入 Android 发布版本。

需要 Node.js 22 或更新版本、PowerShell 7、已有 ChatGPT CLI 登录，以及已启用 Remote 的 Codex Desktop。测试读取当前用户的 `.codex/auth.json`，令牌仅在进程内存中使用。设备密钥位于当前用户的 Windows CNG 密钥存储，私钥不可导出。

## 运行

在本目录执行离线检查：

```powershell
node --test auth-protocol.test.mjs wire-websocket.test.mjs
```

仅检查实时注册挑战及本机签名：

```powershell
node live-key-probe.mjs
```

该检查发起注册起始请求，检查服务器挑战、工作区和用户绑定，并在结束时删除此次临时设备密钥；签名尚未提交给服务器。

完整设备授权与原会话读取测试：

```powershell
$env:REMOTE_PROBE_THREAD_ID = '目标 Desktop 会话 ID'
# PowerShell 7 未在标准位置时，可以指定 REMOTE_PROBE_PWSH。
node enroll-client.mjs
```

进程会在 `localhost:1457` 或 `localhost:1455` 启动十分钟有效的验证入口，入口见本地生成的 `local-test-entry.json`。在电脑浏览器打开后完成官方重新验证。成功注册会创建独立测试设备授权；主机配对、WebSocket 证明接受和会话读取分别检查。完成注册的设备密钥会保留，失败注册的密钥会清理。测试过程不发送聊天消息。

脱敏结果写入本地 `*-results.json` 和独立尝试记录。临时入口、密钥引用和结果文件由本目录的 Git 忽略规则排除；令牌不写入这些文件。

## 已观测结果

2026-10-08：7 项离线检查通过。官方注册起始接口返回 HTTP 200；实时工作区绑定、Unix 秒期限检查和 P-256 签名的本机验证通过。此前官方重新验证和 OAuth 授权码交换成功。完整注册结束请求、WebSocket 证明接受、原会话读取和手机移动网络仍待实测。

官方使用两种账户标识；重新验证绑定登录令牌的 account-user 标识，设备注册在工作区匹配时可绑定 auth-user 标识。服务器挑战期限为 Unix 秒，签名载荷保留服务器原始期限值。

本实验使用官方公开 CLI OAuth 客户端标识，并涉及 Desktop 使用的后端接口。尚未建立独立第三方 OAuth 客户端的注册支持或长期兼容性承诺。

## 来源

- [官方 Remote 连接说明](https://learn.chatgpt.com/docs/remote-connections)
- [官方 app-server 说明](https://learn.chatgpt.com/docs/app-server)
- [OpenAI Codex 公开实现](https://github.com/openai/codex/tree/3e544e843713d254dfc22ced37b902235a7573f8)，对应路径见 `source.json`。
- 本机安装的 Desktop 26.1002.7124.0 的静态协议检查；本目录不包含安装包代码或设备凭据。
