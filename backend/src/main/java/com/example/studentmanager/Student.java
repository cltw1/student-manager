package com.example.studentmanager;

public record Student(String studentNo, String name, java.util.Map<String, Object> fields) {
    public Student(String studentNo, String name) { this(studentNo, name, java.util.Map.of()); }
    public Student { fields = fields == null ? java.util.Map.of() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(fields)); }
}
