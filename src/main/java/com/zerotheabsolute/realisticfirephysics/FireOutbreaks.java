package com.zerotheabsolute.realisticfirephysics;

import com.zerotheabsolute.realisticfirephysics.compat.LegacyNames;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/** Spread allowances belong to connected outbreaks, independently of retained surface history. */
public final class FireOutbreaks extends SavedData {
    public enum Fuel {
        GROUND,
        STRUCTURE
    }

    private static final int MAINTENANCE_INTERVAL = 20;
    private static final int MAX_GROUPS = 4096;
    private final Long2ObjectOpenHashMap<Group> groups = new Long2ObjectOpenHashMap<>();
    private long nextId = 1, lastMaintenance = Long.MIN_VALUE;
    private long groundCells, structureCells;

    private static final class Group {
        final long id;
        long parent, ground, structure, until;

        Group(long id) {
            this.id = this.parent = id;
        }
    }

    private static final Factory<FireOutbreaks> FACTORY =
            new Factory<>(FireOutbreaks::new, FireOutbreaks::load);

    public static FireOutbreaks get(ServerLevel level) {
        return LegacyNames.saved(level, FACTORY, "_outbreaks");
    }

    private Group root(long id) {
        Group first = groups.get(id), node = first;
        while (node != null && node.parent != node.id) node = groups.get(node.parent);
        if (node != null && first != null) first.parent = node.id;
        return node;
    }

    public long live(long id, long now) {
        var group = root(id);
        return group != null && group.until > now ? group.id : 0;
    }

    public long merge(long a, long b, long now) {
        a = live(a, now);
        b = live(b, now);
        if (a == 0 || a == b) return b;
        if (b == 0) return a;
        var keep = root(Math.min(a, b));
        var joined = root(Math.max(a, b));
        joined.parent = keep.id;
        keep.ground += joined.ground;
        keep.structure += joined.structure;
        keep.until = Math.max(keep.until, joined.until);
        setDirty();
        return keep.id;
    }

    public long claim(Fuel fuel, long owner, long now, long until, int limit) {
        tick(now);
        var group = root(live(owner, now));
        long used = group == null ? 0 : fuel == Fuel.GROUND ? group.ground : group.structure;
        long total = fuel == Fuel.GROUND ? groundCells : structureCells;
        int ceiling =
                fuel == Fuel.GROUND
                        ? FireSettings.MAX_SUPPORTED_SAMPLES
                        : FireSettings.MAX_STRUCTURE_CELLS;
        if (used >= limit || total >= ceiling || group == null && groups.size() >= MAX_GROUPS)
            return 0;
        if (group == null) {
            group = new Group(nextId++);
            groups.put(group.id, group);
        }
        if (fuel == Fuel.GROUND) {
            group.ground++;
            groundCells++;
        } else {
            group.structure++;
            structureCells++;
        }
        group.until = Math.max(group.until, until);
        setDirty();
        return group.id;
    }

    /** Legacy active records retain their cost; historical counters never reserve new capacity. */
    public long adopt(Fuel fuel, long owner, long now, long until) {
        var group = root(live(owner, now));
        if (group == null) {
            group = new Group(nextId++);
            groups.put(group.id, group);
        }
        if (fuel == Fuel.GROUND) {
            group.ground++;
            groundCells++;
        } else {
            group.structure++;
            structureCells++;
        }
        group.until = Math.max(group.until, until);
        setDirty();
        return group.id;
    }

    /** Keep a paused chunk's live outbreak allowance until its combustion resumes. */
    public void retain(long owner, long until) {
        var group = root(owner);
        if (group != null && until > group.until) {
            group.until = until;
            setDirty();
        }
    }

    public void tick(long now) {
        if (lastMaintenance != Long.MIN_VALUE
                && now >= lastMaintenance
                && now - lastMaintenance < MAINTENANCE_INTERVAL) return;
        lastMaintenance = now;
        var iterator = groups.values().iterator();
        boolean removed = false;
        while (iterator.hasNext()) {
            var group = iterator.next();
            if (group.parent == group.id && group.until <= now) {
                groundCells -= group.ground;
                structureCells -= group.structure;
                iterator.remove();
                removed = true;
            }
        }
        groups.values().removeIf(group -> root(group.id) == null);
        if (removed) setDirty();
    }

    public void clear() {
        groups.clear();
        groundCells = structureCells = 0;
        setDirty();
    }

    public String status() {
        long roots = groups.values().stream().filter(g -> g.parent == g.id).count();
        return "outbreaks[active="
                + roots
                + ", ground="
                + groundCells
                + ", structures="
                + structureCells
                + "]";
    }

    static FireOutbreaks load(CompoundTag tag, HolderLookup.Provider registries) {
        var result = new FireOutbreaks();
        for (var entry : tag.getList("Groups", Tag.TAG_COMPOUND)) {
            var value = (CompoundTag) entry;
            long id = value.getLong("Id"), parent = value.getLong("Parent");
            if (id <= 0
                    || id == Long.MAX_VALUE
                    || parent <= 0
                    || parent > id
                    || result.groups.containsKey(id)) continue;
            var group = new Group(id);
            group.parent = parent;
            group.ground = Math.max(0, value.getLong("Ground"));
            group.structure = Math.max(0, value.getLong("Structure"));
            group.until = value.getLong("Until");
            result.groups.put(id, group);
            result.nextId = Math.max(result.nextId, id + 1);
            if (parent == id) {
                result.groundCells += group.ground;
                result.structureCells += group.structure;
            }
        }
        result.nextId = Math.max(result.nextId, Math.max(1, tag.getLong("NextId")));
        return result;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        var list = new ListTag();
        for (var group : groups.values()) {
            var entry = new CompoundTag();
            entry.putLong("Id", group.id);
            entry.putLong("Parent", group.parent);
            entry.putLong("Ground", group.ground);
            entry.putLong("Structure", group.structure);
            entry.putLong("Until", group.until);
            list.add(entry);
        }
        tag.put("Groups", list);
        tag.putLong("NextId", nextId);
        return tag;
    }
}
