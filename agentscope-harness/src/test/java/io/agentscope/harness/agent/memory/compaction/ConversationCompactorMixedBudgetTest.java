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
package io.agentscope.harness.agent.memory.compaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.model.Model;
import io.agentscope.harness.agent.memory.MemoryFlushManager;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ConversationCompactorMixedBudgetTest {
    private final ConversationCompactor compactor =
            new ConversationCompactor(mock(Model.class), mock(MemoryFlushManager.class));

    @Test
    void nonToolTextDoesNotInflatePrunableResultBudget() {
        assertBelowMinimum(TextBlock.builder().text("n".repeat(10_000)).build(), Set.of());
    }

    @Test
    void excludedSiblingDoesNotInflatePrunableResultBudget() {
        assertBelowMinimum(result("excluded", "read_file", 10_000), Set.of("read_file"));
    }

    private void assertBelowMinimum(ContentBlock sibling, Set<String> excludedTools) {
        ToolResultBlock eligible = result("eligible", "execute", 1_000);
        List<ContentBlock> blocks = List.of(sibling, eligible);
        Msg original =
                Msg.builder()
                        .id("batch")
                        .role(MsgRole.TOOL)
                        .content(blocks)
                        .metadata(Map.of("source", "review"))
                        .build();
        CompactionConfig config =
                CompactionConfig.builder()
                        .triggerTokens(Integer.MAX_VALUE)
                        .triggerMessages(0)
                        .prune(
                                CompactionConfig.PruneConfig.builder()
                                        .maxOutputChars(100)
                                        .minimumTokens(3_000)
                                        .protectTokens(0)
                                        .excludedTools(excludedTools)
                                        .build())
                        .build();

        assertTrue(
                compactor
                        .compactIfNeeded(null, List.of(original), config, "agent", "session")
                        .block()
                        .isEmpty());
        assertEquals("batch", original.getId());
        assertEquals(Map.of("source", "review"), original.getMetadata());
        assertSame(sibling, original.getContent().get(0));
        assertSame(eligible, original.getContent().get(1));
        assertEquals("x".repeat(1_000), ((TextBlock) eligible.getOutput().get(0)).getText());
    }

    private static ToolResultBlock result(String id, String name, int chars) {
        return ToolResultBlock.of(id, name, TextBlock.builder().text("x".repeat(chars)).build());
    }
}
