package com.example.studentmanager;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")
class StudentIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired StudentService students;
    @Autowired JdbcTemplate jdbc;
    @Autowired ActionService actions;
    @Autowired ToolDispatcher tools;
    @BeforeEach void clean() { jdbc.update("DELETE FROM student"); }
    @Test void crudEnforcesUniqueSevenDigitNumbersAndReturnsFailures() throws Exception {
        mvc.perform(post("/api/students").contentType(MediaType.APPLICATION_JSON).content("{\"studentNo\":\"1000123\",\"name\":\"张三\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.studentNo").value("1000123"));
        mvc.perform(post("/api/students").contentType(MediaType.APPLICATION_JSON).content("{\"studentNo\":\"1000123\",\"name\":\"李四\"}"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.success").value(false));
        mvc.perform(get("/api/students").param("name", "张")).andExpect(jsonPath("$.data.total").value(1));
        mvc.perform(put("/api/students/1000123").contentType(MediaType.APPLICATION_JSON).content("{\"studentNo\":\"1000124\",\"name\":\"李四\",\"expectedName\":\"张三\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.name").value("李四"));
        mvc.perform(delete("/api/students/1000124")).andExpect(status().isOk());
        mvc.perform(delete("/api/students/1000124")).andExpect(status().isNotFound());
        mvc.perform(get("/api/students")).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
    }
    @Test void invalidInputAndPaginationAreRejected() throws Exception {
        mvc.perform(post("/api/students").contentType(MediaType.APPLICATION_JSON).content("{\"studentNo\":\"1000012\",\"name\":\"  \"}"))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/students").param("page", "0")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/students").param("pageSize", "101")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/students").param("page", "abc")).andExpect(status().isBadRequest());
        assertThatThrownBy(() -> students.create("12';DROP", "张三")).isInstanceOf(AppException.class);
    }
    @Test void sevenDigitStudentNumbersAndFifteenCharacterNamesAreEnforced() throws Exception {
        for (String id : java.util.List.of("123456", "12345678", "0123456", "A123456", "１２３４５６７")) {
            mvc.perform(post("/api/students").contentType(MediaType.APPLICATION_JSON).content("{\"studentNo\":\"" + id + "\",\"name\":\"测试\"}")).andExpect(status().isBadRequest());
        }
        String name = "名".repeat(15);
        students.create("7654321", name); students.create("7654322", name);
        assertThat(students.search(null, name, 1, 10).total()).isEqualTo(2);
        assertThatThrownBy(() -> students.create("7654323", name + "名")).hasMessageContaining("15");
        assertThat(StudentService.validate("𠮷".repeat(15), "姓名", 15)).isEqualTo("𠮷".repeat(15));
        assertThatThrownBy(() -> jdbc.update("INSERT INTO student(student_no,name) VALUES (?,?)", "0123456", "数据库验证")).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
    @Test void duplicateRenumberRollsBackAndUnchangedUpdateSucceeds() {
        students.create("1000001", "甲"); students.create("1000002", "乙");
        assertThatThrownBy(() -> students.update("1000001", "1000002", "丙", "甲")).isInstanceOf(DuplicateKeyException.class);
        assertThat(students.get("1000001").name()).isEqualTo("甲");
        assertThat(students.update("1000001", "1000001", "甲", "甲")).isEqualTo(new Student("1000001", "甲"));
    }
    @Test void likeWildcardsAreLiteralAndNamesMayRepeat() {
        students.create("1000001", "张三"); students.create("1000002", "张三"); students.create("1000003", "百分%号");
        assertThat(students.search(null, "张三", 1, 10).total()).isEqualTo(2);
        assertThat(students.search(null, "%", 1, 10).total()).isEqualTo(1);
        assertThat(students.search(null, "' OR 1=1 --", 1, 10).total()).isZero();
    }
    @Test void actionRequiresOwnerIsReplaySafeAndDetectsChangedData() {
        students.create("1000001", "甲");
        var pending = actions.prepare("owner", "delete_student", "1000001", null, null);
        assertThat(students.count()).isEqualTo(1);
        assertThatThrownBy(() -> actions.confirm("other", pending.action().id())).isInstanceOf(AppException.class);
        students.update("1000001", "1000001", "乙", "甲");
        assertThat(actions.confirm("owner", pending.action().id()).code()).isEqualTo("STUDENT_CHANGED");
        assertThat(students.count()).isEqualTo(1);
        var next = actions.prepare("owner", "delete_student", "1000001", null, null);
        var first = actions.confirm("owner", next.action().id());
        assertThat(first.success()).isTrue(); assertThat(actions.confirm("owner", next.action().id())).isSameAs(first);
        assertThat(students.count()).isZero();
    }
    @Test void cancellationDoesNotWriteAndToolsValidateJson() {
        students.create("1000001", "甲");
        var pending = actions.prepare("owner", "update_student", "1000001", null, "乙");
        assertThat(actions.cancel("owner", pending.action().id()).code()).isEqualTo("ACTION_CANCELLED");
        assertThat(actions.confirm("owner", pending.action().id()).mutated()).isFalse();
        assertThat(students.get("1000001").name()).isEqualTo("甲");
        assertThat(tools.execute("owner", "execute_sql", "{\"sql\":\"DROP TABLE student\"}").success()).isFalse();
        assertThat(tools.execute("owner", "create_student", "{\"studentNo\":12,\"name\":\"甲\"}").code()).isEqualTo("REVIEW_REQUIRED");
        assertThat(tools.execute("owner", "create_student", "{\"studentNo\":\"1000002\",\"name\":\"甲\",\"sql\":\"x\"}").code()).isEqualTo("REVIEW_REQUIRED");
        assertThat(tools.execute("owner", "create_student", "unfinished").success()).isFalse();
    }
}
