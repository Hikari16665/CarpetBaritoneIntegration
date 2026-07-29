package baritone.cache;

import baritone.Baritone;
import baritone.api.cache.ICachedRegion;
import baritone.api.cache.ICachedWorld;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.storage.LevelResource;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;

/**
 * Per-ServerLevel chunk knowledge shared by every fake player. This first
 * server cache stage records observed chunks and indexes Baritone's durable
 * points of interest without forcing chunks to load.
 */
public final class ServerWorldCache implements ICachedWorld {
    private static final int CACHE_MAGIC = 0x43424932; // CBI2
    private static final int MAX_INDEXED_POSITIONS_PER_BLOCK_PER_CHUNK = 128;
    private static final Map<ServerLevel, ServerWorldCache> INSTANCES =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Set<Block> TRACKED_BLOCKS = Set.of(
            Blocks.CHEST, Blocks.TRAPPED_CHEST, Blocks.ENDER_CHEST,
            Blocks.FURNACE, Blocks.SPAWNER, Blocks.END_PORTAL,
            Blocks.END_PORTAL_FRAME, Blocks.NETHER_PORTAL, Blocks.HOPPER,
            Blocks.BEACON, Blocks.BREWING_STAND, Blocks.ENCHANTING_TABLE,
            Blocks.ANVIL, Blocks.END_GATEWAY, Blocks.DRAGON_EGG,
            Blocks.COBWEB, Blocks.NETHER_WART, Blocks.LADDER, Blocks.VINE,
            Blocks.BARRIER, Blocks.OBSERVER, Blocks.JUKEBOX,
            Blocks.CREEPER_HEAD, Blocks.CREEPER_WALL_HEAD,
            Blocks.DRAGON_HEAD, Blocks.DRAGON_WALL_HEAD,
            Blocks.PLAYER_HEAD, Blocks.PLAYER_WALL_HEAD,
            Blocks.ZOMBIE_HEAD, Blocks.ZOMBIE_WALL_HEAD,
            Blocks.SKELETON_SKULL, Blocks.SKELETON_WALL_SKULL,
            Blocks.WITHER_SKELETON_SKULL, Blocks.WITHER_SKELETON_WALL_SKULL,
            Blocks.WHITE_SHULKER_BOX, Blocks.ORANGE_SHULKER_BOX,
            Blocks.MAGENTA_SHULKER_BOX, Blocks.LIGHT_BLUE_SHULKER_BOX,
            Blocks.YELLOW_SHULKER_BOX, Blocks.LIME_SHULKER_BOX,
            Blocks.PINK_SHULKER_BOX, Blocks.GRAY_SHULKER_BOX,
            Blocks.LIGHT_GRAY_SHULKER_BOX, Blocks.CYAN_SHULKER_BOX,
            Blocks.PURPLE_SHULKER_BOX, Blocks.BLUE_SHULKER_BOX,
            Blocks.BROWN_SHULKER_BOX, Blocks.GREEN_SHULKER_BOX,
            Blocks.RED_SHULKER_BOX, Blocks.BLACK_SHULKER_BOX,
            Blocks.WHITE_BED, Blocks.ORANGE_BED, Blocks.MAGENTA_BED,
            Blocks.LIGHT_BLUE_BED, Blocks.YELLOW_BED, Blocks.LIME_BED,
            Blocks.PINK_BED, Blocks.GRAY_BED, Blocks.LIGHT_GRAY_BED,
            Blocks.CYAN_BED, Blocks.PURPLE_BED, Blocks.BLUE_BED,
            Blocks.BROWN_BED, Blocks.GREEN_BED, Blocks.RED_BED,
            Blocks.BLACK_BED);
    private static final Set<Block> DYNAMIC_TRACKED_BLOCKS =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final ThreadPoolExecutor PACKER =
            new ThreadPoolExecutor(
                    1, 1, 30L, TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(128),
                    runnable -> {
                        Thread thread = new Thread(
                                runnable, "Baritone-Chunk-Packer");
                        thread.setDaemon(true);
                        thread.setPriority(Math.max(Thread.MIN_PRIORITY,
                                Thread.NORM_PRIORITY - 1));
                        return thread;
                    },
                    new ThreadPoolExecutor.DiscardOldestPolicy());

    static Set<Block> trackedBlocks() {
        Set<Block> result = new HashSet<>(TRACKED_BLOCKS);
        result.addAll(DYNAMIC_TRACKED_BLOCKS);
        return Set.copyOf(result);
    }

    public static void registerTrackedBlocks(
            java.util.Collection<Block> blocks) {
        DYNAMIC_TRACKED_BLOCKS.addAll(blocks);
    }

    private final ServerLevel world;
    private final Map<Long, Long> observedChunks = new HashMap<>();
    private final Map<Block, Set<BlockPos>> indexedBlocks = new HashMap<>();
    private final Map<Long, Map<Block, List<BlockPos>>> chunkIndexes = new HashMap<>();
    private final Map<Long, CachedChunk> snapshots = new HashMap<>();
    private final Map<Long, ExactChunkSnapshot> exactSnapshots = new HashMap<>();
    private final java.util.LinkedHashSet<Long> pendingCaptures =
            new java.util.LinkedHashSet<>();
    private long snapshotRevision;

    private ServerWorldCache(ServerLevel world) {
        this.world = world;
        reloadAllFromDisk();
    }

    public static ServerWorldCache get(ServerLevel world) {
        synchronized (INSTANCES) {
            return INSTANCES.computeIfAbsent(world, ServerWorldCache::new);
        }
    }

    public static ServerWorldCache ifPresent(ServerLevel world) {
        synchronized (INSTANCES) {
            return INSTANCES.get(world);
        }
    }

    public static void saveAll() {
        List<ServerWorldCache> caches;
        synchronized (INSTANCES) {
            caches = new ArrayList<>(new HashSet<>(INSTANCES.values()));
        }
        caches.forEach(ServerWorldCache::save);
    }

    public synchronized int captureLoadedAround(BlockPos center, int radius) {
        return queueCaptureAround(center, radius);
    }

    public synchronized int queueCaptureAround(BlockPos center, int radius) {
        int centerX = center.getX() >> 4;
        int centerZ = center.getZ() >> 4;
        int queued = 0;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (pendingCaptures.add(ChunkPos.asLong(
                        centerX + dx, centerZ + dz))) queued++;
            }
        }
        return queued;
    }

    /**
     * Refreshes a bounded number of chunks and returns the next square-scan
     * cursor. Keeping a strict budget avoids a full view-distance rescan spike.
     */
    public synchronized int captureIncrementally(
            BlockPos center, int radius, int cursor, int budget) {
        int diameter = radius * 2 + 1;
        int total = diameter * diameter;
        if (total <= 0) return 0;
        int centerX = center.getX() >> 4;
        int centerZ = center.getZ() >> 4;
        for (int count = 0; count < budget; count++) {
            long requested = pollPendingCapture();
            int chunkX;
            int chunkZ;
            if (requested != Long.MIN_VALUE) {
                chunkX = ChunkPos.getX(requested);
                chunkZ = ChunkPos.getZ(requested);
            } else {
                int index = Math.floorMod(cursor++, total);
                int dx = index % diameter - radius;
                int dz = index / diameter - radius;
                chunkX = centerX + dx;
                chunkZ = centerZ + dz;
            }
            LevelChunk chunk = world.getChunkSource()
                    .getChunkNow(chunkX, chunkZ);
            if (chunk != null && !chunk.isEmpty()) capture(chunk);
        }
        return Math.floorMod(cursor, total);
    }

    /**
     * Copies only a small nearest-first neighborhood before an asynchronous
     * search starts. Existing immutable snapshots are free and do not consume
     * the budget.
     */
    public synchronized int warmExactSnapshots(
            BlockPos center, int radius, int budget) {
        if (budget <= 0 || radius < 0) return 0;
        int centerChunkX = center.getX() >> 4;
        int centerChunkZ = center.getZ() >> 4;
        int copied = 0;
        outer:
        for (int distance = 0; distance <= radius; distance++) {
            for (int dx = -distance; dx <= distance; dx++) {
                int dzAbs = distance - Math.abs(dx);
                for (int sign : new int[]{-1, 1}) {
                    int dz = dzAbs * sign;
                    int chunkX = centerChunkX + dx;
                    int chunkZ = centerChunkZ + dz;
                    long key = ChunkPos.asLong(chunkX, chunkZ);
                    if (!exactSnapshots.containsKey(key)) {
                        LevelChunk chunk = world.getChunkSource()
                                .getChunkNow(chunkX, chunkZ);
                        if (chunk != null && !chunk.isEmpty()) {
                            captureExact(chunk);
                            copied++;
                        }
                    }
                    if (copied >= budget) break outer;
                    if (dzAbs == 0) break;
                }
            }
        }
        return copied;
    }

    private long pollPendingCapture() {
        java.util.Iterator<Long> iterator = pendingCaptures.iterator();
        if (!iterator.hasNext()) return Long.MIN_VALUE;
        long key = iterator.next();
        iterator.remove();
        return key;
    }

    public synchronized void capture(LevelChunk chunk) {
        long key = chunk.getPos().toLong();
        ExactChunkSnapshot exact = exactSnapshots.get(key);
        if (exact == null) {
            exact = ExactChunkSnapshot.copyOf(
                    chunk, ++snapshotRevision);
            exactSnapshots.put(key, exact);
        }
        Set<Block> tracked = trackedBlocks();
        observedChunks.put(key, world.getGameTime());
        ExactChunkSnapshot snapshotToPack = exact;
        PACKER.execute(() ->
                packAndPublish(key, snapshotToPack, tracked));
    }

    /**
     * Immediately publishes a copy-on-write exact snapshot update while
     * deferring compact repacking to the shared one-chunk maintenance budget.
     */
    public synchronized void updateBlock(
            BlockPos pos, BlockState state, LevelChunk chunk) {
        long key = chunk.getPos().toLong();
        ExactChunkSnapshot previous = exactSnapshots.get(key);
        if (previous != null
                && previous.getBlockState(
                        pos.getX(), pos.getY(), pos.getZ())
                        .equals(state)) {
            return;
        }
        long revision = ++snapshotRevision;
        ExactChunkSnapshot updated = previous == null
                ? ExactChunkSnapshot.copyOf(chunk, revision)
                : previous.withBlock(pos, state, revision);
        if (updated == null) {
            updated = ExactChunkSnapshot.copyOf(chunk, revision);
        }
        exactSnapshots.put(key, updated);
        observedChunks.put(key, world.getGameTime());
        updateIndexAt(key, pos, state.getBlock());
        pendingCaptures.add(key);
    }

    private void updateIndexAt(long key, BlockPos pos, Block block) {
        Map<Block, List<BlockPos>> index = chunkIndexes.computeIfAbsent(
                key, ignored -> new HashMap<>());
        List<Block> empty = new ArrayList<>();
        index.forEach((indexedBlock, positions) -> {
            if (positions.remove(pos)) {
                Set<BlockPos> global = indexedBlocks.get(indexedBlock);
                if (global != null) {
                    global.remove(pos);
                    if (global.isEmpty()) indexedBlocks.remove(indexedBlock);
                }
            }
            if (positions.isEmpty()) empty.add(indexedBlock);
        });
        empty.forEach(index::remove);
        if (!trackedBlocks().contains(block)) return;
        List<BlockPos> positions = index.computeIfAbsent(
                block, ignored -> new ArrayList<>());
        if (positions.size()
                < MAX_INDEXED_POSITIONS_PER_BLOCK_PER_CHUNK) {
            BlockPos immutable = pos.immutable();
            positions.add(immutable);
            indexedBlocks.computeIfAbsent(
                    block, ignored -> new HashSet<>()).add(immutable);
        }
    }

    private void packAndPublish(
            long key,
            ExactChunkSnapshot exact,
            Set<Block> tracked) {
        Map<Block, List<BlockPos>> local = new HashMap<>();
        Map<Block, Integer> seenPerBlock = new HashMap<>();
        for (int y = exact.minY(); y < exact.maxY(); y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    int worldX = (exact.chunkX() << 4) + x;
                    int worldZ = (exact.chunkZ() << 4) + z;
                    Block block = exact.getBlockState(worldX, y, worldZ)
                            .getBlock();
                    if (!tracked.contains(block)) continue;
                    List<BlockPos> positions = local.computeIfAbsent(block,
                            ignored -> new ArrayList<>());
                    BlockPos candidate = new BlockPos(worldX, y, worldZ);
                    int seen = seenPerBlock.merge(block, 1, Integer::sum);
                    if (positions.size()
                            < MAX_INDEXED_POSITIONS_PER_BLOCK_PER_CHUNK) {
                        positions.add(candidate);
                    } else {
                        int replacement = Math.floorMod(
                                candidate.hashCode(), seen);
                        if (replacement
                                < MAX_INDEXED_POSITIONS_PER_BLOCK_PER_CHUNK) {
                            positions.set(replacement, candidate);
                        }
                    }
                }
            }
        }
        CachedChunk compact = CachedChunk.pack(exact);
        synchronized (this) {
            ExactChunkSnapshot current = exactSnapshots.get(key);
            if (current == null
                    || current.revision() != exact.revision()) return;
            removeChunkIndex(key);
            chunkIndexes.put(key, local);
            local.forEach((block, positions) ->
                    indexedBlocks.computeIfAbsent(block,
                            ignored -> new HashSet<>()).addAll(positions));
            snapshots.put(key, compact);
        }
    }

    @Override
    public void queueForPacking(LevelChunk chunk) {
        capture(chunk);
    }

    private void removeChunkIndex(long key) {
        Map<Block, List<BlockPos>> old = chunkIndexes.remove(key);
        if (old == null) return;
        old.forEach((block, positions) -> {
            Set<BlockPos> global = indexedBlocks.get(block);
            if (global != null) {
                global.removeAll(positions);
                if (global.isEmpty()) indexedBlocks.remove(block);
            }
        });
    }

    @Override
    public synchronized boolean isCached(int blockX, int blockZ) {
        return cachedChunk(blockX >> 4, blockZ >> 4) != null;
    }

    public synchronized boolean isChunkCached(int chunkX, int chunkZ) {
        return cachedChunk(chunkX, chunkZ) != null;
    }

    public synchronized Set<Long> observedChunkKeys() {
        removeExpired();
        return Set.copyOf(observedChunks.keySet());
    }

    public synchronized int cachedChunkCount() {
        return snapshots.size();
    }

    public synchronized int indexedLocationCount() {
        return indexedBlocks.values().stream().mapToInt(Set::size).sum();
    }

    public synchronized List<BlockPos> locationsOf(Block block) {
        removeExpired();
        return List.copyOf(indexedBlocks.getOrDefault(block, Set.of()));
    }

    /**
     * Selects the nearest indexed positions without sorting or copying the
     * full global index (important for common targets such as stone).
     */
    public synchronized List<BlockPos> locationsOfNear(
            Block block,
            int centerX,
            int centerZ,
            int chunkRadius,
            int maximum) {
        if (maximum <= 0) return List.of();
        java.util.PriorityQueue<BlockPos> nearest =
                new java.util.PriorityQueue<>(
                        java.util.Comparator.comparingDouble(
                                (BlockPos pos) -> horizontalDistanceSq(
                                        pos, centerX, centerZ)).reversed());
        int centerChunkX = centerX >> 4;
        int centerChunkZ = centerZ >> 4;
        chunkIndexes.forEach((key, index) -> {
            if (Math.abs(ChunkPos.getX(key) - centerChunkX) > chunkRadius
                    || Math.abs(ChunkPos.getZ(key) - centerChunkZ)
                    > chunkRadius) return;
            for (BlockPos pos : index.getOrDefault(block, List.of())) {
                nearest.offer(pos);
                if (nearest.size() > maximum) nearest.poll();
            }
        });
        ArrayList<BlockPos> result = new ArrayList<>(nearest);
        result.sort(java.util.Comparator.comparingDouble(
                pos -> horizontalDistanceSq(pos, centerX, centerZ)));
        return result;
    }

    private static double horizontalDistanceSq(
            BlockPos pos, int centerX, int centerZ) {
        long dx = (long) pos.getX() - centerX;
        long dz = (long) pos.getZ() - centerZ;
        return (double) dx * dx + (double) dz * dz;
    }

    @Override
    public synchronized ArrayList<BlockPos> getLocationsOf(
            String blockName, int maximum, int centerX, int centerZ,
            int maxRegionDistanceSq) {
        removeExpired();
        ResourceLocation id = ResourceLocation.tryParse(blockName);
        Block block = id == null ? null : BuiltInRegistries.BLOCK.get(id);
        if (block == null || maximum <= 0) return new ArrayList<>();
        return indexedBlocks.getOrDefault(block, Set.of()).stream()
                .filter(pos -> {
                    int regionDx = (pos.getX() >> 9) - (centerX >> 9);
                    int regionDz = (pos.getZ() >> 9) - (centerZ >> 9);
                    return regionDx * regionDx + regionDz * regionDz
                            <= maxRegionDistanceSq;
                })
                .sorted(java.util.Comparator.comparingDouble(
                        pos -> pos.distSqr(new BlockPos(centerX, pos.getY(), centerZ))))
                .limit(maximum)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }

    public synchronized CachedChunk cachedChunk(int chunkX, int chunkZ) {
        long key = ChunkPos.asLong(chunkX, chunkZ);
        CachedChunk chunk = snapshots.get(key);
        long expiry = Baritone.settings().cachedChunksExpirySeconds.value;
        if (chunk != null && expiry >= 0
                && System.currentTimeMillis() - chunk.capturedAt() > expiry * 1000L) {
            observedChunks.remove(key);
            snapshots.remove(key);
            removeChunkIndex(key);
            return null;
        }
        return chunk;
    }

    /**
     * Returns a stable shallow copy of the exact chunk snapshot table.
     * Snapshot values are immutable and therefore safe for background A*.
     */
    public synchronized Map<Long, ExactChunkSnapshot> exactSnapshotView() {
        return Map.copyOf(exactSnapshots);
    }

    public synchronized Map<Long, Long> exactSnapshotRevisions() {
        Map<Long, Long> revisions = new HashMap<>();
        exactSnapshots.forEach((key, snapshot) ->
                revisions.put(key, snapshot.revision()));
        return Map.copyOf(revisions);
    }

    public synchronized long exactSnapshotRevision(long chunkKey) {
        ExactChunkSnapshot snapshot = exactSnapshots.get(chunkKey);
        return snapshot == null ? Long.MIN_VALUE : snapshot.revision();
    }

    public synchronized Map<Long, CachedChunk> compactSnapshotView() {
        removeExpired();
        return Map.copyOf(snapshots);
    }

    /**
     * Publishes an exact copy without rebuilding the durable POI index. Used
     * to guarantee that a newly submitted calculation can safely inspect its
     * starting chunk.
     */
    public synchronized void captureExact(LevelChunk chunk) {
        long key = chunk.getPos().toLong();
        if (!exactSnapshots.containsKey(key)) {
            exactSnapshots.put(key,
                    ExactChunkSnapshot.copyOf(
                            chunk, ++snapshotRevision));
        }
    }

    public synchronized boolean hasExactSnapshot(int chunkX, int chunkZ) {
        return exactSnapshots.containsKey(ChunkPos.asLong(chunkX, chunkZ));
    }

    public synchronized long snapshotRevision() {
        return snapshotRevision;
    }

    public synchronized void pruneFarFrom(BlockPos center, int maxChunkDistance) {
        removeExpired();
        int centerX = center.getX() >> 4;
        int centerZ = center.getZ() >> 4;
        List<Long> remove = observedChunks.keySet().stream()
                .filter(key -> Math.abs(ChunkPos.getX(key) - centerX) > maxChunkDistance
                        || Math.abs(ChunkPos.getZ(key) - centerZ) > maxChunkDistance)
                .toList();
        for (long key : remove) {
            observedChunks.remove(key);
            snapshots.remove(key);
            exactSnapshots.remove(key);
            removeChunkIndex(key);
        }
    }

    private void removeExpired() {
        long expiry = Baritone.settings().cachedChunksExpirySeconds.value;
        if (expiry < 0) return;
        long cutoff = System.currentTimeMillis() - expiry * 1000L;
        List<Long> expired = snapshots.entrySet().stream()
                .filter(entry -> entry.getValue().capturedAt() < cutoff)
                .map(Map.Entry::getKey).toList();
        for (long key : expired) {
            observedChunks.remove(key);
            snapshots.remove(key);
            exactSnapshots.remove(key);
            removeChunkIndex(key);
        }
    }

    public synchronized void invalidateChunk(int chunkX, int chunkZ) {
        long key = ChunkPos.asLong(chunkX, chunkZ);
        observedChunks.remove(key);
        snapshots.remove(key);
        exactSnapshots.remove(key);
        removeChunkIndex(key);
    }

    @Override
    public synchronized ICachedRegion getRegion(int regionX, int regionZ) {
        boolean exists = snapshots.keySet().stream().anyMatch(key ->
                Math.floorDiv(ChunkPos.getX(key), 32) == regionX
                        && Math.floorDiv(ChunkPos.getZ(key), 32) == regionZ);
        return exists ? new RegionView(regionX, regionZ) : null;
    }

    @Override
    public synchronized void save() {
        removeExpired();
        Path file = cacheFile();
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.createDirectories(file.getParent());
            try (DataOutputStream output = new DataOutputStream(
                    new GZIPOutputStream(new BufferedOutputStream(
                            Files.newOutputStream(temp))))) {
                output.writeInt(CACHE_MAGIC);
                output.writeInt(snapshots.size());
                for (Map.Entry<Long, CachedChunk> entry : snapshots.entrySet()) {
                    long key = entry.getKey();
                    CachedChunk chunk = entry.getValue();
                    output.writeInt(ChunkPos.getX(key));
                    output.writeInt(ChunkPos.getZ(key));
                    output.writeInt(chunk.minY());
                    output.writeInt(chunk.height());
                    output.writeLong(chunk.capturedAt());
                    byte[] data = chunk.data();
                    output.writeInt(data.length);
                    output.write(data);
                    Map<Block, List<BlockPos>> index =
                            chunkIndexes.getOrDefault(key, Map.of());
                    output.writeInt(index.size());
                    for (Map.Entry<Block, List<BlockPos>> blocks : index.entrySet()) {
                        output.writeUTF(BuiltInRegistries.BLOCK.getKey(
                                blocks.getKey()).toString());
                        output.writeInt(blocks.getValue().size());
                        for (BlockPos pos : blocks.getValue()) {
                            output.writeInt(pos.getX());
                            output.writeInt(pos.getY());
                            output.writeInt(pos.getZ());
                        }
                    }
                }
            }
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            System.err.println("[Baritone] Failed to save world cache: "
                    + exception.getMessage());
        }
    }

    public void saveAsync() {
        PACKER.execute(this::save);
    }

    @Override
    public synchronized void reloadAllFromDisk() {
        Path file = cacheFile();
        if (!Files.isRegularFile(file)) return;
        Map<Long, CachedChunk> loadedSnapshots = new HashMap<>();
        Map<Long, Map<Block, List<BlockPos>>> loadedIndexes = new HashMap<>();
        try (DataInputStream input = new DataInputStream(
                new GZIPInputStream(new BufferedInputStream(
                        Files.newInputStream(file))))) {
            if (input.readInt() != CACHE_MAGIC) {
                throw new IOException("Unsupported cache version");
            }
            int count = input.readInt();
            if (count < 0 || count > 1_000_000) {
                throw new IOException("Invalid cached chunk count " + count);
            }
            for (int i = 0; i < count; i++) {
                int chunkX = input.readInt();
                int chunkZ = input.readInt();
                int minY = input.readInt();
                int height = input.readInt();
                long capturedAt = input.readLong();
                int dataLength = input.readInt();
                if (height <= 0 || height > 4096) {
                    throw new IOException("Invalid cached chunk height");
                }
                int expected = (16 * 16 * height * 2 + 7) / 8;
                if (dataLength != expected) {
                    throw new IOException("Invalid cached chunk dimensions");
                }
                byte[] data = input.readNBytes(dataLength);
                if (data.length != dataLength) throw new IOException("Truncated cache");
                long key = ChunkPos.asLong(chunkX, chunkZ);
                loadedSnapshots.put(key, CachedChunk.fromData(
                        chunkX, chunkZ, minY, height, data, capturedAt));
                int blockTypes = input.readInt();
                if (blockTypes < 0 || blockTypes > 4096) {
                    throw new IOException("Invalid block index size");
                }
                Map<Block, List<BlockPos>> index = new HashMap<>();
                for (int type = 0; type < blockTypes; type++) {
                    ResourceLocation id = ResourceLocation.tryParse(input.readUTF());
                    Block block = id == null ? Blocks.AIR
                            : BuiltInRegistries.BLOCK.get(id);
                    int positions = input.readInt();
                    if (positions < 0 || positions > 65536) {
                        throw new IOException("Invalid block position count");
                    }
                    List<BlockPos> list = new ArrayList<>(positions);
                    for (int position = 0; position < positions; position++) {
                        list.add(new BlockPos(input.readInt(), input.readInt(),
                                input.readInt()));
                    }
                    if (block != null && block != Blocks.AIR) index.put(block, list);
                }
                loadedIndexes.put(key, index);
            }
        } catch (IOException exception) {
            System.err.println("[Baritone] Failed to load world cache: "
                    + exception.getMessage());
            return;
        }
        snapshots.clear();
        observedChunks.clear();
        indexedBlocks.clear();
        chunkIndexes.clear();
        snapshots.putAll(loadedSnapshots);
        chunkIndexes.putAll(loadedIndexes);
        loadedSnapshots.forEach((key, chunk) ->
                observedChunks.put(key, world.getGameTime()));
        loadedIndexes.values().forEach(index -> index.forEach((block, positions) ->
                indexedBlocks.computeIfAbsent(block, ignored -> new HashSet<>())
                        .addAll(positions)));
    }

    private Path cacheFile() {
        String dimension = world.dimension().location().toString()
                .replace(':', '_').replace('/', '_');
        return world.getServer().getWorldPath(LevelResource.ROOT)
                .resolve("baritone").resolve("cache")
                .resolve(dimension + ".cbi2.gz");
    }

    private final class RegionView implements ICachedRegion {
        private final int x;
        private final int z;

        private RegionView(int x, int z) {
            this.x = x;
            this.z = z;
        }

        @Override
        public BlockState getBlock(int blockX, int y, int blockZ) {
            int worldX = (x << 9) + blockX;
            int worldZ = (z << 9) + blockZ;
            CachedChunk chunk = cachedChunk(worldX >> 4, worldZ >> 4);
            return chunk == null ? null : chunk.getBlock(worldX, y, worldZ, world);
        }

        @Override
        public boolean isCached(int blockX, int blockZ) {
            return ServerWorldCache.this.isCached(
                    (x << 9) + blockX, (z << 9) + blockZ);
        }

        @Override public int getX() { return x; }
        @Override public int getZ() { return z; }
    }
}
