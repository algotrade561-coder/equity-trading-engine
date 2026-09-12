package com.equity.store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;

/**
 * Archives to a directory on this machine. The default, and what a machine without S3 gets.
 *
 * <p>Copies to a temporary name in the destination directory and renames into place, so a reader
 * never sees a partial file and a crash mid-copy leaves a {@code .part} to overwrite, not a
 * plausible-looking archive with half a session in it.</p>
 */
public final class LocalDirectoryCandleArchive implements CandleArchive {

    private final Path root;

    public LocalDirectoryCandleArchive(Path root) {
        this.root = root;
    }

    @Override
    public String store(LocalDate tradingDate, Path file, long rows) throws IOException {
        Path dir = root.resolve(String.valueOf(tradingDate.getYear()));
        Files.createDirectories(dir);
        Path target = dir.resolve(CandleArchiver.fileName(tradingDate));
        Path part = dir.resolve(CandleArchiver.fileName(tradingDate) + ".part");
        Files.copy(file, part, StandardCopyOption.REPLACE_EXISTING);
        if (Files.size(part) != Files.size(file)) {
            throw new IOException("copy of " + file + " is " + Files.size(part) + " bytes, expected " + Files.size(file));
        }
        Files.move(part, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return target.toAbsolutePath().toString();
    }

    @Override
    public String describe() {
        return "directory " + root.toAbsolutePath();
    }
}
