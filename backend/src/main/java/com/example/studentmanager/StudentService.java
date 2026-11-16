package com.example.studentmanager;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class StudentService {
    private final StudentStore store;
    public StudentService(StudentStore store) { this.store = store; }
    public StudentPage search(String id, String name, int page, int pageSize) { return store.search(id, name, page, pageSize); }
    public Student get(String id) { return store.get(id); }
    public List<Student> all() { return store.all(); }
    public StudentPage filter(List<StudentFilter> filters, int page, int size) { return store.filter(filters,page,size); }
    public Student create(String id, String name) { return create(id, name, null); }
    public Student create(String id, String name, Map<String, Object> fields) { return store.create(id, name, fields); }
    public Student update(String id, String nextId, String name, String expectedName) { return update(id, nextId, name, expectedName, null, null); }
    public Student update(String id, String nextId, String name, String expectedName, Map<String, Object> fields, Map<String, Object> expectedFields) { return store.update(id, nextId, name, expectedName, fields, expectedFields); }
    public Student delete(String id, String expectedName) { return store.delete(id, expectedName, null); }
    public Student delete(String id, String expectedName, Map<String, Object> expectedFields) { return store.delete(id, expectedName, expectedFields); }
    public long count() { return search(null, null, 1, 1).total(); }
    public static Student normalized(String id, String name) { return new Student(validate(id, "学号", 32), validate(name, "姓名", 50)); }
    public static String validate(String value, String field, int length) {
        if (value == null || value.strip().isEmpty()) throw AppException.bad(field + "不能为空");
        String trimmed = value.strip();
        if (trimmed.length() > length) throw AppException.bad(field + "不能超过" + length + "个字符");
        if (trimmed.codePoints().anyMatch(Character::isISOControl)) throw AppException.bad(field + "不能包含控制字符");
        if (field.contains("学号") && !trimmed.matches("[A-Za-z0-9_-]+")) throw AppException.bad("学号只允许英文字母、数字、下划线和连字符");
        return trimmed;
    }
}
