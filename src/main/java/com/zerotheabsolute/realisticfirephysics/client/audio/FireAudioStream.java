package com.zerotheabsolute.realisticfirephysics.client.audio;

import com.zerotheabsolute.realisticfirephysics.audio.CombustionState;
import com.zerotheabsolute.realisticfirephysics.audio.FireSoundProfile;
import com.zerotheabsolute.realisticfirephysics.audio.FireSynthesizer;

import net.minecraft.client.sounds.AudioStream;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.function.Supplier;

import javax.sound.sampled.AudioFormat;

/** Minecraft uploads each returned buffer synchronously before requesting the next one. */
public final class FireAudioStream implements AudioStream {
    public static final int FRAMES_PER_BUFFER = 2048;
    private static final int BYTES_PER_SAMPLE = Short.BYTES;
    private static final AudioFormat FORMAT =
            new AudioFormat(FireSynthesizer.SAMPLE_RATE, 16, 1, true, false);
    private final Supplier<CombustionState> controls;
    private final FireSynthesizer synthesizer;
    private final float[] samples = new float[FRAMES_PER_BUFFER];
    private ByteBuffer pcm;
    private boolean closed;

    public FireAudioStream(Supplier<CombustionState> controls, long seed) {
        this(controls, seed, FireSoundProfile.TORCH);
    }

    public FireAudioStream(
            Supplier<CombustionState> controls, long seed, FireSoundProfile profile) {
        this.controls = controls;
        synthesizer = new FireSynthesizer(seed, profile);
    }

    @Override
    public AudioFormat getFormat() {
        return FORMAT;
    }

    @Override
    public synchronized ByteBuffer read(int size) throws IOException {
        if (closed) throw new IOException("Fire audio stream is closed");
        if (size < BYTES_PER_SAMPLE) throw new IOException("PCM buffer is too small");
        if (pcm == null)
            pcm =
                    ByteBuffer.allocateDirect(FRAMES_PER_BUFFER * BYTES_PER_SAMPLE)
                            .order(ByteOrder.LITTLE_ENDIAN);
        int frames = Math.min(FRAMES_PER_BUFFER, size / BYTES_PER_SAMPLE);
        synthesizer.update(controls.get());
        synthesizer.render(samples, 0, frames);
        pcm.clear();
        for (int i = 0; i < frames; i++)
            pcm.putShort((short) Math.round(samples[i] * Short.MAX_VALUE));
        return pcm.flip();
    }

    @Override
    public synchronized void close() {
        closed = true;
        pcm = null;
    }
}
