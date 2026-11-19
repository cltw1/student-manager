# API 与执行流程

默认地址：`http://127.0.0.1:8080`。所有接口仅面向本地使用。

学号格式为`^[1-9][0-9]{6}# API 与执行流程

默认地址：`http://127.0.0.1:8080`。所有接口仅面向本地使用。

，姓名最多15个Unicode字符。性别字段的`options`为`["男","女"]`，写入其他值返回400；重复学号返回409。

## 普通响应

```json
{"success":true,"code":"STUDENT_CREATED","message":"新增成功","data":{"studentNo":"2670001","name":"张三"},"requestId":"随机请求标识"}
```

业务失败返回对应 HTTP 状态并设置 `success=false`。主要状态：400 参数无效，404 学生/会话不存在，409 学号冲突或记录已变化，503 数据库不可用。内部异常细节不返回前端。

| 方法 | 路径 | 内容 |
|---|---|---|
| GET | `/api/status` | 数据库连接状态、总人数、API Key 是否已配置，不返回密钥 |
| GET | `/api/students?studentNo=&name=&page=1&pageSize=20` | 学号精确匹配、姓名包含匹配，两个条件同时提供时取交集 |
| GET | `/api/students/{studentNo}` | 唯一学号查询 |
| POST | `/api/students` | JSON：`studentNo`、`name`、自定义字段值`fields` |
| PUT | `/api/students/{oldStudentNo}` | JSON：新 `studentNo`、新 `name`、`fields`及原值`expectedName`、`expectedFields` |
| GET | `/api/fields` | 自定义正式字段列表：key、label、type、required |
| POST | `/api/students/search` | JSON：filters[{field,operator,value}]、page、pageSize；最多10个条件取交集 |
| DELETE | `/api/students/{studentNo}?expectedName=原姓名` | 删除；前端会带上原姓名进行变化检测 |
| GET / POST | `/api/conversations` | 列出/创建内存会话 |
| GET / DELETE | `/api/conversations/{id}` | 获取消息/删除会话，不删除学生 |
| POST | `/api/chat` | SSE 聊天 |
| POST | `/api/chat/actions/{id}/confirm` | JSON：`conversationId`，确认服务端保存的待办 |
| POST | `/api/chat/actions/{id}/cancel` | JSON：`conversationId`，取消待办 |

分页响应的 `data` 为 `items`、`total`、`page`、`pageSize`，每页最多 100 条。姓名中的 `%`、`_` 视为普通字符。

## 聊天事件

请求：

```json
{"conversationId":"已创建的会话ID","requestId":"本轮唯一ID","message":"查询姓名包含张的学生"}
```

使用 `fetch` POST 并读取 `text/event-stream`。事件：

| 事件 | 内容 |
|---|---|
| meta | 会话ID、标题、助手消息ID |
| phase | 当前处理状态 |
| trace | 服务端生成的公开处理摘要：id、title、detail、status、time；不是模型内部思维链 |
| delta | `text`：真实模型文字增量 |
| result | 真实工具执行结果或待确认卡片 |
| error | 错误代码和可读消息；已有成功结果继续有效 |
| done | 完整助手消息（含results结果卡片及steps处理摘要），前端以此校准最终状态 |

## AI 执行

`ChatController → ChatService → DeepSeekClient → ToolDispatcher → StudentService → MySQL`

DeepSeek 请求禁用 thinking，使用原生 `tools` 和 `tool_call_id`。接收完整工具参数后才进行参数校验和执行；不执行流式 JSON 片段。每轮最多5次模型调用、500条写入记录，支持多个工具和批量数组。工具返回值追加回对话，模型继续生成自然语言说明。每条消息最多30000字符，模型输出上限16384 token；超长或未完成的工具调用不执行。

白名单工具：`get_student`、`search_students`、`create_student`、`update_student`、`delete_student`。修改和删除工具创建服务端待办，按钮确认直接执行已保存的参数，不通过模型重新解释确认内容。

批量工具：`batch_create_students`、`batch_query_students`、`batch_update_students`、`batch_delete_students`。统一接收 `rows` 数组，每行 `studentNo` 为必需字符串；新增包含 `name`，修改允许 `name`、`newStudentNo`，删除可附 `name` 核对。批量修改、删除仍需确认。

字段工具：`get_student_fields`、`filter_students`。纠错工具：`prepare_student_operation`、`get_student_operation_review`、`revise_student_operation`。模型只能提出映射/修正，不会通过这些工具执行数据库操作。待处理草稿存在时，同会话直接写入工具会返回原草稿，避免绕过确认。

## 批量与Excel接口

| 方法 | 路径 | 内容 |
|---|---|---|
| POST | `/api/batches/preview` | JSON：`conversationId`、`mode`，以及`text`或`rows`；返回REVIEW_REQUIRED草稿引用 |
| POST | `/api/batches/excel` | multipart：`conversationId`、`mode`、`file`；返回REVIEW_REQUIRED草稿引用 |
| GET | `/api/batches/template` | 下载包含当前正式字段的.xlsx模板 |

JSON预览支持CREATE/UPDATE/UPSERT/DELETE/QUERY。文本支持Tab、逗号、中文逗号、引号及JSON对象数组，保留原列名。所有文本/Excel入口进入下述校对流程；常规AI工具的待办仍使用`/api/chat/actions`确认。

报告包含 `mode,total,successCount,failureCount,readyCount,unchangedCount,rows`。每行含原始行号、学号、姓名、修改前后快照、操作、状态、错误代码和消息。READY待确认，SUCCESS已成功，INVALID预览无效，FAILED执行失败，UNCHANGED相同跳过。部分失败以内部`OperationResult.success=false`及逐行报告表达；外层HTTP200表示批量请求已经正常处理，前端应检查内部结果。

每行分别使用学生服务事务，不采用整批回滚。确认时重新校验记录；原本不存在的学号若被其他操作新增，不会被导入自动覆盖。批量响应对前端保留所有行，对模型仅回传前20行及完整汇总，避免上下文过长。

SQL绑定参数，动态列名只接受服务端登记的合法标识。新增/修改/删除使用事务；修改/删除读取行锁，比较姓名及自定义字段快照，字段结构变化也会使旧预览失效。

重复确认返回已缓存结果。同一会话内重复 `requestId` 重放原有消息，不重复执行。进程重启后内存去重信息消失，数据库保留。

## 交互校对接口

所有接口必须携带创建该草稿的`conversationId`，修改/执行须带当前`revision`；版本冲突返回409。

| 方法 | 路径 | 内容 |
|---|---|---|
| POST | `/api/reviews/text` | conversationId、mode、text、可选hasHeader，返回完整ReviewView |
| POST | `/api/reviews/excel` | multipart：conversationId、mode、file |
| GET | `/api/reviews/{id}?conversationId=...` | 获取完整草稿 |
| PATCH | `/api/reviews/{id}?conversationId=...` | revision、mode、mappings、cells、excluded、backfill、confirmMapping、addColumn、action |
| POST | `/api/reviews/{id}/source` | conversationId、revision、sheetIndex(从0起)、headerRow(从1起)或hasHeader |
| POST | `/api/reviews/{id}/suggest` | conversationId、revision；DeepSeek仅提供映射建议 |
| POST | `/api/reviews/{id}/fields` | conversationId、revision；明确新增正式字段并保存全员补全值 |
| POST | `/api/reviews/{id}/finish` | conversationId、revision；执行冻结预览，或将补全子草稿合并回父草稿 |
| POST | `/api/reviews/{id}/cancel` | conversationId、revision；取消尚未执行部分 |
| GET | `/api/reviews/{id}/backfill-template?conversationId=...` | 全体已有学生的待补全Excel |
| POST | `/api/reviews/{id}/backfill` | multipart：conversationId、file；创建独立校对的补全子草稿 |
| POST | `/api/reviews/retry` | conversationId、report；只加载FAILED/INVALID行 |

`mappings`元素为`{column,target,label,type}`，column从0起，target为studentNo/name/newStudentNo/正式字段key/NEW/IGNORE。`cells`为`{row,column,value}`，row使用原始行号；`backfill`为`{studentNo,values:{字段key:值}}`。`excluded`是明确排除的全部行号；action支持REFRESH_BACKFILL、KEEP_FIRST_DUPLICATE。

状态：MAPPING→ROWS→BACKFILL/SCHEMA_READY→READY→COMPLETE；已通过的步骤会跳过。每个问题必须修正或明确排除后才能进入READY。字段迁移与最终学生操作独立确认，取消不会撤销此前已确认的字段。详见[字段说明](REVIEW.md)。

字段值放入`fields`对象。整数、小数、日期通过JSON字符串传输，避免浏览器数值精度丢失；布尔值使用true/false。支持操作符EQ、CONTAINS、GT、GTE、LT、LTE，布尔字段只支持EQ。
