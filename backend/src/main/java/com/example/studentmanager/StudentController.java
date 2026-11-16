package com.example.studentmanager;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/students")
public class StudentController {
    public record FilterRequest(java.util.List<StudentFilter> filters, Integer page, Integer pageSize) {}
    private final StudentService students;
    public StudentController(StudentService students) { this.students = students; }
    @GetMapping
    public ApiResponse<StudentPage> search(@RequestParam(required = false) String studentNo, @RequestParam(required = false) String name,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int pageSize) {
        StudentPage result = students.search(studentNo, name, page, pageSize);
        return ApiResponse.ok("STUDENTS_FOUND", "查询成功，共 " + result.total() + " 条记录", result);
    }
    @GetMapping("/{studentNo}")
    public ApiResponse<Student> get(@PathVariable String studentNo) { return ApiResponse.ok("STUDENT_FOUND", "查询成功", students.get(studentNo)); }
    @PostMapping("/search") public ApiResponse<StudentPage> filter(@RequestBody FilterRequest input) { return ApiResponse.ok("STUDENTS_FOUND","条件查询完成",students.filter(input.filters(),input.page()==null?1:input.page(),input.pageSize()==null?20:input.pageSize())); }
    @PostMapping
    public ApiResponse<Student> create(@Valid @RequestBody StudentInput input) {
        return ApiResponse.ok("STUDENT_CREATED", "新增成功", students.create(input.studentNo(), input.name(), input.fields()));
    }
    @PutMapping("/{studentNo}")
    public ApiResponse<Student> update(@PathVariable String studentNo, @Valid @RequestBody StudentInput input) {
        return ApiResponse.ok("STUDENT_UPDATED", "修改成功", students.update(studentNo, input.studentNo(), input.name(), input.expectedName(), input.fields(), input.expectedFields()));
    }
    @DeleteMapping("/{studentNo}")
    public ApiResponse<Student> delete(@PathVariable String studentNo, @RequestParam(required = false) String expectedName) {
        return ApiResponse.ok("STUDENT_DELETED", "删除成功", students.delete(studentNo, expectedName));
    }
}
