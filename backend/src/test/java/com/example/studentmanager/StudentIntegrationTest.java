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
    @Test void crudPreservesLeadingZerosAndReturnsFailures() throws Exception {
        mvc.perform(post("/api/students").contentType(MediaType.APPLICATION_JSON).content("{\"studentNo\":\"00123\",\"name\":\"张三\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.studentNo").value("00123"));
        mvc.perform(post("/api/students").contentType(MediaType.APPLICATION_JSON).content("{\"studentNo\":\"00123\",\"name\":\"李四\"}"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.success").value(false));
        mvc.perform(get("/api/students").param("name", "张")).andExpect(jsonPath("$.data.total").value(1));
        mvc.perform(put("/api/students/00123").contentType(MediaType.APPLICATION_JSON).content("{\"studentNo\":\"00124\",\"name\":\"李四\",\"expectedName\":\"张三\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.name").value("李四"));
        mvc.perform(delete("/api/students/00124")).andExpect(status().isOk());
        mvc.perform(delete("/api/students/00124")).andExpect(status().isNotFound());
        mvc.perform(get("/api/students")).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
    }
    @Test void invalidInputAndPaginationAreRejected() throws Exception {
        mvc.perform(post("/api/students").contentType(MediaType.APPLICATION_JSON).content("{\"studentNo\":\"12\",\"name\":\"  \"}"))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/students").param("page", "0")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/students").param("pageSize", "101")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/students").param("page", "abc")).andExpect(status().isBadRequest());
        assertThatThrownBy(() -> students.create("12';DROP", "张三")).isInstanceOf(AppException.class);
    }
    @Test void duplicateRenumberRollsBackAndUnchangedUpdateSucceeds() {
        students.create("1", "甲"); students.create("2", "乙");
        assertThatThrownBy(() -> students.update("1", "2", "丙", "甲")).isInstanceOf(DuplicateKeyException.class);
        assertThat(students.get("1").name()).isEqualTo("甲");
        assertThat(students.update("1", "1", "甲", "甲")).isEqualTo(new Student("1", "甲"));
    }
    @Test void likeWildcardsAreLiteralAndNamesMayRepeat() {
        students.create("1", "张三"); students.create("2", "张三"); students.create("3", "百分%号");
        assertThat(students.search(null, "张三", 1, 10).total()).isEqualTo(2);
        assertThat(students.search(null, "%", 1, 10).total()).isEqualTo(1);
        assertThat(students.search(null, "' OR 1=1 --", 1, 10).total()).isZero();
    }
    @Test void actionRequiresOwnerIsReplaySafeAndDetectsChangedData() {
        students.create("1", "甲");
        var pending = actions.prepare("owner", "delete_student", "1", null, null);
        assertThat(students.count()).isEqualTo(1);
        assertThatThrownBy(() -> actions.confirm("other", pending.action().id())).isInstanceOf(AppException.class);
        students.update("1", "1", "乙", "甲");
        assertThat(actions.confirm("owner", pending.action().id()).code()).isEqualTo("STUDENT_CHANGED");
        assertThat(students.count()).isEqualTo(1);
        var next = actions.prepare("owner", "delete_student", "1", null, null);
        var first = actions.confirm("owner", next.action().id());
        assertThat(first.success()).isTrue(); assertThat(actions.confirm("owner", next.action().id())).isSameAs(first);
        assertThat(students.count()).isZero();
    }
    @Test void cancellationDoesNotWriteAndToolsValidateJson() {
        students.create("1", "甲");
        var pending = actions.prepare("owner", "update_student", "1", null, "乙");
        assertThat(actions.cancel("owner", pending.action().id()).code()).isEqualTo("ACTION_CANCELLED");
        assertThat(actions.confirm("owner", pending.action().id()).mutated()).isFalse();
        assertThat(students.get("1").name()).isEqualTo("甲");
        assertThat(tools.execute("owner", "execute_sql", "{\"sql\":\"DROP TABLE student\"}").success()).isFalse();
        assertThat(tools.execute("owner", "create_student", "{\"studentNo\":12,\"name\":\"甲\"}").code()).isEqualTo("REVIEW_REQUIRED");
        assertThat(tools.execute("owner", "create_student", "{\"studentNo\":\"2\",\"name\":\"甲\",\"sql\":\"x\"}").code()).isEqualTo("REVIEW_REQUIRED");
        assertThat(tools.execute("owner", "create_student", "unfinished").success()).isFalse();
    }
}
