package com.zerotheabsolute.realisticfirephysics.client.mesh;

/** Merges only adjacent constant-color quads; gradients and missing cells remain separate. */
public final class UniformQuadGrid {
    private final int width;
    private final float[] colors;
    private long remaining;
    private int first, last;
    private float color;

    public UniformQuadGrid(int width) {
        if (width < 1 || width > 8) throw new IllegalArgumentException("Grid must fit in 64 cells");
        this.width = width;
        colors = new float[width * width];
    }

    public void clear() {
        remaining = 0;
    }

    public void set(int cell, float a, float b, float c, float d) {
        colors[cell] = a == b && a == c && a == d ? a : Float.NaN;
        remaining |= 1L << cell;
    }

    public boolean next() {
        if (remaining == 0) return false;
        first = Long.numberOfTrailingZeros(remaining);
        color = colors[first];
        int x = first % width, y = first / width;
        int columns = 1, rows = 1;
        while (x + columns < width && matches(first + columns)) columns++;
        grow:
        while (y + rows < width) {
            for (int column = 0; column < columns; column++)
                if (!matches(first + rows * width + column)) break grow;
            rows++;
        }
        long row = ((1L << columns) - 1) << first;
        for (int dy = 0; dy < rows; dy++) remaining &= ~(row << (dy * width));
        last = first + (rows - 1) * width + columns - 1;
        return true;
    }

    private boolean matches(int cell) {
        return (remaining & (1L << cell)) != 0 && colors[cell] == color;
    }

    public int first() {
        return first;
    }

    public int last() {
        return last;
    }

    public float color() {
        return color;
    }
}
