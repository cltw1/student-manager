package com.example.studentmanager;

import java.util.List;
import java.util.Map;

public record ImportGrid(String source, int sheetIndex, int headerRow, List<SheetChoice> sheets, List<String> headers, List<Row> rows, List<String> notes) {
    public record SheetChoice(int index, String name) {}
    public record Row(int number, List<String> values, Map<Integer, String> errors) {}
}
