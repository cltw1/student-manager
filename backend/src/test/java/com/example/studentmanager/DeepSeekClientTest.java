package com.example.studentmanager;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class DeepSeekClientTest {
    private final ObjectMapper mapper = new ObjectMapper();
    @Test void assemblesStreamedToolArgumentsAndSendsKeyOnlyToBackendUpstream() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer test-key");
            var request = mapper.readTree(exchange.getRequestBody()); assertThat(request.path("thinking").path("type").asText()).isEqualTo("disabled");
            String stream = "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call1\",\"type\":\"function\",\"function\":{\"name\":\"get_student\",\"arguments\":\"{\\\"studentNo\\\":\"}}]},\"finish_reason\":null}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"\\\"001\\\"}\"}}]},\"finish_reason\":\"tool_calls\"}]}\n\ndata: [DONE]\n\n";
            byte[] bytes = stream.getBytes(StandardCharsets.UTF_8); exchange.getResponseHeaders().add("Content-Type", "text/event-stream"); exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        try {
            var client = new DeepSeekClient(new DeepSeekProperties("http://127.0.0.1:" + server.getAddress().getPort(), "test-key", "test-model", 10), mapper);
            var result = client.complete(List.of(mapper.createObjectNode().put("role", "user").put("content", "查询")), mapper.createArrayNode(), text -> {});
            assertThat(result.path("tool_calls").path(0).path("function").path("arguments").asText()).isEqualTo("{\"studentNo\":\"001\"}");
        } finally { server.stop(0); }
    }
    @Test void missingApiKeyReturnsExplicitError() {
        var client = new DeepSeekClient(new DeepSeekProperties("https://api.deepseek.com", "", "deepseek-flash", 10), mapper);
        assertThatThrownBy(() -> client.complete(List.of(), mapper.createArrayNode(), text -> {})).isInstanceOf(AppException.class).hasMessageContaining("DEEPSEEK_API_KEY");
    }
    @Test void incompleteStreamCannotReturnAnExecutableToolCall() throws Exception {
        assertRejected(200, "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"c1\",\"function\":{\"name\":\"delete_student\",\"arguments\":\"{\\\"studentNo\\\":\\\"1\\\"}\"}}]}}]}\n\n", "模型连接中断");
    }
    @Test void invalidApiKeyReturnsReadableFeedback() throws Exception {
        assertRejected(401, "{\"error\":{\"message\":\"secret upstream details\"}}", "API Key 无效");
    }
    @Test void privateReasoningContentIsNotForwardedToUi() throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/chat/completions",exchange->{exchange.getRequestBody().readAllBytes();String stream="data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"PRIVATE_REASONING\"}}]}\n\ndata: {\"choices\":[{\"delta\":{\"content\":\"处理结果摘要\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n";byte[] bytes=stream.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();});server.start();
        var client=new DeepSeekClient(new DeepSeekProperties("http://127.0.0.1:"+server.getAddress().getPort(),"test-key","test",10),mapper);List<String> deltas=new ArrayList<>();
        try{var response=client.complete(List.of(),mapper.createArrayNode(),deltas::add);assertThat(deltas).containsExactly("处理结果摘要");assertThat(response.toString()).doesNotContain("PRIVATE_REASONING","reasoning_content");}finally{client.close();server.stop(0);}
    }
    private void assertRejected(int status, String body, String message) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes(); byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        var client = new DeepSeekClient(new DeepSeekProperties("http://127.0.0.1:" + server.getAddress().getPort(), "test-key", "test", 10), mapper);
        try { assertThatThrownBy(() -> client.complete(List.of(), mapper.createArrayNode(), text -> {})).isInstanceOf(AppException.class).hasMessageContaining(message); }
        finally { client.close(); server.stop(0); }
    }
}
