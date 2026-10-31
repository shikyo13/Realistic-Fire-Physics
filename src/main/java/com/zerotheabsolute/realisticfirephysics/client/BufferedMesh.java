package com.zerotheabsolute.realisticfirephysics.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.VertexBuffer;

import net.minecraft.client.renderer.RenderType;

import org.joml.Matrix4f;

/** Retained vertex geometry that can be drawn repeatedly without rebuilding or uploading. */
final class BufferedMesh implements AutoCloseable {
    private final int initialCapacity;
    private final Matrix4f translatedView = new Matrix4f();
    private ByteBufferBuilder storage;
    private VertexBuffer vertices;
    private boolean populated;

    BufferedMesh(int initialCapacity) {
        this.initialCapacity = initialCapacity;
    }

    BufferBuilder begin(RenderType type) {
        populated = false;
        if (storage == null) storage = new ByteBufferBuilder(initialCapacity);
        return new BufferBuilder(storage, type.mode(), type.format());
    }

    void upload(BufferBuilder builder, RenderType type) {
        try (var mesh = builder.build()) {
            if (mesh == null) {
                close();
                return;
            }
            if (type.sortOnUpload()) {
                FlameSortScratch.begin();
                try {
                    mesh.sortQuads(storage, RenderSystem.getVertexSorting());
                } finally {
                    FlameSortScratch.end();
                }
            }
            if (vertices == null) vertices = new VertexBuffer(VertexBuffer.Usage.DYNAMIC);
            vertices.bind();
            try {
                vertices.upload(mesh);
                populated = true;
            } finally {
                VertexBuffer.unbind();
            }
        }
    }

    void draw(RenderType type) {
        draw(type, 0, 0, 0);
    }

    void draw(RenderType type, float x, float y, float z) {
        if (!populated) return;
        type.setupRenderState();
        try {
            vertices.bind();
            // Apply a material's view-space depth bias before the cached mesh's local origin.
            translatedView.set(RenderSystem.getModelViewMatrix()).translate(x, y, z);
            vertices.drawWithShader(
                    translatedView, RenderSystem.getProjectionMatrix(), RenderSystem.getShader());
        } finally {
            VertexBuffer.unbind();
            type.clearRenderState();
        }
    }

    @Override
    public void close() {
        populated = false;
        if (vertices != null) {
            vertices.close();
            vertices = null;
        }
        if (storage != null) {
            storage.close();
            storage = null;
        }
    }
}
