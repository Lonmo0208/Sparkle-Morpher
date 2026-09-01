package com.micaftic.exspm_hserverysm_model.server;

import com.micaftic.exspm_hserverysm_model.YsmUploadMod;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Writes uploaded model files into the official YSM custom model folder
 * ({@code config/yes_steve_model/custom}). The folder layout mirrors what the
 * official YSM server scan expects:
 * <ul>
 *   <li>{@code .ysm} files are stored directly as {@code <modelId>.ysm}.</li>
 *   <li>{@code .zip} model packs are extracted under {@code <modelId>/} (the
 *       pack is expected to contain a {@code ysm.json} folder-model).</li>
 *   <li>{@code .bbmodel} / unknown formats are rejected: the official YSM
 *       server cannot consume them from the custom folder.</li>
 * </ul>
 */
public final class YsmModelStore {

    public static final String YSM_MOD_ID = "yes_steve_model";

    private static volatile Path customRoot;

    private YsmModelStore() {
    }

    public static Path customRoot() {
        Path root = customRoot;
        if (root == null) {
            root = Path.of("config", YSM_MOD_ID, "custom").toAbsolutePath().normalize();
            customRoot = root;
        }
        return root;
    }

    /**
     * Normalizes a model id so it cannot escape the custom folder. Keeps only
     * letters, digits and {@code . - _} characters.
     */
    public static String sanitizeModelId(String raw) {
        if (raw == null) {
            return null;
        }
        String cleaned = raw.replace('\\', '/');
        StringBuilder sb = new StringBuilder(cleaned.length());
        for (char c : cleaned.toCharArray()) {
            if (Character.isLetterOrDigit(c) || c == '.' || c == '-' || c == '_') {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        String result = sb.toString().trim();
        return result.isEmpty() || ".".equals(result) || "..".equals(result) ? null : result;
    }

    /**
     * Stores an uploaded file. {@code fileKind} is the lowercase extension
     * without the dot (e.g. {@code ysm}, {@code zip}, {@code bbmodel}).
     */
    public static Result store(String fileKind, byte[] data, String modelId) {
        if (data == null || data.length == 0) {
            return Result.failure("Empty file");
        }
        String safeId = sanitizeModelId(modelId);
        if (safeId == null) {
            return Result.failure("Invalid model id");
        }
        try {
            if ("ysm".equals(fileKind)) {
                return writeFile(safeId + ".ysm", data);
            }
            if ("zip".equals(fileKind)) {
                return extractZip(safeId, data);
            }
            return Result.failure("Unsupported format for YSM server: ." + fileKind);
        } catch (IOException e) {
            YsmUploadMod.LOGGER.error("Failed to store uploaded model '{}'", safeId, e);
            return Result.failure(e.getMessage() == null ? "Storage failed" : e.getMessage());
        }
    }

    private static Result writeFile(String fileName, byte[] data) throws IOException {
        Path target = safeTarget(fileName);
        Files.createDirectories(target.getParent());
        Path temp = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".tmp");
        Files.write(temp, data);
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return Result.success(target);
    }

    private static Result extractZip(String modelId, byte[] data) throws IOException {
        Path targetDir = safeTarget(modelId);
        Files.createDirectories(targetDir);

        int entryCount = 0;
        long totalUncompressed = 0L;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(data))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entryCount++;
                if (entryCount > YsmUploadConfig.maxZipEntries()) {
                    return Result.failure("Zip contains too many entries");
                }
                if (entry.isDirectory()) {
                    continue;
                }
                totalUncompressed += entry.getSize() < 0 ? 0 : entry.getSize();
                if (totalUncompressed > YsmUploadConfig.maxZipUncompressedBytes()) {
                    return Result.failure("Zip too large after extraction");
                }
                Path out = safeResolve(targetDir, entry.getName());
                if (out == null) {
                    return Result.failure("Zip contains an unsafe path");
                }
                Files.createDirectories(out.getParent());
                Files.copy(zip, out, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        return Result.success(targetDir);
    }

    private static Path safeTarget(String child) throws IOException {
        Path root = customRoot();
        Files.createDirectories(root);
        Path resolved = root.resolve(child).normalize();
        if (!resolved.startsWith(root)) {
            throw new IOException("Path escapes the custom folder: " + child);
        }
        return resolved;
    }

    private static Path safeResolve(Path base, String entryName) {
        Path resolved = base.resolve(entryName.replace('\\', '/')).normalize();
        if (!resolved.startsWith(base.toAbsolutePath().normalize())) {
            return null;
        }
        return resolved;
    }

    /** Outcome of a store attempt. */
    public record Result(boolean success, String message, Path path) {
        static Result success(Path path) {
            return new Result(true, "", path);
        }

        static Result failure(String message) {
            return new Result(false, message, null);
        }
    }
}
