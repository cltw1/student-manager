package com.example.studentmanager;

import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/reviews")
public class ReviewController {
    public record TextRequest(String conversationId, String mode, String text, Boolean hasHeader) {}
    public record VersionRequest(String conversationId, int revision) {}
    public record SourceRequest(String conversationId, int revision, Integer sheetIndex, Integer headerRow, Boolean hasHeader) {}
    public record RetryRequest(String conversationId, BatchReport report) {}
    private final ReviewService reviews;
    private final ChatService chats;
    public ReviewController(ReviewService reviews, ChatService chats) { this.reviews = reviews; this.chats = chats; }
    @PostMapping("/text")
    public ApiResponse<ReviewView> text(@RequestBody TextRequest input) {
        chats.require(input.conversationId()); ReviewView view = reviews.text(input.conversationId(), input.mode(), input.text(), input.hasHeader());
        chats.external(input.conversationId(), "校对文本数据", () -> reviews.reference(view)); return ok(view);
    }
    @PostMapping(value = "/excel", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<ReviewView> excel(@RequestParam String conversationId, @RequestParam(defaultValue = "UPSERT") String mode, @RequestParam MultipartFile file) throws Exception {
        chats.require(conversationId); ReviewView view = reviews.excel(conversationId, mode, file.getBytes(), file.getOriginalFilename());
        chats.external(conversationId, "校对Excel数据", () -> reviews.reference(view)); return ok(view);
    }
    @GetMapping("/{id}") public ApiResponse<ReviewView> get(@PathVariable String id, @RequestParam String conversationId) { chats.require(conversationId); return ok(reviews.get(conversationId, id)); }
    @PatchMapping("/{id}") public ApiResponse<ReviewView> patch(@PathVariable String id, @RequestParam String conversationId, @RequestBody ReviewView.Patch patch) { chats.require(conversationId); return ok(reviews.patch(conversationId, id, patch)); }
    @PostMapping("/{id}/source") public ApiResponse<ReviewView> source(@PathVariable String id, @RequestBody SourceRequest input) { chats.require(input.conversationId()); return ok(reviews.source(input.conversationId(), id, input.revision(), input.sheetIndex(), input.headerRow(), input.hasHeader())); }
    @PostMapping("/{id}/suggest") public ApiResponse<ReviewView> suggest(@PathVariable String id, @RequestBody VersionRequest input) { chats.require(input.conversationId()); return ok(reviews.suggest(input.conversationId(), id, input.revision())); }
    @PostMapping("/{id}/fields") public ApiResponse<ReviewView> fields(@PathVariable String id, @RequestBody VersionRequest input) {
        chats.require(input.conversationId()); ReviewView view = reviews.addFields(input.conversationId(), id, input.revision());
        chats.reviewUpdate(input.conversationId(), id, reviews.reference(view)); return ok(view);
    }
    @PostMapping("/{id}/finish") public ApiResponse<ReviewView> finish(@PathVariable String id, @RequestBody VersionRequest input) {
        chats.require(input.conversationId()); ReviewView view = reviews.finish(input.conversationId(), id, input.revision());
        if (view.result() != null) chats.reviewUpdate(input.conversationId(), view.id(), view.result()); return ok(view);
    }
    @PostMapping("/{id}/cancel") public ApiResponse<ReviewView> cancel(@PathVariable String id, @RequestBody VersionRequest input) { chats.require(input.conversationId()); ReviewView view = reviews.cancel(input.conversationId(), id, input.revision()); if (view.result() != null) chats.reviewUpdate(input.conversationId(), id, view.result()); return ok(view); }
    @PostMapping(value = "/{id}/backfill", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<ReviewView> backfill(@PathVariable String id, @RequestParam String conversationId, @RequestParam MultipartFile file) throws Exception {
        chats.require(conversationId); return ok(reviews.backfillFile(conversationId, id, file.getBytes(), file.getOriginalFilename()));
    }
    @GetMapping("/{id}/backfill-template")
    public ResponseEntity<byte[]> backfillTemplate(@PathVariable String id, @RequestParam String conversationId) throws Exception {
        chats.require(conversationId); ReviewView view = reviews.get(conversationId, id);
        try (var workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook(); var output = new java.io.ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("已有学生补全"); var header = sheet.createRow(0); header.createCell(0).setCellValue("学号"); header.createCell(1).setCellValue("姓名");
            for (int i = 0; i < view.proposedFields().size(); i++) header.createCell(i + 2).setCellValue(view.proposedFields().get(i).label());
            var style = workbook.createCellStyle(); style.setDataFormat(workbook.createDataFormat().getFormat("@"));
            for (int i = 0; i < view.existingStudents().size(); i++) { Student student = view.existingStudents().get(i); var row = sheet.createRow(i + 1); row.createCell(0).setCellValue(student.studentNo()); row.getCell(0).setCellStyle(style); row.createCell(1).setCellValue(student.name());
                for (int j = 0; j < view.proposedFields().size(); j++) { Object value = view.backfill().getOrDefault(student.studentNo(), Map.of()).get(view.proposedFields().get(j).key()); if (value != null) row.createCell(j + 2).setCellValue(value.toString()); } }
            for (int i = 0; i < view.proposedFields().size() + 2; i++) sheet.setColumnWidth(i, 24 * 256); sheet.createFreezePane(2, 1); workbook.write(output);
            return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=existing-students-backfill.xlsx").contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")).body(output.toByteArray());
        }
    }
    @PostMapping("/retry") public ApiResponse<ReviewView> retry(@RequestBody RetryRequest input) { chats.require(input.conversationId()); ReviewView view = reviews.retry(input.conversationId(), input.report()); chats.external(input.conversationId(), "修正失败记录", () -> reviews.reference(view)); return ok(view); }
    private ApiResponse<ReviewView> ok(ReviewView view) { return ApiResponse.ok("REVIEW_STATE", view.message(), view); }
}
