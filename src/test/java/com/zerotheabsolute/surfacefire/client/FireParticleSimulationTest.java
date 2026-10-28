package com.zerotheabsolute.surfacefire.client;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class FireParticleSimulationTest {
    private static final FireParticleSimulation.Airflow CALM =
            (t, x, y, z, out) -> {
                out[0] = out[1] = out[2] = 0;
            };

    @Test
    void smokeCannotExhaustTheSparkBudget() {
        var model = new FireParticleSimulation();
        for (int i = 0; i < 2000; i++) model.spawn(true, 0, 0, 0, 1, i);
        assertEquals(FireParticleSimulation.MAX_SMOKE, model.size());
        assertTrue(model.spawn(false, 0, 0, 0, 1, 0));
        for (int i = 0; i < 2000; i++) model.spawn(false, 0, 0, 0, 1, i);
        assertEquals(FireParticleSimulation.CAPACITY, model.size());
    }

    @Test
    void coolingExpiresAllParticlesAndReturnsTheirSlots() {
        var model = new FireParticleSimulation();
        model.spawn(true, 0, 0, 0, 1, 5);
        model.spawn(false, 0, 0, 0, 1, 8);
        model.advance(20, 20, CALM);
        assertEquals(0, model.size());
        assertTrue(model.spawn(false, 0, 0, 0, 1, 10));
    }

    @Test
    void hotSmokeRisesAndEmbersFollowWind() {
        var calm = new FireParticleSimulation();
        var windy = new FireParticleSimulation();
        calm.spawn(true, 0, 0, 0, 1, 21);
        windy.spawn(false, 0, 0, 0, 1, 21);
        double initialY = calm.get(0).y;
        FireParticleSimulation.Airflow wind =
                (t, x, y, z, out) -> {
                    out[0] = 3;
                    out[1] = 0;
                    out[2] = 0;
                };
        for (int i = 0; i < 20; i++) {
            calm.advance(.05, i * .05, CALM);
            windy.advance(.05, i * .05, wind);
        }
        assertTrue(calm.get(0).y > initialY + .5);
        assertTrue(windy.get(0).x > 1);
    }

    @Test
    void framePartitionDoesNotChangeAdvection() {
        var a = new FireParticleSimulation();
        var b = new FireParticleSimulation();
        a.spawn(false, 1, 2, 3, 1, 19);
        b.spawn(false, 1, 2, 3, 1, 19);
        for (int i = 0; i < 10; i++) a.advance(.05, i * .05, CALM);
        for (int i = 0; i < 5; i++) b.advance(.1, i * .1, CALM);
        assertEquals(a.get(0).x, b.get(0).x, .01);
        assertEquals(a.get(0).y, b.get(0).y, .03);
    }

    @Test
    void aStallDoesNotCreateUnboundedIntegrationWork() {
        var model = new FireParticleSimulation();
        model.spawn(true, 0, 0, 0, 1, 1);
        int[] calls = {0};
        model.advance(
                1,
                1,
                (t, x, y, z, out) -> {
                    calls[0]++;
                    CALM.sample(t, x, y, z, out);
                });
        assertTrue(calls[0] <= 5);
        assertTrue(model.get(0).y < 1);
    }

    @Test
    void smallWicksScaleTheirParticlesAndInheritEmitterMomentum() {
        var fire = new FireParticleSimulation();
        var torch = new FireParticleSimulation();
        fire.spawn(false, 0, 0, 0, 1, 55);
        assertTrue(torch.spawnSmall(false, 0, 0, 0, .35, 3, 0, 0, 55));
        assertEquals(fire.get(0).radius * .35, torch.get(0).radius, 1e-9);
        assertTrue(torch.get(0).lifetime < fire.get(0).lifetime);
        assertEquals(fire.get(0).vx * .35 + 3 * .35, torch.get(0).vx, 1e-9);
        assertFalse(torch.spawnSmall(true, 0, 0, 0, Double.NaN, 0, 0, 0, 1));
    }

    @Test
    void resetAndInvalidInputsCannotLeaveStrayParticles() {
        var model = new FireParticleSimulation();
        assertFalse(model.spawn(true, Double.NaN, 0, 0, 1, 1));
        model.spawn(true, 0, 0, 0, 1, 2);
        model.clear();
        assertEquals(0, model.size());
        model.spawn(false, 0, 0, 0, 1, 3);
        model.advance(.05, .05, (t, x, y, z, out) -> out[0] = Double.NaN);
        assertEquals(0, model.size());
    }
}
