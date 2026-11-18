package com.zerotheabsolute.realisticfirephysics.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import com.mojang.logging.LogUtils;

import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.Vec3;

import org.joml.Matrix4f;
import org.lwjgl.opengl.GL15;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;

/**
 * Fire surface quads kept on the GPU between frames.
 *
 * <p>Each frame the caller passes the quads it would otherwise write to an immediate buffer, with
 * their corner colors. A quad's vertices are written once, into a slot, the first time it is seen.
 * After that only two small streams change: four color bytes per vertex, and the list of slots to
 * draw this frame. A quad that is not passed in a frame is not drawn, exactly as before. Positions,
 * texture coordinates, normals and light belong to the quad and never change, so the drawn result
 * is the immediate one without rebuilding every vertex (and every shader-pack vertex extension)
 * each frame.
 */
final class RetainedQuads<Q> implements AutoCloseable {
    /** Writes a quad's four vertices relative to the anchor, with colors {@code colors[first..]}. */
    interface Writer<Q> {
        void write(VertexConsumer out, Q quad, double anchorX, double anchorY, double anchorZ,
                int[] colors, int first);
    }

    private static final int ARRAY_BUFFER = 34962, ELEMENT_ARRAY_BUFFER = 34963;
    private static final int DYNAMIC_DRAW = 35048, STREAM_DRAW = 35040, UNSIGNED_INT = 5125;
    private static final int TRIANGLES = 4, MIN_SLOTS = 256, COMPACT_SLOTS = 1024;
    private static final double REANCHOR_DISTANCE = 512;
    private static final Matrix4f VIEW = new Matrix4f();
    private static ByteBufferBuilder scratch;
    /** Positions are stored relative to one anchor so a layer draws with a single uniform set. */
    private static double anchorX, anchorY, anchorZ;
    private static long generation;
    private static boolean anchored;
    /** Cleared when a vertex extension rewrote colors; the immediate path is used from then on. */
    private static boolean available = true;
    private static Boolean reported;

    private final Reference2IntOpenHashMap<Q> slots = new Reference2IntOpenHashMap<>();
    private Object[] slotQuads = new Object[MIN_SLOTS];
    private int[] colors = new int[MIN_SLOTS * 4];
    private int[] active = new int[MIN_SLOTS], drawn = new int[0];
    private int slotCount, firstUnwritten, capacity, activeCount, drawnCount;
    private boolean colorsChanged;
    private long builtGeneration = -1;
    private int vertexArray, vertices, colorStream, indices;
    /** Layout of the written slots, and the layout whose attribute pointers are set up. */
    private VertexFormat format, bound;
    private ByteBuffer upload;

    RetainedQuads() {
        slots.defaultReturnValue(-1);
    }

    static boolean available() {
        boolean on = available && FireClientSettings.RETAINED_GEOMETRY.get();
        if (reported == null || reported != on) {
            reported = on;
            LogUtils.getLogger()
                    .info("Fire surfaces: {}", on ? "retained geometry" : "drawn each frame");
        }
        return on;
    }

    /** Moves the shared anchor near the camera when it is far away; every mesh then rewrites. */
    static void follow(Vec3 camera) {
        if (anchored
                && Math.abs(camera.x - anchorX) < REANCHOR_DISTANCE
                && Math.abs(camera.y - anchorY) < REANCHOR_DISTANCE
                && Math.abs(camera.z - anchorZ) < REANCHOR_DISTANCE) return;
        anchorX = Math.floor(camera.x);
        anchorY = Math.floor(camera.y);
        anchorZ = Math.floor(camera.z);
        anchored = true;
        generation++;
    }

    /** Forces every mesh to rewrite its vertices, for example after the format may have changed. */
    static void invalidateAll() {
        generation++;
    }

    /** Packs color bytes in BufferBuilder's order, with its float-to-byte conversion. */
    static int color(float red, float green, float blue, float alpha) {
        return ((int) (red * 255f) & 255)
                | ((int) (green * 255f) & 255) << 8
                | ((int) (blue * 255f) & 255) << 16
                | ((int) (alpha * 255f) & 255) << 24;
    }

    static VertexConsumer setColor(VertexConsumer vertex, int color) {
        return vertex.setColor(color & 255, color >>> 8 & 255, color >>> 16 & 255, color >>> 24);
    }

    void begin() {
        activeCount = 0;
        if (builtGeneration != generation) forgetSlots();
    }

    /** Adds a quad to this frame, with one packed color per corner. */
    void quad(Q quad, int first, int second, int third, int fourth) {
        quad(quad, -1, first, second, third, fourth);
    }

    /**
     * Adds a quad to this frame and returns its slot. A caller may pass the slot this mesh
     * returned for the quad last time; it is used only while that slot still holds the quad.
     */
    int quad(Q quad, int knownSlot, int first, int second, int third, int fourth) {
        int slot =
                knownSlot >= 0 && knownSlot < slotCount && slotQuads[knownSlot] == quad
                        ? knownSlot
                        : slots.getInt(quad);
        if (slot < 0) {
            slot = slotCount++;
            if (slot == slotQuads.length) {
                slotQuads = Arrays.copyOf(slotQuads, slot * 2);
                colors = Arrays.copyOf(colors, slot * 8);
            }
            slotQuads[slot] = quad;
            slots.put(quad, slot);
        }
        int at = slot * 4;
        if (colors[at] != first
                || colors[at + 1] != second
                || colors[at + 2] != third
                || colors[at + 3] != fourth) {
            colors[at] = first;
            colors[at + 1] = second;
            colors[at + 2] = third;
            colors[at + 3] = fourth;
            colorsChanged = true;
        }
        if (activeCount == active.length) active = Arrays.copyOf(active, activeCount * 2);
        active[activeCount++] = slot;
        return slot;
    }

    /** Sends this frame's changes: new quads' vertices, then colors and the drawn slot list. */
    void finish(RenderType type, Writer<Q> writer) {
        builtGeneration = generation;
        if (slotCount > COMPACT_SLOTS && activeCount * 3 < slotCount) compact();
        if (firstUnwritten < slotCount && !writeVertices(type, writer)) {
            drawnCount = 0;
            return;
        }
        if (slotCount == 0) {
            drawnCount = 0;
            return;
        }
        if (colorsChanged) sendColors();
        if (drawnCount != activeCount || !Arrays.equals(active, 0, activeCount, drawn, 0, activeCount))
            sendDrawnSlots();
    }

    private void forgetSlots() {
        slots.clear();
        Arrays.fill(slotQuads, 0, slotCount, null);
        slotCount = firstUnwritten = activeCount = drawnCount = 0;
        format = null;
    }

    /** Keeps only this frame's quads so that dropped quads stop holding slots. */
    @SuppressWarnings("unchecked")
    private void compact() {
        slots.clear();
        var quads = new Object[Math.max(MIN_SLOTS, Integer.highestOneBit(activeCount) * 2)];
        var kept = new int[quads.length * 4];
        for (int i = 0; i < activeCount; i++) {
            int from = active[i];
            quads[i] = slotQuads[from];
            System.arraycopy(colors, from * 4, kept, i * 4, 4);
            slots.put((Q) quads[i], i);
            active[i] = i;
        }
        slotQuads = quads;
        colors = kept;
        slotCount = activeCount;
        firstUnwritten = drawnCount = 0;
        colorsChanged = true;
    }

    @SuppressWarnings("unchecked")
    private boolean writeVertices(RenderType type, Writer<Q> writer) {
        boolean reallocate = format == null || slotCount > capacity;
        if (reallocate) {
            capacity = Math.max(MIN_SLOTS, Integer.highestOneBit(slotCount) * 2);
            firstUnwritten = 0;
        }
        if (scratch == null) scratch = new ByteBufferBuilder(1 << 18);
        var builder = new BufferBuilder(scratch, type.mode(), type.format());
        for (int slot = firstUnwritten; slot < slotCount; slot++)
            writer.write(builder, (Q) slotQuads[slot], anchorX, anchorY, anchorZ, colors, slot * 4);
        try (var mesh = builder.build()) {
            if (mesh == null) return false;
            var built = mesh.drawState().format();
            if (!reallocate && !built.equals(format)) {
                // The layout changed without an invalidation; rewrite every slot next frame.
                format = null;
                return false;
            }
            var data = mesh.vertexBuffer();
            if (!colorsMatch(data, built)) return false;
            if (vertexArray == 0) {
                vertexArray = GlStateManager._glGenVertexArrays();
                vertices = GlStateManager._glGenBuffers();
                colorStream = GlStateManager._glGenBuffers();
                indices = GlStateManager._glGenBuffers();
            }
            int stride = built.getVertexSize();
            BufferUploader.invalidate();
            GlStateManager._glBindVertexArray(vertexArray);
            GlStateManager._glBindBuffer(ARRAY_BUFFER, vertices);
            if (reallocate) {
                GL15.glBufferData(ARRAY_BUFFER, (long) capacity * 4 * stride, DYNAMIC_DRAW);
                if (bound != null) bound.clearBufferState();
                built.setupBufferState();
                // Colors come from their own stream; every other attribute stays in the slots.
                GlStateManager._glBindBuffer(ARRAY_BUFFER, colorStream);
                GL15.glBufferData(ARRAY_BUFFER, (long) capacity * 16, STREAM_DRAW);
                VertexFormatElement.COLOR.setupBufferState(
                        built.getElements().indexOf(VertexFormatElement.COLOR), 0, 4);
                GlStateManager._glBindBuffer(ELEMENT_ARRAY_BUFFER, indices);
                GL15.glBufferData(ELEMENT_ARRAY_BUFFER, (long) capacity * 24, STREAM_DRAW);
                GlStateManager._glBindBuffer(ARRAY_BUFFER, vertices);
                format = bound = built;
            }
            GL15.glBufferSubData(ARRAY_BUFFER, (long) firstUnwritten * 4 * stride, data);
            VertexBuffer.unbind();
        }
        firstUnwritten = slotCount;
        colorsChanged = true;
        drawnCount = 0;
        return true;
    }

    /** A vertex extension that rewrites colors would make the separate color stream wrong. */
    private boolean colorsMatch(ByteBuffer data, VertexFormat built) {
        if (!built.contains(VertexFormatElement.COLOR)) return disable("no color attribute");
        int stride = built.getVertexSize(), offset = built.getOffset(VertexFormatElement.COLOR);
        int vertexCount = (slotCount - firstUnwritten) * 4;
        if (data.remaining() != stride * vertexCount) return disable("unexpected vertex count");
        for (int i = 0; i < vertexCount; i++)
            if (data.getInt(data.position() + i * stride + offset)
                    != colors[firstUnwritten * 4 + i])
                return disable("vertex colors were changed after writing");
        return true;
    }

    private boolean disable(String reason) {
        available = false;
        LogUtils.getLogger()
                .warn("Retained fire geometry disabled ({}); drawing fire surfaces each frame",
                        reason);
        return false;
    }

    private ByteBuffer staging(int bytes) {
        if (upload == null || upload.capacity() < bytes)
            upload = upload == null
                    ? MemoryUtil.memAlloc(Math.max(bytes, 4096))
                    : MemoryUtil.memRealloc(upload, Math.max(bytes, upload.capacity() * 2));
        upload.clear();
        return upload;
    }

    private void sendColors() {
        var bytes = staging(slotCount * 16);
        bytes.asIntBuffer().put(colors, 0, slotCount * 4);
        bytes.limit(slotCount * 16);
        GlStateManager._glBindBuffer(ARRAY_BUFFER, colorStream);
        GL15.glBufferSubData(ARRAY_BUFFER, 0, bytes);
        colorsChanged = false;
    }

    /** Uploads the indices of this frame's quads, in vanilla's quad triangle order. */
    private void sendDrawnSlots() {
        if (activeCount > 0) {
            var bytes = staging(activeCount * 24);
            var out = bytes.asIntBuffer();
            for (int i = 0; i < activeCount; i++) {
                int base = active[i] * 4;
                out.put(base).put(base + 1).put(base + 2).put(base + 2).put(base + 3).put(base);
            }
            bytes.limit(activeCount * 24);
            BufferUploader.invalidate();
            GlStateManager._glBindVertexArray(vertexArray);
            GlStateManager._glBindBuffer(ELEMENT_ARRAY_BUFFER, indices);
            GL15.glBufferSubData(ELEMENT_ARRAY_BUFFER, 0, bytes);
            VertexBuffer.unbind();
        }
        if (drawn.length < active.length) drawn = new int[active.length];
        System.arraycopy(active, 0, drawn, 0, activeCount);
        drawnCount = activeCount;
    }

    /** Draws every non-empty mesh with one material setup, relative to the shared anchor. */
    static void draw(RenderType type, List<? extends RetainedQuads<?>> meshes, Vec3 camera) {
        boolean any = false;
        for (var mesh : meshes) any |= mesh.drawnCount > 0;
        if (!any) return;
        type.setupRenderState();
        var shader = RenderSystem.getShader();
        try {
            if (shader == null) return;
            VIEW.set(RenderSystem.getModelViewMatrix())
                    .translate(
                            (float) (anchorX - camera.x),
                            (float) (anchorY - camera.y),
                            (float) (anchorZ - camera.z));
            shader.setDefaultUniforms(
                    VertexFormat.Mode.QUADS,
                    VIEW,
                    RenderSystem.getProjectionMatrix(),
                    Minecraft.getInstance().getWindow());
            shader.apply();
            BufferUploader.invalidate();
            for (var mesh : meshes) {
                if (mesh.drawnCount == 0) continue;
                GlStateManager._glBindVertexArray(mesh.vertexArray);
                RenderSystem.drawElements(TRIANGLES, mesh.drawnCount * 6, UNSIGNED_INT);
            }
            shader.clear();
        } finally {
            VertexBuffer.unbind();
            type.clearRenderState();
        }
    }

    @Override
    public void close() {
        if (vertexArray != 0) {
            RenderSystem.assertOnRenderThread();
            GlStateManager._glDeleteVertexArrays(vertexArray);
            GlStateManager._glDeleteBuffers(vertices);
            GlStateManager._glDeleteBuffers(colorStream);
            GlStateManager._glDeleteBuffers(indices);
            vertexArray = vertices = colorStream = indices = 0;
        }
        if (upload != null) {
            MemoryUtil.memFree(upload);
            upload = null;
        }
        forgetSlots();
        bound = null;
        capacity = 0;
        builtGeneration = -1;
    }
}
