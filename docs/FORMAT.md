# 学生数据格式规则

生效规则：学号为7位数字，首位1～9，不能重复；姓名为1～15个字符，允许重复；性别只能为“男”或“女”。

- 学号使用字符串保存，格式为`^[1-9][0-9]{6}$`。6位、8位、以0开头、字母、全角数字等不符合规则。
- 姓名按Unicode字符计数，不能为空；不限制同名学生。
- 正式字段“性别”（包括gender/sex命名）提供男/女选项。传统表单、导入修正表和补全表使用下拉选择；Excel导入模板也包含下拉列表。
- 前端校验、后端业务、AI工具参数及数据库约束共同执行规则。重复学号由数据库主键保证不会写入。
- 新的文本/Excel输入不符合规则时进入校对流程，不会自动补零、截断或猜测性别；英文male/female等仅提供需明确采用的建议。

## 本次已有数据整理

按用户确认的处理方式，原有学号不足7位在末尾补0，超过7位从末尾截短。转换前检查首位、数字格式及全表重号，有冲突就停止，不覆盖其他学生。

本次计划转换：

| 原学号 | 新学号 |
|---|---|
| 111111 | 1111110 |
| 12345 | 1234500 |
| 222222 | 2222220 |

其余4条学号已经合规；所有现有姓名和性别无需改值。最终迁移结果以`work/format-migration-*/result.json`为准。

迁移先生成SQL备份，再建立带约束的暂存表，锁定并复制全部记录，只转换学号，最后切换表名；原表以`student_format_backup_*`保留。迁移备份位于本机`work/`，不会提交GitHub。

## 其他已有数据库应用新规则

以下脚本仅处理本项目专用3307实例。新安装使用更新后的`database/schema.sql`；已有库先预览，再根据输出决定是否执行：

```powershell
Set-Location D:\JavaProject\student-manager
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Apply-StudentFormat.ps1
```

执行迁移前停止后端：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Stop.ps1 -KeepDatabase
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Apply-StudentFormat.ps1 -Apply
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Start.ps1 -SkipBuild
```

本机完成迁移后不需要再次执行。发现重号、无法按规则转换的学号、不合规姓名/性别、外键或触发器时，脚本会停止，保留原表供人工处理。完整备份表用于核对或由管理员恢复，不要直接覆盖正在使用的新表。
