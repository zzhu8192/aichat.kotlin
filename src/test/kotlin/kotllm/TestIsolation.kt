package kotllm

import kotllm.config.Config

/** Detaches a test `Config` from whatever happens to be in the real config
 * directory.
 *
 * `Memory` and `RagStore` both resolve their locations through
 * `config.Paths`, which follows `KOTLLM_CONFIG_DIR`. So a developer with a
 * memory note saved, or an index left active, gets a different system prompt
 * than a fresh checkout does -- and any spec asserting on an exact system
 * prompt, or on a token budget tight enough that a few hundred injected
 * characters matter, fails for a reason that has nothing to do with what it
 * tests.
 *
 * That is not hypothetical: running `mvn -Plive test` with a populated config
 * dir failed three specs this way, two of them by pushing a deliberately tiny
 * rate-limit budget over its threshold. Specs that are actually about
 * memories or retrieval point at a temp directory instead.
 */
object TestIsolation {

    fun isolate(c: Config): Config =
        c.copy(
            memory = c.memory.copy(enabled = false),
            rag = c.rag.copy(enabled = false),
            // Skills are the same hazard and then some: the default roots include
            // `~/.claude/skills` and every `.claude/skills` between here and the
            // filesystem root, so a developer who uses Claude Code has a populated
            // one and would get a different system prompt than CI does.
            skills = c.skills.copy(enabled = false),
            // The same hazard once more, and the one this object originally missed:
            // user tools are compiled from `<config dir>/tools/`, so a developer with
            // any tool file saved gets it prepended to the system prompt and every
            // spec asserting on an exact prompt fails on their machine but not in CI.
            // `UserToolsSpec` points at its own temp directory, so nothing that is
            // actually about user tools relies on this being on.
            userToolsEnabled = false,
            // `save` defaults to **true**, so every spec that built a Runner wrote a
            // real JSONL into `Paths.sessionsDir` -- the developer's own config dir
            // whenever `KOTLLM_CONFIG_DIR` is set, which `runit.bat` sets. A test run
            // left a scatter of `stub-model` conversations in the user's history,
            // and `search_chats` then retrieved them alongside real ones. Specs that
            // are actually about persistence construct their own `SessionStore` over
            // a temp directory, so nothing needs this on.
            save = false,
            // The prompt is a file on disk now, so every Runner spec silently depended
            // on whatever `agents/chat.md` the developer happened to have. Pinning it
            // to the packaged default is the same isolation this object already does
            // for memories, skills and user tools.
            chatAgent = kotllm.prompt.ChatAgent.packaged
        )
}
