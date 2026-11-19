package com.example.studentmanager;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest @ActiveProfiles("test")
class ChatWorkflowIntegrationTest {
    @MockitoBean ChatModelClient model;
    @Autowired ChatService chats;
    @Autowired StudentService students;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @BeforeEach void clean() { jdbc.update("DELETE FROM student"); reset(model); }
    private ObjectNode response() { return mapper.createObjectNode().put("role", "assistant").put("content", ""); }
    private void toolResponse(String tool, String arguments) {
        ObjectNode toolCall = response(); var call = toolCall.putArray("tool_calls").addObject();
        call.put("id", "tool_" + System.nanoTime()).put("type", "function"); call.putObject("function").put("name", tool).put("arguments", arguments);
        when(model.complete(anyList(), any(), any())).thenReturn(toolCall).thenReturn(response());
    }
    @Test void createQueryModifyCancelAndDeleteUseActualBusinessServices() {
        String owner = chats.create().id();
        toolResponse("create_student", "{\"studentNo\":\"1000001\",\"name\":\"张三\"}");
        chats.chat(owner, "r1", "添加学生001张三", (event, data) -> {});
        assertThat(students.get("1000001").name()).isEqualTo("张三");
        toolResponse("get_student", "{\"studentNo\":\"1000001\"}");
        chats.chat(owner, "r2", "查询001", (event, data) -> {});
        assertThat(chats.detail(owner).messages().get(3).results.get(0).code()).isEqualTo("STUDENT_FOUND");
        toolResponse("update_student", "{\"studentNo\":\"1000001\",\"newStudentNo\":\"1000002\",\"name\":\"李四\"}");
        chats.chat(owner, "r3", "把001改成002李四", (event, data) -> {});
        var pending = chats.detail(owner).messages().get(5).results.get(0);
        assertThat(students.get("1000001").name()).isEqualTo("张三");
        assertThat(chats.resolve(owner, pending.action().id(), true).code()).isEqualTo("STUDENT_UPDATED");
        assertThat(chats.resolve(owner, pending.action().id(), true).code()).isEqualTo("STUDENT_UPDATED");
        assertThat(students.get("1000002").name()).isEqualTo("李四");
        assertThat(chats.detail(owner).messages().get(5).results.get(0).action().status()).isEqualTo("CONFIRMED");
        toolResponse("delete_student", "{\"studentNo\":\"1000002\"}");
        chats.chat(owner, "r4", "删除002", (event, data) -> {});
        var cancelled = chats.detail(owner).messages().get(7).results.get(0);
        assertThat(chats.resolve(owner, cancelled.action().id(), false).code()).isEqualTo("ACTION_CANCELLED");
        assertThat(students.count()).isEqualTo(1);
        toolResponse("delete_student", "{\"studentNo\":\"1000002\"}");
        chats.chat(owner, "r5", "删除002", (event, data) -> {});
        var deletion = chats.detail(owner).messages().get(9).results.get(0);
        assertThat(chats.resolve(owner, deletion.action().id(), true).code()).isEqualTo("STUDENT_DELETED");
        assertThat(students.count()).isZero();
    }
}
