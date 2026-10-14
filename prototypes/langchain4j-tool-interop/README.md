# LangChain4j tool interoperability prototype

Provider-free proof of the proposed AgentScope Java #3542 adapter. This is a standalone prototype, not an integrated or released AgentScope extension.

Tested with LangChain4j **1.22.0**, Java **17**, and AgentScope Core **2.1.0-SNAPSHOT** built from main `febc795fc6afa6b3b81b2d6dde21dfca4f464374`.

From the AgentScope repository root, install its local source artifacts, then run this fixture:

```sh
mvn -T1 -pl agentscope-core,agentscope-dependencies-bom -am -DskipTests install
cd prototypes/langchain4j-tool-interop
mvn spotless:check clean verify
```

The adapter registers ordinary `AgentTool` objects into `Toolkit`, reuses the bound LangChain4j executor, and converts the published `ToolSpecification.toJson()` representation through AgentScope's JSON codec. That preserves required fields, nested properties, enum/array schemas, raw constraints, `$defs`/`$ref`, nullable `anyOf` and the strict flag without a second schema mapper. Returned schema maps are independent copies. Duplicate input or existing names are rejected before the batch changes the registry; this assumes registration is not concurrently mutated elsewhere.

The caller supplies a bounded worker scheduler and an explicit per-call `InvocationContext` mapper. The adapter does not share RuntimeContext or credentials through mutable fields. It retains the call ID/name, ordered text blocks and explicit error state. Synchronous thrown exceptions remain exceptions at the adapter boundary; Toolkit applies its existing error handling. Read-only stays false, so importing a foreign tool does not grant Plan Mode permission. Scheduler ownership stays with the caller.

The 19 controls cover real inherited/renamed annotated methods, actual nested record/enum/array execution, Toolkit rejection of invalid input, schema fidelity and mutation isolation, duplicate registration, return flags, error/result identity, text ordering, unsupported result types, execution deferral, worker offload, cancellation and overlapping memory-ID/invocation-parameter isolation. Removing worker offload makes the thread-isolation control fail; restoring it passes again.

Decisions still needed before an extension PR:

- `ToolService.findTools` is marked **Internal** even in the released version. Explicit `AiServiceTool` adaptation uses public APIs; annotation discovery needs a documented version policy or a supported upstream discovery entry point.
- `IMMEDIATE` maps to returnDirect, but this fixture does not prove equivalence of complete multi-tool agent loops. `IMMEDIATE_IF_LAST` cannot be represented by one per-tool boolean and is rejected rather than silently changed.
- Native image/audio/video results need a separate content mapping; this text-result prototype rejects them. Compensation, dynamic tool providers, tool-search metadata, executor result attributes and automatic context/credential sharing are not adapted here.
- This is direct adapter/Toolkit verification, not a model/provider, full ReAct permission/middleware, deployment or full repository test. A production extension still needs registry/BOM integration, EN/ZH documentation, supported-version checks and those broader contracts.
