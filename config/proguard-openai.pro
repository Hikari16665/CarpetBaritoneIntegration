# CBI uses only the official SDK's asynchronous Chat Completions transport.
# Keep names stable for code compiled against the upstream SDK and shrink only;
# optimization/obfuscation make SDK upgrades and Fabric diagnostics brittle.
-dontoptimize
-dontobfuscate

-keepattributes Exceptions,InnerClasses,Signature,Deprecated,SourceFile,LineNumberTable,*Annotation*,EnclosingMethod,Record,PermittedSubclasses,MethodParameters

# Entry point created directly by OpenAiSdkChatGateway.
-keep public class com.openai.client.okhttp.OpenAIOkHttpClientAsync {
    public static com.openai.client.okhttp.OpenAIOkHttpClientAsync$Builder builder();
}
-keep public class com.openai.client.okhttp.OpenAIOkHttpClientAsync$Builder {
    public *** baseUrl(java.lang.String);
    public *** timeout(java.time.Duration);
    public *** maxRetries(int);
    public *** apiKey(java.lang.String);
    public com.openai.client.OpenAIClientAsync build();
}

# Methods invoked by CBI after the builder returns the general client API.
-keep public interface com.openai.client.OpenAIClientAsync
-keepclassmembers interface com.openai.client.OpenAIClientAsync {
    public com.openai.services.async.ChatServiceAsync chat();
    public void close();
}
-keep public interface com.openai.services.async.ChatServiceAsync
-keepclassmembers interface com.openai.services.async.ChatServiceAsync {
    public com.openai.services.async.chat.ChatCompletionServiceAsync completions();
}
-keep public interface com.openai.services.async.chat.ChatCompletionServiceAsync
-keepclassmembers interface com.openai.services.async.chat.ChatCompletionServiceAsync {
    public java.util.concurrent.CompletableFuture create(com.openai.models.chat.completions.ChatCompletionCreateParams);
}

# Request/response models are serialized by Jackson and several members are
# reached reflectively, so retain their public model surface and annotations.
-keep class com.openai.models.ResponseFormatJsonObject {
    public static *** builder();
}
-keep class com.openai.models.ResponseFormatJsonObject$Builder {
    public *** build();
}
-keepclassmembers class com.openai.models.ResponseFormatJsonObject {
    @com.fasterxml.jackson.annotation.JsonCreator <init>(...);
    @com.fasterxml.jackson.annotation.JsonProperty <fields>;
    @com.fasterxml.jackson.annotation.JsonProperty <methods>;
    @com.fasterxml.jackson.annotation.JsonAnyGetter <methods>;
    @com.fasterxml.jackson.annotation.JsonAnySetter <methods>;
}
-keep class com.openai.models.ChatModel** { *; }
-keep class com.openai.models.chat.completions.ChatCompletionCreateParams {
    public static *** builder();
}
-keep class com.openai.models.chat.completions.ChatCompletionCreateParams$Builder {
    public *** addSystemMessage(java.lang.String);
    public *** addUserMessage(java.lang.String);
    public *** addAssistantMessage(java.lang.String);
    public *** model(java.lang.String);
    public *** maxTokens(long);
    public *** responseFormat(com.openai.models.ResponseFormatJsonObject);
    public *** putAdditionalBodyProperty(java.lang.String,com.openai.core.JsonValue);
    public *** build();
}
-keep class com.openai.models.chat.completions.ChatCompletion { *; }
-keep class com.openai.models.chat.completions.ChatCompletion$* { *; }
-keep class com.openai.models.chat.completions.ChatCompletionMessage { *; }
-keep class com.openai.models.chat.completions.ChatCompletionMessage$* { *; }
-keep class com.openai.models.completions.CompletionUsage** { *; }
-keep class com.openai.core.Json** { *; }
-keep @interface com.openai.core.ExcludeMissing
-keep public class com.openai.errors.OpenAIServiceException { public *; }

# Jackson instantiates generated serializers from annotations rather than
# bytecode references. Restrict these rules to the JSON primitives and Chat
# Completions models used by CBI; a global rule would retain every SDK API.
-keep class com.openai.core.JsonValue$Serializer { *; }
-keep class com.openai.core.JsonValue$Deserializer { *; }
-keep class com.openai.models.ResponseFormatJsonObject$Serializer { *; }
-keep class com.openai.models.ResponseFormatJsonObject$Deserializer { *; }
-keep class com.openai.models.ChatModel$Serializer { *; }
-keep class com.openai.models.ChatModel$Deserializer { *; }
-keep class com.openai.models.chat.completions.**$Serializer { *; }
-keep class com.openai.models.chat.completions.**$Deserializer { *; }

# Generated SDK models are instantiated and inspected by Jackson. Retain only
# annotated members of otherwise reachable Chat Completions model classes.
-keepclassmembers class com.openai.models.chat.completions.** {
    @com.fasterxml.jackson.annotation.JsonCreator <init>(...);
    @com.fasterxml.jackson.annotation.JsonValue <methods>;
    @com.fasterxml.jackson.annotation.JsonProperty <fields>;
    @com.fasterxml.jackson.annotation.JsonProperty <methods>;
    @com.fasterxml.jackson.annotation.JsonAnyGetter <methods>;
    @com.fasterxml.jackson.annotation.JsonAnySetter <methods>;
    @com.fasterxml.jackson.annotation.JsonIgnore <fields>;
    @com.fasterxml.jackson.annotation.JsonIgnore <methods>;
}

# Kotlin/Jackson/OkHttp remain unmodified library jars. Optional platform
# integrations referenced by those libraries need not exist on Minecraft.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
