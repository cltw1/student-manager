package com.example.studentmanager;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class StudentStore {
    private final JdbcTemplate jdbc;
    private final FieldCatalog catalog;
    private final SchemaGate gate;
    private final TransactionTemplate transaction;
    public StudentStore(JdbcTemplate jdbc, FieldCatalog catalog, SchemaGate gate, PlatformTransactionManager manager) { this.jdbc = jdbc; this.catalog = catalog; this.gate = gate; this.transaction = new TransactionTemplate(manager); }
    public <T> T read(Supplier<T> work) { gate.lock.readLock().lock(); try { return work.get(); } finally { gate.lock.readLock().unlock(); } }
    private <T> T write(Supplier<T> work) { return read(() -> transaction.execute(status -> work.get())); }
    public static Student from(java.sql.ResultSet rs, List<StudentField> definitions) throws java.sql.SQLException {
        Map<String, Object> fields = new LinkedHashMap<>();
        for (StudentField field : definitions) { Object value = rs.getObject(field.key()); if (field.type().equals("BOOLEAN") && value instanceof Number number) value = number.intValue() != 0; fields.put(field.key(), value == null ? null : field.normalize(value)); }
        return new Student(rs.getString("student_no"), rs.getString("name"), fields);
    }
    public static String columns(List<StudentField> definitions) { return "student_no, name" + definitions.stream().map(f -> ", " + FieldCatalog.quote(f.key())).collect(java.util.stream.Collectors.joining()); }
    private Student find(String id, boolean lock) {
        List<StudentField> fields = catalog.fields();
        List<Student> result = jdbc.query("SELECT " + columns(fields) + " FROM student WHERE student_no = ?" + (lock ? " FOR UPDATE" : ""), (rs, row) -> from(rs, fields), id);
        if (result.isEmpty()) throw new AppException(HttpStatus.NOT_FOUND, "STUDENT_NOT_FOUND", "学号 " + id + " 不存在");
        return result.get(0);
    }
    public Student get(String id) { return read(() -> find(StudentService.validate(id, "学号", 7), false)); }
    public List<Student> all() { return read(() -> { var fields = catalog.fields(); return jdbc.query("SELECT " + columns(fields) + " FROM student ORDER BY student_no", (rs, row) -> from(rs, fields)); }); }
    public StudentPage search(String studentNo, String name, int page, int pageSize) {
        return read(() -> {
            if (page < 1 || page > 100000 || pageSize < 1 || pageSize > 100) throw AppException.bad("页码必须为1～100000，每页条数必须为1～100");
            String where = " WHERE 1=1"; List<Object> args = new ArrayList<>();
            if (studentNo != null && !studentNo.isBlank()) { where += " AND student_no = ?"; args.add(StudentService.validate(studentNo, "学号", 7)); }
            if (name != null && !name.isBlank()) { where += " AND name LIKE ? ESCAPE '!'"; args.add("%" + StudentService.validate(name, "姓名", 15).replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%"); }
            Long count = jdbc.queryForObject("SELECT COUNT(*) FROM student" + where, Long.class, args.toArray());
            args.add(pageSize); args.add((page - 1) * pageSize); var fields = catalog.fields();
            return new StudentPage(jdbc.query("SELECT " + columns(fields) + " FROM student" + where + " ORDER BY student_no LIMIT ? OFFSET ?", (rs, row) -> from(rs, fields), args.toArray()), count == null ? 0 : count, page, pageSize);
        });
    }
    public Student create(String id, String name, Map<String, Object> input) {
        return write(() -> {
            Student normalized = StudentService.normalized(id, name); Map<String, Object> values = catalog.normalize(input, null); var definitions = catalog.fields();
            List<Object> args = new ArrayList<>(List.of(normalized.studentNo(), normalized.name())); for (StudentField field : definitions) args.add(values.get(field.key()));
            jdbc.update("INSERT INTO student (" + columns(definitions) + ") VALUES (" + java.util.Collections.nCopies(args.size(), "?").stream().collect(java.util.stream.Collectors.joining(",")) + ")", args.toArray());
            return new Student(normalized.studentNo(), normalized.name(), values);
        });
    }
    public StudentPage filter(List<StudentFilter> filters, int page, int size) {
        return read(() -> {
            if (page < 1 || page > 100000 || size < 1 || size > 100 || filters == null || filters.isEmpty() || filters.size() > 10) throw AppException.bad("请提供1～10个查询条件，每页最多100条");
            String where = " WHERE 1=1"; List<Object> args = new ArrayList<>(); var definitions = catalog.fields();
            for (StudentFilter filter : filters) {
                if (filter == null || filter.field() == null) throw AppException.bad("请选择有效的查询字段");
                StudentField definition = filter.field().equals("studentNo") ? new StudentField("student_no","学号","TEXT",true) : filter.field().equals("name") ? new StudentField("name","姓名","TEXT",true) : definitions.stream().filter(field -> field.key().equals(filter.field())).findFirst().orElseThrow(() -> AppException.bad("查询字段不存在，请重新选择现有字段"));
                String operator = filter.operator() == null ? "EQ" : filter.operator().toUpperCase(java.util.Locale.ROOT); String sqlOperator = switch (operator) { case "EQ" -> "="; case "GT" -> ">"; case "GTE" -> ">="; case "LT" -> "<"; case "LTE" -> "<="; case "CONTAINS" -> "LIKE"; default -> throw AppException.bad("查询比较方式不支持"); };
                Object value = definition.normalize(filter.value());
                if (definition.type().equals("BOOLEAN") && !operator.equals("EQ")) throw AppException.bad("是/否字段只支持相等查询");
                if (operator.equals("CONTAINS")) { if (!definition.type().equals("TEXT")) throw AppException.bad("包含查询只适用于文本字段"); value = "%" + value.toString().replace("!","!!").replace("%","!%").replace("_","!_") + "%"; }
                where += " AND " + FieldCatalog.quote(definition.key()) + " " + sqlOperator + " ?" + (operator.equals("CONTAINS") ? " ESCAPE '!'" : ""); args.add(value);
            }
            Long total = jdbc.queryForObject("SELECT COUNT(*) FROM student" + where,Long.class,args.toArray()); args.add(size); args.add((page-1)*size);
            return new StudentPage(jdbc.query("SELECT " + columns(definitions) + " FROM student" + where + " ORDER BY student_no LIMIT ? OFFSET ?",(rs,row)->from(rs,definitions),args.toArray()),total==null?0:total,page,size);
        });
    }
    public Student update(String id, String nextId, String name, String expectedName, Map<String, Object> input, Map<String, Object> expectedFields) {
        return write(() -> {
            Student current = find(StudentService.validate(id, "原学号", 7), true); snapshot(current, expectedName, expectedFields);
            Student normalized = StudentService.normalized(nextId, name); Map<String, Object> values = catalog.normalize(input, current.fields());
            String assignments = "student_no=?, name=?"; List<Object> args = new ArrayList<>(List.of(normalized.studentNo(), normalized.name()));
            for (StudentField field : catalog.fields()) { assignments += ", " + FieldCatalog.quote(field.key()) + "=?"; args.add(values.get(field.key())); }
            args.add(current.studentNo()); jdbc.update("UPDATE student SET " + assignments + " WHERE student_no=?", args.toArray());
            return new Student(normalized.studentNo(), normalized.name(), values);
        });
    }
    public Student delete(String id, String expectedName, Map<String, Object> expectedFields) {
        return write(() -> { Student current = find(StudentService.validate(id, "学号", 7), true); snapshot(current, expectedName, expectedFields); jdbc.update("DELETE FROM student WHERE student_no=?", current.studentNo()); return current; });
    }
    private void snapshot(Student current, String name, Map<String, Object> fields) {
        if ((name != null && !current.name().equals(name)) || (fields != null && !current.fields().equals(catalog.normalize(fields, null)))) throw new AppException(HttpStatus.CONFLICT, "STUDENT_CHANGED", "学生信息已发生变化，请刷新并重新确认");
    }
}
