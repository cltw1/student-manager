package com.example.studentmanager;

import java.util.List;
import java.util.Set;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/batches")
public class BatchController {
    public record PreviewRequest(@NotBlank String conversationId, @NotBlank String mode, String text, List<BatchReport.Input> rows) {}
    private final BatchService batches;
    private final ActionService actions;
    private final ChatService chats;
    private final ExcelImportService excel;
    private final ReviewService reviews;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper;
    private final FieldCatalog catalog;
    public BatchController(BatchService batches, ActionService actions, ChatService chats, ExcelImportService excel, ReviewService reviews, com.fasterxml.jackson.databind.ObjectMapper mapper, FieldCatalog catalog) { this.batches = batches; this.actions = actions; this.chats = chats; this.excel = excel; this.reviews = reviews; this.mapper = mapper; this.catalog = catalog; }
    @PostMapping("/preview")
    public ApiResponse<OperationResult> preview(@Valid @RequestBody PreviewRequest request) {
        String mode = BatchService.mode(request.mode());
        chats.require(request.conversationId()); ReviewView view;
        if (request.rows() == null) view = reviews.text(request.conversationId(), mode, request.text(), null);
        else {
            var rows = mapper.createArrayNode();
            for (var input : request.rows()) { var row = rows.addObject(); if (input.studentNo() != null) row.put("学号", input.studentNo()); if (input.name() != null) row.put("姓名", input.name()); if (input.newStudentNo() != null) row.put("新学号", input.newStudentNo());
                if (input.fields() != null) input.fields().forEach((key, value) -> row.set(catalog.fields().stream().filter(field -> field.key().equals(key)).map(StudentField::label).findFirst().orElse(key), mapper.valueToTree(value))); }
            view = reviews.objects(request.conversationId(), mode, rows);
        }
        OperationResult result = chats.external(request.conversationId(), "校对批量" + label(mode), () -> reviews.reference(view));
        return ApiResponse.ok("BATCH_PREVIEW", "批量预览已生成", result);
    }
    @PostMapping(value = "/excel", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<OperationResult> upload(@RequestParam String conversationId, @RequestParam(defaultValue = "UPSERT") String mode, @RequestParam MultipartFile file) {
        String type = BatchService.mode(mode);
        if (!Set.of("CREATE", "UPDATE", "UPSERT").contains(type)) throw AppException.bad("Excel导入仅支持新增、更新或新增及更新");
        chats.require(conversationId);
        ReviewView view;
        try { view = reviews.excel(conversationId, type, file.getBytes(), file.getOriginalFilename()); } catch (java.io.IOException ex) { throw AppException.bad("无法读取上传文件，请重新选择"); }
        OperationResult result = chats.external(conversationId, "校对Excel：" + file.getOriginalFilename(), () -> reviews.reference(view));
        return ApiResponse.ok("BATCH_PREVIEW", "Excel已解析，请查看预览", result);
    }
    @GetMapping("/template")
    public ResponseEntity<byte[]> template() {
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=students-template.xlsx")
            .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")).body(excel.template());
    }
    private String label(String mode) {
        return switch (mode) { case "CREATE" -> "新增"; case "UPDATE" -> "更新"; case "UPSERT" -> "新增或更新"; case "DELETE" -> "删除"; default -> "查询"; };
    }
}
