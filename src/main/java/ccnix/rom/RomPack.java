package ccnix.rom;

import com.mojang.logging.LogUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.AbstractPackResources;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackSelectionConfig;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.IoSupplier;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.event.AddPackFindersEvent;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * The data pack that puts the ROM where CC: Tweaked builds every computer's ROM from (data/computercraft/lua/rom):
 * the repo at lua/rom/ccnix, its .manifest beside it, and lua/rom/autorun/ccnix.lua. The ROM is read again each
 * time data packs load, so /reload picks up a git pull in the server's checkout.
 */
public final class RomPack extends AbstractPackResources {
    private static final Logger LOG = LogUtils.getLogger();
    private static final String NS = "computercraft";
    private static final byte[] PACK_META = ("{\"pack\":{\"description\":\"ccnix in every computer's ROM\","
        + "\"pack_format\":48}}").getBytes(StandardCharsets.UTF_8);

    /** The ROM the last load found, for /ccnix. */
    private static volatile Rom current;

    private Map<String, byte[]> entries;

    private RomPack(PackLocationInfo location) {
        super(location);
    }

    public static Rom current() {
        return current;
    }

    /** The pack, from the mod's AddPackFindersEvent. */
    public static void addTo(AddPackFindersEvent event) {
        if (event.getPackType() != PackType.SERVER_DATA) return;
        var info = new PackLocationInfo("ccnix_rom", Component.literal("ccnix ROM"), PackSource.BUILT_IN, Optional.empty());
        var resources = new Pack.ResourcesSupplier() {
            @Override
            public PackResources openPrimary(PackLocationInfo location) {
                return new RomPack(location);
            }

            @Override
            public PackResources openFull(PackLocationInfo location, Pack.Metadata metadata) {
                return new RomPack(location);
            }
        };
        var pack = Pack.readMetaAndCreate(info, resources, PackType.SERVER_DATA,
            new PackSelectionConfig(true, Pack.Position.TOP, false));
        if (pack != null) event.addRepositorySource(packs -> packs.accept(pack));
    }

    /** The files, by their path under data/computercraft/: read when first asked for. */
    private synchronized Map<String, byte[]> entries() {
        if (entries != null) return entries;
        var map = new TreeMap<String, byte[]>();
        try {
            var rom = Rom.load(FMLPaths.GAMEDIR.get(), RomPack.class.getClassLoader(), w -> LOG.warn("ccnix ROM: {}", w));
            rom.files.forEach((path, data) -> map.put("lua/rom/ccnix/" + path, data));
            map.put("lua/rom/ccnix/.manifest", rom.manifest());
            map.put("lua/rom/autorun/ccnix.lua", Rom.AUTORUN.getBytes(StandardCharsets.UTF_8));
            if (current == null || !current.commit.equals(rom.commit) || !current.source.equals(rom.source)) {
                LOG.info("ccnix ROM: {} {} from {}, {} files", rom.version, rom.commit.substring(0, Math.min(7, rom.commit.length())),
                    rom.source, rom.files.size());
                if (!rom.leftOut.isEmpty()) LOG.info("ccnix ROM: left out (no name for them in a data pack): {}", rom.leftOut);
            }
            current = rom;
        } catch (IOException | RuntimeException e) {
            LOG.error("ccnix ROM: none: {}", e.toString());
        }
        entries = map;
        return map;
    }

    @Nullable
    @Override
    public IoSupplier<InputStream> getRootResource(String... elements) {
        if (elements.length == 1 && elements[0].equals(PACK_META_NAME)) return () -> new ByteArrayInputStream(PACK_META);
        return null;
    }

    private static final String PACK_META_NAME = "pack.mcmeta";

    @Nullable
    @Override
    public IoSupplier<InputStream> getResource(PackType type, ResourceLocation location) {
        if (type != PackType.SERVER_DATA || !location.getNamespace().equals(NS)) return null;
        var data = entries().get(location.getPath());
        return data == null ? null : () -> new ByteArrayInputStream(data);
    }

    @Override
    public void listResources(PackType type, String namespace, String path, ResourceOutput output) {
        if (type != PackType.SERVER_DATA || !namespace.equals(NS)) return;
        var prefix = path.endsWith("/") ? path : path + "/";
        entries().forEach((p, data) -> {
            if (p.startsWith(prefix)) output.accept(ResourceLocation.fromNamespaceAndPath(NS, p), () -> new ByteArrayInputStream(data));
        });
    }

    @Override
    public Set<String> getNamespaces(PackType type) {
        return type == PackType.SERVER_DATA ? Set.of(NS) : Set.of();
    }

    @Override
    public void close() {
    }
}
