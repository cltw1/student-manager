package com.example.studentmanager;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import jakarta.annotation.PreDestroy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class DeepSeekClient implements ChatModelClient {
    private final DeepSeekProperties config;
    private final ObjectMapper mapper;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ScheduledExecutorService timeouts = Executors.newSingleThreadScheduledExecutor(r -> { Thread thread = new Thread(r, "deepseek-timeout"); thread.setDaemon(true); return thread; });
    public DeepSeekClient(DeepSeekProperties config, ObjectMapper mapper) { this.config = config; this.mapper = mapper; }
    @Override
    public ObjectNode complete(List<ObjectNode> messages, ArrayNode tools, Consumer<String> onDelta) {
        if (!config.configured()) throw new AppException(HttpStatus.SERVICE_UNAVAILABLE, "AI_NOT_CONFIGURED", "请在项目 .env 中填写 DEEPSEEK_API_KEY 并重启后端；学生管理页面可以正常使用");
        ScheduledFuture<?> watchdog = null;
        long timeout = Math.min(120, Math.max(5, config.timeoutSeconds()));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeout);
        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("model", config.model()); body.set("messages", mapper.valueToTree(messages));
            body.set("tools", tools); body.put("stream", true); body.put("max_tokens", 16384);
            body.putObject("thinking").put("type", "disabled");
            String base = config.baseUrl().replaceAll("/+$", "");
            HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/chat/completions"))
                .timeout(Duration.ofSeconds(timeout))
                .header("Content-Type", "application/json").header("Accept", "text/event-stream")
                .header("Authorization", "Bearer " + config.apiKey()).POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build();
            HttpResponse<java.io.InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            watchdog = timeouts.schedule(() -> { try { response.body().close(); } catch (Exception ignored) {} }, Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            try (var input = response.body()) {
                if (response.statusCode() != 200) {
                    String message = switch (response.statusCode()) {
                        case 401 -> "DeepSeek API Key 无效，请检查后端配置";
                        case 402 -> "DeepSeek API 余额不足";
                        case 429 -> "DeepSeek 请求过于频繁，请稍后重试";
                        case 400, 404 -> "DeepSeek 请求配置不兼容，请检查模型名称和 API 地址";
                        default -> "DeepSeek 服务暂时不可用（HTTP " + response.statusCode() + "）";
                    };
                    throw new AppException(HttpStatus.BAD_GATEWAY, "AI_UPSTREAM_ERROR", message);
                }
                StringBuilder content = new StringBuilder();
                Map<Integer, ObjectNode> calls = new TreeMap<>();
                boolean finished = false;
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (!line.startsWith("data:")) continue;
                        String data = line.substring(5).strip();
                        if (data.equals("[DONE]")) { finished = true; break; }
                        if (data.isEmpty()) continue;
                        JsonNode chunk = mapper.readTree(data);
                        if (chunk.has("error")) throw new AppException(HttpStatus.BAD_GATEWAY, "AI_UPSTREAM_ERROR", "模型生成失败，请稍后重试");
                        JsonNode choice = chunk.path("choices").path(0);
                        if (choice.isMissingNode()) continue;
                        String reason = choice.path("finish_reason").asText("");
                        if (reason.equals("stop") || reason.equals("tool_calls")) finished = true;
                        if (reason.equals("length")) throw new AppException(HttpStatus.BAD_GATEWAY, "AI_OUTPUT_LIMIT", "模型输出达到长度限制，未完成的工具不会执行，请缩短指令");
                        JsonNode delta = choice.path("delta");
                        if (delta.path("content").isTextual()) {
                            String text = delta.get("content").asText(); content.append(text); onDelta.accept(text);
                        }
                        for (JsonNode call : delta.path("tool_calls")) {
                            int index = call.path("index").asInt();
                            ObjectNode target = calls.computeIfAbsent(index, ignored -> {
                                ObjectNode node = mapper.createObjectNode(); node.put("id", ""); node.put("type", "function");
                                node.putObject("function").put("name", "").put("arguments", ""); return node;
                            });
                            if (call.hasNonNull("id")) target.put("id", target.path("id").asText() + call.get("id").asText());
                            ObjectNode function = (ObjectNode) target.get("function");
                            if (call.path("function").hasNonNull("name")) function.put("name", function.path("name").asText() + call.path("function").get("name").asText());
                            if (call.path("function").hasNonNull("arguments")) function.put("arguments", function.path("arguments").asText() + call.path("function").get("arguments").asText());
                        }
                    }
                }
                if (!finished) throw new AppException(HttpStatus.BAD_GATEWAY, "AI_STREAM_INTERRUPTED", "模型连接中断，未完成的工具不会执行");
                ObjectNode result = mapper.createObjectNode(); result.put("role", "assistant"); result.put("content", content.toString());
                if (!calls.isEmpty()) {
                    ArrayNode array = result.putArray("tool_calls");
                    for (ObjectNode call : calls.values()) {
                        if (call.path("id").asText().isBlank() || call.path("function").path("name").asText().isBlank())
                            throw new AppException(HttpStatus.BAD_GATEWAY, "INVALID_TOOL_CALL", "模型工具调用不完整，未执行");
                        array.add(call);
                    }
                }
                return result;
            }
        } catch (AppException ex) { throw ex;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt(); throw new AppException(HttpStatus.GATEWAY_TIMEOUT, "AI_TIMEOUT", "模型请求已中断，请查看操作结果卡片");
        } catch (Exception ex) {
            throw new AppException(HttpStatus.BAD_GATEWAY, "AI_CONNECTION_ERROR", "无法完成 DeepSeek 请求，请检查网络和配置；已显示的操作结果仍然有效");
        } finally { if (watchdog != null) watchdog.cancel(false); }
    }
    @PreDestroy void close() { timeouts.shutdownNow(); }
}
