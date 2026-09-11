package me.advait.contender.util;

import org.bukkit.configuration.file.YamlConfiguration;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class YamlStorage {
    private YamlStorage() { }
    public static void save(YamlConfiguration yaml, File file) {
        Path target = file.toPath();
        try {
            Files.createDirectories(target.toAbsolutePath().getParent());
            Path temporary = Files.createTempFile(target.toAbsolutePath().getParent(), file.getName(), ".tmp");
            try {
                Files.writeString(temporary, yaml.saveToString());
                try {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException ignored) {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException failure) {
            throw new UncheckedIOException("Could not save " + file.getName(), failure);
        }
    }
}
