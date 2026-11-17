# Git版本控制

项目从当前完整系统建立第一次提交。以前的功能阶段可阅读根目录`CHANGELOG.md`，没有伪造旧版提交。

项目已完成初始提交并关联远程，默认分支为`main`。以下“第一次上传”保留作参考；后续修改请使用“日常提交”流程，实际进度以`git status`为准。

## 第一次上传

1. 打开PowerShell并进入项目，查看文件状态：

```powershell
Set-Location D:\JavaProject\student-manager
git status
```

`Untracked files`表示尚未加入Git的文件。当前项目不需要再次执行`git init`。

2. 将需要管理的文件加入暂存区，再检查清单：

```powershell
git add .
git diff --cached --name-only
git diff --cached --stat
```

`.env.example`可以提交；真实`.env`、`.local/`、`work/`、依赖目录和构建产物不应出现在清单中。`git add`只是准备本次提交，尚未上传。

3. 创建本地版本快照：

```powershell
git commit -m "chore: initial snapshot of current student manager"
git log --oneline
```

这一步只保存在本机，是当前完整系统的首次版本基线，不是最早初版系统的还原。

4. 在浏览器登录GitHub，打开 <https://github.com/new>，Owner选择你自己的账号（当前连接账号为`cltw1`），仓库名可填写`student-manager`，建议先选Private。创建时不要勾选README、不要选择.gitignore或License，以保留空仓库，避免与本地首次提交产生不同历史。已有这些文件随源码上传。

5. 按仓库页面显示的HTTPS地址关联并推送。下面仅以该账号和仓库名为例，名称不同请替换：

```powershell
git remote add origin https://github.com/cltw1/student-manager.git
git remote -v
git push -u origin main
```

`origin`是这个远程仓库在本地的简称，`main`是分支名，`-u`设置今后推送的默认对应关系。第一条关联命令只需执行一次。你自己运行最后一条命令才会开始上传。

本机Git已配置Git Credential Manager。若首次推送出现登录提示，按提示在浏览器中完成GitHub登录及可能的双重验证；应用调用DeepSeek的API Key不能用作GitHub登录凭据。

6. 刷新GitHub仓库页面，检查源码、README和Commits历史。确认`.env`与`.local/`没有出现在仓库里。GitHub中保存的是源码版本，不包括本机MySQL学生数据。

上述空仓库与首次推送步骤依据[GitHub官方上传说明](https://docs.github.com/zh/migrations/importing-source-code/using-the-command-line-to-import-source-code/adding-locally-hosted-code-to-github)，登录方式见[GitHub凭据说明](https://docs.github.com/zh/get-started/git-basics/caching-your-github-credentials-in-git)。

## 日常提交

在项目目录执行：

```powershell
Set-Location D:\JavaProject\student-manager
git status
git diff
git add .
git diff --cached --stat
git commit -m "说明本次修改的功能或修复"
git push
```

每次提交前检查暂存文件。`.gitignore`排除`.env`及其他本机环境文件、`.local/`数据库和管理员配置、`work/`缓存和测试运行数据、依赖目录、构建产物、日志和私钥文件。仅提交空白配置模板`.env.example`，运行时在本机初始化数据库并填写密钥。

## 查看历史和新建开发分支

```powershell
git log --oneline --decorate
git switch -c feature/你的功能名
```

想查看旧提交且保留当前工作目录时，可用独立目录：

```powershell
git worktree add ..\student-manager-review 需要查看的提交ID
```

不要把未提交修改直接覆盖到旧版本。数据库另行备份；切换源码不会恢复MySQL的数据或表结构。涉及新增字段时，先保留数据库备份，再核对旧源码是否兼容现有结构。

## 另一台电脑使用

克隆仓库后按README准备Java、Node.js、Maven和MySQL，初始化本机数据库，复制`.env.example`为`.env`并填写本机配置。前端生产文件和JAR由构建脚本生成，仓库不会携带本机数据库或已填写的DeepSeek密钥。
