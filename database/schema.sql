CREATE DATABASE IF NOT EXISTS student_manager CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
USE student_manager;
CREATE TABLE IF NOT EXISTS student (
    student_no VARCHAR(32) COLLATE utf8mb4_bin NOT NULL,
    name VARCHAR(50) NOT NULL,
    PRIMARY KEY (student_no),
    CONSTRAINT chk_student_no_not_blank CHECK (CHAR_LENGTH(TRIM(student_no)) > 0),
    CONSTRAINT chk_student_name_not_blank CHECK (CHAR_LENGTH(TRIM(name)) > 0)
) ENGINE=InnoDB;
