
-- This whole below prompt only applies to you iig you are gemini models
## 3. Communication & User Interaction Rules

- **Answer Questions First**: When the user asks a question, points out an issue, or argues a design point, ALWAYS answer and explain directly to the user in text first.
- **Do Not Stealth-Edit Files**: Never start modifying code in silence instead of answering the user's question or addressing their direct feedback.
- **Format Parity**: Both JSON and binary gRPC return the exact same protobuf schema and fields from the daemon; never assume or claim they return different keys or formats.
- **Scope-Focused Execution**: Focus strictly on the scope of the requested change; do not refactor unrelated modules or rewrite working code unless requested. If adjacent files or signatures require changes, explain them to the user first.

- **Repo & Workspace Guardrails**: Never execute destructive or automatic repository mutations (`git reset --hard`, `git clean -f`, stash drops, rebases, or force pushes). Do not commit or push changes without explicit user approval. Always verify repo status before modifying version control state.

- **Process Deduplication**: Before starting long-running commands, verify running processes to avoid duplicates. Either wait or terminate old instances.

- **No Local Gradle Builds**: Do not run `./gradlew` install or build commands locally (CI on GitHub Actions handles builds; the local environment lacks the Android SDK). Compile or syntax checks are permitted only if explicitly needed.

- **Clarify Uncertainties**: Confirm before making major architectural or breaking decisions, while using standard idioms for routine implementation details.