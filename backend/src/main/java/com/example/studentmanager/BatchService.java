package com.example.studentmanager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

@Service
public class BatchService {
    public static final int MAX_ROWS = 500;
    private final StudentService students;
    private final FieldCatalog catalog;
    public BatchService(StudentService students, FieldCatalog catalog) { this.students = students; this.catalog = catalog; }
    public static String mode(String mode) {
        String value = mode == null ? "" : mode.strip().toUpperCase(java.util.Locale.ROOT);
        if (!Set.of("CREATE", "UPDATE", "UPSERT", "DELETE", "QUERY").contains(value)) throw AppException.bad("不支持的批量操作类型");
        return value;
    }
    public BatchReport preview(String requestedMode, List<BatchReport.Input> inputs) {
        String mode = mode(requestedMode);
        if (inputs == null || inputs.isEmpty()) throw AppException.bad("请至少提供一条学生记录");
        if (inputs.size() > MAX_ROWS) throw AppException.bad("每批最多处理500条学生记录");
        Map<String, Integer> ids = new HashMap<>(), targets = new HashMap<>();
        for (BatchReport.Input input : inputs) {
            if (input == null) continue;
            if (input.studentNo() != null) ids.merge(input.studentNo().strip(), 1, Integer::sum);
            String target = input.newStudentNo() == null ? input.studentNo() : input.newStudentNo();
            if (target != null) targets.merge(target.strip(), 1, Integer::sum);
        }
        List<BatchReport.Row> rows = new ArrayList<>();
        for (int i = 0; i < inputs.size(); i++) {
            BatchReport.Input input = inputs.get(i);
            int row = input == null || input.row() <= 0 ? i + 1 : input.row();
            String id = input == null ? null : input.studentNo();
            String name = input == null ? null : input.name();
            String newId = input == null ? null : input.newStudentNo();
            try {
                if (input == null) throw AppException.bad("学生记录不能为空");
                if (input.error() != null) throw AppException.bad(input.error());
                id = StudentService.validate(id, "学号", 32);
                if (ids.getOrDefault(id, 0) > 1) throw AppException.bad("本批次学号重复，请去重后重新处理");
                String target = newId == null ? id : StudentService.validate(newId, "新学号", 32);
                if ((mode.equals("UPDATE") || mode.equals("UPSERT")) && targets.getOrDefault(target, 0) > 1) throw AppException.bad("本批次有多个记录使用相同的新学号");
                Student before = null;
                try { before = students.get(id); }
                catch (AppException ex) { if (!ex.code().equals("STUDENT_NOT_FOUND")) throw ex; }
                if (mode.equals("CREATE") && before != null) throw new AppException(org.springframework.http.HttpStatus.CONFLICT, "STUDENT_EXISTS", "学号已存在，未新增");
                if (Set.of("UPDATE", "DELETE", "QUERY").contains(mode) && before == null) throw new AppException(org.springframework.http.HttpStatus.NOT_FOUND, "STUDENT_NOT_FOUND", "学号不存在");
                if (mode.equals("QUERY")) {
                    rows.add(new BatchReport.Row(row, id, before.name(), null, before, null, "QUERY", "SUCCESS", "STUDENT_FOUND", "查询成功")); continue;
                }
                if (mode.equals("DELETE")) {
                    if (name != null && !before.name().equals(name.strip())) throw AppException.bad("提供的姓名与该学号当前记录不一致");
                    rows.add(new BatchReport.Row(row, id, before.name(), null, before, null, "DELETE", "READY", "READY", "待确认删除")); continue;
                }
                if (mode.equals("UPDATE") && name == null && newId == null && (input.fields() == null || input.fields().isEmpty())) throw AppException.bad("请提供需要修改的信息");
                String nextName = name == null && before != null && mode.equals("UPDATE") ? before.name() : name;
                Student base = StudentService.normalized(target, nextName);
                Student after = new Student(base.studentNo(), base.name(), catalog.normalize(input.fields(), before == null ? null : before.fields()));
                String operation = before == null ? "CREATE" : "UPDATE";
                if (before != null && !before.studentNo().equals(after.studentNo())) {
                    try { students.get(after.studentNo()); throw new AppException(org.springframework.http.HttpStatus.CONFLICT, "STUDENT_EXISTS", "目标学号已存在，不能更改学号"); }
                    catch (AppException ex) { if (!ex.code().equals("STUDENT_NOT_FOUND")) throw ex; }
                }
                boolean unchanged = after.equals(before);
                rows.add(new BatchReport.Row(row, id, after.name(), after.studentNo().equals(id) ? null : after.studentNo(), before, after, operation,
                    unchanged ? "UNCHANGED" : "READY", unchanged ? "UNCHANGED" : "READY", unchanged ? "信息相同，跳过" : "待确认"));
            } catch (AppException ex) {
                rows.add(new BatchReport.Row(row, id, name, newId, null, null, mode, "INVALID", ex.code(), ex.getMessage()));
            }
        }
        return BatchReport.of(mode, rows);
    }
    public OperationResult execute(String tool, BatchReport preview) {
        List<BatchReport.Row> rows = new ArrayList<>();
        for (BatchReport.Row row : preview.rows()) {
            if (!row.status().equals("READY")) { rows.add(row); continue; }
            try {
                switch (row.operation()) {
                    case "CREATE" -> students.create(row.after().studentNo(), row.after().name(), row.after().fields());
                    case "UPDATE" -> students.update(row.before().studentNo(), row.after().studentNo(), row.after().name(), row.before().name(), row.after().fields(), row.before().fields());
                    case "DELETE" -> students.delete(row.before().studentNo(), row.before().name(), row.before().fields());
                    default -> throw AppException.bad("批量操作类型无效");
                }
                rows.add(row.outcome("SUCCESS", "ROW_SUCCEEDED", "处理成功"));
            } catch (DuplicateKeyException ex) { rows.add(row.outcome("FAILED", "STUDENT_EXISTS", "学号已存在，原记录保持不变"));
            } catch (AppException ex) { rows.add(row.outcome("FAILED", ex.code(), ex.getMessage()));
            } catch (DataAccessException ex) { rows.add(row.outcome("FAILED", "DATABASE_ERROR", "数据库处理失败，请查询核实该条记录")); }
        }
        return result(tool, BatchReport.of(preview.mode(), rows));
    }
    public OperationResult result(String tool, BatchReport report) {
        String message = "共" + report.total() + "条，成功" + report.successCount() + "条，失败" + report.failureCount() + "条，跳过" + report.unchangedCount() + "条";
        return new OperationResult(tool, report.failureCount() == 0, report.failureCount() == 0 ? "BATCH_COMPLETED" : "BATCH_PARTIAL_FAILURE", message,
            report, null, !report.mode().equals("QUERY") && report.successCount() > 0);
    }
    public List<BatchReport.Input> parseText(String text) {
        if (text == null || text.isBlank()) throw AppException.bad("请输入需要处理的学生记录");
        if (text.length() > 30000) throw AppException.bad("批量文本最多30000个字符");
        List<BatchReport.Input> rows = new ArrayList<>();
        String[] lines = text.split("\\R", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].strip(); if (line.isEmpty()) continue;
            String[] cells = line.split("[\\s,，\\t]+", 2);
            if (cells[0].replace("\uFEFF", "").equals("学号") && cells.length > 1 && cells[1].strip().equals("姓名")) continue;
            rows.add(new BatchReport.Input(i + 1, cells[0], cells.length > 1 ? cells[1].strip() : null, null, null));
            if (rows.size() > MAX_ROWS) throw AppException.bad("每批最多处理500条学生记录");
        }
        return rows;
    }
}
