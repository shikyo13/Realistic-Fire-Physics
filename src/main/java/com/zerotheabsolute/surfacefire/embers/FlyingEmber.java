package com.zerotheabsolute.surfacefire.embers;

import com.zerotheabsolute.surfacefire.FireField;
import com.zerotheabsolute.surfacefire.FireSettings;
import com.zerotheabsolute.surfacefire.simulation.FireClocks;
import com.zerotheabsolute.surfacefire.structure.CanopyHeat;
import com.zerotheabsolute.surfacefire.structure.StructureField;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Server-authoritative contact ignition; unloaded terrain is never queried or loaded. */
public final class FlyingEmber extends Entity {
    private static final EntityDataAccessor<Integer> AGE =
            SynchedEntityData.defineId(FlyingEmber.class, EntityDataSerializers.INT);
    private final EmberMotion motion = new EmberMotion();
    private int age, lerpSteps;
    private long outbreak;
    private Vec3 target = Vec3.ZERO;

    public FlyingEmber(EntityType<? extends FlyingEmber> type, Level level) {
        super(type, level);
        noPhysics = true;
    }

    void initialize(CanopyHeat.Emitter source) {
        setPos(source.x(), source.y() + .08, source.z());
        outbreak = source.owner();
        motion.y = .07 + random.nextDouble() * .04;
    }

    public float heat() {
        return EmberMotion.heat(entityData.get(AGE));
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(AGE, 0);
    }

    @Override
    public void tick() {
        super.tick();
        if (!(level() instanceof ServerLevel level)) {
            if (lerpSteps > 0) {
                setPos(position().lerp(target, 1.0 / lerpSteps));
                lerpSteps--;
            }
            return;
        }
        var pool = Embers.get(level);
        if (!pool.active.contains(getId()) && pool.active.size() >= Embers.MAX_ACTIVE) {
            discard();
            return;
        }
        pool.active.add(getId());
        if (!FireSettings.EMBER_IGNITION.get()
                || age >= EmberMotion.MAX_AGE
                || !level.getGameRules().getBoolean(GameRules.RULE_DOFIRETICK)
                || level.getFluidState(blockPosition()).is(FluidTags.WATER)
                || level.isRainingAt(blockPosition())) {
            discard();
            return;
        }
        if (!FireClocks.ticking(level, ChunkPos.asLong(blockPosition()))) return;
        int wind =
                pool.wind.sample(
                        blockPosition().asLong(),
                        blockPosition().getX(),
                        blockPosition().getY(),
                        blockPosition().getZ(),
                        level.getGameTime());
        motion.advance(age, pool.wind.x(wind), pool.wind.z(wind));
        Vec3 next = position().add(motion.x, motion.y, motion.z);
        var destination = BlockPos.containing(next);
        if (!FireClocks.ticking(level, ChunkPos.asLong(destination))) return;
        var hit =
                level.clip(
                        new ClipContext(
                                position(),
                                next,
                                ClipContext.Block.COLLIDER,
                                ClipContext.Fluid.ANY,
                                this));
        if (hit.getType() != HitResult.Type.MISS) {
            land(level, hit);
            discard();
            return;
        }
        setPos(next);
        age++;
        if (age % 5 == 0) entityData.set(AGE, age);
    }

    private void land(ServerLevel level, BlockHitResult hit) {
        var pos = hit.getBlockPos();
        if (!level.getFluidState(pos).isEmpty()
                || level.isRainingAt(pos.above())
                || EmberMotion.heat(age) <= 0) return;
        if (hit.getDirection() == Direction.UP && FireField.fuel(level, pos) > 0) {
            FireField.get(level)
                    .ignite(
                            level,
                            new FireField.Site(
                                    (int) Math.floor(hit.getLocation().x * 8),
                                    pos.getY(),
                                    (int) Math.floor(hit.getLocation().z * 8)),
                            outbreak);
        } else {
            StructureField.get(level).igniteFrom(level, pos, position(), outbreak);
        }
    }

    @Override
    public void lerpTo(double x, double y, double z, float yaw, float pitch, int steps) {
        target = new Vec3(x, y, z);
        lerpSteps = Math.max(1, steps);
    }

    @Override
    public void remove(RemovalReason reason) {
        super.remove(reason);
        if (level() instanceof ServerLevel level) Embers.release(level, getId());
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putInt("Age", age);
        tag.putLong("Outbreak", outbreak);
        tag.putDouble("Vx", motion.x);
        tag.putDouble("Vy", motion.y);
        tag.putDouble("Vz", motion.z);
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        age = Math.clamp(tag.getInt("Age"), 0, EmberMotion.MAX_AGE);
        outbreak = tag.getLong("Outbreak");
        motion.x = finiteVelocity(tag.getDouble("Vx"));
        motion.y = finiteVelocity(tag.getDouble("Vy"));
        motion.z = finiteVelocity(tag.getDouble("Vz"));
        entityData.set(AGE, age);
    }

    private static double finiteVelocity(double value) {
        return Double.isFinite(value) ? Math.clamp(value, -.25, .25) : 0;
    }
}
