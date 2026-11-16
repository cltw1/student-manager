package com.example.studentmanager;

import jakarta.annotation.PreDestroy;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api")
public class ChatController {
    public record ChatRequest(@NotBlank String conversationId,
        @NotBlank @Size(max = 80) String requestId,
        @NotBlank @Size(max = 30000, message = "每条消息最多30000个字符") String message) {}
    public record ActionRequest(@NotBlank String conversationId) {}
    private final ChatService chats;
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(2, 4, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(16), r -> {
        Thread t = new Thread(r, "ai-chat-worker"); t.setDaemon(true); return t;
    });
    public ChatController(ChatService chats) { this.chats = chats; }
    @GetMapping("/conversations")
    public ApiResponse<List<ChatService.ConversationView>> list() { return ApiResponse.ok("OK", "查询成功", chats.list()); }
    @PostMapping("/conversations")
    public ApiResponse<ChatService.ConversationView> create() { return ApiResponse.ok("OK", "会话已创建", chats.create()); }
    @GetMapping("/conversations/{id}")
    public ApiResponse<ChatService.ConversationDetail> detail(@PathVariable String id) { return ApiResponse.ok("OK", "查询成功", chats.detail(id)); }
    @DeleteMapping("/conversations/{id}")
    public ApiResponse<Void> delete(@PathVariable String id) { chats.delete(id); return ApiResponse.ok("OK", "会话已删除", null); }
    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@Valid @RequestBody ChatRequest request) {
        chats.require(request.conversationId());
        if (executor.getQueue().remainingCapacity() == 0) throw AppException.bad("服务繁忙，请稍后重试");
        SseEmitter emitter = new SseEmitter(240000L);
        AtomicBoolean connected = new AtomicBoolean(true);
        emitter.onCompletion(() -> connected.set(false)); emitter.onTimeout(() -> { connected.set(false); emitter.complete(); });
        emitter.onError(ex -> connected.set(false));
        executor.execute(() -> {
            try {
                chats.chat(request.conversationId(), request.requestId(), request.message().strip(), (event, data) -> {
                    if (!connected.get()) return;
                    try { emitter.send(SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON)); }
                    catch (IOException | IllegalStateException ex) { connected.set(false); }
                });
            } catch (Exception ex) {
                if (connected.get()) try { emitter.send(SseEmitter.event().name("error").data(Map.of("message", "会话处理失败，请刷新查询核实"))); } catch (Exception ignored) {}
            } finally { if (connected.get()) emitter.complete(); }
        });
        return emitter;
    }
    @PostMapping("/chat/actions/{id}/confirm")
    public ApiResponse<OperationResult> confirm(@PathVariable String id, @Valid @RequestBody ActionRequest request) {
        return ApiResponse.ok("ACTION_PROCESSED", "操作已处理", chats.resolve(request.conversationId(), id, true));
    }
    @PostMapping("/chat/actions/{id}/cancel")
    public ApiResponse<OperationResult> cancel(@PathVariable String id, @Valid @RequestBody ActionRequest request) {
        return ApiResponse.ok("ACTION_PROCESSED", "操作已处理", chats.resolve(request.conversationId(), id, false));
    }
    @PreDestroy void stop() { executor.shutdownNow(); }
}
