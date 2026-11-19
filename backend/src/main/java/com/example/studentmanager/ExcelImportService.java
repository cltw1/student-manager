package com.example.studentmanager;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class ExcelImportService {
    private final FieldCatalog catalog;
    public ExcelImportService(FieldCatalog catalog) { this.catalog = catalog; }
    public List<BatchReport.Input> read(MultipartFile file) {
        if (file == null || file.isEmpty()) throw AppException.bad("请选择Excel文件");
        if (file.getSize() > 2 * 1024 * 1024) throw AppException.bad("Excel文件不能超过2MB");
        String filename = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        if (!filename.endsWith(".xlsx") && !filename.endsWith(".xls")) throw AppException.bad("仅支持.xlsx和.xls文件");
        try (Workbook workbook = WorkbookFactory.create(file.getInputStream())) {
            Sheet sheet = null; Row header = null;
            DataFormatter formatter = new DataFormatter(Locale.ROOT);
            for (int s = 0; s < workbook.getNumberOfSheets() && header == null; s++) {
                Sheet candidate = workbook.getSheetAt(s);
                if (workbook.isSheetHidden(s) || workbook.isSheetVeryHidden(s)) continue;
                for (Row row : candidate) {
                    boolean nonempty = false;
                    for (Cell cell : row) if (!formatter.formatCellValue(cell).isBlank()) { nonempty = true; break; }
                    if (nonempty) { sheet = candidate; header = row; break; }
                }
            }
            if (header == null || sheet == null) throw AppException.bad("Excel工作表为空");
            int idColumn = -1, nameColumn = -1;
            for (Cell cell : header) {
                String label = formatter.formatCellValue(cell).replace("\uFEFF", "").strip();
                if (label.equals("学号")) { if (idColumn >= 0) throw AppException.bad("表头中存在重复的学号列"); idColumn = cell.getColumnIndex(); }
                if (label.equals("姓名")) { if (nameColumn >= 0) throw AppException.bad("表头中存在重复的姓名列"); nameColumn = cell.getColumnIndex(); }
            }
            if (idColumn < 0 || nameColumn < 0) throw AppException.bad("首个非空工作表的首个非空行必须包含“学号”和“姓名”表头");
            List<BatchReport.Input> rows = new ArrayList<>();
            for (Row row : sheet) {
                if (row.getRowNum() <= header.getRowNum()) continue;
                Cell idCell = row.getCell(idColumn), nameCell = row.getCell(nameColumn);
                String id = null, name = null, error = null;
                try { id = value(idCell, formatter, true); name = value(nameCell, formatter, false); }
                catch (AppException ex) { error = ex.getMessage(); }
                if (error == null && (id == null || id.isBlank()) && (name == null || name.isBlank())) continue;
                if (error == null && (name == null || name.isBlank())) error = "姓名不能为空";
                rows.add(new BatchReport.Input(row.getRowNum() + 1, id, name, null, error));
                if (rows.size() > BatchService.MAX_ROWS) throw AppException.bad("每批最多导入500条学生记录");
            }
            if (rows.isEmpty()) throw AppException.bad("表头下没有学生数据");
            return rows;
        } catch (AppException ex) { throw ex;
        } catch (Exception ex) { throw AppException.bad("无法读取Excel，请使用未加密且格式有效的.xlsx或.xls文件"); }
    }
    private String value(Cell cell, DataFormatter formatter, boolean studentNo) {
        if (cell == null || cell.getCellType() == CellType.BLANK) return "";
        if (cell.getCellType() == CellType.FORMULA) throw AppException.bad("学号和姓名请填写实际值，不能使用公式");
        if (cell.getCellType() == CellType.BOOLEAN || cell.getCellType() == CellType.ERROR) throw AppException.bad("学号或姓名的单元格类型无效");
        if (studentNo && cell.getCellType() == CellType.NUMERIC) {
            double number = cell.getNumericCellValue();
            if (!Double.isFinite(number) || number < 0 || Math.floor(number) != number || DateUtil.isCellDateFormatted(cell)) throw AppException.bad("学号必须是文本或非负整数，不能使用日期、小数");
            if (number >= 1e15) throw AppException.bad("超过15位的学号必须设置为文本，避免Excel精度丢失");
            String formatted = formatter.formatCellValue(cell).strip();
            if (formatted.matches("[0-9]+")) return formatted;
            if (formatted.matches("[0-9.]+[Ee][+-]?[0-9]+") || cell.getCellStyle().getDataFormat() == 0)
                return BigDecimal.valueOf(number).toBigIntegerExact().toString();
            throw AppException.bad("学号数字格式不明确，请将单元格设置为文本后重新上传");
        }
        return formatter.formatCellValue(cell).strip();
    }
    public byte[] template() {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("学生信息");
            CellStyle headerStyle = workbook.createCellStyle();
            headerStyle.setFillForegroundColor(IndexedColors.ROYAL_BLUE.getIndex()); headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            Font font = workbook.createFont(); font.setBold(true); font.setColor(IndexedColors.WHITE.getIndex()); headerStyle.setFont(font);
            Row header = sheet.createRow(0); header.setHeightInPoints(26);
            header.createCell(0).setCellValue("学号"); header.createCell(1).setCellValue("姓名");
            int index = 2; for (StudentField field : catalog.fields()) { header.createCell(index).setCellValue(field.label()); header.getCell(index).setCellStyle(headerStyle); sheet.setColumnWidth(index, 24 * 256); index++; }
            header.getCell(0).setCellStyle(headerStyle); header.getCell(1).setCellStyle(headerStyle);
            CellStyle text = workbook.createCellStyle(); text.setDataFormat(workbook.createDataFormat().getFormat("@"));
            sheet.setDefaultColumnStyle(0, text); sheet.setDefaultColumnStyle(1, text);
            sheet.setColumnWidth(0, 26 * 256); sheet.setColumnWidth(1, 22 * 256); sheet.createFreezePane(0, 1);
            int fieldColumn = 2;
            for (StudentField field : catalog.fields()) { if (field.gender()) { var helper = sheet.getDataValidationHelper(); var validation = helper.createValidation(helper.createExplicitListConstraint(new String[]{"男", "女"}), new org.apache.poi.ss.util.CellRangeAddressList(1, 500, fieldColumn, fieldColumn)); validation.setShowErrorBox(true); validation.setErrorStyle(org.apache.poi.ss.usermodel.DataValidation.ErrorStyle.STOP); validation.createErrorBox("性别格式错误", "性别只能选择男或女"); sheet.addValidationData(validation); } fieldColumn++; }
            workbook.write(output); return output.toByteArray();
        } catch (Exception ex) { throw new IllegalStateException("Excel模板生成失败", ex); }
    }
}
