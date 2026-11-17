# 验证记录与排查

> 本文件记录首版验证。批量/Excel功能及最新真实DeepSeek验收见 `BATCH-TESTING.md`，使用说明见 `BATCH.md`。

验证日期：2026-11-12。项目当前目录：`D:\JavaProject\student-manager`；后续目录变更验证见[RENAME-TESTING.md](RENAME-TESTING.md)。

## 已通过

| 验证 | 结果 |
|---|---|
| Vue / TypeScript | 类型检查与 Vite 生产构建通过 |
| Java 构建 | Java 17、Spring Boot 3.5.16；干净构建并生成可执行 JAR |
| 后端自动化测试 | 13 项通过，0 失败、0 错误 |
| 真实 MySQL HTTP 验收 | 36 项断言通过，最终 JAR 再次验收通过 |
| 数据库结构 | `student_no`、`name`，仅两个 VARCHAR 字段 |
| 数据持久化 | 新增临时记录，完全停止后端和独立 MySQL，再启动后仍能读到原记录 |
| 前端资源 | 首页 HTML、JS、CSS、favicon 均返回 HTTP 200 |
| 初始化幂等性 | 重复初始化保留已有数据库，启动现有实例 |
| 启停脚本 | 最终停止后，8080、3307 均未监听，本项目 Java 进程数量为 0 |
| 测试清理 | 临时学生和测试会话完成后清理，验收服务已停止 |

后端自动化测试使用 H2 的 MySQL 兼容模式；模型部分使用测试替身和本机测试 HTTP 服务。真实数据库验收连接本项目独立的 MySQL 8.0.34 实例，而不是 H2。

13 项测试覆盖：增删查改与前导零、重复学号、无效参数与分页、主键冲突回滚、无变化更新、重名查询和 LIKE 通配符转义、确认操作归属与重复确认、旧数据检测、取消操作、非法工具参数、请求重放和模型后续失败、每轮一个写操作、完整聊天业务流程、流式工具参数拼接、缺少/无效 Key 的协议错误、未完成的模型流。若干相关场景合并在同一个测试方法中。

## 尚未完成的外部验证

1. **真实 DeepSeek 调用**：本机未配置 API Key，未向线上模型发送付费请求。工具调用协议、模型错误处理和会话业务链路已通过测试；真实鉴权、账号余额、模型可用性及自然语言识别效果，需填入 Key 后验证。
2. **浏览器视觉与点击验收**：内嵌浏览器和 Windows Computer Use 均在启动时报告 `failed to write kernel assets: 系统找不到指定的路径 (os error 3)`。因此未声称完成截图、移动端显示或完整点击流程验收。前端类型检查、构建和静态资源 HTTP 检查已通过。

## 配置 Key 后的人工验收

使用不与现有数据冲突的学号，逐条尝试：

```text
添加学生，学号 20269901，姓名测试同学
查询学号 20269901 的学生
把学号 20269901 的学生姓名改成测试同学乙
```

第三条应显示待确认卡片。先取消并查询原记录，再发起修改并确认。最后请求删除并确认，核对“学生管理”页面与聊天查询一致。测试学号若已存在，请选择其他学号。

实际结果以工具卡片和传统页面查询为准。观察到失败或网络中断时先查询核实，不要反复发送写操作。

## 常见问题

| 情况 | 排查 |
|---|---|
| Key 未配置 | 编辑根目录 `.env` 的 `DEEPSEEK_API_KEY`，运行停止脚本后重新启动 |
| 401 | 检查 API Key；不要把密钥粘贴进聊天消息 |
| 402 | 检查 DeepSeek API 账户余额 |
| 400 / 404 模型配置错误 | 按官方文档检查 `DEEPSEEK_MODEL` 与 `DEEPSEEK_BASE_URL` |
| 数据库未连接 | 检查 3307 项目实例是否运行、`.env` 账号和密码是否正确；不要删掉数据目录后重新初始化 |
| 8080 被占用 | 停止正在运行的项目；如为其他应用，更改 `SERVER_PORT` 后重启 |
| 构建提示项目正在运行 | 先执行 `scripts/Stop.ps1 -KeepDatabase`，再构建 |
| Windows JAR 文件被占用 | 使用项目停止脚本，它会验证 JAR 路径并结束实际 JVM；脚本优先使用 `JAVA_HOME`，避免 Java 转发启动器遗留子进程 |
| 配置后仍使用旧值 | `.env` 由启动脚本读取；手动 `java -jar` 需要事先载入环境变量 |
| 重启后聊天记录消失 | 当前设计为内存会话；学生记录保存在 MySQL，不受影响 |

## 可复现命令

```powershell
# 重新构建并运行后端测试
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Build.ps1

# 启动已构建应用
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Start.ps1 -SkipBuild

# 真实数据库 HTTP 验收（不主动调用线上模型）
node .\scripts\smoke-test.mjs

# 停止本项目服务，保留数据
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Stop.ps1
```
