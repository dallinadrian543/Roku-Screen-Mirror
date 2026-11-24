package com.dhatech.roku.capture

import kotlinx.coroutines.channels.Channel

/**
 * Bounded video frame queue that preserves H.264 codec configuration (SPS/PPS)
 * and drops whole delta chains back to the next IDR keyframe on overflow so
 * slow Wi-Fi never corrupts the receiver's hardware decoder.
 */
class KeyFrameVideoBuffer(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val onOverflowRequestKeyFrame: () -> Unit = {},
) {
    private val lock = Any()
    private val queue = ArrayDeque<EncodedFrame>(capacity + 2)
    private val signal = Channel<Unit>(Channel.CONFLATED)
    private var awaitingKeyFrame = false
    private var closed = false

    fun offer(frame: EncodedFrame) {
        var triggerKeyFrame = false
        synchronized(lock) {
            if (closed) return
            when {
                frame.isCodecConfig -> {
                    queue.addLast(frame)
                }

                awaitingKeyFrame -> {
                    if (frame.isKeyFrame) {
                        awaitingKeyFrame = false
                        queue.addLast(frame)
                    }
                }

                nonConfigCountLocked() >= capacity -> {
                    dropNonConfigFramesLocked()
                    if (frame.isKeyFrame) {
                        awaitingKeyFrame = false
                        queue.addLast(frame)
                    } else {
                        awaitingKeyFrame = true
                        triggerKeyFrame = true
                    }
                }

                else -> {
                    queue.addLast(frame)
                }
            }
        }
        if (triggerKeyFrame) {
            onOverflowRequestKeyFrame()
        }
        signal.trySend(Unit)
    }

    suspend fun consume(block: suspend (EncodedFrame) -> Unit) {
        while (true) {
            while (true) {
                val next = synchronized(lock) { queue.removeFirstOrNull() } ?: break
                block(next)
            }
            val result = signal.receiveCatching()
            if (result.isClosed) {
                while (true) {
                    val tail = synchronized(lock) { queue.removeFirstOrNull() } ?: break
                    block(tail)
                }
                return
            }
        }
    }

    fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            queue.clear()
        }
        signal.close()
    }

    private fun nonConfigCountLocked(): Int = queue.count { !it.isCodecConfig }

    private fun dropNonConfigFramesLocked() {
        if (queue.isEmpty()) return
        val preserved = ArrayDeque<EncodedFrame>(2)
        for (item in queue) {
            if (item.isCodecConfig) preserved.addLast(item)
        }
        queue.clear()
        preserved.forEach { queue.addLast(it) }
    }

    companion object {
        const val DEFAULT_CAPACITY = 8
    }
}
