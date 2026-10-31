package com.zerotheabsolute.realisticfirephysics;

import com.zerotheabsolute.realisticfirephysics.FireField.Site;

import net.minecraft.core.BlockPos;

import java.util.function.Predicate;

/** Resolves one horizontal lattice step, including a one-block change in elevation. */
final class GroundFirePath {
    private GroundFirePath() {}

    static Site next(Site source, Site target, Predicate<BlockPos> fuel) {
        boolean diagonal = source.x() != target.x() && source.z() != target.z();
        if (diagonal
                && (!fuel.test(new Site(target.x(), source.y(), source.z()).block())
                        || !fuel.test(new Site(source.x(), source.y(), target.z()).block())))
            return null;
        if (fuel.test(target.block())) return target;

        // Elevation changes only occur at block boundaries. Cardinal transitions avoid
        // cutting across corners where the two surfaces do not actually touch.
        if (diagonal || source.block().equals(target.block())) return null;
        var higher = new Site(target.x(), target.y() + 1, target.z());
        if (fuel.test(higher.block())) return higher;
        var lower = new Site(target.x(), target.y() - 1, target.z());
        return fuel.test(lower.block()) ? lower : null;
    }
}
