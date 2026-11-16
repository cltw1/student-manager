package com.example.studentmanager;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record ReviewView(String id, int revision, String purpose, String parentId, String mode, String stage, String message, Instant expiresAt,
    String source, int sheetIndex, int headerRow, List<ImportGrid.SheetChoice> sheets, List<Column> columns,
    List<Row> rows, List<Issue> issues, List<StudentField> fields, List<StudentField> proposedFields,
    List<Student> existingStudents, Map<String, Map<String, Object>> backfill, List<String> notes, BatchReport preview, OperationResult result) {
    public record Ref(String reviewId, String title, String stage, int issueCount, boolean schemaChanged) {}
    public record Column(int index, String label, String target, String newLabel, String newType, List<String> samples, List<ColumnAdviser.Guess> suggestions) {}
    public record Row(int number, Map<Integer, String> values, boolean excluded, List<Issue> issues, List<Student> candidates) {}
    public record Issue(String code, String message, Integer row, Integer column, String suggestion, List<String> choices) {}
    public record Mapping(int column, String target, String label, String type) {}
    public record CellEdit(int row, int column, String value) {}
    public record BackfillEdit(String studentNo, Map<String, Object> values) {}
    public record Patch(int revision, String mode, List<Mapping> mappings, List<CellEdit> cells, List<Integer> excluded,
                        List<BackfillEdit> backfill, Boolean confirmMapping, String addColumn, String action) {}
}
