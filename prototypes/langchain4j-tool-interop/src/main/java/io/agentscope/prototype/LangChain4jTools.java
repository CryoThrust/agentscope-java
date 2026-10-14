/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.prototype;

import com.fasterxml.jackson.core.type.TypeReference;
import dev.langchain4j.agent.tool.ReturnBehavior;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.service.tool.AiServiceTool;
import dev.langchain4j.service.tool.ToolExecutionResult;
import dev.langchain4j.service.tool.ToolService;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.util.JsonUtils;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

/**
 * Local compatibility prototype; scheduler lifecycle and invocation mapping belong to the caller.
 */
public final class LangChain4jTools {
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    private LangChain4jTools() {}

    /**
     * Reuses LangChain4j's scanner, which is marked Internal in 1.22.0. A production extension
     * needs a documented version policy or a supported upstream discovery API.
     */
    public static List<AgentTool> registerAnnotated(
            Toolkit toolkit,
            Object object,
            Scheduler scheduler,
            Function<ToolCallParam, InvocationContext> contextMapper) {
        return register(toolkit, ToolService.findTools(object), scheduler, contextMapper);
    }

    /**
     * Validates the complete input before registering it; concurrent registry mutation is excluded.
     */
    public static List<AgentTool> register(
            Toolkit toolkit,
            List<AiServiceTool> tools,
            Scheduler scheduler,
            Function<ToolCallParam, InvocationContext> contextMapper) {
        Objects.requireNonNull(toolkit);
        List<AgentTool> adapted =
                tools.stream().map(tool -> adapt(tool, scheduler, contextMapper)).toList();
        HashSet<String> names = new HashSet<>();
        for (AgentTool tool : adapted) {
            if (!names.add(tool.getName()) || toolkit.getTool(tool.getName()) != null) {
                throw new IllegalArgumentException("Duplicate tool name: " + tool.getName());
            }
        }
        adapted.forEach(toolkit::registerAgentTool);
        return adapted;
    }

    /** Adapts explicitly supplied tools without depending on the internal discovery scanner. */
    public static AgentTool adapt(
            AiServiceTool tool,
            Scheduler scheduler,
            Function<ToolCallParam, InvocationContext> contextMapper) {
        return new AdaptedTool(tool, scheduler, contextMapper);
    }

    private static final class AdaptedTool implements AgentTool {
        private final AiServiceTool tool;
        private final Scheduler scheduler;
        private final Function<ToolCallParam, InvocationContext> contextMapper;
        private final String parametersJson;

        private AdaptedTool(
                AiServiceTool tool,
                Scheduler scheduler,
                Function<ToolCallParam, InvocationContext> contextMapper) {
            this.tool = Objects.requireNonNull(tool);
            this.scheduler = Objects.requireNonNull(scheduler);
            this.contextMapper = Objects.requireNonNull(contextMapper);
            if (tool.returnBehavior() == ReturnBehavior.IMMEDIATE_IF_LAST) {
                throw new UnsupportedOperationException(
                        "IMMEDIATE_IF_LAST requires batch-order semantics, not a per-tool boolean");
            }
            // Public lossless serializer preserves $defs, raw constraints, anyOf and strictness.
            Map<String, Object> definition =
                    JsonUtils.getJsonCodec().fromJson(tool.toolSpecification().toJson(), MAP);
            Object parameters = definition.get("parameters");
            parametersJson =
                    JsonUtils.getJsonCodec()
                            .toJson(
                                    parameters == null
                                            ? Map.of("type", "object", "properties", Map.of())
                                            : parameters);
        }

        @Override
        public String getName() {
            return tool.name();
        }

        @Override
        public String getDescription() {
            return Objects.requireNonNullElse(tool.toolSpecification().description(), "");
        }

        @Override
        public Map<String, Object> getParameters() {
            return JsonUtils.getJsonCodec().fromJson(parametersJson, MAP);
        }

        @Override
        public Boolean getStrict() {
            return tool.toolSpecification().strict();
        }

        @Override
        public boolean isReturnDirect() {
            return tool.returnBehavior() == ReturnBehavior.IMMEDIATE;
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            return Mono.fromCallable(
                            () -> {
                                Objects.requireNonNull(param);
                                if (param.getToolUseBlock() == null
                                        || !getName().equals(param.getToolUseBlock().getName())) {
                                    throw new IllegalArgumentException(
                                            "Tool call must name " + getName());
                                }
                                ToolExecutionRequest request =
                                        ToolExecutionRequest.builder()
                                                .id(param.getToolUseBlock().getId())
                                                .name(getName())
                                                .arguments(
                                                        JsonUtils.getJsonCodec()
                                                                .toJson(param.getInput()))
                                                .build();
                                InvocationContext context =
                                        Objects.requireNonNull(
                                                contextMapper.apply(param),
                                                "mapped invocation context");
                                ToolExecutionResult result =
                                        Objects.requireNonNull(
                                                tool.toolExecutor()
                                                        .executeWithContext(request, context));
                                List<ContentBlock> output = new ArrayList<>();
                                for (var content : result.resultContents()) {
                                    if (!(content instanceof TextContent text)) {
                                        throw new UnsupportedOperationException(
                                                "Prototype supports text tool results only: "
                                                        + content.getClass().getSimpleName());
                                    }
                                    output.add(TextBlock.builder().text(text.text()).build());
                                }
                                return ToolResultBlock.builder()
                                        .id(request.id())
                                        .name(getName())
                                        .output(output)
                                        .state(
                                                result.isError()
                                                        ? ToolResultState.ERROR
                                                        : ToolResultState.SUCCESS)
                                        .build();
                            })
                    .subscribeOn(scheduler);
        }
    }
}
