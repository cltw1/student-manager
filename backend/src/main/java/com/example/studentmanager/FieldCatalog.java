package com.example.studentmanager;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.stereotype.Service;

@Service
public class FieldCatalog {
    private final DataSource datasource;
    private final ObjectMapper mapper;
    private volatile List<StudentField> cached;
    private volatile long loadedAt;
    public FieldCatalog(DataSource datasource, ObjectMapper mapper) { this.datasource = datasource; this.mapper = mapper; }
    public synchronized List<StudentField> fields() {
        if (cached != null && System.currentTimeMillis() - loadedAt < 2000) return cached;
        try (Connection connection = datasource.getConnection()) { cached = inspect(connection); loadedAt = System.currentTimeMillis(); return cached; }
        catch (Exception ex) { throw new AppException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "SCHEMA_UNAVAILABLE", "无法读取学生字段，请检查数据库连接"); }
    }
    public List<StudentField> inspect(Connection connection) throws Exception {
        List<StudentField> fields = new ArrayList<>();
        try (var columns = connection.getMetaData().getColumns(connection.getCatalog(), null, "student", null)) {
            while (columns.next()) {
                String name = columns.getString("COLUMN_NAME");
                if (!columns.getString("TABLE_NAME").equalsIgnoreCase("student") || name.equalsIgnoreCase("student_no") || name.equalsIgnoreCase("name")) continue;
                quote(name);
                String remarks = columns.getString("REMARKS");
                if (remarks != null && remarks.startsWith("student-field:")) {
                    var definition = mapper.readTree(new String(Base64.getUrlDecoder().decode(remarks.substring(14)), StandardCharsets.UTF_8));
                    fields.add(new StudentField(name, definition.path("label").asText(), StudentField.type(definition.path("type").asText()), columns.getInt("NULLABLE") == 0));
                } else {
                    String type = switch (columns.getInt("DATA_TYPE")) { case Types.BIGINT, Types.INTEGER, Types.SMALLINT -> "INTEGER"; case Types.DECIMAL, Types.NUMERIC, Types.DOUBLE, Types.FLOAT -> "DECIMAL"; case Types.DATE -> "DATE"; case Types.BOOLEAN, Types.BIT, Types.TINYINT -> "BOOLEAN"; default -> "TEXT"; };
                    fields.add(new StudentField(name, remarks == null || remarks.isBlank() ? name : remarks, type, columns.getInt("NULLABLE") == 0));
                }
            }
        }
        return List.copyOf(fields);
    }
    public synchronized void invalidate() { cached = null; loadedAt = 0; }
    public String comment(StudentField field) {
        try { return "student-field:" + Base64.getUrlEncoder().withoutPadding().encodeToString(mapper.writeValueAsBytes(Map.of("label", field.label(), "type", field.type()))); }
        catch (Exception ex) { throw new IllegalStateException(ex); }
    }
    public static String quote(String name) { if (name == null || !name.matches("[a-zA-Z][a-zA-Z0-9_]{0,63}")) throw AppException.bad("数据库字段标识不规范，请联系管理员处理"); return "`" + name + "`"; }
    public Map<String, Object> normalize(Map<String, Object> values, Map<String, Object> previous) {
        List<StudentField> definitions = fields(); Map<String, Object> result = new LinkedHashMap<>();
        if (values != null) for (String key : values.keySet()) if (definitions.stream().noneMatch(f -> f.key().equals(key))) throw AppException.bad("存在未登记的字段，请先确认字段映射或新增字段");
        for (StudentField field : definitions) result.put(field.key(), field.normalize(values != null && values.containsKey(field.key()) ? values.get(field.key()) : previous == null ? null : previous.get(field.key())));
        return java.util.Collections.unmodifiableMap(result);
    }
    public String version() { return fields().toString(); }
}
