package com.privateplanner

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

class ReminderSoundTest {
    @Test fun compressedChimesDecodeToTheOriginalAudioOnAndroid() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // SHA-256 of the original mono, 22,050 Hz, signed 16-bit little-endian samples.
        for ((resource, expected) in listOf(
            R.raw.reminder_start to "cdc4b63951ea2e2c02e920e86be967d1f9889b2b186baee52d2b7dbb508bbf4f",
            R.raw.reminder_upcoming to "6d4e82508748631383b040279c78d58c6a58475b337505442d6d92a2a6e2c814"
        )) {
            val extractor = MediaExtractor()
            try {
                context.resources.openRawResourceFd(resource).use {
                    extractor.setDataSource(it.fileDescriptor, it.startOffset, it.length)
                }
                extractor.selectTrack(0)
                val format = extractor.getTrackFormat(0)
                val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
                try {
                    codec.configure(format, null, null, 0)
                    codec.start()
                    val digest = MessageDigest.getInstance("SHA-256")
                    val info = MediaCodec.BufferInfo()
                    val deadline = SystemClock.uptimeMillis() + 5_000
                    var inputEnded = false
                    while (true) {
                        assertTrue("FLAC decoding did not finish", SystemClock.uptimeMillis() < deadline)
                        if (!inputEnded) {
                            val input = codec.dequeueInputBuffer(10_000)
                            if (input >= 0) {
                                val size = extractor.readSampleData(codec.getInputBuffer(input)!!, 0)
                                inputEnded = size < 0
                                codec.queueInputBuffer(input, 0, size.coerceAtLeast(0),
                                    if (inputEnded) 0 else extractor.sampleTime,
                                    if (inputEnded) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                                if (!inputEnded) extractor.advance()
                            }
                        }
                        val output = codec.dequeueOutputBuffer(info, 10_000)
                        if (output >= 0) {
                            if (info.size > 0) {
                                val samples = codec.getOutputBuffer(output)!!
                                samples.position(info.offset)
                                samples.limit(info.offset + info.size)
                                digest.update(samples)
                            }
                            codec.releaseOutputBuffer(output, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                        }
                    }
                    assertEquals(22_050, codec.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE))
                    assertEquals(1, codec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
                    assertEquals(expected, digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) })
                } finally {
                    codec.release()
                }
            } finally {
                extractor.release()
            }
        }
    }
}
