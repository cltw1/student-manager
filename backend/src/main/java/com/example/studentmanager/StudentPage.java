package com.example.studentmanager;

import java.util.List;

public record StudentPage(List<Student> items, long total, int page, int pageSize) {}
