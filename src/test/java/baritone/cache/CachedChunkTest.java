package baritone.cache;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public class CachedChunkTest {
    @Test
    public void packedDataRoundTripsAtModernWorldHeight() {
        int height = 384;
        byte[] data = new byte[CachedChunk.sizeInBytes(CachedChunk.size(height))];
        data[0] = 3;
        data[data.length - 1] = 2;
        CachedChunk chunk = CachedChunk.fromData(
                -12, 34, -64, height, data, 123456L);
        assertEquals(-12, chunk.x);
        assertEquals(34, chunk.z);
        assertEquals(-64, chunk.minY());
        assertEquals(height, chunk.height);
        assertEquals(123456L, chunk.cacheTimestamp);
        assertArrayEquals(data, chunk.toByteArray());
    }

    @Test
    public void positionIndexCoversEveryBlockWithoutOverlap() {
        int last = CachedChunk.getPositionIndex(15, 383, 15);
        assertEquals(CachedChunk.size(384) - 2, last);
    }
}
