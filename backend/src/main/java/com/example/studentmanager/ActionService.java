package com.example.studentmanager;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class ActionService {
    public record ActionView(String id, String type, Student before, Student after, Instant expiresAt, String status, BatchReport batch) {
        public ActionView(String id, String type, Student before, Student after, Instant expiresAt, String status) { this(id, type, before, after, expiresAt, status, null); }
    }
    private static class Pending {
        final String id = UUID.randomUUID().toString();
        final String owner;
        final String type;
        final Student before;
        final Student after;
        final BatchReport batch;
        final Instant expiresAt = Instant.now().plusSeconds(300);
        String status = "PENDING";
        OperationResult result;
        String version;
        Pending(String owner, String type, Student before, Student after) { this(owner, type, before, after, null); }
        Pending(String owner, String type, Student before, Student after, BatchReport batch) { this.owner = owner; this.type = type; this.before = before; this.after = after; this.batch = batch; }
        ActionView view() { return new ActionView(id, type, before, after, expiresAt, status, batch); }
    }
    private final Map<String, Pending> actions = new ConcurrentHashMap<>();
    private final StudentService students;
    private final BatchService batches;
    private final FieldCatalog catalog;
    public ActionService(StudentService students, BatchService batches, FieldCatalog catalog) { this.students = students; this.batches = batches; this.catalog = catalog; }

    public OperationResult prepareBatch(String owner, String mode, java.util.List<BatchReport.Input> rows) {
        BatchReport preview = batches.preview(mode, rows);
        String tool = "batch_" + preview.mode().toLowerCase(java.util.Locale.ROOT);
        if (preview.mode().equals("QUERY") || preview.readyCount() == 0) return batches.result(tool, preview);
        actions.entrySet().removeIf(e -> e.getValue().expiresAt.plusSeconds(3600).isBefore(Instant.now()));
        if (actions.size() >= 1000) throw AppException.bad("待办过多，请稍后重试");
        Pending pending = new Pending(owner, tool, null, null, preview); pending.version = catalog.version(); actions.put(pending.id, pending);
        return new OperationResult(tool, true, "CONFIRMATION_REQUIRED", "请核对批量预览，确认后处理" + preview.readyCount() + "条有效记录；无效记录会跳过", null, pending.view(), false);
    }

    public OperationResult prepare(String owner, String type, String studentNo, String nextStudentNo, String nextName) {
        return prepare(owner, type, studentNo, nextStudentNo, nextName, null);
    }
    public OperationResult prepare(String owner, String type, String studentNo, String nextStudentNo, String nextName, Map<String, Object> values) {
        actions.entrySet().removeIf(e -> e.getValue().expiresAt.plusSeconds(3600).isBefore(Instant.now()));
        if (actions.size() >= 1000) throw AppException.bad("待办过多，请稍后重试");
        Student before = students.get(studentNo);
        Student after = type.equals("update_student") ? new Student(StudentService.validate(nextStudentNo == null ? before.studentNo() : nextStudentNo, "学号", 7), StudentService.validate(nextName == null ? before.name() : nextName, "姓名", 15), catalog.normalize(values, before.fields())) : null;
        Pending pending = new Pending(owner, type, before, after);
        pending.version = catalog.version();
        actions.put(pending.id, pending);
        return new OperationResult(type, true, "CONFIRMATION_REQUIRED", "请核对信息并点击确认，当前尚未执行", null, pending.view(), false);
    }
    private Pending owned(String owner, String id) {
        Pending value = actions.get(id);
        if (value == null || !value.owner.equals(owner)) throw new AppException(HttpStatus.NOT_FOUND, "ACTION_NOT_FOUND", "待办不存在，请重新发起操作");
        return value;
    }
    public OperationResult confirm(String owner, String id) {
        Pending pending = owned(owner, id);
        synchronized (pending) {
            if (pending.result != null) return pending.result;
            if (!catalog.version().equals(pending.version)) {
                pending.status = "FAILED"; pending.result = OperationResult.failure(pending.type, "SCHEMA_CHANGED", "学生字段已变化，请重新生成预览并补全新字段"); return pending.result;
            }
            if (!pending.status.equals("PENDING")) throw AppException.bad("该操作已取消");
            if (pending.expiresAt.isBefore(Instant.now())) {
                pending.status = "EXPIRED";
                pending.result = OperationResult.failure(pending.type, "ACTION_EXPIRED", "确认已过期，请重新发起操作");
                return pending.result;
            }
            try {
                if (pending.batch != null) {
                    pending.result = batches.execute(pending.type, pending.batch);
                    pending.status = pending.result.success() ? "CONFIRMED" : pending.result.mutated() ? "PARTIAL" : "FAILED";
                    return pending.result;
                }
                Student result = pending.type.equals("delete_student") ? students.delete(pending.before.studentNo(), pending.before.name(), pending.before.fields())
                    : students.update(pending.before.studentNo(), pending.after.studentNo(), pending.after.name(), pending.before.name(), pending.after.fields(), pending.before.fields());
                pending.status = "CONFIRMED";
                pending.result = OperationResult.success(pending.type, pending.type.equals("delete_student") ? "STUDENT_DELETED" : "STUDENT_UPDATED",
                    pending.type.equals("delete_student") ? "删除成功" : "修改成功", result, true);
            } catch (DuplicateKeyException ex) {
                pending.status = "FAILED"; pending.result = OperationResult.failure(pending.type, "STUDENT_EXISTS", "目标学号已存在，原记录保持不变");
            } catch (AppException ex) {
                pending.status = "FAILED"; pending.result = OperationResult.failure(pending.type, ex.code(), ex.getMessage());
            } catch (DataAccessException ex) {
                pending.status = "FAILED"; pending.result = OperationResult.failure(pending.type, "DATABASE_UNAVAILABLE", "数据库处理失败，请刷新核实记录后重新操作");
            }
            return pending.result;
        }
    }
    public OperationResult cancel(String owner, String id) {
        Pending pending = owned(owner, id);
        synchronized (pending) {
            if (pending.result != null) return pending.result;
            pending.status = "CANCELLED";
            pending.result = OperationResult.success(pending.type, "ACTION_CANCELLED", "已取消，未修改学生信息", null, false);
            return pending.result;
        }
    }
}
