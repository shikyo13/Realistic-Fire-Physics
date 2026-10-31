package com.zerotheabsolute.realisticfirephysics.lighting;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

class LightClusterTest {
    private record Node(int x, int y, int z, int light) {}

    private static List<Node> nodes(LightCluster.Source source) {
        var result = new ArrayList<Node>();
        source.emit((x, y, z, light) -> result.add(new Node(x, y, z, light)));
        return result;
    }

    @Test
    void keepsFractionalCellOrigin() {
        var light = new LightCluster();
        light.add(-.9375, 1.1, .8125, 15, true);
        assertEquals(-.9375, light.source().x(), 1e-9);
        assertEquals(.8125, light.source().z(), 1e-9);
    }

    @Test
    void equalOpposingFrontsJoinAtTheirWeightedCenter() {
        var light = new LightCluster();
        light.add(-.875, 1.1, 0, 15, true);
        light.add(.875, 1.1, 0, 15, true);
        assertEquals(0, light.source().x(), 1e-9);
    }

    @Test
    void activeFlameOutweighsOldEmbers() {
        var light = new LightCluster();
        light.add(0, 1.1, 0, 15, true);
        light.add(1, 1.1, 0, 5, false);
        assertTrue(light.source().x() < .1);
    }

    @Test
    void cooledCellsProduceNoLight() {
        var light = new LightCluster();
        light.add(0, 1, 0, 0, false);
        assertTrue(nodes(light.source()).isEmpty());
    }

    @Test
    void reusedClusterDoesNotRetainPreviousEnergyOrPosition() {
        var cluster = new LightCluster();
        cluster.add(100, 20, 50, 15, true);
        cluster.reset();
        assertTrue(nodes(cluster.source()).isEmpty());
        cluster.add(-2, 1, -3, 5, false);
        assertEquals(-2, cluster.source().x(), 1e-9);
        assertTrue(cluster.source().intensity() < FireEmission.MINIMUM_FLAME);
    }

    @Test
    void oneBlockOfEmbersIsWeakerThanOneBlockOfFlames() {
        var flame = new LightCluster();
        var ember = new LightCluster();
        for (int i = 0; i < 64; i++) {
            double x = ((i & 7) + .5) / 8, z = ((i >> 3) + .5) / 8;
            flame.add(x, 1.1, z, 15, true);
            ember.add(x, 1.1, z, 5, false);
        }
        assertEquals(19, flame.source().intensity(), 1e-9);
        assertEquals(5, ember.source().intensity(), 1e-9);
    }

    @Test
    void lightEngineSamplesAreBounded() {
        var source = new LightCluster.Source(.2, 1.1, -.3, 15);
        var points = nodes(source);
        assertTrue(points.size() <= 8);
        assertTrue(points.stream().allMatch(p -> p.light > 0 && p.light <= 15));
    }

    @Test
    void largerFlamesReachFurtherWithBoundedNodesAndNoWallShortcut() {
        var source = new LightCluster.Source(.5, 1.5, .5, FireEmission.MAXIMUM_SOURCE);
        var extended = nodes(source);
        assertTrue(extended.size() <= 14);
        assertTrue(extended.stream().allMatch(p -> p.light > 0 && p.light <= 15));
        assertTrue(
                propagated(extended, 9, 1, 0)
                        > propagated(nodes(new LightCluster.Source(.5, 1.5, .5, 15)), 9, 1, 0));
        var blocked = new ArrayList<Node>();
        source.emit(
                (x, y, z, light) -> blocked.add(new Node(x, y, z, light)),
                (x, y, z, tx, ty, tz) -> tx < 2);
        assertTrue(blocked.stream().allMatch(p -> p.x < 2));
    }

    @Test
    void integerTranslationPreservesLightShape() {
        var a = nodes(new LightCluster.Source(.2, 1.1, -.3, 14));
        var b = nodes(new LightCluster.Source(12.2, 1.1, -16.3, 14));
        assertEquals(a.size(), b.size());
        for (int i = 0; i < a.size(); i++) {
            assertEquals(a.get(i).light, b.get(i).light);
            assertEquals(a.get(i).x + 12, b.get(i).x);
            assertEquals(a.get(i).z - 16, b.get(i).z);
        }
    }

    @Test
    void crossingBlockBoundaryDoesNotCauseLargeLightJump() {
        var before = nodes(new LightCluster.Source(.4375, 1.1, .2, 15));
        var after = nodes(new LightCluster.Source(.5625, 1.1, .2, 15));
        for (int x = -8; x <= 8; x++)
            for (int z = -8; z <= 8; z++) {
                assertTrue(Math.abs(propagated(before, x, 1, z) - propagated(after, x, 1, z)) <= 1);
            }
    }

    private static int propagated(List<Node> points, int x, int y, int z) {
        int light = 0;
        for (var p : points)
            light =
                    Math.max(
                            light,
                            p.light - Math.abs(p.x - x) - Math.abs(p.y - y) - Math.abs(p.z - z));
        return light;
    }
}
