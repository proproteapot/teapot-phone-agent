package com.teapot.phoneagent;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/** Fail closed rather than truncate existing files on copy or archive extraction. */
final class FileSafety {
    private FileSafety() { }

    static OutputStream openNew(File file) throws IOException {
        return Files.newOutputStream(file.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    static void safeCopyFile(File src, File dst, boolean overwrite) throws IOException {
        if (Files.isSymbolicLink(src.toPath()) || Files.isSymbolicLink(dst.toPath())) {
            throw new IOException("安全限制：禁止复制或覆盖符号链接");
        }
        if (src.getCanonicalFile().equals(dst.getCanonicalFile())) throw new IOException("源文件和目标文件相同");
        File parent = dst.getParentFile();
        if (parent == null) throw new IOException("目标路径缺少父目录");
        File tmp = File.createTempFile(".phoneagent-copy-", ".tmp", parent);
        try {
            try (InputStream in = Files.newInputStream(src.toPath()); OutputStream out = Files.newOutputStream(tmp.toPath())) {
                byte[] buffer = new byte[65536];
                int n;
                while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            }
            if (overwrite) {
                Files.move(tmp.toPath(), dst.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } else {
                // ATOMIC_MOVE may replace an existing file even without REPLACE_EXISTING.
                Files.move(tmp.toPath(), dst.toPath());
            }
        } finally { if (tmp.exists()) tmp.delete(); }
    }
}
