package com.example.studentmanager;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.poi.ss.usermodel.*;
import org.springframework.stereotype.Service;

@Service
public class ImportReader {
    private final ObjectMapper mapper;
    public ImportReader(ObjectMapper mapper) { this.mapper = mapper; }
    public ImportGrid excel(byte[] bytes, String filename, Integer requestedSheet, Integer requestedHeader, int maxRows) {
        if (bytes == null || bytes.length == 0 || bytes.length > 2 * 1024 * 1024) throw AppException.bad("请选择2MB以内的Excel文件");
        if (filename == null || !filename.toLowerCase(Locale.ROOT).matches(".*\\.xlsx?")) throw AppException.bad("仅支持.xlsx和.xls文件");
        try (Workbook workbook = WorkbookFactory.create(new java.io.ByteArrayInputStream(bytes))) {
            List<ImportGrid.SheetChoice> choices = new ArrayList<>();
            for (int i = 0; i < workbook.getNumberOfSheets(); i++) if (!workbook.isSheetHidden(i) && !workbook.isSheetVeryHidden(i) && workbook.getSheetAt(i).getPhysicalNumberOfRows() > 0) choices.add(new ImportGrid.SheetChoice(i, workbook.getSheetName(i)));
            if (choices.isEmpty()) throw AppException.bad("没有可读取的非空可见工作表");
            int index = requestedSheet == null ? choices.get(0).index() : requestedSheet;
            if (choices.stream().noneMatch(choice -> choice.index() == index)) throw AppException.bad("请选择列表中的工作表");
            Sheet sheet = workbook.getSheetAt(index); DataFormatter formatter = new DataFormatter(Locale.ROOT);
            int headerIndex = requestedHeader == null ? -1 : requestedHeader - 1;
            if (headerIndex < 0) for (Row row : sheet) { if (row.getPhysicalNumberOfCells() > 0 && row.cellIterator().hasNext()) { boolean nonempty = false; for (Cell cell : row) if (!formatter.formatCellValue(cell).isBlank()) { nonempty = true; break; } if (nonempty) { headerIndex = row.getRowNum(); break; } } }
            Row header = sheet.getRow(headerIndex);
            if (header == null) throw AppException.bad("所选表头行不存在，请调整表头行号");
            int width = Math.max(1, header.getLastCellNum());
            List<ImportGrid.Row> rows = new ArrayList<>(); List<String> notes = new ArrayList<>();
            for (Row row : sheet) {
                if (row.getRowNum() <= headerIndex) continue;
                List<String> values = new ArrayList<>(); Map<Integer, String> errors = new LinkedHashMap<>();
                int last = Math.max(0, row.getLastCellNum());
                if (last > 50) { for (int i = 50; i < last; i++) if (row.getCell(i) != null && !formatter.formatCellValue(row.getCell(i)).isBlank()) throw AppException.bad("最多读取50列，请删除多余非空列后上传"); last = 50; }
                for (int i = 0; i < last; i++) {
                    Cell cell = row.getCell(i); String value = "";
                    if (cell != null) {
                        if (cell.getCellType() == CellType.FORMULA) { value = "=" + cell.getCellFormula(); errors.put(i, "公式未执行，请手动填写真实值"); }
                        else if (cell.getCellType() == CellType.ERROR) { value = formatter.formatCellValue(cell); errors.put(i, "Excel单元格包含错误，请修正真实值"); }
                        else if (cell.getCellType() == CellType.BOOLEAN) value = Boolean.toString(cell.getBooleanCellValue());
                        else if (cell.getCellType() == CellType.NUMERIC) {
                            double number = cell.getNumericCellValue();
                            if (DateUtil.isCellDateFormatted(cell)) { value = cell.getLocalDateTimeCellValue().toLocalDate().toString(); if (notes.size() < 10) notes.add("第" + (row.getRowNum() + 1) + "行日期已按yyyy-MM-dd呈现，请核对"); }
                            else {
                                value = formatter.formatCellValue(cell);
                                if (Math.abs(number) >= 1e15) errors.put(i, "数字超过Excel的15位精度范围，请从原始数据重新填写文本值");
                                if (value.matches("[0-9.+-]+[Ee][+-]?[0-9]+")) value = BigDecimal.valueOf(number).stripTrailingZeros().toPlainString();
                            }
                        } else value = formatter.formatCellValue(cell);
                    }
                    values.add(value);
                }
                if (values.stream().allMatch(String::isBlank) && errors.isEmpty()) continue;
                for (int i = 0; i < values.size(); i++) if (!values.get(i).isBlank() || errors.containsKey(i)) width = Math.max(width, i + 1);
                rows.add(new ImportGrid.Row(row.getRowNum() + 1, values, errors));
                if (rows.size() > maxRows) throw AppException.bad("本次最多读取" + maxRows + "条数据，请分批上传");
            }
            if (width > 50) throw AppException.bad("最多读取50列，请检查表头范围");
            List<String> headers = new ArrayList<>();
            for (int i = 0; i < width; i++) { Cell cell = header.getCell(i); headers.add(cell == null ? "" : formatter.formatCellValue(cell).replace("\uFEFF", "").strip()); }
            if (rows.isEmpty()) throw AppException.bad("表头下没有学生数据，请调整工作表或表头行");
            return new ImportGrid(filename, index, headerIndex + 1, choices, headers, rows, notes);
        } catch (AppException ex) { throw ex;
        } catch (Exception ex) { throw AppException.bad("无法读取Excel，请使用有效且未加密的工作簿"); }
    }
    public ImportGrid text(String text) {
        return text(text, null);
    }
    public ImportGrid text(String text, Boolean hasHeader) {
        if (text == null || text.isBlank() || text.length() > 30000) throw AppException.bad("请填写30000字符以内的学生数据");
        String source = text.strip();
        try {
            if (source.startsWith("{") || source.startsWith("[")) {
                JsonNode root = mapper.readTree(source); if (root.isObject()) { for (JsonNode node : root) if (node.isArray()) { root = node; break; } if (root.isObject()) root = mapper.createArrayNode().add(root); }
                return objects(root);
            }
        } catch (AppException ex) { throw ex; } catch (Exception ex) { throw AppException.bad("JSON数据格式不完整，请检查括号和引号，或改用表格粘贴"); }
        List<String> lines = new ArrayList<>(source.lines().filter(line -> !line.isBlank()).toList());
        if (lines.size() > 1 && lines.get(0).matches(".*(?:添加|新增|修改|更新|删除|查询|导入|学生信息|以下|下列).*[：:]\\s*")) lines.remove(0);
        if (lines.stream().allMatch(line -> line.matches(".*[^\\s,，:：]+\\s*[:：=].*"))) {
            var objects = mapper.createArrayNode();
            var pattern = java.util.regex.Pattern.compile("([^\\s,，:：=]+)\\s*[:：=]\\s*(.*?)(?=(?:[,，\\s]+[^\\s,，:：=]+\\s*[:：=])|$)");
            for (String line : lines) { var object = objects.addObject(); var matcher = pattern.matcher(line); while (matcher.find()) object.put(matcher.group(1), matcher.group(2).replaceAll("[,，]$", "").strip()); }
            return objects(objects);
        }
        List<List<String>> values = new ArrayList<>();
        for (String line : lines) values.add(split(line));
        if (values.isEmpty()) throw AppException.bad("没有可读取的记录");
        List<String> first = values.get(0);
        boolean header = hasHeader != null ? hasHeader : alias(first.get(0)) != null;
        int width = values.stream().mapToInt(List::size).max().orElse(0);
        List<String> headers = new ArrayList<>();
        if (header) { headers.addAll(first); values.remove(0); }
        else { headers.add(first.size() == 1 && !first.get(0).matches("[A-Za-z0-9_-]+") ? "姓名" : "学号"); if (width > 1) headers.add("姓名"); }
        while (headers.size() < width) headers.add("");
        List<ImportGrid.Row> rows = new ArrayList<>(); for (int i = 0; i < values.size(); i++) rows.add(new ImportGrid.Row(i + (header ? 2 : 1), values.get(i), Map.of()));
        if (rows.isEmpty() || rows.size() > 500 || width > 50) throw AppException.bad("请输入1～500条记录，最多50列");
        return new ImportGrid("批量文本", 0, header ? 1 : 0, List.of(), headers, rows, List.of());
    }
    public ImportGrid objects(JsonNode root) {
        if (!root.isArray() || root.isEmpty() || root.size() > 500) throw AppException.bad("学生数据应为1～500条对象记录");
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (JsonNode row : root) { if (!row.isObject()) throw AppException.bad("每条学生数据都应是字段与值组成的对象"); row.fieldNames().forEachRemaining(names::add); }
        if (names.isEmpty() || names.size() > 50) throw AppException.bad("请提供1～50列学生字段");
        List<String> headers = List.copyOf(names); List<ImportGrid.Row> rows = new ArrayList<>();
        for (JsonNode row : root) { List<String> values = new ArrayList<>(); Map<Integer, String> errors = new LinkedHashMap<>();
            for (int i = 0; i < headers.size(); i++) { JsonNode value = row.get(headers.get(i)); if (value == null || value.isNull()) values.add(""); else if (value.isContainerNode()) { values.add(value.toString()); errors.put(i, "嵌套对象或数组不能直接作为单元格值，请展开或手动修正"); } else values.add(value.asText()); }
            rows.add(new ImportGrid.Row(rows.size() + 1, values, errors)); }
        return new ImportGrid("结构化数据", 0, 1, List.of(), headers, rows, List.of());
    }
    private List<String> split(String line) {
        char delimiter = line.indexOf('\t') >= 0 ? '\t' : line.indexOf(',') >= 0 ? ',' : line.indexOf('，') >= 0 ? '，' : ' ';
        List<String> result = new ArrayList<>(); StringBuilder cell = new StringBuilder(); boolean quoted = false;
        for (int i = 0; i < line.length(); i++) { char c = line.charAt(i); if (c == '"') { if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') { cell.append('"'); i++; } else quoted = !quoted; }
            else if (!quoted && (c == delimiter || delimiter == ' ' && Character.isWhitespace(c))) { if (delimiter != ' ' || !cell.isEmpty()) { result.add(cell.toString().strip()); cell.setLength(0); } }
            else cell.append(c); }
        if (quoted) throw AppException.bad("文本中有未闭合的引号，请修正后重新校验");
        if (delimiter != ' ' || !cell.isEmpty()) result.add(cell.toString().strip()); return result;
    }
    public static String alias(String input) {
        String value = input == null ? "" : java.text.Normalizer.normalize(input, java.text.Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replaceAll("[\\s_\\-]", "").replace("\uFEFF", "");
        if (java.util.Set.of("学号", "学生学号", "学生编号", "id", "stuid", "studentid", "studentno", "sno", "sid", "学籍号", "学籍编号").contains(value)) return "studentNo";
        if (java.util.Set.of("姓名", "学生姓名", "名字", "名称", "name", "studentname", "stuname", "sname").contains(value)) return "name";
        if (java.util.Set.of("新学号", "newstudentno", "newid").contains(value)) return "newStudentNo";
        return null;
    }
}
