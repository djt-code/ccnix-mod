package ccnix.gateway;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Every gateway block in the world, by its ID: who placed it and where, kept with the world (data/ccnix_gateways.dat
 * in the overworld), so a player's blocks can be counted wherever they are, loaded or not.
 */
public final class GatewayRegistry extends SavedData {
    private static final String NAME = "ccnix_gateways";

    /** A block: owner null if no player placed it (or it's older than IDs). */
    public record Entry(int id, UUID owner, String ownerName, String dimension, BlockPos pos) {
        public String where() {
            return dimension + " " + pos.getX() + " " + pos.getY() + " " + pos.getZ();
        }
    }

    private int next = 1;
    private final Map<Integer, Entry> entries = new TreeMap<>();

    public static GatewayRegistry get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
            new SavedData.Factory<>(GatewayRegistry::new, GatewayRegistry::load, null), NAME);
    }

    /** A new block's ID. */
    public synchronized int add(Level level, BlockPos pos) {
        int id = next++;
        entries.put(id, new Entry(id, null, "", level.dimension().location().toString(), pos.immutable()));
        setDirty();
        return id;
    }

    /** A block that has an ID: where it is now, and its owner (kept if the registry lost it). */
    public synchronized void put(int id, UUID owner, String ownerName, Level level, BlockPos pos) {
        var e = new Entry(id, owner, ownerName == null ? "" : ownerName, level.dimension().location().toString(), pos.immutable());
        if (!e.equals(entries.get(id))) {
            entries.put(id, e);
            next = Math.max(next, id + 1);
            setDirty();
        }
    }

    public synchronized boolean remove(int id) {
        boolean had = entries.remove(id) != null;
        if (had) setDirty();
        return had;
    }

    public synchronized int count(UUID owner) {
        int n = 0;
        for (var e : entries.values()) if (owner.equals(e.owner())) n++;
        return n;
    }

    public synchronized Entry get(int id) {
        return entries.get(id);
    }

    public synchronized List<Entry> all() {
        return new ArrayList<>(entries.values());
    }

    @Override
    public synchronized CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("next", next);
        var list = new ListTag();
        for (var e : entries.values()) {
            var t = new CompoundTag();
            t.putInt("id", e.id());
            if (e.owner() != null) t.putUUID("owner", e.owner());
            t.putString("ownerName", e.ownerName());
            t.putString("dimension", e.dimension());
            t.putInt("x", e.pos().getX());
            t.putInt("y", e.pos().getY());
            t.putInt("z", e.pos().getZ());
            list.add(t);
        }
        tag.put("gateways", list);
        return tag;
    }

    private static GatewayRegistry load(CompoundTag tag, HolderLookup.Provider registries) {
        var r = new GatewayRegistry();
        r.next = Math.max(1, tag.getInt("next"));
        for (var x : tag.getList("gateways", Tag.TAG_COMPOUND)) {
            var t = (CompoundTag) x;
            int id = t.getInt("id");
            r.entries.put(id, new Entry(id, t.hasUUID("owner") ? t.getUUID("owner") : null, t.getString("ownerName"),
                t.getString("dimension"), new BlockPos(t.getInt("x"), t.getInt("y"), t.getInt("z"))));
            r.next = Math.max(r.next, id + 1);
        }
        return r;
    }
}
