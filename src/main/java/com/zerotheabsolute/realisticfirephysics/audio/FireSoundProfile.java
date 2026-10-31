package com.zerotheabsolute.realisticfirephysics.audio;

/** Source scale changes combustion pressure and body without adding a separate wind recording. */
public enum FireSoundProfile {
    TORCH(.055, .9, .12, 3500),
    WILDFIRE(.24, .70, .28, 2200);
    final double pressureGain, crackleGain, softeningMix, softeningFrequency;

    FireSoundProfile(
            double pressureGain,
            double crackleGain,
            double softeningMix,
            double softeningFrequency) {
        this.pressureGain = pressureGain;
        this.crackleGain = crackleGain;
        this.softeningMix = softeningMix;
        this.softeningFrequency = softeningFrequency;
    }
}
