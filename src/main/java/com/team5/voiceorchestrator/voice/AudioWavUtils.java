package com.team5.voiceorchestrator.voice;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Utility for wrapping raw {@code PCM_S16LE} audio bytes in a standard 44-byte RIFF/WAVE header.
 */
public final class AudioWavUtils {

    private static final int WAV_HEADER_SIZE = 44;

    private AudioWavUtils() {
    }

    /**
     * Wraps raw signed 16-bit little-endian PCM bytes in a 44-byte WAV header.
     *
     * @param pcmBytes      raw PCM audio bytes
     * @param sampleRateHz  sample rate in Hz (e.g. 16000)
     * @param channels      channel count (e.g. 1)
     * @param bitsPerSample bits per sample (e.g. 16)
     * @return complete WAV-formatted byte array
     */
    public static byte[] pcmToWav(byte[] pcmBytes, int sampleRateHz, int channels, int bitsPerSample) {
        byte[] safePcm = pcmBytes == null ? new byte[0] : pcmBytes;
        int byteRate = sampleRateHz * channels * (bitsPerSample / 8);
        int blockAlign = channels * (bitsPerSample / 8);
        int dataSize = safePcm.length;
        int chunkSize = 36 + dataSize;

        ByteBuffer buffer = ByteBuffer.allocate(WAV_HEADER_SIZE + dataSize).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put((byte) 'R').put((byte) 'I').put((byte) 'F').put((byte) 'F');
        buffer.putInt(chunkSize);
        buffer.put((byte) 'W').put((byte) 'A').put((byte) 'V').put((byte) 'E');
        buffer.put((byte) 'f').put((byte) 'm').put((byte) 't').put((byte) ' ');
        buffer.putInt(16); // Subchunk1Size for PCM
        buffer.putShort((short) 1); // AudioFormat = 1 (PCM)
        buffer.putShort((short) channels);
        buffer.putInt(sampleRateHz);
        buffer.putInt(byteRate);
        buffer.putShort((short) blockAlign);
        buffer.putShort((short) bitsPerSample);
        buffer.put((byte) 'd').put((byte) 'a').put((byte) 't').put((byte) 'a');
        buffer.putInt(dataSize);
        buffer.put(safePcm);
        return buffer.array();
    }
}
