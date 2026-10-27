package com.zerotheabsolute.surfacefire.audio;

/** Reusable, in-place complex transform. Instances own only immutable lookup tables. */
public final class Radix2Fft {
    private final int size;
    private final int[] reversed;
    private final double[] cosine, sine;

    public Radix2Fft(int size) {
        if (size < 2 || Integer.bitCount(size) != 1)
            throw new IllegalArgumentException("Transform size must be a power of two");
        this.size = size;
        reversed = new int[size];
        cosine = new double[size / 2];
        sine = new double[size / 2];
        int shift = 32 - Integer.numberOfTrailingZeros(size);
        for (int i = 0; i < size; i++) reversed[i] = Integer.reverse(i) >>> shift;
        for (int i = 0; i < size / 2; i++) {
            double phase = 2 * Math.PI * i / size;
            cosine[i] = Math.cos(phase);
            sine[i] = Math.sin(phase);
        }
    }

    public void transform(double[] real, double[] imaginary, boolean inverse) {
        if (real.length != size || imaginary.length != size)
            throw new IllegalArgumentException("Transform buffer size mismatch");
        for (int i = 0; i < size; i++) {
            int j = reversed[i];
            if (j <= i) continue;
            double a = real[i];
            real[i] = real[j];
            real[j] = a;
            double b = imaginary[i];
            imaginary[i] = imaginary[j];
            imaginary[j] = b;
        }
        for (int width = 2; width <= size; width <<= 1) {
            int half = width / 2, stride = size / width;
            for (int start = 0; start < size; start += width) {
                for (int j = 0; j < half; j++) {
                    int k = j * stride, left = start + j, right = left + half;
                    double wr = cosine[k], wi = inverse ? sine[k] : -sine[k];
                    double a = wr * real[right] - wi * imaginary[right];
                    double b = wr * imaginary[right] + wi * real[right];
                    real[right] = real[left] - a;
                    imaginary[right] = imaginary[left] - b;
                    real[left] += a;
                    imaginary[left] += b;
                }
            }
        }
        if (inverse)
            for (int i = 0; i < size; i++) {
                real[i] /= size;
                imaginary[i] /= size;
            }
    }
}
