package com.interview_ai.util;

import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.*;

public class FileStore {

    public static String saveToLocal(MultipartFile file, String baseDir, Long kbId, String saveName) throws IOException {
        Path dir = Paths.get(baseDir, String.valueOf(kbId));
        Files.createDirectories(dir);

        Path target = dir.resolve(saveName);
        Files.copy(file.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);

        return target.toAbsolutePath().toString();
    }

    public static String ext(String filename) {
        if (filename == null) return null;
        int idx = filename.lastIndexOf('.');
        return (idx >= 0 && idx < filename.length() - 1) ? filename.substring(idx + 1).toLowerCase() : "";
    }
}
