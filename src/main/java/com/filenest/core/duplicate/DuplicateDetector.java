package com.filenest.core.duplicate;

import com.filenest.model.DuplicateGroup;
import com.filenest.model.FileMeta;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Finds byte-for-byte identical files by content hash (SHA-256).
 *
 * <p>Deliberately <b>not</b> AI: exact duplicate detection is a solved, deterministic
 * problem and a hash is more reliable than any model. The AI layer is reserved for the
 * genuinely fuzzy case ("similar but not identical"). Use the right tool, don't abuse AI.
 */
public final class DuplicateDetector {

    private static final int BUFFER = 1 << 16; // 64 KiB

    /**
     * Groups the given files by content. Only groups with 2+ members are returned;
     * within each group the first file (in input order) is the {@code keeper}.
     */
    public List<DuplicateGroup> detect(List<FileMeta> files) {
        // Cheap pre-filter: only files sharing a size can possibly be identical,
        // so we avoid hashing files that are alone at their size.
        Map<Long, List<FileMeta>> bySize = new LinkedHashMap<>();
        for (FileMeta f : files) {
            bySize.computeIfAbsent(f.size(), k -> new ArrayList<>()).add(f);
        }

        Map<String, List<FileMeta>> byHash = new LinkedHashMap<>();
        for (List<FileMeta> sameSize : bySize.values()) {
            if (sameSize.size() < 2) {
                continue; // unique size => cannot have a duplicate
            }
            for (FileMeta f : sameSize) {
                try {
                    String hash = sha256(f);
                    byHash.computeIfAbsent(hash, k -> new ArrayList<>()).add(f);
                } catch (IOException | NoSuchAlgorithmException e) {
                    System.err.println("[Duplicate] cannot hash " + f.path() + ": " + e.getMessage());
                }
            }
        }

        List<DuplicateGroup> groups = new ArrayList<>();
        for (Map.Entry<String, List<FileMeta>> e : byHash.entrySet()) {
            if (e.getValue().size() >= 2) {
                groups.add(new DuplicateGroup(e.getKey(), e.getValue()));
            }
        }
        return groups;
    }

    private String sha256(FileMeta file) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(file.path());
             DigestInputStream din = new DigestInputStream(in, digest)) {
            byte[] buf = new byte[BUFFER];
            while (din.read(buf) != -1) {
                // reading feeds the digest
            }
        }
        byte[] hash = digest.digest();
        StringBuilder sb = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
