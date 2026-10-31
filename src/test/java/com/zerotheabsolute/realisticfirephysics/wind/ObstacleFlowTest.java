package com.zerotheabsolute.realisticfirephysics.wind;

import static org.junit.jupiter.api.Assertions.*;

import com.zerotheabsolute.realisticfirephysics.wind.flow.ObstacleFlow;

import org.junit.jupiter.api.Test;

class ObstacleFlowTest {
    @Test
    void openAirPreservesTheIncidentWindExactly() {
        var flow = new ObstacleFlow();
        flow.rebuild(new ObstacleFlow.Workspace(), (x, y, z) -> false, 0, 0, 0);
        double[] velocity = {2, .3, -.4};
        flow.sample(3.3, 4.5, 6.7, velocity);
        assertArrayEquals(new double[] {2, .3, -.4}, velocity, 0);
    }

    @Test
    void sealedRoomHasNoOutdoorDraft() {
        var flow = new ObstacleFlow();
        flow.rebuild(
                new ObstacleFlow.Workspace(),
                (x, y, z) -> x <= 0 || x >= 7 || y <= 0 || y >= 7 || z <= 0 || z >= 7,
                0,
                0,
                0);
        double[] velocity = {3, .5, -2};
        flow.sample(3.5, 3.5, 3.5, velocity);
        assertArrayEquals(new double[3], velocity, .02);
        assertTrue(flow.divergence() < .003);
    }

    @Test
    void wallStopsNormalFlowButPreservesFlowAlongItsFace() {
        var flow = new ObstacleFlow();
        flow.rebuild(new ObstacleFlow.Workspace(), (x, y, z) -> x == 3, 0, 0, 0);
        double[] normal = {2, 0, 0}, tangent = {0, 0, 2};
        flow.sample(3, 3.5, 3.5, normal);
        flow.sample(2.5, 3.5, 3.5, tangent);
        assertArrayEquals(new double[3], normal, 1e-5);
        assertTrue(tangent[2] > 1.9);
        assertTrue(flow.divergence() < .003);
    }

    @Test
    void flowBendsAroundAnObstacleAndRespondsToItsRemoval() {
        var flow = new ObstacleFlow();
        var workspace = new ObstacleFlow.Workspace();
        ObstacleFlow.Terrain obstacle = (x, y, z) -> x == 3 && y >= 2 && y <= 5 && z >= 2 && z <= 5;
        assertTrue(flow.rebuild(workspace, obstacle, 0, 0, 0));
        double[] velocity = {2, 0, 0};
        flow.sample(2.6, 5.5, 3.5, velocity);
        assertTrue(velocity[0] < 1.8);
        assertTrue(velocity[1] > .1);
        assertFalse(flow.rebuild(workspace, obstacle, 0, 0, 0));
        assertTrue(flow.rebuild(workspace, (x, y, z) -> false, 0, 0, 0));
        velocity[0] = 2;
        velocity[1] = velocity[2] = 0;
        flow.sample(2.6, 5.5, 3.5, velocity);
        assertArrayEquals(new double[] {2, 0, 0}, velocity, 0);
    }

    @Test
    void samplingNeverReadsTerrainAndCacheWorkIsBounded() {
        var wind = new TerrainWind();
        double[] velocity = new double[3];
        for (int n = 0; n < 1000; n++) wind.sample(0, n * 8, 1, 0, velocity);
        assertEquals(TerrainWind.CAPACITY, wind.pending());
        int[] reads = {0};
        wind.advance(
                0,
                (x, y, z) -> {
                    reads[0]++;
                    return y == 0;
                });
        assertEquals(1, wind.size());
        assertEquals(ObstacleFlow.SIZE * ObstacleFlow.SIZE * ObstacleFlow.SIZE, reads[0]);
        int before = reads[0];
        for (int n = 0; n < 1000; n++) wind.sample(1, .1, 1.1, .1, velocity);
        assertEquals(before, reads[0]);
        wind.clear();
        assertEquals(0, wind.pending());
        assertEquals(0, wind.size());
    }

    @Test
    void coldTilesExpire() {
        var wind = new TerrainWind();
        wind.sample(0, -1, -1, -1, new double[3]);
        wind.advance(0, (x, y, z) -> false);
        assertEquals(1, wind.size());
        wind.advance(
                200,
                (x, y, z) -> {
                    fail("Expired tiles must not read terrain");
                    return false;
                });
        assertEquals(0, wind.size());
    }

    @Test
    void UndergroundAirHasNoOutdoorGustEvenWhenWallsAreBeyondTheTile() {
        var flow = new ObstacleFlow();
        flow.rebuild(
                new ObstacleFlow.Workspace(),
                new ObstacleFlow.Terrain() {
                    public boolean solid(int x, int y, int z) {
                        return false;
                    }

                    public boolean outdoors(int x, int y, int z) {
                        return false;
                    }
                },
                0,
                0,
                0);
        double[] velocity = {2, .3, -.4};
        flow.sample(3.5, 4.5, 3.5, velocity);
        assertArrayEquals(new double[3], velocity, .001);
    }
}
