package com.example.studentmanager;

import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.springframework.stereotype.Component;

@Component
public class SchemaGate {
    public final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);
}
