package com.merbancapital.backend.service;

import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

/**
 * Remote-only stub FileService.
 *
 * The application has been migrated to rely exclusively on the remote OCR API. The legacy
 * implementation that created and scanned local folders (incoming-scan, fully_indexed, etc.)
 * has been removed to avoid accidental dependence on a local filesystem layout.
 *
 * This service now acts only as a minimal fallback (e.g. during tests when remote OCR is not
 * configured) by writing to the system temporary directory. Listing and loading are therefore
 * best-effort and should not be relied upon in production when remote OCR is enabled.
 */
@Service
public class FileService {
    private static final Logger log = LoggerFactory.getLogger(FileService.class);

    private final Path tempRoot;

    public FileService() throws IOException {
        this.tempRoot = Paths.get(System.getProperty("java.io.tmpdir"), "merbanhub-uploads");
        Files.createDirectories(tempRoot);
        log.info("[FileService] Initialized remote-only stub using temp dir: {}", tempRoot.toAbsolutePath());
    }

    /**
     * Store a file under a temp directory (only used if remote forwarding is not active).
     */
    public String store(MultipartFile file) throws IOException {
        String original = file.getOriginalFilename();
        if (original == null || original.trim().isEmpty()) original = "upload-" + System.currentTimeMillis();
        String filename = StringUtils.cleanPath(original);
        Path target = tempRoot.resolve(filename);
        Files.copy(file.getInputStream(), target); // overwrite not critical here
        log.info("[FileService] Stored file '{}' at {} (temp fallback)", filename, target.toAbsolutePath());
        return target.toAbsolutePath().toString();
    }

    /**
     * Return an empty stream in remote mode. (Local indexed listing removed.)
     */
    public Stream<Path> listIndexedFiles() throws IOException {
        if (!Files.exists(tempRoot)) return Stream.empty();
        try { return Files.list(tempRoot).filter(Files::isRegularFile); } catch (IOException e) { return Stream.empty(); }
    }

    /**
     * Load a file from the temp fallback location (not used in remote OCR mode).
     */
    public Resource loadAsResource(String filename) throws IOException {
        Path p = tempRoot.resolve(filename);
        if (Files.exists(p) && Files.isRegularFile(p)) {
            return new UrlResource(p.toUri());
        }
        throw new FileNotFoundException("[FileService] File not found in temp fallback: " + filename);
    }

    public boolean isFileInIndexed(String filePath) {
        if (filePath == null || filePath.isBlank()) return false;
        Path p = Paths.get(filePath);
        return Files.exists(p) && Files.isRegularFile(p);
    }
}
