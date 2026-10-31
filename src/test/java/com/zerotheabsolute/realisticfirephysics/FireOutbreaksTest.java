package com.zerotheabsolute.realisticfirephysics;

import static org.junit.jupiter.api.Assertions.*;

import net.minecraft.nbt.CompoundTag;

import org.junit.jupiter.api.Test;

class FireOutbreaksTest {
    private static final FireOutbreaks.Fuel GROUND = FireOutbreaks.Fuel.GROUND;
    private static final FireOutbreaks.Fuel WOOD = FireOutbreaks.Fuel.STRUCTURE;

    @Test
    void pausedOutbreakKeepsItsSpentAllowanceWithoutBlockingIndependentFires() {
        var budgets = new FireOutbreaks();
        long paused = budgets.claim(GROUND, 0, 100, 200, 1);
        budgets.retain(paused, 1200);
        budgets.tick(1000);
        assertEquals(paused, budgets.live(paused, 1000));
        assertEquals(0, budgets.claim(GROUND, paused, 1000, 1200, 1));
        assertNotEquals(0, budgets.claim(GROUND, 0, 1000, 1200, 1));
        budgets.tick(1250);
        assertEquals(0, budgets.live(paused, 1250));
    }

    @Test
    void exhaustedFireDoesNotBlockAnIndependentIgnition() {
        var budgets = new FireOutbreaks();
        long a = budgets.claim(GROUND, 0, 100, 200, 2);
        assertEquals(a, budgets.claim(GROUND, a, 101, 201, 2));
        assertEquals(0, budgets.claim(GROUND, a, 102, 202, 2));
        long b = budgets.claim(GROUND, 0, 102, 202, 2);
        assertNotEquals(0, b);
        assertNotEquals(a, b);
        budgets.tick(240);
        assertEquals(0, budgets.live(a, 240));
        assertNotEquals(0, budgets.claim(GROUND, 0, 240, 500, 2));
    }

    @Test
    void mergedFrontsKeepCombinedSpentAllowanceAndBothFuelCounters() {
        var budgets = new FireOutbreaks();
        long a = budgets.claim(GROUND, 0, 100, 200, 2);
        long b = budgets.claim(GROUND, 0, 100, 300, 2);
        long merged = budgets.merge(a, b, 101);
        assertEquals(merged, budgets.live(a, 101));
        assertEquals(merged, budgets.live(b, 101));
        assertEquals(0, budgets.claim(GROUND, b, 102, 302, 2));
        assertEquals(merged, budgets.claim(WOOD, b, 102, 302, 2));
        budgets.tick(250);
        assertNotEquals(0, budgets.live(a, 250));
        budgets.tick(400);
        assertTrue(budgets.status().contains("ground=0, structures=0"));
    }

    @Test
    void reloadPreservesLiveBudgetsAndDoesNotReuseHistoricalIds() {
        var before = new FireOutbreaks();
        long a = before.claim(WOOD, 0, 100, 200, 1);
        long b = before.claim(GROUND, 0, 100, 200, 1);
        before.merge(a, b, 101);
        var after = FireOutbreaks.load(before.save(new CompoundTag(), null), null);
        assertEquals(0, after.claim(WOOD, b, 110, 210, 1));
        after.tick(250);
        long c = after.claim(WOOD, 0, 250, 300, 1);
        assertTrue(c > b);
        assertEquals(0, after.live(a, 250));
    }

    @Test
    void quenchedHistoryCannotReconnectToARetiredOutbreak() {
        var budgets = new FireOutbreaks();
        long old = budgets.claim(GROUND, 0, 10, 20, 1);
        long fresh = budgets.claim(GROUND, 0, 30, 50, 2);
        assertEquals(fresh, budgets.merge(old, fresh, 30));
        assertEquals(fresh, budgets.claim(GROUND, fresh, 30, 50, 2));
    }

    @Test
    void loweringLimitStopsFurtherGrowthWithoutRemovingExistingHistory() {
        var budgets = new FireOutbreaks();
        long a = budgets.claim(GROUND, 0, 0, 100, 8);
        for (int i = 0; i < 5; i++) assertEquals(a, budgets.claim(GROUND, a, 0, 100, 8));
        assertEquals(0, budgets.claim(GROUND, a, 1, 101, 2));
        assertTrue(budgets.status().contains("ground=6"));
    }
}
