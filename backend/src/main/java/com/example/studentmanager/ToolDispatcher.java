package com.example.studentmanager;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

@Service
public class ToolDispatcher {
    private final StudentService students;
    private final ActionService actions;
    private final ObjectMapper mapper;
    private final BatchService batches;
    private final FieldCatalog catalog;
    private final ReviewService reviews;
    public ToolDispatcher(StudentService students, ActionService actions, ObjectMapper mapper, BatchService batches, FieldCatalog catalog, ReviewService reviews) { this.students = students; this.actions = actions; this.mapper = mapper; this.batches = batches; this.catalog = catalog; this.reviews = reviews; }
    private static final String SCHEMA = """
      [
        {"type":"function","function":{"name":"get_student","description":"按用户明确给出的唯一学号查询学生。","parameters":{"type":"object","properties":{"studentNo":{"type":"string"}},"required":["studentNo"],"additionalProperties":false}}},
        {"type":"function","function":{"name":"search_students","description":"分页查询学生。name为姓名包含匹配；省略name则查看所有学生。不能用姓名直接修改或删除。","parameters":{"type":"object","properties":{"name":{"type":"string"},"page":{"type":"integer","minimum":1},"pageSize":{"type":"integer","minimum":1,"maximum":100}},"additionalProperties":false}}},
        {"type":"function","function":{"name":"create_student","description":"新增一名学生。学号和姓名必须由用户明确提供，缺少信息必须追问。","parameters":{"type":"object","properties":{"studentNo":{"type":"string"},"name":{"type":"string"}},"required":["studentNo","name"],"additionalProperties":false}}},
        {"type":"function","function":{"name":"update_student","description":"根据用户指定或确认选择的学号生成修改预览，等待用户在页面点击确认。允许修改姓名和学号；未修改字段可省略。","parameters":{"type":"object","properties":{"studentNo":{"type":"string"},"newStudentNo":{"type":"string"},"name":{"type":"string"}},"required":["studentNo"],"additionalProperties":false}}},
        {"type":"function","function":{"name":"delete_student","description":"根据用户指定或确认选择的唯一学号生成删除预览，等待页面点击确认。不能根据姓名猜测学号。","parameters":{"type":"object","properties":{"studentNo":{"type":"string"}},"required":["studentNo"],"additionalProperties":false}}}
      ]
      """;
    public ArrayNode definitions() {
        try {
            ArrayNode result = (ArrayNode) mapper.readTree(SCHEMA);
            for (String mode : java.util.List.of("create", "update", "delete", "query")) {
                var function = result.addObject().put("type", "function").putObject("function");
                function.put("name", "batch_" + mode + "_students");
                function.put("description", switch (mode) {
                    case "create" -> "批量新增学生，一次提交用户声明的所有记录；每行学号和姓名都必须来自用户，逐行反馈重复和错误。";
                    case "update" -> "批量修改学生，一次提交全部修改，生成确认卡片；studentNo是原学号，name是新姓名，可选newStudentNo更正学号。";
                    case "delete" -> "按用户指定的学号列表批量删除学生，先生成待确认卡片；可附姓名核对，不能猜测学号。";
                    default -> "按多个学号批量查询，一次返回每个学号的查询结果或不存在提示。";
                });
                var parameters = function.putObject("parameters"); parameters.put("type", "object"); parameters.put("additionalProperties", false); parameters.putArray("required").add("rows");
                var rows = parameters.putObject("properties").putObject("rows"); rows.put("type", "array").put("minItems", 1).put("maxItems", BatchService.MAX_ROWS);
                var item = rows.putObject("items"); item.put("type", "object").put("additionalProperties", false); item.putArray("required").add("studentNo");
                var props = item.putObject("properties"); props.putObject("studentNo").put("type", "string");
                if (!mode.equals("query")) props.putObject("name").put("type", "string");
                if (mode.equals("create")) ((ArrayNode) item.get("required")).add("name");
                if (mode.equals("update")) props.putObject("newStudentNo").put("type", "string");
            }
            var prepare = result.addObject().put("type", "function").putObject("function"); prepare.put("name", "prepare_student_operation"); prepare.put("description", "字段别名、缺失值、额外字段、重复或含义不明时，保留用户原字段名和全部数据，生成让用户确认和补全的纠错界面，不执行写入。rows优先于text；不能自行改名或丢弃列。");
            var parameters = prepare.putObject("parameters"); parameters.put("type", "object"); parameters.putArray("required").add("mode");
            var properties = parameters.putObject("properties"); properties.putObject("mode").put("type", "string").putArray("enum").add("CREATE").add("UPDATE").add("UPSERT").add("DELETE").add("QUERY"); properties.putObject("text").put("type", "string"); properties.putObject("rows").put("type", "array").putObject("items").put("type", "object").put("additionalProperties", true);
            var fieldTool = result.addObject().put("type", "function").putObject("function"); fieldTool.put("name", "get_student_fields"); fieldTool.put("description", "读取当前学生字段的key、名称、类型和必填要求"); fieldTool.putObject("parameters").put("type", "object").putObject("properties");
            var filterTool = result.addObject().put("type","function").putObject("function"); filterTool.put("name","filter_students").put("description","按现有正式字段查询学生，例如班级、年龄、日期等。先读取字段key，最多10个条件取交集，支持EQ、CONTAINS、GT、GTE、LT、LTE，布尔字段只支持EQ。"); var fp=filterTool.putObject("parameters").put("type","object"); fp.putArray("required").add("filters");var fprops=fp.putObject("properties");fprops.putObject("page").put("type","integer");fprops.putObject("pageSize").put("type","integer");var fi=fprops.putObject("filters").put("type","array").putObject("items").put("type","object");fi.putArray("required").add("field").add("operator").add("value");var fip=fi.putObject("properties");fip.putObject("field").put("type","string");fip.putObject("operator").put("type","string").putArray("enum").add("EQ").add("CONTAINS").add("GT").add("GTE").add("LT").add("LTE");fip.putObject("value").put("type","string");
            var readReview = result.addObject().put("type", "function").putObject("function"); readReview.put("name", "get_student_operation_review").put("description", "读取当前会话待处理草稿的列、原始记录、问题和待确认项，以便理解用户后续回复。reviewId可省略，读取最新待处理草稿。"); var rp = readReview.putObject("parameters").put("type", "object").putObject("properties"); rp.putObject("reviewId").put("type", "string");
            var revise = result.addObject().put("type", "function").putObject("function"); revise.put("name", "revise_student_operation").put("description", "根据用户明确的回复修正已有草稿，保留原始列编号和行号。不执行数据库操作，最终仍需用户点击界面确认。映射和缺失值不能自行臆造；无法确定时先追问。");
            var vp = revise.putObject("parameters").put("type", "object"); vp.putArray("required").add("reviewId"); var properties2 = vp.putObject("properties"); properties2.putObject("reviewId").put("type", "string"); properties2.putObject("mode").put("type", "string"); properties2.putObject("confirmMapping").put("type", "boolean");
            var mapItems = properties2.putObject("mappings").put("type", "array").putObject("items").put("type", "object"); var mp = mapItems.putObject("properties"); mp.putObject("column").put("type", "integer"); mp.putObject("target").put("type", "string"); mp.putObject("label").put("type", "string"); mp.putObject("type").put("type", "string"); mapItems.putArray("required").add("column").add("target");
            var cellItems = properties2.putObject("cells").put("type", "array").putObject("items").put("type", "object"); var cp = cellItems.putObject("properties"); cp.putObject("row").put("type", "integer"); cp.putObject("column").put("type", "integer"); cp.putObject("value").put("type", "string"); cellItems.putArray("required").add("row").add("column").add("value"); properties2.putObject("excluded").put("type", "array").putObject("items").put("type", "integer");
            for (var item : result) {
                String name = item.path("function").path("name").asText();
                com.fasterxml.jackson.databind.node.ObjectNode props;
                if (name.equals("create_student") || name.equals("update_student")) props = (com.fasterxml.jackson.databind.node.ObjectNode) item.path("function").path("parameters").path("properties");
                else if (name.equals("batch_create_students") || name.equals("batch_update_students")) props = (com.fasterxml.jackson.databind.node.ObjectNode) item.path("function").path("parameters").path("properties").path("rows").path("items").path("properties");
                else continue;
                var extra = props.putObject("fields"); extra.put("type", "object").put("additionalProperties", false); var fieldProps = extra.putObject("properties");
                for (StudentField field : catalog.fields()) fieldProps.putObject(field.key()).put("type", field.type().equals("BOOLEAN") ? "boolean" : "string").put("description", field.label() + (field.type().equals("DATE") ? "，格式yyyy-MM-dd" : field.type().equals("INTEGER") || field.type().equals("DECIMAL") ? "，请用十进制字符串，保留完整精度" : ""));
            }
            return result;
        } catch (Exception ex) { throw new IllegalStateException(ex); }
    }
    public OperationResult execute(String owner, String tool, String arguments) {
        try {
            JsonNode args = mapper.readTree(arguments);
            if (args == null || !args.isObject()) throw AppException.bad("工具参数必须为JSON对象");
            if (tool.equals("get_student_operation_review")) {
                String id = text(args, "reviewId", false); if (id == null) id = reviews.pending(owner).stream().findFirst().orElseThrow(() -> AppException.bad("当前没有待处理草稿")).reviewId();
                ReviewView view = reviews.get(owner, id); var summary = mapper.valueToTree(view); ((com.fasterxml.jackson.databind.node.ObjectNode)summary).remove("existingStudents"); ((com.fasterxml.jackson.databind.node.ObjectNode)summary).remove("backfill");
                var rows = mapper.createArrayNode(); view.rows().stream().limit(20).forEach(row -> rows.add(mapper.valueToTree(row))); ((com.fasterxml.jackson.databind.node.ObjectNode)summary).set("rows",rows);
                return OperationResult.success(tool,"REVIEW_FOUND","草稿状态已读取，未执行写入",summary,false);
            }
            if (tool.equals("revise_student_operation")) {
                String id = text(args,"reviewId",true); var view = reviews.get(owner,id);
                var mapping = args.has("mappings") ? mapper.convertValue(args.get("mappings"),new com.fasterxml.jackson.core.type.TypeReference<java.util.List<ReviewView.Mapping>>() {}) : null;
                var cells = args.has("cells") ? mapper.convertValue(args.get("cells"),new com.fasterxml.jackson.core.type.TypeReference<java.util.List<ReviewView.CellEdit>>() {}) : null;
                var excluded = args.has("excluded") ? mapper.convertValue(args.get("excluded"),new com.fasterxml.jackson.core.type.TypeReference<java.util.List<Integer>>() {}) : null;
                return reviews.reference(reviews.patch(owner,id,new ReviewView.Patch(view.revision(),text(args,"mode",false),mapping,cells,excluded,null,args.path("confirmMapping").asBoolean(false),null,null)));
            }
            boolean writes = java.util.Set.of("create_student","update_student","delete_student","batch_create_students","batch_update_students","batch_delete_students","prepare_student_operation").contains(tool);
            if (writes && !reviews.pending(owner).isEmpty()) return reviews.reference(reviews.get(owner,reviews.pending(owner).get(0).reviewId()));
            if (tool.equals("prepare_student_operation")) {
                ReviewView view = args.path("rows").isArray() ? reviews.objects(owner, text(args, "mode", true), args.get("rows")) : reviews.text(owner, text(args, "mode", true), text(args, "text", true), null);
                return reviews.reference(view);
            }
            if (tool.equals("get_student_fields")) return OperationResult.success(tool, "FIELDS_FOUND", "字段定义已读取", catalog.fields(), false);
            if (tool.equals("filter_students")) { var filters=mapper.convertValue(args.get("filters"),new com.fasterxml.jackson.core.type.TypeReference<java.util.List<StudentFilter>>(){});var result=students.filter(filters,number(args,"page",1),number(args,"pageSize",20));return OperationResult.success(tool,"STUDENTS_FOUND","查询成功，共"+result.total()+"条",result,false); }
            Set<String> allowed = switch (tool) {
                case "get_student", "delete_student" -> Set.of("studentNo");
                case "create_student" -> Set.of("studentNo", "name", "fields");
                case "update_student" -> Set.of("studentNo", "newStudentNo", "name", "fields");
                case "search_students" -> Set.of("name", "page", "pageSize");
                case "batch_create_students", "batch_update_students", "batch_delete_students", "batch_query_students" -> Set.of("rows");
                default -> throw AppException.bad("不支持的工具名称");
            };
            Iterator<String> fields = args.fieldNames();
            while (fields.hasNext()) if (!allowed.contains(fields.next())) throw AppException.bad("工具包含未定义的参数");
            return switch (tool) {
                case "batch_create_students" -> executeBatch(owner, tool, "CREATE", args);
                case "batch_query_students" -> batches.result(tool, batches.preview("QUERY", batchInputs(args, tool)));
                case "batch_update_students" -> executeBatch(owner, tool, "UPDATE", args);
                case "batch_delete_students" -> executeBatch(owner, tool, "DELETE", args);
                case "get_student" -> OperationResult.success(tool, "STUDENT_FOUND", "查询成功", students.get(text(args, "studentNo", true)), false);
                case "search_students" -> {
                    StudentPage result = students.search(null, text(args, "name", false), number(args, "page", 1), number(args, "pageSize", 20));
                    yield OperationResult.success(tool, "STUDENTS_FOUND", "查询成功，共 " + result.total() + " 条记录", result, false);
                }
                case "create_student" -> OperationResult.success(tool, "STUDENT_CREATED", "新增成功", students.create(text(args, "studentNo", true), text(args, "name", true), values(args)), true);
                case "update_student" -> {
                    if (!args.has("name") && !args.has("newStudentNo") && !args.has("fields")) throw AppException.bad("请提供需要修改的信息");
                    yield actions.prepare(owner, tool, text(args, "studentNo", true), text(args, "newStudentNo", false), text(args, "name", false), values(args));
                }
                case "delete_student" -> actions.prepare(owner, tool, text(args, "studentNo", true), null, null);
                default -> throw AppException.bad("不支持的工具");
            };
        } catch (DuplicateKeyException ex) { return recover(owner, tool, arguments, "STUDENT_EXISTS", "学号已存在，是想更新该学生吗？请在校对界面选择");
        } catch (AppException ex) { return recover(owner, tool, arguments, ex.code(), ex.getMessage());
        } catch (DataAccessException ex) { return OperationResult.failure(tool, "DATABASE_UNAVAILABLE", "数据库处理失败，请刷新核实记录");
        } catch (Exception ex) { return OperationResult.failure(tool, "INVALID_TOOL_ARGUMENTS", "模型生成的参数无效，本次工具未执行"); }
    }
    public OperationResult originalReview(String owner, String text, String tool) {
        if (!reviews.requiresMapping(text)) return null;
        if (!reviews.pending(owner).isEmpty()) return reviews.reference(reviews.get(owner,reviews.pending(owner).get(0).reviewId()));
        try { String mode = tool.contains("create") ? "CREATE" : tool.contains("update") ? "UPDATE" : tool.contains("delete") ? "DELETE" : "QUERY"; return reviews.reference(reviews.text(owner,mode,text,null)); } catch (AppException ex) { return OperationResult.failure(tool,ex.code(),ex.getMessage()); }
    }
    private java.util.List<BatchReport.Input> batchInputs(JsonNode args, String tool) {
        JsonNode array = args.path("rows");
        if (!array.isArray() || array.isEmpty() || array.size() > BatchService.MAX_ROWS) throw AppException.bad("批量rows必须为1～500条记录的数组");
        java.util.List<BatchReport.Input> rows = new java.util.ArrayList<>();
        Set<String> allowed = tool.equals("batch_query_students") ? Set.of("studentNo") : tool.equals("batch_update_students") ? Set.of("studentNo", "name", "newStudentNo", "fields") : Set.of("studentNo", "name", "fields");
        for (JsonNode item : array) {
            int row = rows.size() + 1;
            try {
                if (!item.isObject()) throw AppException.bad("每条学生记录必须为对象");
                var fields = item.fieldNames(); while (fields.hasNext()) if (!allowed.contains(fields.next())) throw AppException.bad("记录包含未定义参数");
                rows.add(new BatchReport.Input(row, text(item, "studentNo", true), text(item, "name", false), text(item, "newStudentNo", false), null, values(item)));
            } catch (AppException ex) { rows.add(new BatchReport.Input(row, item.path("studentNo").isTextual() ? item.get("studentNo").asText() : null, null, null, ex.getMessage())); }
        }
        return rows;
    }
    private OperationResult executeBatch(String owner, String tool, String mode, JsonNode args) {
        var preview = batches.preview(mode, batchInputs(args, tool));
        if (preview.failureCount() > 0) return reviews.reference(reviews.objects(owner, mode, flatten(args.path("rows"))));
        return mode.equals("CREATE") ? batches.execute(tool, preview) : actions.prepareBatch(owner, mode, batchInputs(args, tool));
    }
    private java.util.Map<String, Object> values(JsonNode args) {
        if (!args.has("fields")) return null; if (!args.get("fields").isObject()) throw AppException.bad("fields必须为字段对象");
        return mapper.convertValue(args.get("fields"), new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, Object>>() {});
    }
    private ArrayNode flatten(JsonNode rows) {
        ArrayNode result = mapper.createArrayNode();
        for (JsonNode row : rows) { if (!row.isObject()) { result.add(row); continue; } var object = result.addObject(); row.fields().forEachRemaining(entry -> { if (!entry.getKey().equals("fields")) object.set(entry.getKey(), entry.getValue()); });
            row.path("fields").fields().forEachRemaining(entry -> { String label = catalog.fields().stream().filter(field -> field.key().equals(entry.getKey())).map(StudentField::label).findFirst().orElse(entry.getKey()); object.set(label, entry.getValue()); }); }
        return result;
    }
    private OperationResult recover(String owner, String tool, String arguments, String code, String message) {
        if (Set.of("create_student", "update_student", "delete_student", "get_student", "batch_create_students", "batch_update_students", "batch_delete_students", "batch_query_students").contains(tool)) try {
            JsonNode args = mapper.readTree(arguments); String mode = tool.contains("create") ? "CREATE" : tool.contains("update") ? "UPDATE" : tool.contains("delete") ? "DELETE" : "QUERY";
            ArrayNode rows = args.path("rows").isArray() ? (ArrayNode) args.get("rows") : mapper.createArrayNode().add(args);
            return reviews.reference(reviews.objects(owner, mode, flatten(rows)));
        } catch (Exception ignored) {}
        return OperationResult.failure(tool, code, message);
    }
    private String text(JsonNode args, String key, boolean required) {
        JsonNode value = args.get(key);
        if (value == null) { if (required) throw AppException.bad("缺少参数：" + key); return null; }
        if (!value.isTextual()) throw AppException.bad(key + "必须为字符串");
        return value.asText();
    }
    private int number(JsonNode args, String key, int fallback) {
        JsonNode value = args.get(key);
        if (value == null) return fallback;
        if (!value.isIntegralNumber() || !value.canConvertToInt()) throw AppException.bad(key + "必须为整数");
        return value.asInt();
    }
}
