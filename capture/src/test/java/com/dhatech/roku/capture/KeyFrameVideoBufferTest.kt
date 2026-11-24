package com.dhatech.roku.capture

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

class KeyFrameVideoBufferTest {

    @Test
    fun `preserves codec config and drops deltas until next keyframe on overflow`() = runBlocking {
        val keyFrameRequests = AtomicInteger(0)
        val buffer = KeyFrameVideoBuffer(capacity = 2) {
            keyFrameRequests.incrementAndGet()
        }

        val config = EncodedFrame(byteArrayOf(0, 0, 0, 1, 0x67), 10L, isKeyFrame = false, isCodecConfig = true)
        val idr1 = EncodedFrame(byteArrayOf(0, 0, 0, 1, 0x65, 1), 20L, isKeyFrame = true, isCodecConfig = false)
        val p1 = EncodedFrame(byteArrayOf(0, 0, 0, 1, 0x41, 1), 30L, isKeyFrame = false, isCodecConfig = false)
        val p2 = EncodedFrame(byteArrayOf(0, 0, 0, 1, 0x41, 2), 40L, isKeyFrame = false, isCodecConfig = false)
        val p3 = EncodedFrame(byteArrayOf(0, 0, 0, 1, 0x41, 3), 50L, isKeyFrame = false, isCodecConfig = false)
        val idr2 = EncodedFrame(byteArrayOf(0, 0, 0, 1, 0x65, 2), 60L, isKeyFrame = true, isCodecConfig = false)

        buffer.offer(config)
        buffer.offer(idr1)
        buffer.offer(p1)
        // Queue now holds config + idr1 + p1 (nonConfigCount == capacity == 2).
        // Offering p2 triggers overflow, drops idr1/p1 while preserving config, and awaits a keyframe.
        buffer.offer(p2)
        // p3 must be skipped while awaitingKeyFrame == true.
        buffer.offer(p3)
        // idr2 satisfies awaitingKeyFrame and should be delivered right after config.
        buffer.offer(idr2)

        val delivered = Collections.synchronizedList(mutableListOf<EncodedFrame>())
        val expectedArrived = CompletableDeferred<Unit>()
        val job = launch(Dispatchers.Default) {
            buffer.consume { frame ->
                delivered += frame
                if (delivered.size >= 2) expectedArrived.complete(Unit)
            }
        }
        withTimeout(2_000) { expectedArrived.await() }
        buffer.close()
        job.join()

        assertEquals(1, keyFrameRequests.get())
        assertEquals(2, delivered.size)
        assertTrue(delivered[0].isCodecConfig)
        assertEquals(10L, delivered[0].presentationTimeUs)
        assertTrue(delivered[1].isKeyFrame)
        assertEquals(60L, delivered[1].presentationTimeUs)
    }

    @Test
    fun `overflow with incoming keyframe delivers config and new keyframe immediately`() = runBlocking {
        val keyFrameRequests = AtomicInteger(0)
        val buffer = KeyFrameVideoBuffer(capacity = 1) {
            keyFrameRequests.incrementAndGet()
        }

        val config = EncodedFrame(byteArrayOf(1), 1L, isKeyFrame = false, isCodecConfig = true)
        val p1 = EncodedFrame(byteArrayOf(2), 2L, isKeyFrame = false, isCodecConfig = false)
        val idr = EncodedFrame(byteArrayOf(3), 3L, isKeyFrame = true, isCodecConfig = false)

        buffer.offer(config)
        buffer.offer(p1)
        // Queue at capacity=1; offering an IDR directly on overflow keeps config + IDR with 0 keyframe requests.
        buffer.offer(idr)

        val timestamps = Collections.synchronizedList(mutableListOf<Long>())
        val expectedArrived = CompletableDeferred<Unit>()
        val job = launch(Dispatchers.Default) {
            buffer.consume { frame ->
                timestamps += frame.presentationTimeUs
                if (timestamps.size >= 2) expectedArrived.complete(Unit)
            }
        }
        withTimeout(2_000) { expectedArrived.await() }
        buffer.close()
        job.join()

        assertEquals(0, keyFrameRequests.get())
        assertEquals(listOf(1L, 3L), timestamps.toList())
    }
}
