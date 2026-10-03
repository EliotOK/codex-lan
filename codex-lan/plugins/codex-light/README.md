# Codex Light 插件

用于安装、升级、修复、查询或停止 Windows 电脑连接服务。插件内包含安装工作流与 PowerShell 辅助程序；完整 Windows 包自带 Node，首次安装下载并校验项目 Release。

## 安装

完整 Windows 包：解压 `codex-light-windows-x64.zip`，双击 `install.bat`，服务与插件一并安装。

从 GitHub 安装插件：

```powershell
codex plugin marketplace add EliotOK/codex-lan
codex plugin add codex-light@codex-light
```

重启 Codex，打开新聊天，输入“使用 Codex Light 插件安装电脑连接服务”。安装时使用当前用户，不要以管理员身份运行整个安装器；防火墙步骤单独申请 Windows 权限。

安装包不会创建聊天或发送消息。手机使用已有的本机 Desktop 聊天；安装与修复保留已有配对信息、证书和附件。服务以 Windows 普通用户守护任务保持运行；闲置时不调用模型。插件目录中的安装记录只保存服务路径。
