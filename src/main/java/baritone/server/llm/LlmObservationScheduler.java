package baritone.server.llm;

import baritone.Baritone;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import me.nuoyuan.carpetbaritoneintegration.Carpetbaritoneintegration;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Executes model observations on the server thread under a per-tick budget. */
public final class LlmObservationScheduler {
    public static final LlmObservationScheduler INSTANCE =
            new LlmObservationScheduler();
    private static final int WORK_UNITS_PER_TICK = 8;

    private final Map<MinecraftServer, ArrayDeque<Request>> queues =
            new IdentityHashMap<>();

    private LlmObservationScheduler() { }

    public CompletableFuture<ObjectNode> submit(
            MinecraftServer server, UUID senderId, UUID fakeId,
            String name, JsonNode arguments) {
        CompletableFuture<ObjectNode> future = new CompletableFuture<>();
        server.execute(() -> queues.computeIfAbsent(
                server, ignored -> new ArrayDeque<>()).addLast(
                new Request(senderId, fakeId, name, arguments.deepCopy(), future)));
        return future;
    }

    /** Called once from ServerBaritoneRegistry.tick. */
    public void tick(MinecraftServer server) {
        ArrayDeque<Request> queue = queues.get(server);
        if (queue == null || queue.isEmpty()) return;
        int remaining = WORK_UNITS_PER_TICK;
        while (remaining > 0 && !queue.isEmpty()) {
            Request request = queue.peekFirst();
            try {
                if (request.job == null) initialize(server, request);
                int granted = request.name.equals("scan_storage_items")
                        ? remaining : 1;
                boolean complete = request.job.step(granted);
                remaining -= granted;
                if (complete) {
                    queue.removeFirst();
                    request.future.complete(request.job.result());
                }
            } catch (Throwable throwable) {
                queue.removeFirst();
                request.future.completeExceptionally(throwable);
                remaining--;
            }
        }
        if (queue.isEmpty()) queues.remove(server);
    }

    public void clear(MinecraftServer server) {
        ArrayDeque<Request> queue = queues.remove(server);
        if (queue != null) queue.forEach(request -> request.future
                .completeExceptionally(new IllegalStateException(
                        "server stopped before observation completed")));
    }

    private static void initialize(MinecraftServer server, Request request) {
        ServerPlayer sender = server.getPlayerList().getPlayer(request.senderId);
        ServerPlayer fake = server.getPlayerList().getPlayer(request.fakeId);
        if (sender == null || fake == null) {
            throw new IllegalStateException("玩家或假人已离线");
        }
        Baritone baritone = Carpetbaritoneintegration.BARITONES
                .getOrCreate(server, fake);
        request.job = LlmObservationTools.incremental(
                request.name, request.arguments, sender, fake, baritone);
    }

    private static final class Request {
        private final UUID senderId;
        private final UUID fakeId;
        private final String name;
        private final JsonNode arguments;
        private final CompletableFuture<ObjectNode> future;
        private LlmObservationTools.IncrementalObservation job;

        private Request(UUID senderId, UUID fakeId, String name,
                        JsonNode arguments, CompletableFuture<ObjectNode> future) {
            this.senderId = senderId;
            this.fakeId = fakeId;
            this.name = name;
            this.arguments = arguments;
            this.future = future;
        }
    }
}
