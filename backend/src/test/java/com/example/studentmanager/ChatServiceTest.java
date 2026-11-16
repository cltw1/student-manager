package com.example.studentmanager;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class ChatServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private ObjectNode response(String text) { var result = mapper.createObjectNode(); result.put("role", "assistant"); result.put("content", text); return result; }
    @Test void replaysRequestWithoutRepeatingWritesAndKeepsResultWhenModelFails() {
        ChatModelClient model = mock(ChatModelClient.class); ToolDispatcher tools = mock(ToolDispatcher.class); ActionService actions = mock(ActionService.class);
        when(tools.definitions()).thenReturn(mapper.createArrayNode());
        ObjectNode first = response("");
        var call = first.putArray("tool_calls").addObject(); call.put("id", "call1"); call.put("type", "function");
        call.putObject("function").put("name", "create_student").put("arguments", "{\"studentNo\":\"1\",\"name\":\"甲\"}");
        when(model.complete(anyList(), any(), any())).thenReturn(first).thenThrow(AppException.bad("模型后续回复失败"));
        when(tools.execute(anyString(), eq("create_student"), anyString())).thenReturn(OperationResult.success("create_student", "STUDENT_CREATED", "新增成功", new Student("1", "甲"), true));
        ChatService chats = new ChatService(model, tools, actions, mapper); String id = chats.create().id();
        List<String> events = new ArrayList<>(); chats.chat(id, "request1", "添加学生", (event, data) -> events.add(event));
        chats.chat(id, "request1", "添加学生", (event, data) -> events.add(event));
        verify(tools, times(1)).execute(anyString(), anyString(), anyString());
        assertThat(chats.detail(id).messages()).hasSize(2);
        assertThat(chats.detail(id).messages().get(1).results.get(0).mutated()).isTrue();
        assertThat(events).contains("result", "error", "done");
    }
    @Test void multipleWritesAreSupportedInOneTurn() {
        ChatModelClient model = mock(ChatModelClient.class); ToolDispatcher tools = mock(ToolDispatcher.class);
        when(tools.definitions()).thenReturn(mapper.createArrayNode()); ObjectNode first = response(""); var calls = first.putArray("tool_calls");
        for (int i = 0; i < 2; i++) { var call = calls.addObject(); call.put("id", "call" + i); call.putObject("function").put("name", "create_student").put("arguments", "{\"studentNo\":\"" + i + "\",\"name\":\"甲\"}"); }
        when(model.complete(anyList(), any(), any())).thenReturn(first).thenReturn(response("完成"));
        when(tools.execute(anyString(), anyString(), anyString())).thenReturn(OperationResult.success("create_student", "STUDENT_CREATED", "新增成功", null, true));
        ChatService chats = new ChatService(model, tools, mock(ActionService.class), mapper); String id = chats.create().id();
        chats.chat(id, "r1", "新增", (event, data) -> {});
        verify(tools, times(2)).execute(anyString(), anyString(), anyString());
        assertThat(chats.detail(id).messages().get(1).results).hasSize(2).allMatch(OperationResult::success);
    }
}
