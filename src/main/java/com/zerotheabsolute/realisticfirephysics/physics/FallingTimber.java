package com.zerotheabsolute.realisticfirephysics.physics;

import com.zerotheabsolute.realisticfirephysics.FireField;
import com.zerotheabsolute.realisticfirephysics.scorch.ScorchArchive;
import com.zerotheabsolute.realisticfirephysics.structure.StructureField;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.syncher.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.*;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.*;
import net.neoforged.neoforge.entity.IEntityWithComplexSpawn;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.*;

/** One server-owned body per detached tree, with voxel contacts and ordinary entity persistence. */
public final class FallingTimber extends Entity implements IEntityWithComplexSpawn {
    private static final EntityDataAccessor<Quaternionf> ROTATION =
            SynchedEntityData.defineId(FallingTimber.class, EntityDataSerializers.QUATERNION);
    private static final EntityDataAccessor<Boolean> WET =
            SynchedEntityData.defineId(FallingTimber.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> CLEARED =
            SynchedEntityData.defineId(FallingTimber.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Integer> BURN_AGE =
            SynchedEntityData.defineId(FallingTimber.class, EntityDataSerializers.INT);
    private static final int SUBSTEPS = 6, REST_TICKS = 16, MAX_FLIGHT_TICKS = 600;
    private static final int CONTACT_INTERVAL = 5;
    private static final int MAX_SETTLING_CONTACT_TICKS = 50;
    private static final double INITIAL_ANGULAR_SPEED = .023;
    private static final double LANDING_LINEAR_DAMPING = .82, LANDING_ANGULAR_DAMPING = .72;
    public final TimberDynamics motion = new TimberDynamics();
    private final TimberCollision collision = new TimberCollision();
    private List<TimberBlocks> parts = List.of();
    private List<Vec3> offsets = List.of();
    private Vec3 center = Vec3.ZERO;
    private double radius = 1;
    private int age, quietTicks, settledContactTicks;
    private long clearEpoch, heatEpoch;
    private Vec3 longAxis = new Vec3(0, 1, 0);
    private final Quaternionf previousRotation = new Quaternionf(),
            visualRotation = new Quaternionf();
    private Vec3 target = Vec3.ZERO;
    private int lerpSteps;
    private boolean tracked, impactSound;
    private boolean lightTracked;

    public FallingTimber(EntityType<? extends FallingTimber> type, Level level) {
        super(type, level);
        noPhysics = true;
        if (level instanceof ServerLevel server) heatEpoch = ScorchArchive.get(server).heatEpoch();
    }

    public void initialize(BlockPos origin, List<TimberBlocks> values, Vec3 wind) {
        clearEpoch = TimberCollapse.get((ServerLevel) level()).clearEpoch();
        parts = List.copyOf(values);
        geometry();
        motion.position = Vec3.atCenterOf(origin).add(center);
        Vec3 direction = wind.lengthSqr() > .01 ? wind.normalize() : new Vec3(1, 0, .3).normalize();
        motion.angularVelocity =
                new Vec3(direction.z, 0, -direction.x).scale(INITIAL_ANGULAR_SPEED);
        setPos(motion.position);
    }

    private void geometry() {
        Vec3 sum = Vec3.ZERO;
        int count = 0;
        for (var p : parts)
            if (!p.leaf()) {
                sum = sum.add(Vec3.atLowerCornerOf(p.offset()));
                count++;
            }
        center = count == 0 ? Vec3.ZERO : sum.scale(1.0 / count);
        var local = new ArrayList<Vec3>();
        double inertia = 0;
        radius = 1;
        for (var p : parts) {
            Vec3 offset = Vec3.atLowerCornerOf(p.offset()).subtract(center);
            local.add(offset);
            radius = Math.max(radius, offset.length() + 1);
            if (!p.leaf()) inertia += offset.lengthSqr();
        }
        offsets = List.copyOf(local);
        motion.inertia(inertia / Math.max(1, count));
        double sx = 0, sy = 0, sz = 0;
        for (int i = 0; i < parts.size(); i++)
            if (!parts.get(i).leaf()) {
                var p = offsets.get(i);
                sx += p.x * p.x;
                sy += p.y * p.y;
                sz += p.z * p.z;
            }
        longAxis =
                sy >= sx && sy >= sz
                        ? new Vec3(0, 1, 0)
                        : sx >= sz ? new Vec3(1, 0, 0) : new Vec3(0, 0, 1);
    }

    public List<TimberBlocks> parts() {
        return parts;
    }

    public Vec3 offset(int index) {
        return offsets.get(index);
    }

    public int age() {
        return age;
    }

    public void extinguish() {
        entityData.set(WET, true);
    }

    public boolean quenched() {
        return entityData.get(WET);
    }

    public boolean burnMarksCleared() {
        return entityData.get(CLEARED);
    }

    public Quaternionf rotation(float partial) {
        return new Quaternionf(previousRotation).slerp(visualRotation, partial);
    }

    public Vec3 centerOf(int i) {
        return motion.position.add(motion.rotate(offsets.get(i)));
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(ROTATION, new Quaternionf());
        builder.define(WET, false);
        builder.define(CLEARED, false);
        builder.define(BURN_AGE, 0);
    }

    @Override
    public void tick() {
        super.tick();
        if (!lightTracked) {
            com.zerotheabsolute.realisticfirephysics.FireLighting.get(level()).trackTimber(this);
            lightTracked = true;
        }
        previousRotation.set(visualRotation);
        if (level().isClientSide) {
            if (lerpSteps > 0) {
                setPos(position().lerp(target, 1.0 / lerpSteps));
                lerpSteps--;
            }
            visualRotation.slerp(entityData.get(ROTATION), .65f);
            age = entityData.get(BURN_AGE);
            return;
        }
        var level = (ServerLevel) level();
        long heat = ScorchArchive.get(level).heatEpoch();
        if (heatEpoch != heat) {
            extinguish();
            heatEpoch = heat;
        }
        if (!tracked) {
            TimberCollapse.get(level).track(getId());
            tracked = true;
        }
        if (parts.isEmpty()) {
            discard();
            return;
        }
        long epoch = TimberCollapse.get(level).clearEpoch();
        if (clearEpoch != epoch) {
            entityData.set(WET, true);
            entityData.set(CLEARED, true);
            clearEpoch = epoch;
        }
        // Never load terrain for a body approaching the loaded-world boundary.
        for (int i = 0; i < parts.size(); i++) {
            var p = BlockPos.containing(centerOf(i));
            if (!level.hasChunkAt(p)
                    || !level.hasChunkAt(p.offset(1, 0, 1))
                    || !level.hasChunkAt(p.offset(-1, 0, -1))) return;
        }
        if (level.getGameRules().getBoolean(GameRules.RULE_DOFIRETICK) && !quenched()) age++;
        entityData.set(BURN_AGE, age);
        boolean contact = false;
        for (int step = 0; step < SUBSTEPS; step++) {
            motion.integrate(1.0 / SUBSTEPS);
            collision.orient(motion.rotation);
            for (int i = 0; i < parts.size(); i++) {
                if (parts.get(i).leaf() || consumed(parts.get(i))) continue;
                Vec3 point = centerOf(i);
                var box = collision.bounds(point);
                for (var shape : level.getBlockCollisions(this, box)) {
                    for (var solid : shape.toAabbs()) {
                        var hit = collision.contact(point, solid);
                        if (hit == null) continue;
                        motion.resolve(hit);
                        point = centerOf(i);
                        contact = true;
                    }
                }
            }
        }
        setPos(motion.position);
        entityData.set(ROTATION, new Quaternionf(motion.rotation));
        visualRotation.set(entityData.get(ROTATION));
        if (contact && !impactSound) {
            level.playSound(
                    null, blockPosition(), SoundEvents.WOOD_BREAK, SoundSource.BLOCKS, 1.7f, .65f);
            impactSound = true;
        }
        quietTicks = contact && motion.resting() ? quietTicks + 1 : 0;
        if (contact && Math.abs(motion.rotate(longAxis).y) < .5) {
            settledContactTicks++;
            motion.velocity = motion.velocity.scale(LANDING_LINEAR_DAMPING);
            motion.angularVelocity = motion.angularVelocity.scale(LANDING_ANGULAR_DAMPING);
        }
        if (tickCount % CONTACT_INTERVAL == 0) environment(level);
        if (quietTicks >= REST_TICKS
                || settledContactTicks >= MAX_SETTLING_CONTACT_TICKS
                || tickCount >= MAX_FLIGHT_TICKS) TimberLanding.settle(this, level);
        if (getY() < level.getMinBuildHeight() - 32) discard();
    }

    public boolean consumed(TimberBlocks part) {
        return part.consumed(age);
    }

    public void emitLight(it.unimi.dsi.fastutil.longs.Long2ByteMap nodes) {
        if (quenched()) return;
        for (int i = 0; i < parts.size(); i++) {
            if (!parts.get(i).burning(age)) continue;
            Vec3 point;
            if (level().isClientSide) {
                var local = offsets.get(i);
                var rotated =
                        visualRotation.transform(
                                new Vector3f((float) local.x, (float) local.y, (float) local.z));
                point = position().add(rotated.x, rotated.y, rotated.z);
            } else point = centerOf(i);
            new com.zerotheabsolute.realisticfirephysics.lighting.LightCluster.Source(
                            point.x, point.y + .5, point.z, 14)
                    .emit(
                            (x, y, z, light) -> {
                                long key = BlockPos.asLong(x, y, z);
                                if (light > nodes.get(key)) nodes.put(key, (byte) light);
                            });
        }
    }

    private void environment(ServerLevel level) {
        var box = getBoundingBox();
        var nearby = level.getEntitiesOfClass(LivingEntity.class, box, e -> !e.isSpectator());
        for (int i = 0; i < parts.size(); i++) {
            var part = parts.get(i);
            if (part.leaf() || consumed(part)) continue;
            Vec3 point = centerOf(i);
            BlockPos pos = BlockPos.containing(point);
            if (level.getFluidState(pos).is(FluidTags.WATER) || level.isRainingAt(pos))
                entityData.set(WET, true);
            var logBox =
                    new AABB(
                            point.x - .52,
                            point.y - .52,
                            point.z - .52,
                            point.x + .52,
                            point.y + .52,
                            point.z + .52);
            for (var living : nearby)
                if (living.getBoundingBox().intersects(logBox)) {
                    if (motion.velocity.lengthSqr() > .0064) {
                        living.hurt(
                                damageSources().fallingBlock(this),
                                Math.min(8, (float) (motion.velocity.length() * 12)));
                        living.push(motion.velocity.x * .7, .1, motion.velocity.z * .7);
                    }
                    if (!quenched() && part.burning(age)) {
                        living.hurt(damageSources().inFire(), 1);
                        living.igniteForSeconds(2);
                    }
                }
            if (quenched() || !part.burning(age)) continue;
            var below = pos.below();
            if (FireField.fuel(level, below) > 0)
                FireField.get(level)
                        .ignite(
                                level,
                                new FireField.Site(
                                        below.getX() * 8 + 4, below.getY(), below.getZ() * 8 + 4));
            for (Direction d : Direction.values())
                StructureField.get(level).igniteFrom(level, pos.relative(d), point);
        }
    }

    @Override
    protected AABB makeBoundingBox() {
        return new AABB(
                getX() - radius,
                getY() - radius,
                getZ() - radius,
                getX() + radius,
                getY() + radius,
                getZ() + radius);
    }

    @Override
    public boolean isPickable() {
        return false;
    }

    @Override
    public void lerpTo(double x, double y, double z, float yaw, float pitch, int steps) {
        target = new Vec3(x, y, z);
        lerpSteps = Math.max(1, steps);
    }

    @Override
    public void remove(RemovalReason reason) {
        super.remove(reason);
        if (tracked && level() instanceof ServerLevel server)
            TimberCollapse.get(server).release(getId());
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putLong("HeatEpoch", heatEpoch);
        tag.put("Parts", TimberStorage.write(parts));
        tag.putInt("Age", age);
        tag.putInt("Quiet", quietTicks);
        tag.putInt("ContactTicks", settledContactTicks);
        tag.putBoolean("Wet", quenched());
        tag.putBoolean("Cleared", burnMarksCleared());
        tag.putLong("ClearEpoch", clearEpoch);
        tag.putInt("Flight", tickCount);
        tag.putDouble("Qx", motion.rotation.x);
        tag.putDouble("Qy", motion.rotation.y);
        tag.putDouble("Qz", motion.rotation.z);
        tag.putDouble("Qw", motion.rotation.w);
        tag.putDouble("Vx", motion.velocity.x);
        tag.putDouble("Vy", motion.velocity.y);
        tag.putDouble("Vz", motion.velocity.z);
        tag.putDouble("Wx", motion.angularVelocity.x);
        tag.putDouble("Wy", motion.angularVelocity.y);
        tag.putDouble("Wz", motion.angularVelocity.z);
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        if (tag.contains("HeatEpoch")) heatEpoch = tag.getLong("HeatEpoch");
        parts = TimberStorage.read(tag.getList("Parts", Tag.TAG_COMPOUND), registryAccess());
        geometry();
        age = Math.max(0, tag.getInt("Age"));
        quietTicks = tag.getInt("Quiet");
        settledContactTicks = Math.max(0, tag.getInt("ContactTicks"));
        entityData.set(BURN_AGE, age);
        tickCount = Math.max(0, tag.getInt("Flight"));
        entityData.set(WET, tag.getBoolean("Wet"));
        entityData.set(CLEARED, tag.getBoolean("Cleared"));
        clearEpoch = tag.getLong("ClearEpoch");
        motion.position = position();
        motion.rotation
                .set(
                        tag.getDouble("Qx"),
                        tag.getDouble("Qy"),
                        tag.getDouble("Qz"),
                        tag.contains("Qw") ? tag.getDouble("Qw") : 1)
                .normalize();
        motion.velocity = new Vec3(tag.getDouble("Vx"), tag.getDouble("Vy"), tag.getDouble("Vz"));
        motion.angularVelocity =
                new Vec3(tag.getDouble("Wx"), tag.getDouble("Wy"), tag.getDouble("Wz"));
        entityData.set(ROTATION, new Quaternionf(motion.rotation));
        visualRotation.set(entityData.get(ROTATION));
        previousRotation.set(visualRotation);
        setPos(position());
    }

    @Override
    public void writeSpawnData(RegistryFriendlyByteBuf buffer) {
        TimberSpawnData.write(buffer, parts);
        buffer.writeVarInt(age);
        buffer.writeBoolean(quenched());
        buffer.writeBoolean(burnMarksCleared());
        buffer.writeQuaternion(new Quaternionf(motion.rotation));
    }

    @Override
    public void readSpawnData(RegistryFriendlyByteBuf buffer) {
        parts = TimberSpawnData.read(buffer);
        geometry();
        age = Math.max(0, buffer.readVarInt());
        entityData.set(BURN_AGE, age);
        entityData.set(WET, buffer.readBoolean());
        entityData.set(CLEARED, buffer.readBoolean());
        visualRotation.set(buffer.readQuaternion()).normalize();
        previousRotation.set(visualRotation);
        entityData.set(ROTATION, new Quaternionf(visualRotation));
        motion.rotation.set(visualRotation);
        motion.position = position();
        setPos(position());
    }
}
