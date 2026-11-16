package com.example.studentmanager;

import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/fields")
public class FieldController {
    private final FieldCatalog catalog;
    public FieldController(FieldCatalog catalog) { this.catalog = catalog; }
    @GetMapping public ApiResponse<List<StudentField>> fields() { return ApiResponse.ok("FIELDS_FOUND", "字段读取成功", catalog.fields()); }
}
