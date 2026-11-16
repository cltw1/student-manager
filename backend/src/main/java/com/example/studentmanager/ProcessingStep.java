package com.example.studentmanager;

import java.time.Instant;

public record ProcessingStep(String id, String title, String detail, String status, Instant time) {}
