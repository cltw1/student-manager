# 学序 · 学生信息管理系统

Java + Vue + MySQL 的本地学生管理应用。支持 DeepSeek 在线模型驱动的聊天操作，以及独立的传统管理页面。初始字段为学号和姓名，现支持用户确认后新增正式的文本、整数、小数、日期、布尔字段。

项目目录：`D:\JavaProject\student-manager`。

启动脚本按所在目录定位项目；专用MySQL的路径会在启动前自动适配当前目录。移动或重命名说明见[本地路径与目录变更](docs/LOCAL-PATHS.md)。

版本控制从当前完整系统建立基线。功能演进见[CHANGELOG.md](CHANGELOG.md)，提交与分支使用方法见[docs/GIT.md](docs/GIT.md)。

## 当前完成情况

- 交互校对：保留文本/Excel原始列名，确认别名和重复列、补全缺值、修正类型、选择候选学生、明确忽略或新增额外字段；支持聊天继续说明意图。
- 正式字段扩展：新增前必须逐条补齐所有已有学生，支持界面填写或Excel补全；字段新增和本批学生操作分别确认。
- 折叠处理摘要：展示识别、校验、待确认和执行结果等可核对的依据，不展示模型内部思维链。
- 新功能使用与限制见 [交互校对与字段扩展说明](docs/REVIEW.md)，本次验证见 [验证记录](docs/REVIEW-TESTING.md)。

- 聊天界面：会话列表、新建/删除会话、快捷指令、Markdown 回复、真实 SSE 流式回复、工具执行结果表格。
- AI 操作：按学号/姓名查询、新增、修改姓名或学号、删除。修改和删除必须点击确认卡片；缺少信息或同名时由助手追问。
- 人工操作：搜索、分页、新增、编辑、删除确认、成功/失败反馈。
- 后端：工具白名单、参数校验、参数化 SQL、事务、重复学号冲突、记录变更校验、请求重放保护、模型超时与错误反馈。
- 本地交付：前端已集成进可执行 JAR；提供构建、启动、停止、独立数据库初始化脚本。
- 模型仅使用线上 DeepSeek API，不包含本地模型和模拟聊天入口。

## 在这台电脑上快速启动

项目专用 MySQL 已初始化在 `.local/mysql/`，端口为 **3307**，账号密码已写入本机 `.env`。原有 MySQL 3306 服务和数据未被修改。

1. 用编辑器打开项目根目录 `.env`，填写这一行：

   ```dotenv
   DEEPSEEK_API_KEY=填写你自己的DeepSeekAPIKey
   ```

   保留已经生成的数据库密码。模型默认 `deepseek-flash`，可通过 `DEEPSEEK_MODEL` 更改为账号可用的模型标识。

2. 双击根目录 `start.cmd`，或在 PowerShell 中运行：

   ```powershell
   Set-Location D:\JavaProject\student-manager
   powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Start.ps1 -SkipBuild
   ```

3. 浏览器打开 [http://127.0.0.1:8080](http://127.0.0.1:8080)。先打开“学生管理”确认数据库连接，再尝试聊天。

4. 双击 `stop.cmd` 停止本项目后端和独立 MySQL。学生数据保留。修改 `.env` 后需要停止并重新启动应用。

API Key 为空时，传统学生管理仍可使用，聊天会明确提示缺少配置。页面“已配置”只表示后端读到了密钥，不代表已通过 DeepSeek 的鉴权和余额检查。

## 从源码构建

需要 JDK 17+、Maven 3.6.3+、Node.js 22.18+/24.12+、MySQL 8.0.16+ 或 8.4。当前机器使用 Java 17、Maven 3.9.5、Node.js 24、MySQL 8.0.34。

```powershell
Set-Location D:\JavaProject\student-manager
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Build.ps1
```

该命令会安装缺失的前端依赖、执行 TypeScript 检查和 Vue 生产构建、复制静态资源、运行后端测试并生成：

```text
backend/target/student-manager-1.0.0.jar
```

已有 JAR 时，`Start.ps1 -SkipBuild` 直接启动。源码修改后运行 `Build.ps1` 再重启，或运行不带 `-SkipBuild` 的 `Start.ps1`。

脚本使用项目级 HTTPS Maven/npm 源和 `work/` 下的下载缓存，不依赖机器上原有的旧镜像地址，也不修改全局 Maven/npm 配置。

## 在另一台电脑初始化数据库

安装 MySQL 后执行：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Initialize-Database.ps1
```

找不到安装位置时指定二进制目录：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Initialize-Database.ps1 -MySqlBin 'C:\Program Files\MySQL\MySQL Server 8.4\bin'
```

初始化脚本在本项目 `.local/mysql/` 下创建独立数据目录，绑定 `127.0.0.1:3307`，自动生成 root 和应用账号密码。应用账号只有学生表所在数据库的增删查改权限。已有数据目录不会被重新初始化。重复执行会检查并启动已存在的项目数据库。

如果希望使用已有 MySQL，请自行以管理员账号执行 `database/schema.sql`，创建具有 `SELECT, INSERT, UPDATE, DELETE` 权限的应用账号，然后在 `.env` 中修改 `DB_HOST`、`DB_PORT`、`DB_NAME`、`DB_USERNAME`、`DB_PASSWORD`。这时不需要运行独立数据库初始化脚本。

## 开发模式

将 `.env` 中的变量载入当前终端，启动 Java 后端；另一个终端运行前端：

```powershell
Set-Location D:\JavaProject\student-manager
. .\scripts\Common.ps1
Read-ProjectEnv
Set-Location .\backend
mvn -gs ..\scripts\maven-settings.xml -s ..\scripts\maven-settings.xml '-Dmaven.repo.local=D:\JavaProject\student-manager\work\maven-repository' spring-boot:run
```

```powershell
Set-Location D:\JavaProject\student-manager\frontend
npm run dev
```

前端访问 `http://127.0.0.1:5173`，`/api` 通过 Vite 代理到 8080。日常使用构建后的单 JAR 即可，无需运行 Node.js 服务。

## 目录

```text
student-manager/
├─ backend/                 Spring Boot 源码及后端测试
├─ frontend/                Vue 3 + TypeScript 界面
├─ database/schema.sql      两字段学生表建表脚本
├─ scripts/                 构建、启动、停止、初始化与 HTTP 验收
├─ docs/                    接口与测试说明
├─ .env.example             配置模板
├─ .env                     本机配置（不提交）
├─ .local/                  本机数据库、进程 PID、日志（不提交）
├─ work/                    下载缓存和中间文件（不提交）
├─ start.cmd / stop.cmd     双击启动和停止
└─ java-homework/            原有目录，未修改
```

## 使用规则

- 学号必须为7位数字，首位不能为0且不能重复；姓名最多15个字符，允许重名；性别只能为男或女。详见[数据格式规则](docs/FORMAT.md)。
- 查询无匹配记录返回成功，显示 0 条；修改或删除不存在的学号返回失败。
- 聊天支持批量新增、查找、修改、删除，每轮最多处理500条写入记录；可直接粘贴多行“学号 姓名”。
- 聊天输入框和学生管理页面均有“批量处理”“Excel导入”入口。Excel支持.xlsx/.xls、2MB以内、每批最多500条，字段别名和额外列需要确认；支持仅新增、仅更新、新增或更新三种模式。
- 文本/Excel导入先确认映射、修正或明确排除全部问题行，再生成执行预览。确认后各记录独立提交；并发冲突逐行反馈，可仅重试失败记录。正式字段新增需先全员补齐，再分别确认字段和本批学生操作。
- 修改/删除确认卡片 5 分钟有效，绑定原会话。确认时重新检查原记录，变化后要求重新操作。
- 模型生成的文字不能作为成功凭据，结果卡片来自数据库实际执行结果。
- 模型连接失败不影响传统管理功能。操作结果已成功但后续模型回复失败时，保留真实操作结果。
- 网络中断时不会自动重放写操作；请刷新查询核实。请求去重与会话仅在当前后端进程内有效，重启后请根据数据库核实结果。
- 学生记录持久化；聊天和待确认操作保存在内存中，重启后清空。聊天最多 100 个会话，历史上下文按完整轮次裁剪。
- 本地应用无登录，后端和专用数据库均绑定本机地址。线上模型处理用户指令和必要的学生查询结果。

## 验证与排查

```powershell
node .\scripts\smoke-test.mjs
```

该脚本对运行中的应用执行真实数据库 HTTP 验收，测试记录使用唯一前缀，完成后清理。没有 Key 时还会验证聊天缺少配置的明确反馈，不会调用付费接口。

日志：`.local/backend.stdout.log`、`.local/backend.stderr.log`、`.local/mysql/mysql-error.log`。常见错误与验证边界见 `docs/TESTING.md`；接口定义见 `docs/API.md`。

官方协议参考：[DeepSeek 接入](https://api-docs.deepseek.com/)、[工具调用](https://api-docs.deepseek.com/guides/tool_calls/)。

批量功能的操作示例、Excel格式和处理规则见 `docs/BATCH.md`。
