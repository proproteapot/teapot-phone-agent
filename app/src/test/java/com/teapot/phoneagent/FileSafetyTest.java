package com.teapot.phoneagent;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import static org.junit.Assert.*;

public final class FileSafetyTest {
    @Rule public TemporaryFolder dir = new TemporaryFolder();
    private File file(String name, String value) throws Exception {
        File f = dir.newFile(name);
        Files.write(f.toPath(), value.getBytes(StandardCharsets.UTF_8));
        return f;
    }
    private String text(File f) throws Exception { return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8); }

    @Test public void selfCopyNeverTruncates() throws Exception {
        File original = file("original.txt", "preserve");
        try { FileSafety.safeCopyFile(original, original, true); fail("expected rejection"); }
        catch (java.io.IOException expected) { assertEquals("preserve", text(original)); }
    }
    @Test public void noOverwritePreservesDestination() throws Exception {
        File source = file("source.txt", "new");
        File target = file("target.txt", "old");
        try { FileSafety.safeCopyFile(source, target, false); fail("expected rejection"); }
        catch (java.io.IOException expected) { assertEquals("old", text(target)); assertEquals("new", text(source)); }
    }
    @Test public void archiveEntryDoesNotOverwrite() throws Exception {
        File target = file("existing.txt", "keep");
        try { FileSafety.openNew(target).close(); fail("expected rejection"); }
        catch (java.io.IOException expected) { assertEquals("keep", text(target)); }
    }
    @Test public void copyCreatesNewFile() throws Exception {
        File source = file("src.txt", "hello");
        File target = new File(dir.getRoot(), "new.txt");
        FileSafety.safeCopyFile(source, target, false);
        assertEquals("hello", text(target));
    }
    @Test public void overwriteDoesNotLoseExistingDataIfAtomicMoveFails() throws Exception {
        File source = file("new.txt", "new content");
        File target = file("old.txt", "old content");
        try {
            FileSafety.safeCopyFile(source, target, true);
            assertEquals("new content", text(target));
        } catch (java.io.IOException unsupported) {
            assertEquals("old content", text(target));
        }
        assertEquals("new content", text(source));
    }
}
