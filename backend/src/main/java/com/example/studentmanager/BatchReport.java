package com.example.studentmanager;

import java.util.List;

public record BatchReport(String mode, int total, int successCount, int failureCount, int readyCount, int unchangedCount, List<Row> rows) {
    public record Input(int row, String studentNo, String name, String newStudentNo, String error, java.util.Map<String, Object> fields) {
        public Input(int row, String studentNo, String name, String newStudentNo, String error) { this(row, studentNo, name, newStudentNo, error, null); }
    }
    public record Row(int row, String studentNo, String name, String newStudentNo, Student before, Student after,
                      String operation, String status, String code, String message) {
        public Row outcome(String status, String code, String message) {
            return new Row(row, studentNo, name, newStudentNo, before, after, operation, status, code, message);
        }
    }
    public static BatchReport of(String mode, List<Row> rows) {
        return new BatchReport(mode, rows.size(), (int) rows.stream().filter(r -> r.status().equals("SUCCESS")).count(),
            (int) rows.stream().filter(r -> r.status().equals("INVALID") || r.status().equals("FAILED")).count(),
            (int) rows.stream().filter(r -> r.status().equals("READY")).count(),
            (int) rows.stream().filter(r -> r.status().equals("UNCHANGED")).count(), List.copyOf(rows));
    }
}
