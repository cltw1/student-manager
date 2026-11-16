package com.example.studentmanager;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class SchemaService {
    public record Change(List<StudentField> fields, int backfilled, String backupTable) {}
    private final DataSource datasource;
    private final FieldCatalog catalog;
    private final SchemaGate gate;
    private final StudentService students;
    private final ObjectMapper mapper;
    public SchemaService(DataSource datasource, FieldCatalog catalog, SchemaGate gate, StudentService students, ObjectMapper mapper) {
        this.datasource = datasource; this.catalog = catalog; this.gate = gate; this.students = students; this.mapper = mapper;
    }
    public String fingerprint() { return fingerprint(catalog.fields(), students.all()); }
    private String fingerprint(List<StudentField> fields, List<Student> students) {
        try { return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(List.of(fields, students)))); }
        catch (Exception ex) { throw new IllegalStateException(ex); }
    }
    public Change add(List<StudentField> additions, String expectedFingerprint, Map<String, Map<String, Object>> backfill) {
        if (additions == null || additions.isEmpty()) throw AppException.bad("没有待新增字段");
        gate.lock.writeLock().lock();
        String token = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String stage = "student_stage_" + token, backup = "student_backup_" + token;
        boolean created = false, swapped = false;
        try (Connection connection = datasource.getConnection()) {
            connection.setAutoCommit(true);
            List<StudentField> old = catalog.inspect(connection);
            if (old.size() + additions.size() > 20) throw AppException.bad("最多支持20个自定义学生字段");
            java.util.Set<String> labels = new java.util.HashSet<>(List.of("学号", "姓名"));
            old.forEach(field -> labels.add(field.label().toLowerCase(java.util.Locale.ROOT)));
            for (StudentField field : additions) { FieldCatalog.quote(field.key()); StudentField.label(field.label()); StudentField.type(field.type()); if (!labels.add(field.label().toLowerCase(java.util.Locale.ROOT))) throw AppException.bad("字段名称重复，请重新选择映射"); }
            boolean mysql = connection.getMetaData().getDatabaseProductName().toLowerCase(java.util.Locale.ROOT).contains("mysql");
            if (mysql) {
                try (var imported = connection.getMetaData().getImportedKeys(connection.getCatalog(), null, "student"); var exported = connection.getMetaData().getExportedKeys(connection.getCatalog(), null, "student")) {
                    if (imported.next() || exported.next()) throw AppException.bad("学生表存在外键，当前不能自动扩展字段，请联系管理员处理表依赖");
                }
                try (var statement = connection.prepareStatement("SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE() AND EVENT_OBJECT_TABLE='student'"); var result = statement.executeQuery()) {
                    if (result.next() && result.getInt(1) > 0) throw AppException.bad("学生表存在触发器，不能直接自动扩展，请联系管理员确认迁移方式");
                }
                execute(connection, "CREATE TABLE " + FieldCatalog.quote(stage) + " LIKE student");
            } else {
                execute(connection, "CREATE TABLE " + FieldCatalog.quote(stage) + " (student_no VARCHAR(32) PRIMARY KEY, name VARCHAR(50) NOT NULL" + old.stream().map(field -> ", " + definition(field)).collect(java.util.stream.Collectors.joining()) + ")");
            }
            created = true;
            for (StudentField field : additions) execute(connection, "ALTER TABLE " + FieldCatalog.quote(stage) + " ADD COLUMN " + definition(field));
            boolean locked = false;
            try {
                if (mysql) { execute(connection, "LOCK TABLES student WRITE, " + FieldCatalog.quote(stage) + " WRITE"); locked = true; }
                List<Student> current = new ArrayList<>();
                try (var statement = connection.prepareStatement("SELECT " + StudentStore.columns(old) + " FROM student ORDER BY student_no"); var result = statement.executeQuery()) { while (result.next()) current.add(StudentStore.from(result, old)); }
                if (!fingerprint(old, current).equals(expectedFingerprint)) throw new AppException(HttpStatus.CONFLICT, "DATASET_CHANGED", "学生数据或字段已变化，请刷新待补全名单并重新校验；尚未新增字段");
                List<StudentField> all = new ArrayList<>(old); all.addAll(additions);
                String sql = "INSERT INTO " + FieldCatalog.quote(stage) + " (" + StudentStore.columns(all) + ") VALUES (" + String.join(",", java.util.Collections.nCopies(all.size() + 2, "?")) + ")";
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    for (Student student : current) {
                        Map<String, Object> values = backfill == null ? null : backfill.get(student.studentNo());
                        statement.setString(1, student.studentNo()); statement.setString(2, student.name()); int column = 3;
                        for (StudentField field : old) statement.setObject(column++, student.fields().get(field.key()));
                        for (StudentField field : additions) statement.setObject(column++, field.normalize(values == null ? null : values.get(field.key())));
                        statement.addBatch();
                    }
                    statement.executeBatch();
                }
                if (mysql) execute(connection, "RENAME TABLE student TO " + FieldCatalog.quote(backup) + ", " + FieldCatalog.quote(stage) + " TO student");
                else { execute(connection, "ALTER TABLE student RENAME TO " + FieldCatalog.quote(backup)); try { execute(connection, "ALTER TABLE " + FieldCatalog.quote(stage) + " RENAME TO student"); } catch (Exception ex) { execute(connection, "ALTER TABLE " + FieldCatalog.quote(backup) + " RENAME TO student"); throw ex; } }
                swapped = true; catalog.invalidate();
                return new Change(List.copyOf(all), current.size(), backup);
            } finally { if (locked) execute(connection, "UNLOCK TABLES"); }
        } catch (AppException ex) { throw ex;
        } catch (Exception ex) {
            throw new AppException(HttpStatus.SERVICE_UNAVAILABLE, "SCHEMA_CHANGE_FAILED", "字段扩展未完成，请检查数据库结构权限和连接；原表备份会保留，请刷新核实字段状态");
        } finally {
            if (created && !swapped) try (Connection cleanup = datasource.getConnection()) { execute(cleanup, "DROP TABLE IF EXISTS " + FieldCatalog.quote(stage)); } catch (Exception ignored) {}
            catalog.invalidate(); gate.lock.writeLock().unlock();
        }
    }
    private String definition(StudentField field) { return FieldCatalog.quote(field.key()) + " " + field.sqlType() + (field.required() ? " NOT NULL" : " NULL") + " COMMENT '" + catalog.comment(field) + "'"; }
    private void execute(Connection connection, String sql) throws Exception { try (var statement = connection.createStatement()) { statement.setQueryTimeout(30); statement.execute(sql); } }
}
