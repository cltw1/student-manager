package com.example.studentmanager;

import static org.assertj.core.api.Assertions.*;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")
class ReviewWorkflowTest {
    @Autowired ReviewService reviews;
    @Autowired StudentService students;
    @Autowired FieldCatalog catalog;
    @Autowired JdbcTemplate jdbc;
    @Autowired javax.sql.DataSource source;
    @Autowired ToolDispatcher tools;
    @Autowired ImportReader reader;
    @BeforeEach @AfterEach void reset() throws Exception {
        try (var connection = source.getConnection()) {
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("H2");
            jdbc.update("DELETE FROM student");
            for (StudentField field : catalog.inspect(connection)) jdbc.execute("ALTER TABLE student DROP COLUMN " + FieldCatalog.quote(field.key()));
            List<String> temporary = new ArrayList<>(); try (var tables = connection.getMetaData().getTables(connection.getCatalog(), null, "student_%", new String[]{"TABLE"})) { while (tables.next()) { String name = tables.getString("TABLE_NAME"); if (name.startsWith("student_backup_") || name.startsWith("student_stage_")) temporary.add(name); } }
            for (String name : temporary) jdbc.execute("DROP TABLE " + FieldCatalog.quote(name));
        }
        catalog.invalidate();
        for (var pending : reviews.pending("owner")) { var view = reviews.get("owner", pending.reviewId()); reviews.cancel("owner", view.id(), view.revision()); }
    }
    private ReviewView.Patch patch(ReviewView view, List<ReviewView.Mapping> mapping, List<ReviewView.CellEdit> cells, List<Integer> excluded, List<ReviewView.BackfillEdit> backfill, Boolean confirm, String add, String action) { return new ReviewView.Patch(view.revision(), null, mapping, cells, excluded, backfill, confirm, add, action); }
    private ReviewView confirm(ReviewView view) { return reviews.patch("owner", view.id(), patch(view, view.columns().stream().map(c -> new ReviewView.Mapping(c.index(), c.target(), c.newLabel(), c.newType())).toList(), null, null, null, true, null, null)); }
    private ReviewView map(ReviewView view, Map<Integer, String> overrides, Map<Integer, String> types) {
        var decisions = view.columns().stream().map(c -> new ReviewView.Mapping(c.index(), overrides.getOrDefault(c.index(), c.target()), c.label(), types.getOrDefault(c.index(), c.newType()))).toList();
        return reviews.patch("owner", view.id(), patch(view, decisions, null, null, null, true, null, null));
    }
    @Test void aliasHeadersRequireExplicitConfirmationBeforeWritingAndReplayDoesNotRepeat() {
        var view = reviews.text("owner", "CREATE", "stuID,name\n1000001,张三\n1000002,李四", null);
        assertThat(view.stage()).isEqualTo("MAPPING"); assertThat(view.columns().get(0).target()).isEqualTo("studentNo");
        final var waiting = view; assertThatThrownBy(() -> reviews.finish("owner", waiting.id(), waiting.revision())).hasMessageContaining("待确认"); assertThat(students.count()).isZero();
        view = confirm(view); assertThat(view.stage()).isEqualTo("READY"); int version = view.revision();
        view = reviews.finish("owner", view.id(), version); assertThat(((BatchReport) view.result().data()).successCount()).isEqualTo(2);
        assertThat(reviews.finish("owner", view.id(), version).stage()).isEqualTo("COMPLETE"); assertThat(students.count()).isEqualTo(2);
    }
    @Test void duplicateMeaningColumnsMustBeChosenOrExplicitlyIgnored() {
        var view = reviews.text("owner", "CREATE", "id,stuID,name\n1000001,999,张三", true);
        view = confirm(view); assertThat(view.stage()).isEqualTo("MAPPING"); assertThat(view.issues()).anyMatch(issue -> issue.code().equals("DUPLICATE_MAPPING"));
        view = map(view, Map.of(1, "IGNORE"), Map.of()); assertThat(view.stage()).isEqualTo("READY"); reviews.finish("owner", view.id(), view.revision());
        assertThat(students.get("1000001").name()).isEqualTo("张三"); assertThatThrownBy(() -> students.get("1000999")).isInstanceOf(AppException.class);
    }
    @Test void missingStudentNumberOffersCandidatesAndAnEditableBlankColumn() {
        students.create("1000001", "张三"); students.create("1000002", "张三");
        var view = reviews.text("owner", "UPDATE", "name\n张三", true);
        assertThat(view.issues()).anyMatch(issue -> issue.code().equals("MISSING_COLUMN"));
        view = reviews.patch("owner", view.id(), patch(view, null, null, null, null, null, "studentNo", null)); view = confirm(view);
        assertThat(view.stage()).isEqualTo("ROWS"); assertThat(view.rows().get(0).candidates()).hasSize(2);
        int row = view.rows().get(0).number(), idColumn = view.columns().stream().filter(c -> c.target().equals("studentNo")).findFirst().orElseThrow().index();
        view = reviews.patch("owner", view.id(), patch(view, null, List.of(new ReviewView.CellEdit(row, idColumn, "1000002")), null, null, null, null, null));
        assertThat(view.stage()).isEqualTo("READY"); reviews.finish("owner", view.id(), view.revision()); assertThat(students.count()).isEqualTo(2);
    }
    @Test void conflictsOfferModeChangeAndDoNotSilentlyOverwrite() {
        students.create("1000001", "原姓名"); var view = confirm(reviews.text("owner", "CREATE", "学号 姓名\n1000001 新姓名", true));
        assertThat(view.stage()).isEqualTo("ROWS"); assertThat(view.issues()).anyMatch(issue -> issue.code().equals("STUDENT_EXISTS") && issue.choices().contains("UPDATE"));
        assertThat(students.get("1000001").name()).isEqualTo("原姓名");
        view = reviews.patch("owner", view.id(), new ReviewView.Patch(view.revision(), "UPDATE", null, null, null, null, null, null, null));
        reviews.finish("owner", view.id(), view.revision()); assertThat(students.get("1000001").name()).isEqualTo("新姓名");
    }
    @Test void unknownColumnsAreNotSilentlyIgnored() {
        var view = reviews.text("owner", "CREATE", "id,name,备注\n1000001,张三,不要丢失", true);
        assertThat(view.columns().get(2).target()).isEqualTo("UNDECIDED"); assertThat(view.issues()).anyMatch(issue -> issue.code().equals("UNDECIDED_COLUMN"));
        view = map(view, Map.of(2, "IGNORE"), Map.of()); assertThat(view.stage()).isEqualTo("READY"); reviews.finish("owner", view.id(), view.revision()); assertThat(catalog.fields()).isEmpty();
    }
    @Test void duplicateRowsWaitForUserSelectionThenOnlyChosenRowsRun() {
        var view = confirm(reviews.text("owner", "CREATE", "id name\n1000001 甲\n1000001 乙\n1000002 丙", true));
        assertThat(view.stage()).isEqualTo("ROWS"); assertThat(students.count()).isZero();
        view = reviews.patch("owner", view.id(), patch(view, null, null, null, null, null, null, "KEEP_FIRST_DUPLICATE"));
        assertThat(view.rows().stream().filter(ReviewView.Row::excluded)).hasSize(1); reviews.finish("owner", view.id(), view.revision()); assertThat(students.get("1000001").name()).isEqualTo("甲"); assertThat(students.count()).isEqualTo(2);
    }
    @Test void exclusionRequiresUserActionAndFailedRowsDoNotRun() {
        var view = confirm(reviews.text("owner", "CREATE", "id,name\n1000001,\n1000002,有效", true));
        final var invalid = view; assertThatThrownBy(() -> reviews.finish("owner", invalid.id(), invalid.revision())).isInstanceOf(AppException.class); assertThat(students.count()).isZero();
        view = reviews.patch("owner", view.id(), patch(view, null, null, List.of(view.rows().get(0).number()), null, null, null, null));
        reviews.finish("owner", view.id(), view.revision()); assertThat(students.count()).isEqualTo(1); assertThat(students.get("1000002").name()).isEqualTo("有效");
    }
    @Test void newFieldsCannotBeAddedUntilEveryExistingStudentIsBackfilled() {
        students.create("1000001", "原同学甲"); students.create("1000002", "原同学乙");
        var view = map(reviews.text("owner", "CREATE", "id,name,班级\n1000003,新同学,高一", true), Map.of(2, "NEW"), Map.of(2, "TEXT"));
        assertThat(view.stage()).isEqualTo("BACKFILL"); assertThat(view.existingStudents()).hasSize(2); assertThat(catalog.fields()).isEmpty();
        final var blocked = view; assertThatThrownBy(() -> reviews.addFields("owner", blocked.id(), blocked.revision())).isInstanceOf(AppException.class);
        String key = view.proposedFields().get(0).key();
        view = reviews.patch("owner", view.id(), patch(view, null, null, null, List.of(new ReviewView.BackfillEdit("1000001", Map.of(key, "高二"))), null, null, null));
        assertThat(view.stage()).isEqualTo("BACKFILL"); assertThat(catalog.fields()).isEmpty();
        view = reviews.patch("owner", view.id(), patch(view, null, null, null, List.of(new ReviewView.BackfillEdit("1000002", Map.of(key, "高三"))), null, null, null)); assertThat(view.stage()).isEqualTo("SCHEMA_READY");
        view = reviews.addFields("owner", view.id(), view.revision()); assertThat(view.stage()).isEqualTo("READY");
        catalog.invalidate(); assertThat(catalog.fields()).extracting(StudentField::label).containsExactly("班级");
        assertThat(students.get("1000001").fields().get(key)).isEqualTo("高二"); assertThat(students.count()).isEqualTo(2);
        reviews.finish("owner", view.id(), view.revision()); assertThat(students.get("1000003").fields().get(key)).isEqualTo("高一");
    }
    @Test void multipleTypedFieldsPreserveFalseAndZeroAndArePhysicalColumns() {
        students.create("1000001", "原学生");
        var view = map(reviews.text("owner", "CREATE", "id,name,备注,年龄,成绩,入学日期,在读\n1000002,新生,文本,0,95.500000,2024-02-29,false", true), Map.of(2,"NEW",3,"NEW",4,"NEW",5,"NEW",6,"NEW"), Map.of(2,"TEXT",3,"INTEGER",4,"DECIMAL",5,"DATE",6,"BOOLEAN"));
        Map<String,Object> values = new LinkedHashMap<>(); for (StudentField field : view.proposedFields()) values.put(field.key(), switch (field.type()) { case "TEXT" -> "已补齐"; case "INTEGER" -> 0; case "DECIMAL" -> "0.25"; case "DATE" -> "2025-01-01"; default -> false; });
        view = reviews.patch("owner", view.id(), patch(view, null, null, null, List.of(new ReviewView.BackfillEdit("1000001", values)), null, null, null)); assertThat(view.stage()).isEqualTo("SCHEMA_READY");
        view = reviews.addFields("owner", view.id(), view.revision()); reviews.finish("owner", view.id(), view.revision());
        assertThat(catalog.fields()).hasSize(5); Student current = students.get("1000001"); assertThat(current.fields().values()).contains(false, "0", "2025-01-01");
        for (StudentField field : catalog.fields()) assertThat(jdbc.queryForObject("SELECT COUNT(" + FieldCatalog.quote(field.key()) + ") FROM student", Integer.class)).isEqualTo(2);
        assertThatThrownBy(() -> students.create("1000003", "缺少新字段")).isInstanceOf(AppException.class);
        String grade=catalog.fields().stream().filter(field->field.label().equals("成绩")).findFirst().orElseThrow().key();assertThat(students.filter(List.of(new StudentFilter(grade,"GT","50")),1,20).total()).isEqualTo(1);
        String date=catalog.fields().stream().filter(field->field.type().equals("DATE")).findFirst().orElseThrow().key();assertThat(students.filter(List.of(new StudentFilter(date,"GTE","2025-01-01")),1,20).total()).isEqualTo(1);
        Map<String,Object> precise=new LinkedHashMap<>(students.get("1000001").fields());precise.put(grade,"12345678901234.123456"); students.update("1000001","1000001","原学生","原学生",precise,students.get("1000001").fields());assertThat(students.get("1000001").fields().get(grade)).isEqualTo("12345678901234.123456");
    }
    @Test void staleDatasetBlocksSchemaChangeAndRefreshRetainsBackfill() {
        students.create("1000001", "原学生"); var view = map(reviews.text("owner", "CREATE", "id,name,班级\n1000002,新生,A", true), Map.of(2,"NEW"), Map.of(2,"TEXT")); String key = view.proposedFields().get(0).key();
        view = reviews.patch("owner", view.id(), patch(view, null, null, null, List.of(new ReviewView.BackfillEdit("1000001",Map.of(key,"B"))),null,null,null));
        students.update("1000001","1000001","人工改名","原学生"); final var stale = view;
        assertThatThrownBy(() -> reviews.addFields("owner",stale.id(),stale.revision())).isInstanceOf(AppException.class).hasMessageContaining("已变化"); assertThat(catalog.fields()).isEmpty();
        view = reviews.patch("owner",view.id(),patch(view,null,null,null,null,null,null,"REFRESH_BACKFILL")); assertThat(view.backfill().get("1000001").get(key)).isEqualTo("B");
        view = reviews.addFields("owner",view.id(),view.revision()); assertThat(students.get("1000001").name()).isEqualTo("人工改名");
    }
    @Test void dateAndBooleanSuggestionsNeverApplyUntilUserEdits() {
        var view = map(reviews.text("owner","CREATE","id,name,日期,启用\n1000001,张三,2024/2/29,是",true),Map.of(2,"NEW",3,"NEW"),Map.of(2,"DATE",3,"BOOLEAN"));
        assertThat(view.stage()).isEqualTo("ROWS"); assertThat(view.issues()).anyMatch(issue -> "2024-02-29".equals(issue.suggestion())).anyMatch(issue -> "true".equals(issue.suggestion())); assertThat(catalog.fields()).isEmpty();
        var edits=view.issues().stream().filter(issue -> issue.suggestion()!=null && issue.column()!=null).map(issue -> new ReviewView.CellEdit(issue.row(),issue.column(),issue.suggestion())).toList();
        view=reviews.patch("owner",view.id(),patch(view,null,edits,null,null,null,null,null)); assertThat(view.stage()).isEqualTo("SCHEMA_READY");
        view=reviews.addFields("owner",view.id(),view.revision()); reviews.finish("owner",view.id(),view.revision()); assertThat(students.get("1000001").fields().values()).contains(true,"2024-02-29");
    }
    @Test void oldPreviewDetectsConcurrentChangesAtExecutionAndRetryIncludesOnlyFailedRows() {
        students.create("1000001","甲"); students.create("1000002","乙"); var view=confirm(reviews.text("owner","UPDATE","id,name\n1000001,新甲\n1000002,新乙",true));
        students.update("1000001","1000001","并发修改","甲"); view=reviews.finish("owner",view.id(),view.revision()); var report=(BatchReport)view.result().data();
        assertThat(report.successCount()).isEqualTo(1); assertThat(report.failureCount()).isEqualTo(1); assertThat(students.get("1000001").name()).isEqualTo("并发修改");
        var retry=reviews.retry("owner",report); assertThat(retry.rows()).hasSize(1); assertThat(retry.rows().get(0).values().values()).contains("1000001","新甲");
    }
    @Test void revisionsOwnersAndExpiredActionsDoNotAllowUnconfirmedWrites() {
        var view=reviews.text("owner","CREATE","id name\n1000001 张三",true); final var initial=view;
        assertThatThrownBy(() -> reviews.get("other",initial.id())).isInstanceOf(AppException.class);
        view=confirm(view); final var latest=view;
        assertThatThrownBy(() -> reviews.patch("owner",latest.id(),new ReviewView.Patch(0,null,null,null,null,null,null,null,null))).hasMessageContaining("其他窗口"); assertThat(students.count()).isZero();
    }
    @Test void excelFormulaAndPrecisionIssuesRemainEditable() throws Exception {
        try(Workbook workbook=new XSSFWorkbook()) { Sheet sheet=workbook.createSheet(); Row header=sheet.createRow(0);header.createCell(0).setCellValue("id");header.createCell(1).setCellValue("name");
            Row row=sheet.createRow(1);row.createCell(0).setCellValue(1234567890123456d);row.createCell(1).setCellFormula("\"张三\"");
            var view=confirm(reviews.excel("owner","CREATE",bytes(workbook),"test.xlsx"));assertThat(view.stage()).isEqualTo("ROWS"); assertThat(view.rows().get(0).issues()).anyMatch(issue -> issue.code().equals("SOURCE_CELL_ERROR"));
            view=reviews.patch("owner",view.id(),patch(view,null,List.of(new ReviewView.CellEdit(2,0,"1234567"),new ReviewView.CellEdit(2,1,"张三")),null,null,null,null,null));
            assertThat(view.stage()).isEqualTo("READY");reviews.finish("owner",view.id(),view.revision());assertThat(students.get("1234567").name()).isEqualTo("张三"); }
    }
    @Test void sheetAndHeaderSelectionCanBeCorrectedWithoutGuessing() throws Exception {
        try(Workbook workbook=new XSSFWorkbook()) { Sheet sheet=workbook.createSheet("有标题");sheet.createRow(0).createCell(0).setCellValue("学生名单");Row header=sheet.createRow(1);header.createCell(0).setCellValue("id");header.createCell(1).setCellValue("name");Row row=sheet.createRow(2);row.createCell(0).setCellValue("1000001");row.createCell(1).setCellValue("张三");
            Sheet second=workbook.createSheet("另一批"); Row h=second.createRow(0);h.createCell(0).setCellValue("学号");h.createCell(1).setCellValue("姓名");Row r=second.createRow(1);r.createCell(0).setCellValue("1000002");r.createCell(1).setCellValue("李四");
            var view=reviews.excel("owner","CREATE",bytes(workbook),"test.xlsx");assertThat(view.sheets()).hasSize(2);
            view=reviews.source("owner",view.id(),view.revision(),0,2,null);view=confirm(view);assertThat(view.stage()).isEqualTo("READY");
            view=reviews.source("owner",view.id(),view.revision(),1,1,null);view=confirm(view);reviews.finish("owner",view.id(),view.revision());assertThat(students.get("1000002").name()).isEqualTo("李四");assertThatThrownBy(()->students.get("1000001")).isInstanceOf(AppException.class); }
    }
    @Test void backfillExcelHasItsOwnMappingConfirmationAndMergesWithoutWriting() throws Exception {
        students.create("1000001","原学生");var parent=map(reviews.text("owner","CREATE","id,name,班级\n1000002,新生,A",true),Map.of(2,"NEW"),Map.of(2,"TEXT"));String field=parent.proposedFields().get(0).key();
        try(Workbook workbook=new XSSFWorkbook()){Sheet sheet=workbook.createSheet();Row header=sheet.createRow(0);header.createCell(0).setCellValue("stuID");header.createCell(1).setCellValue("班级");Row row=sheet.createRow(1);row.createCell(0).setCellValue("1000001");row.createCell(1).setCellValue("B");
            var child=reviews.backfillFile("owner",parent.id(),bytes(workbook),"backfill.xlsx");assertThat(child.purpose()).isEqualTo("BACKFILL");child=confirm(child);assertThat(child.stage()).isEqualTo("READY");
            parent=reviews.finish("owner",child.id(),child.revision());assertThat(parent.stage()).isEqualTo("SCHEMA_READY");assertThat(parent.backfill().get("1000001").get(field)).isEqualTo("B");assertThat(catalog.fields()).isEmpty();assertThat(students.get("1000001").fields()).isEmpty(); }
    }
    @Test void textParsingPreservesFirstDataRowAndHeaderCanBeOverridden() {
        assertThat(reader.text("ABC Alice\nDEF Bob").rows()).hasSize(2); assertThat(reader.text("001 张三\n1000002 李四").rows()).hasSize(2);
        var view=reviews.text("owner","CREATE","foo bar\n1000001 张三",null);view=reviews.source("owner",view.id(),view.revision(),null,null,true);assertThat(view.rows()).hasSize(1);assertThat(view.columns()).extracting(ReviewView.Column::label).containsExactly("foo","bar");
        assertThat(reader.text("id,name\n1000001,\"Li Ming\"",true).rows().get(0).values()).containsExactly("1000001","Li Ming");
    }
    @Test void staleBackfillFileCannotMergeAfterParentCancellation() throws Exception {
        students.create("1000001", "原学生");
        var parent=map(reviews.text("owner","CREATE","id,name,班级\n1000002,新生,A",true),Map.of(2,"NEW"),Map.of(2,"TEXT"));
        try(Workbook workbook=new XSSFWorkbook()) {
            Sheet sheet=workbook.createSheet();Row header=sheet.createRow(0);header.createCell(0).setCellValue("学号");header.createCell(1).setCellValue("班级");
            Row row=sheet.createRow(1);row.createCell(0).setCellValue("1000001");row.createCell(1).setCellValue("B");
            var child=confirm(reviews.backfillFile("owner",parent.id(),bytes(workbook),"backfill.xlsx"));
            reviews.cancel("owner",parent.id(),parent.revision());
            assertThatThrownBy(()->reviews.finish("owner",child.id(),child.revision())).isInstanceOf(AppException.class).hasMessageContaining("取消");
            assertThat(catalog.fields()).isEmpty();assertThat(students.get("1000001").fields()).isEmpty();
        }
    }
    @Test void structuredAliasesAndExtraFieldsArePreservedForReview() {
        var result=tools.execute("owner","prepare_student_operation","{\"mode\":\"CREATE\",\"rows\":[{\"stuID\":\"001\",\"name\":\"张三\",\"班级\":\"A\"}]}");
        assertThat(result.code()).isEqualTo("REVIEW_REQUIRED"); var ref=(ReviewView.Ref)result.data();var view=reviews.get("owner",ref.reviewId());assertThat(view.columns()).extracting(ReviewView.Column::label).containsExactly("stuID","name","班级");assertThat(students.count()).isZero();
    }
    @Test void pendingReviewCannotBeBypassedAndChatFeedbackCanReviseTheDraft() {
        var start=tools.execute("owner","prepare_student_operation","{\"mode\":\"CREATE\",\"rows\":[{\"id\":\"1000001\",\"name\":\"张三\"}]}");String id=((ReviewView.Ref)start.data()).reviewId();
        assertThat(tools.execute("owner","create_student","{\"studentNo\":\"1000001\",\"name\":\"张三\"}").code()).isEqualTo("REVIEW_REQUIRED");assertThat(students.count()).isZero();
        var revised=tools.execute("owner","revise_student_operation","{\"reviewId\":\""+id+"\",\"mappings\":[{\"column\":0,\"target\":\"studentNo\"},{\"column\":1,\"target\":\"name\"}],\"confirmMapping\":true}");
        assertThat(((ReviewView.Ref)revised.data()).stage()).isEqualTo("READY");assertThat(students.count()).isZero();var view=reviews.get("owner",id);reviews.finish("owner",id,view.revision());assertThat(students.count()).isEqualTo(1);
    }
    @Test void cancellingAReviewReleasesItsWriteGate() {
        var view=reviews.text("owner","CREATE","id name\n1000001 张三",true);reviews.cancel("owner",view.id(),view.revision());
        assertThat(reviews.pending("owner")).isEmpty();assertThat(tools.execute("owner","create_student","{\"studentNo\":\"1000002\",\"name\":\"李四\"}").code()).isEqualTo("STUDENT_CREATED");
    }
    @Test void originalInputWithAliasesRequiresReviewEvenIfModelNormalizedArguments() {
        var result=tools.originalReview("owner","添加下列学生：\nid,name\n1000001,张三","batch_create_students");assertThat(result.code()).isEqualTo("REVIEW_REQUIRED");assertThat(students.count()).isZero();
    }
    private byte[] bytes(Workbook workbook)throws Exception{var stream=new ByteArrayOutputStream();workbook.write(stream);return stream.toByteArray();}
    @Test void genderIsAnExplicitChoiceAcrossImportAndDatabaseWrites() {
        var view=map(reviews.text("owner","CREATE","学号,姓名,性别\n7654321,同名,男",true),Map.of(2,"NEW"),Map.of(2,"TEXT"));
        assertThat(view.stage()).isEqualTo("SCHEMA_READY"); view=reviews.addFields("owner",view.id(),view.revision());reviews.finish("owner",view.id(),view.revision());
        StudentField gender=catalog.fields().get(0);assertThat(gender.options()).containsExactly("男","女");
        students.create("7654322","同名",Map.of(gender.key(),"女"));
        assertThatThrownBy(()->students.create("7654323","同名",Map.of(gender.key(),"未知"))).hasMessageContaining("男或女");
        assertThatThrownBy(()->jdbc.update("INSERT INTO student(student_no,name,"+FieldCatalog.quote(gender.key())+") VALUES (?,?,?)","7654323","同名","X")).isInstanceOf(org.springframework.dao.DataAccessException.class);
        var correction=confirm(reviews.text("owner","CREATE","学号,姓名,性别\n7654323,同名,M",true));
        assertThat(correction.stage()).isEqualTo("ROWS");assertThat(correction.issues()).anyMatch(issue->issue.code().equals("FIELD_TYPE")&&"男".equals(issue.suggestion()));
        final var pending=correction;assertThatThrownBy(()->reviews.finish("owner",pending.id(),pending.revision())).isInstanceOf(AppException.class);
        correction=reviews.patch("owner",correction.id(),patch(correction,null,List.of(new ReviewView.CellEdit(2,2,"男")),null,null,null,null,null));
        reviews.finish("owner",correction.id(),correction.revision());assertThat(students.count()).isEqualTo(3);
    }
    @Test void originalStructuredInputCannotBypassNewFormatRules() {
        var view=map(reviews.text("owner","CREATE","学号,姓名,性别\n7654321,同名,男",true),Map.of(2,"NEW"),Map.of(2,"TEXT"));
        view=reviews.addFields("owner",view.id(),view.revision());reviews.finish("owner",view.id(),view.revision());
        assertThat(reviews.requiresMapping("学号,姓名,性别\n7654322,同名,男")).isFalse();
        assertThat(reviews.requiresMapping("学号,姓名,性别\n0123456,同名,男")).isTrue();
        assertThat(reviews.requiresMapping("学号,姓名,性别\n7654322,"+"名".repeat(16)+",男")).isTrue();
        assertThat(tools.originalReview("owner","学号,姓名,性别\n7654322,同名,M","batch_create_students").code()).isEqualTo("REVIEW_REQUIRED");
        assertThat(students.count()).isEqualTo(1);
    }
}
