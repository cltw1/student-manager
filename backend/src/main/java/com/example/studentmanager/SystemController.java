package com.example.studentmanager;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SystemController {
    private final StudentService students;
    private final DeepSeekProperties config;
    public SystemController(StudentService students, DeepSeekProperties config) { this.students = students; this.config = config; }
    @GetMapping("/api/status")
    public ApiResponse<Map<String, Object>> status() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("application", "student-ai-manager");
        data.put("aiConfigured", config.configured()); data.put("model", config.model());
        try { data.put("studentCount", students.count()); data.put("databaseConnected", true); }
        catch (Exception ex) { data.put("databaseConnected", false); data.put("databaseMessage", "无法连接数据库，请检查 MySQL 和 .env 配置"); }
        data.put("chatStorage", "memory"); return ApiResponse.ok("OK", "服务运行中", data);
    }
}
