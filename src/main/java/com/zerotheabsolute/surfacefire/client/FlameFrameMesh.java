package com.zerotheabsolute.surfacefire.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.VertexBuffer;

import net.minecraft.client.renderer.RenderType;

/** One frame of flame geometry, shared by the depth and glow materials. */
final class FlameFrameMesh implements AutoCloseable {
    private static final int INITIAL_CAPACITY = 1 << 20;
    private ByteBufferBuilder storage;
    private VertexBuffer vertices;
    private boolean populated;

    BufferBuilder begin(RenderType type) {
        populated = false;
        if (storage == null) storage = new ByteBufferBuilder(INITIAL_CAPACITY);
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
        if (!populated) return;
        type.setupRenderState();
        try {
            vertices.bind();
            vertices.drawWithShader(
                    RenderSystem.getModelViewMatrix(),
                    RenderSystem.getProjectionMatrix(),
                    RenderSystem.getShader());
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
