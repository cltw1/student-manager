package com.example.studentmanager;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record StudentInput(
    @NotBlank(message = "学号不能为空") @Pattern(regexp = "^[1-9][0-9]{6}$", message = "学号必须为7位数字，首位不能为0") String studentNo,
    @NotBlank(message = "姓名不能为空") String name,
    String expectedName,
    java.util.Map<String, Object> fields,
    java.util.Map<String, Object> expectedFields
) {
    public StudentInput(String studentNo, String name, String expectedName) { this(studentNo, name, expectedName, null, null); }
}
