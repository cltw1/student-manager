package com.example.studentmanager;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.function.Consumer;

public interface ChatModelClient {
    ObjectNode complete(List<ObjectNode> messages, ArrayNode tools, Consumer<String> onDelta);
}
