package me.advait.contender.arena;

import me.advait.contender.map.ArenaMap;
import me.advait.contender.map.BlockBounds;
import me.advait.contender.util.YamlStorage;
import org.bukkit.World;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** A single-use record of copies flushed to disk during an orderly shutdown. */
final class ArenaReadyCache {
    private static final int VERSION = 2;
    record Slot(String mapId, int number) { }
    record Entry(Slot slot, String fingerprint) { }
    private final Path folder;
    private final Path file;
    private Map<Slot, String> saved = Map.of();

    ArenaReadyCache(Path folder) {
        this.folder = folder;
        this.file = folder.resolve("arena-ready.yml");
    }

    /** Delete before trusting anything: a subsequent crash must never reuse this record. */
    void consume() throws IOException {
        saved = Map.of();
        if (!Files.exists(file)) return;
        String contents;
        try { contents = Files.readString(file); }
        finally { Files.deleteIfExists(file); }
        YamlConfiguration yaml = new YamlConfiguration();
        try { yaml.loadFromString(contents); }
        catch (InvalidConfigurationException failure) { throw new IOException("Invalid arena-ready.yml", failure); }
        if (yaml.getInt("version") != VERSION) return;
        Map<Slot, String> entries = new LinkedHashMap<>();
        for (Map<?, ?> row : yaml.getMapList("copies")) {
            if (!(row.get("map") instanceof String id) || !(row.get("slot") instanceof Number slot)
                    || !(row.get("fingerprint") instanceof String fingerprint)) continue;
            entries.put(new Slot(id, slot.intValue()), fingerprint);
        }
        saved = Map.copyOf(entries);
    }

    void invalidate() throws IOException {
        saved = Map.of();
        Files.deleteIfExists(file);
    }

    void forget() { saved = Map.of(); }
    void forget(String mapId) {
        if (saved.keySet().stream().noneMatch(slot -> slot.mapId().equals(mapId))) return;
        Map<Slot, String> remaining = new HashMap<>(saved);
        remaining.keySet().removeIf(slot -> slot.mapId().equals(mapId));
        saved = Map.copyOf(remaining);
    }

    boolean reusable(ArenaMap template, ArenaInstance instance, World world, int spacing) throws IOException {
        String expected = saved.get(new Slot(template.getId(), instance.slot()));
        if (expected == null || !expected.equals(entry(template, instance, world, spacing).fingerprint())) return false;
        return hasChunks(world.getWorldFolder().toPath(), instance.map().getBounds());
    }

    Entry entry(ArenaMap template, ArenaInstance instance, World world, int spacing) throws IOException {
        String name = template.getSchematic();
        if (name == null || !name.matches("[a-zA-Z0-9_-]+\\.schem")) throw new IOException("No saved schematic for " + template.getId());
        Path schematic = folder.resolve("maps").resolve(name);
        var attributes = Files.readAttributes(schematic, java.nio.file.attribute.BasicFileAttributes.class);
        if (!attributes.isRegularFile()) throw new IOException("Missing schematic for " + template.getId());
        String identity = VERSION + "\n" + world.getUID() + "\n" + world.getName() + "\n"
                + world.getWorldFolder().toPath().toAbsolutePath().normalize() + "\n" + spacing + "\n"
                + world.getMinHeight() + "\n" + world.getMaxHeight() + "\n"
                + template.getId() + "\n" + template.getWorldName() + "\n" + template.getBounds() + "\n"
                + instance.slot() + "\n" + instance.map().getBounds() + "\n" + instance.cell() + "\n"
                + name + "\n" + attributes.size() + "\n" + attributes.lastModifiedTime();
        try {
            String fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(identity.getBytes(StandardCharsets.UTF_8)));
            return new Entry(new Slot(template.getId(), instance.slot()), fingerprint);
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    /** Caller must flush the world successfully before publishing these entries. */
    void write(List<Entry> entries) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("version", VERSION);
        yaml.set("copies", entries.stream().map(entry -> Map.of("map", entry.slot().mapId(),
                "slot", entry.slot().number(), "fingerprint", entry.fingerprint())).toList());
        YamlStorage.save(yaml, file.toFile());
    }

    /** Check region headers without loading or generating arena chunks on the server thread. */
    static boolean hasChunks(Path worldFolder, BlockBounds bounds) throws IOException {
        Map<Path, ByteBuffer> headers = new HashMap<>();
        Map<Path, Long> sizes = new HashMap<>();
        for (int x = Math.floorDiv(bounds.minX(), 16); x <= Math.floorDiv(bounds.maxX(), 16); x++) {
            for (int z = Math.floorDiv(bounds.minZ(), 16); z <= Math.floorDiv(bounds.maxZ(), 16); z++) {
                Path region = worldFolder.resolve("region").resolve("r." + Math.floorDiv(x, 32) + "." + Math.floorDiv(z, 32) + ".mca");
                ByteBuffer header = headers.get(region);
                if (header == null) {
                    if (!Files.isRegularFile(region)) return false;
                    try (FileChannel channel = FileChannel.open(region, StandardOpenOption.READ)) {
                        if (channel.size() < 8192) return false;
                        header = ByteBuffer.allocate(4096);
                        while (header.hasRemaining()) if (channel.read(header) < 0) return false;
                        sizes.put(region, channel.size());
                    }
                    headers.put(region, header);
                }
                int location = header.getInt(4 * (Math.floorMod(x, 32) + 32 * Math.floorMod(z, 32)));
                int offset = location >>> 8;
                int sectors = location & 255;
                if (offset < 2 || sectors == 0 || ((long) offset + sectors) * 4096 > sizes.get(region)) return false;
            }
        }
        return true;
    }
}
