package baritone.api;

/** Wire protocol used by an OpenAI-compatible language-model endpoint. */
public enum LlmApiMode {
    /** Infer from a complete endpoint or a known provider, otherwise Responses. */
    AUTO,
    RESPONSES,
    CHAT_COMPLETIONS
}
