package com.zerotheabsolute.surfacefire.embers;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class EmberMotionTest {
    @Test
    void aLiftedEmberSettlesBeforeItCools() {
        var motion = new EmberMotion();
        motion.y = .11;
        double y = 1, highest = y;
        int landing = 0;
        for (int age = 0; age < EmberMotion.MAX_AGE; age++) {
            motion.advance(age, 0, 0);
            y += motion.y;
            highest = Math.max(highest, y);
            if (y < 0) {
                landing = age;
                break;
            }
        }
        assertTrue(highest > 3);
        assertTrue(landing > 40 && landing < EmberMotion.MAX_AGE);
        assertTrue(EmberMotion.heat(landing) > 0);
    }

    @Test
    void reversingWindChangesTravelWithoutVelocityJumps() {
        var motion = new EmberMotion();
        for (int i = 0; i < 40; i++) motion.advance(i, 3, 0);
        assertTrue(motion.x > .14);
        double previous = motion.x;
        motion.advance(40, -3, 0);
        assertTrue(motion.x > 0 && motion.x < previous);
        for (int i = 41; i < 81; i++) motion.advance(i, -3, 0);
        assertTrue(motion.x < -.13);
    }

    @Test
    void heatExpiresAndFallSpeedRemainsBounded() {
        var motion = new EmberMotion();
        for (int i = 0; i < 1000; i++) motion.advance(i, 0, 0);
        assertEquals(-.12, motion.y, 1e-9);
        assertEquals(0, EmberMotion.heat(EmberMotion.MAX_AGE));
        assertEquals(0, EmberMotion.heat(Integer.MAX_VALUE));
        assertEquals(1, EmberMotion.heat(-1));
    }
}
