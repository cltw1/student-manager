package com.example.studentmanager;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")
class BatchIntegrationTest {
    @Autowired BatchService batches;
    @Autowired StudentService students;
    @Autowired ActionService actions;
    @Autowired ExcelImportService excel;
    @Autowired ToolDispatcher tools;
    @Autowired ChatService chats;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired ReviewService reviews;
    @BeforeEach void clean() { jdbc.update("DELETE FROM student"); }
    private BatchReport.Input input(String id, String name) { return new BatchReport.Input(0, id, name, null, null); }
    @Test void batchCreateCommitsValidRowsAndReportsDuplicateAndBlankRows() {
        students.create("1", "已有");
        OperationResult result = batches.execute("batch_create_students", batches.preview("CREATE", List.of(input("1", "覆盖"), input("2", "有效"), input("3", " "))));
        BatchReport report = (BatchReport) result.data();
        assertThat(report.successCount()).isEqualTo(1); assertThat(report.failureCount()).isEqualTo(2);
        assertThat(result.success()).isFalse(); assertThat(result.mutated()).isTrue();
        assertThat(students.get("1").name()).isEqualTo("已有"); assertThat(students.get("2").name()).isEqualTo("有效");
    }
    @Test void duplicateInputIdentifiersAreAllRejected() {
        var preview = batches.preview("CREATE", List.of(input("001", "甲"), input("001 ", "乙")));
        assertThat(preview.failureCount()).isEqualTo(2); assertThat(preview.readyCount()).isZero();
        assertThat(batches.execute("batch_create", preview).mutated()).isFalse(); assertThat(students.count()).isZero();
    }
    @Test void upsertPreviewDoesNotWriteAndConfirmationAddsUpdatesAndSkips() {
        students.create("1", "旧姓名"); students.create("2", "相同");
        var pending = actions.prepareBatch("owner", "UPSERT", List.of(input("1", "新姓名"), input("2", "相同"), input("003", "新增")));
        assertThat(students.get("1").name()).isEqualTo("旧姓名"); assertThat(students.count()).isEqualTo(2);
        assertThat(pending.action().batch().readyCount()).isEqualTo(2);
        OperationResult result = actions.confirm("owner", pending.action().id()); BatchReport report = (BatchReport) result.data();
        assertThat(report.successCount()).isEqualTo(2); assertThat(report.unchangedCount()).isEqualTo(1); assertThat(students.count()).isEqualTo(3);
        assertThat(students.get("003").name()).isEqualTo("新增"); assertThat(students.get("1").name()).isEqualTo("新姓名");
        assertThat(actions.confirm("owner", pending.action().id())).isSameAs(result);
    }
    @Test void updateAndDeleteDetectStaleRowsWithoutRollingBackOtherValidRows() {
        students.create("1", "甲"); students.create("2", "乙");
        var update = actions.prepareBatch("owner", "UPDATE", List.of(input("1", "新甲"), input("2", "新乙")));
        students.update("1", "1", "人工修改", "甲");
        var result = actions.confirm("owner", update.action().id());
        assertThat(((BatchReport) result.data()).failureCount()).isEqualTo(1);
        assertThat(students.get("1").name()).isEqualTo("人工修改"); assertThat(students.get("2").name()).isEqualTo("新乙");
        var delete = actions.prepareBatch("owner", "DELETE", List.of(input("1", null), input("2", null)));
        assertThatThrownBy(() -> actions.confirm("other", delete.action().id())).isInstanceOf(AppException.class);
        assertThat(students.count()).isEqualTo(2);
        assertThat(actions.cancel("owner", delete.action().id()).code()).isEqualTo("ACTION_CANCELLED");
        assertThat(actions.confirm("owner", delete.action().id()).mutated()).isFalse();
        var next = actions.prepareBatch("owner", "DELETE", List.of(input("1", null), input("2", null)));
        assertThat(actions.confirm("owner", next.action().id()).success()).isTrue(); assertThat(students.count()).isZero();
    }
    @Test void queryReportsMissingIdentifiersAndDoesNotWrite() {
        students.create("0001", "张三");
        var result = batches.result("batch_query_students", batches.preview("QUERY", List.of(input("0001", null), input("notfound", null))));
        var report = (BatchReport) result.data(); assertThat(report.successCount()).isEqualTo(1); assertThat(report.failureCount()).isEqualTo(1);
        assertThat(report.rows().get(0).name()).isEqualTo("张三"); assertThat(result.mutated()).isFalse(); assertThat(students.count()).isEqualTo(1);
    }
    @Test void limitAndDuplicateTargetChecksPreventAmbiguousWrites() {
        var inputs = new ArrayList<BatchReport.Input>(); for (int i = 0; i < 501; i++) inputs.add(input("x" + i, "甲"));
        assertThatThrownBy(() -> batches.preview("CREATE", inputs)).hasMessageContaining("500");
        students.create("1", "甲"); students.create("2", "乙");
        var preview = batches.preview("UPDATE", List.of(new BatchReport.Input(1, "1", "甲", "3", null), new BatchReport.Input(2, "2", "乙", "3", null)));
        assertThat(preview.failureCount()).isEqualTo(2); assertThat(preview.readyCount()).isZero();
    }
    @Test void textParserAcceptsPastedExcelColumnsAndRetainsSourceLineNumbers() {
        var rows = batches.parseText("学号\t姓名\n\n001\t李 明\n002，张三\n");
        assertThat(rows).hasSize(2); assertThat(rows.get(0).row()).isEqualTo(3); assertThat(rows.get(0).studentNo()).isEqualTo("001"); assertThat(rows.get(0).name()).isEqualTo("李 明");
    }
    @Test void excelSupportsBothFormatsReorderedHeadersBlankRowsAndFormattedLeadingZeros() throws Exception {
        for (boolean xlsx : List.of(true, false)) try (Workbook workbook = xlsx ? new XSSFWorkbook() : new HSSFWorkbook()) {
            Sheet sheet = workbook.createSheet(); Row header = sheet.createRow(1); header.createCell(0).setCellValue(" 姓名 "); header.createCell(2).setCellValue("学号");
            Row first = sheet.createRow(3); first.createCell(0).setCellValue("张三"); Cell id = first.createCell(2); id.setCellValue(12);
            CellStyle style = workbook.createCellStyle(); style.setDataFormat(workbook.createDataFormat().getFormat("000000")); id.setCellStyle(style);
            Row second = sheet.createRow(4); second.createCell(0).setCellValue("李四"); second.createCell(2).setCellValue("000013");
            var rows = excel.read(file(workbook, xlsx ? "input.xlsx" : "input.xls"));
            assertThat(rows).hasSize(2); assertThat(rows.get(0).studentNo()).isEqualTo("000012"); assertThat(rows.get(0).row()).isEqualTo(4); assertThat(rows.get(1).studentNo()).isEqualTo("000013");
        }
    }
    @Test void excelRejectsFormulaAndLongNumericIdentifiersPerRow() throws Exception {
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = headers(workbook); Row formula = sheet.createRow(1); formula.createCell(0).setCellFormula("1+1"); formula.createCell(1).setCellValue("公式");
            Row numeric = sheet.createRow(2); numeric.createCell(0).setCellValue(1234567890123456d); numeric.createCell(1).setCellValue("长数字");
            Row valid = sheet.createRow(3); valid.createCell(0).setCellValue("001234567890123456"); valid.createCell(1).setCellValue("文本长学号");
            var preview = batches.preview("CREATE", excel.read(file(workbook, "input.xlsx")));
            assertThat(preview.failureCount()).isEqualTo(2); assertThat(preview.readyCount()).isEqualTo(1);
            assertThat(preview.rows().get(0).message()).contains("公式"); assertThat(preview.rows().get(1).message()).contains("15位");
        }
    }
    @Test void invalidExcelHeadersAndFilesAreRejectedWithoutWrites() throws Exception {
        try (Workbook workbook = new XSSFWorkbook()) {
            workbook.createSheet().createRow(0).createCell(0).setCellValue("姓名");
            var missing = file(workbook, "input.xlsx"); assertThatThrownBy(() -> excel.read(missing)).hasMessageContaining("表头");
        }
        assertThatThrownBy(() -> excel.read(new MockMultipartFile("file", "wrong.xlsx", "", new byte[]{1,2,3}))).hasMessageContaining("无法读取");
        assertThatThrownBy(() -> excel.read(new MockMultipartFile("file", "wrong.csv", "", new byte[]{1}))).hasMessageContaining("仅支持");
        assertThatThrownBy(() -> excel.read(new MockMultipartFile("file", "huge.xlsx", "", new byte[2 * 1024 * 1024 + 1]))).hasMessageContaining("2MB");
        assertThat(students.count()).isZero();
    }
    @Test void multipartUploadCreatesPreviewAndConfirmUsesActualBusinessServices() throws Exception {
        String owner = chats.create().id();
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = headers(workbook); Row row = sheet.createRow(1); row.createCell(0).setCellValue("001"); row.createCell(1).setCellValue("王同学");
            String response = mvc.perform(multipart("/api/batches/excel").file(file(workbook, "input.xlsx")).param("conversationId", owner).param("mode", "UPSERT"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.code").value("REVIEW_REQUIRED")).andReturn().getResponse().getContentAsString();
            assertThat(students.count()).isZero();
            String reviewId = mapper.readTree(response).path("data").path("data").path("reviewId").asText();
            var view = reviews.get(owner, reviewId); var mappings = view.columns().stream().map(column -> new ReviewView.Mapping(column.index(), column.target(), column.newLabel(), column.newType())).toList();
            view = reviews.patch(owner, reviewId, new ReviewView.Patch(view.revision(), null, mappings, null, null, null, true, null, null));
            mvc.perform(post("/api/reviews/" + reviewId + "/finish").contentType(MediaType.APPLICATION_JSON).content("{\"conversationId\":\"" + owner + "\",\"revision\":" + view.revision() + "}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.result.data.successCount").value(1));
            assertThat(students.get("001").name()).isEqualTo("王同学");
            assertThat(chats.detail(owner).messages().get(1).results.get(0).code()).isEqualTo("BATCH_COMPLETED");
        }
    }
    @Test void batchToolsValidateRowsIndividuallyAndExposeAllFourOperations() {
        var result = tools.execute("owner", "batch_create_students", "{\"rows\":[{\"studentNo\":\"1\",\"name\":\"甲\"},{\"studentNo\":2,\"name\":\"乙\"},{\"studentNo\":\"3\",\"name\":\"丙\"}]}");
        assertThat(result.code()).isEqualTo("REVIEW_REQUIRED"); assertThat(students.count()).isZero();
        assertThat(tools.definitions().toString()).contains("batch_create_students", "batch_query_students", "batch_update_students", "batch_delete_students");
    }
    @Test void templateHasRequiredHeadersAndTextColumns() throws Exception {
        try (Workbook workbook = WorkbookFactory.create(new java.io.ByteArrayInputStream(excel.template()))) {
            var sheet = workbook.getSheetAt(0); assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("学号");
            assertThat(sheet.getRow(0).getCell(1).getStringCellValue()).isEqualTo("姓名"); assertThat(sheet.getColumnStyle(0).getDataFormatString()).isEqualTo("@");
        }
    }
    private Sheet headers(Workbook workbook) { Sheet sheet = workbook.createSheet("学生信息"); Row header = sheet.createRow(0); header.createCell(0).setCellValue("学号"); header.createCell(1).setCellValue("姓名"); return sheet; }
    private MockMultipartFile file(Workbook workbook, String filename) throws Exception { ByteArrayOutputStream stream = new ByteArrayOutputStream(); workbook.write(stream); return new MockMultipartFile("file", filename, "application/octet-stream", stream.toByteArray()); }
}
