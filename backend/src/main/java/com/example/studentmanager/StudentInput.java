package com.example.studentmanager;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record StudentInput(
    @NotBlank(message = "学号不能为空") @Size(max = 32, message = "学号不能超过32个字符") String studentNo,
    @NotBlank(message = "姓名不能为空") @Size(max = 50, message = "姓名不能超过50个字符") String name,
    String expectedName,
    java.util.Map<String, Object> fields,
    java.util.Map<String, Object> expectedFields
) {
    public StudentInput(String studentNo, String name, String expectedName) { this(studentNo, name, expectedName, null, null); }
}
