CREATE DATABASE IF NOT EXISTS student_manager CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
USE student_manager;
CREATE TABLE IF NOT EXISTS student (
    student_no VARCHAR(7) COLLATE utf8mb4_bin NOT NULL,
    name VARCHAR(15) NOT NULL,
    PRIMARY KEY (student_no),
    CONSTRAINT chk_student_no_format CHECK (student_no REGEXP '^[1-9][0-9]{6}$'),
    CONSTRAINT chk_student_name_length CHECK (CHAR_LENGTH(name) BETWEEN 1 AND 15 AND CHAR_LENGTH(TRIM(name)) > 0)
) ENGINE=InnoDB;
