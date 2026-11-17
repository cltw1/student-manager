# 本地路径与目录变更

当前项目根目录：`D:\JavaProject\student-manager`。

启动、停止和构建脚本根据自身所在位置计算项目根目录，`start.cmd`、`stop.cmd`使用相对位置。Java/Vue源代码、MySQL连接地址和数据库名称不依赖外层文件夹名称。

## 再次移动或重命名项目

1. 在原目录先运行`stop.cmd`，正常停止项目后端及专用MySQL。
2. 移动整个项目，包括隐藏的`.git`、`.env`、`.local`。这些本地配置和数据不在GitHub仓库中。
3. 在VS Code中重新打开新的根目录；旧终端若仍在原目录，应重新打开终端。
4. 双击新目录的`start.cmd`，或在新根目录运行`scripts/Start.ps1 -SkipBuild`。
5. 需要重新构建时，先停止后端，再运行`scripts/Build.ps1`，构建完成后重新启动。

`Start-Database.ps1`在启动专用MySQL之前，会核对当前`.local/mysql/data/auto.cnf`并更新`my.ini`中的`datadir`、`log-error`和`pid-file`。只有路径发生变化时才写入，同时保留`my.ini.before-path-update.*.bak`原配置备份。数据库数据目录缺失或配置条目异常时停止启动，不会自动新建空库覆盖原数据。

历史日志、历史配置备份可能仍记载此前的目录，这是保留的原始记录，不参与当前启动。Maven的生成路径元数据通过重新构建刷新。

## 名称分别表示什么

| 项目项 | 目录改名时的处理 |
|---|---|
| 本地文件夹及文档中的本地命令 | 使用当前根目录 |
| `.local/mysql/my.ini`的本地数据、日志和PID路径 | 启动脚本自动适配 |
| MySQL端口3307、库名`student_manager`、账号`student_app` | 沿用现有数据库配置 |
| DeepSeek密钥及数据库密码 | 沿用本机`.env`和管理员配置 |
| Git分支`main`、提交历史和暂存内容 | 沿用现有Git记录 |
| GitHub仓库名称及`origin`地址 | 独立于本地文件夹名称；本地改名不会自动修改远程 |
| Java包名、前端包名、应用名及JAR名 | 与外层目录无关，无需跟随目录改名 |

本地常用命令：

```powershell
Set-Location D:\JavaProject\student-manager
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Start.ps1 -SkipBuild
```

通过GitHub克隆到另一台电脑时，按README初始化该机器的数据库和本机配置，不复制他人的密钥或数据库管理员配置。
