package com.zerotheabsolute.surfacefire;

import static org.junit.jupiter.api.Assertions.*;

import io.netty.buffer.Unpooled;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.FriendlyByteBuf;

import org.junit.jupiter.api.Test;

import java.util.List;

class VegetationScorchTest {
    private static FirePacket.Sample burning() {
        return new FirePacket.Sample(new FireField.Site(-1, 0, -1), 100, 2, false);
    }

    @Test
    void quenchingFreezesHalfBurnedPlants() {
        var stopped = burning().quench(119);
        assertEquals(.5f, VegetationScorch.exposure(stopped, 120));
        assertEquals(.5f, VegetationScorch.exposure(stopped, 100000));
        assertEquals(1, VegetationScorch.exposure(burning(), 200));
    }

    @Test
    void futureIgnitionsAndNonPlantsHaveNoScorch() {
        assertEquals(0, VegetationScorch.exposure(burning(), 90));
        assertEquals(
                0,
                VegetationScorch.exposure(
                        new FirePacket.Sample(burning().site(), 100, 1, false), 200));
    }

    @Test
    void networkSnapshotRetainsTheStoppedClock() {
        var packet =
                new FirePacket("minecraft:overworld", true, 300, List.of(burning().quench(119)));
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            FirePacket.CODEC.encode(buffer, packet);
            assertEquals(packet, FirePacket.CODEC.decode(buffer));
        } finally {
            buffer.release();
        }
    }

    @Test
    void savedFieldRetainsPartialExposure() {
        var item = new CompoundTag();
        item.putInt("X", -1);
        item.putInt("Y", 0);
        item.putInt("Z", -1);
        item.putLong("Ignition", 100);
        item.putInt("Fuel", 2);
        item.putBoolean("Quenched", true);
        item.putLong("StoppedAt", 119);
        var samples = new ListTag();
        samples.add(item);
        var input = new CompoundTag();
        input.put("Samples", samples);
        var output = FireField.load(input, null).save(new CompoundTag(), null);
        assertEquals(119, output.getList("Samples", 10).getCompound(0).getLong("StoppedAt"));
    }
}
