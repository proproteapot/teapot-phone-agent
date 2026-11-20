package com.teapot.phoneagent;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Environment;
import android.provider.AlarmClock;
import android.provider.CalendarContract;
import android.provider.DocumentsContract;
import android.webkit.MimeTypeMap;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** 系统工具集：日历、SAF文档、完整共享存储与相册整理、压缩解压、本机打开文件及闹钟请求。 */
final class SystemTools {
    private static final Set<String> PROTECTED_TOP_DIRS = new HashSet<>(Arrays.asList(
            "", "android", "dcim", "pictures", "movies", "music", "download", "documents"
    ));
    private static boolean isProtectedTopDir(String rel) {
        return rel == null || PROTECTED_TOP_DIRS.contains(rel.toLowerCase(Locale.ROOT));
    }
    private final Context context;

    SystemTools(Context context) { this.context = context.getApplicationContext(); }

    JSONArray calendars() throws JSONException {
        require(Manifest.permission.READ_CALENDAR);
        JSONArray result = new JSONArray();
        String[] cols = {CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                CalendarContract.Calendars.ACCOUNT_NAME, CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL};
        try (Cursor c = context.getContentResolver().query(CalendarContract.Calendars.CONTENT_URI, cols, null, null, null)) {
            if (c == null) throw new IllegalStateException("日历提供程序不可用");
            while (c.moveToNext()) result.put(new JSONObject().put("id", c.getLong(0)).put("name", c.getString(1))
                    .put("account", c.getString(2)).put("writable", c.getInt(3) >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR));
        }
        return result;
    }

    long createEvent(long calendarId, String title, long startMillis, long endMillis, String timezone) {
        require(Manifest.permission.WRITE_CALENDAR);
        if (title == null || title.trim().isEmpty() || endMillis <= startMillis) throw new IllegalArgumentException("日程标题或时间无效");
        if (timezone == null || timezone.isEmpty()) timezone = TimeZone.getDefault().getID();
        if (!Arrays.asList(TimeZone.getAvailableIDs()).contains(timezone)) throw new IllegalArgumentException("无效的时区");
        ContentValues values = new ContentValues();
        values.put(CalendarContract.Events.CALENDAR_ID, calendarId);
        values.put(CalendarContract.Events.TITLE, title);
        values.put(CalendarContract.Events.DTSTART, startMillis);
        values.put(CalendarContract.Events.DTEND, endMillis);
        values.put(CalendarContract.Events.EVENT_TIMEZONE, timezone);
        Uri created = context.getContentResolver().insert(CalendarContract.Events.CONTENT_URI, values);
        if (created == null) throw new IllegalStateException("系统日历未返回事件");
        return Long.parseLong(created.getLastPathSegment());
    }

    JSONArray listEvents(long startMillis, long endMillis) throws JSONException {
        require(Manifest.permission.READ_CALENDAR);
        if (endMillis <= startMillis || endMillis - startMillis > 90L * 24 * 60 * 60 * 1000) throw new IllegalArgumentException("时间范围无效或超过 90 天");
        Uri.Builder b = CalendarContract.Instances.CONTENT_URI.buildUpon();
        android.content.ContentUris.appendId(b, startMillis);
        android.content.ContentUris.appendId(b, endMillis);
        String[] cols = {CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.TITLE,
                CalendarContract.Instances.BEGIN, CalendarContract.Instances.END, CalendarContract.Instances.CALENDAR_ID};
        JSONArray result = new JSONArray();
        try (Cursor c = context.getContentResolver().query(b.build(), cols, null, null, CalendarContract.Instances.BEGIN + " ASC")) {
            if (c == null) throw new IllegalStateException("日历提供程序不可用");
            while (c.moveToNext() && result.length() < 200) result.put(new JSONObject().put("event_id", c.getLong(0))
                    .put("title", c.getString(1)).put("start_ms", c.getLong(2)).put("end_ms", c.getLong(3)).put("calendar_id", c.getLong(4)));
        }
        return result;
    }

    JSONArray listRootFiles(Uri tree) throws JSONException {
        ContentResolver resolver = context.getContentResolver();
        requireTree(tree);
        String rootId = DocumentsContract.getTreeDocumentId(tree);
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, rootId);
        String[] cols = {DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE};
        JSONArray result = new JSONArray();
        try (Cursor c = resolver.query(children, cols, null, null, null)) {
            if (c == null) throw new IllegalStateException("目录不可访问");
            while (c.moveToNext() && result.length() < 200) result.put(new JSONObject().put("id", c.getString(0))
                    .put("name", c.getString(1)).put("mimeType", c.getString(2)).put("size", c.isNull(3) ? JSONObject.NULL : c.getLong(3)));
        }
        return result;
    }

    String readTextFile(Uri tree, String documentId) throws IOException {
        requireTree(tree);
        ContentResolver resolver = context.getContentResolver();
        Uri root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
        Uri document = DocumentsContract.buildDocumentUriUsingTree(tree, documentId);
        if (!DocumentsContract.isChildDocument(resolver, root, document)) throw new SecurityException("文件不在授权目录中");
        try (InputStream input = resolver.openInputStream(document)) {
            if (input == null) throw new IOException("无法读取文件");
            return decodeUtf8Stream(input, 1024 * 1024);
        }
    }

    private static String decodeUtf8Stream(InputStream input, int maxBytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = input.read(buffer)) != -1) {
            if (output.size() + n > maxBytes) throw new IOException("文件超过大小限制");
            output.write(buffer, 0, n);
        }
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
        try { return decoder.decode(ByteBuffer.wrap(output.toByteArray())).toString(); }
        catch (CharacterCodingException e) { throw new IOException("文件不是有效的 UTF-8 文本"); }
    }

    private void requireTree(Uri tree) {
        for (android.content.UriPermission p : context.getContentResolver().getPersistedUriPermissions()) {
            if (p.getUri().equals(tree) && p.isReadPermission()) return;
        }
        throw new SecurityException("目录未获授权");
    }

    Intent alarmIntent(int hour, int minute, String label) {
        if (hour < 0 || hour > 23 || minute < 0 || minute > 59) throw new IllegalArgumentException("时间无效");
        Intent intent = new Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, hour)
                .putExtra(AlarmClock.EXTRA_MINUTES, minute)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        if (label != null && !label.isEmpty()) intent.putExtra(AlarmClock.EXTRA_MESSAGE, label);
        return intent;
    }

    private void require(String permission) {
        if (context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) throw new SecurityException("缺少权限：" + permission);
    }

    // ==================== 共享存储文件系统、相册整理、压缩解压、打开文件 ====================

    private File storageRoot() throws IOException {
        if (!Environment.isExternalStorageManager()) {
            throw new SecurityException("尚未授予【所有文件访问权限】(MANAGE_EXTERNAL_STORAGE)，请在 Teapot Phone-Agent 设置面板点击授权");
        }
        return Environment.getExternalStorageDirectory().getCanonicalFile();
    }

    File resolvePath(String rawPath) throws IOException {
        File root = storageRoot();
        String p = rawPath == null ? "" : rawPath.trim().replace('\\', '/');
        if (p.startsWith("/storage/emulated/0")) p = p.substring("/storage/emulated/0".length());
        else if (p.startsWith("/sdcard")) p = p.substring("/sdcard".length());
        while (p.startsWith("/")) p = p.substring(1);
        File target = p.isEmpty() ? root : new File(root, p).getCanonicalFile();
        String rootPath = root.getPath();
        String targetPath = target.getPath();
        if (!targetPath.equals(rootPath) && !targetPath.startsWith(rootPath + File.separator)) {
            throw new SecurityException("路径越界：仅允许访问内部共享存储 (/storage/emulated/0)");
        }
        String rel = relativePath(root, target);
        if ("Android".equalsIgnoreCase(rel) || rel.toLowerCase(Locale.ROOT).startsWith("android/")) {
            throw new SecurityException("安全限制：禁止访问系统 Android/data 与 Android/obb 目录");
        }
        return target;
    }

    private static String relativePath(File root, File target) {
        String r = root.getPath();
        String t = target.getPath();
        if (t.equals(r)) return "";
        if (t.startsWith(r + File.separator)) return t.substring(r.length() + 1).replace(File.separatorChar, '/');
        return t.replace(File.separatorChar, '/');
    }

    static String guessMimeType(String name) {
        int dot = name == null ? -1 : name.lastIndexOf('.');
        String ext = (dot >= 0 && dot < name.length() - 1) ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
        if (!ext.isEmpty()) {
            String fromMap = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
            if (fromMap != null) return fromMap;
            switch (ext) {
                case "jpg": case "jpeg": return "image/jpeg";
                case "png": return "image/png";
                case "webp": return "image/webp";
                case "gif": return "image/gif";
                case "heic": case "heif": return "image/heic";
                case "mp4": return "video/mp4";
                case "mkv": return "video/x-matroska";
                case "mov": return "video/quicktime";
                case "mp3": return "audio/mpeg";
                case "m4a": return "audio/mp4";
                case "wav": return "audio/wav";
                case "flac": return "audio/flac";
                case "zip": return "application/zip";
                case "7z": return "application/x-7z-compressed";
                case "rar": return "application/vnd.rar";
                case "pdf": return "application/pdf";
                case "txt": case "md": case "log": case "csv": case "json": case "xml": case "yaml": case "yml":
                    return "text/plain";
                case "apk": return "application/vnd.android.package-archive";
            }
        }
        return "application/octet-stream";
    }

    static String classifyFile(String name, boolean isDir) {
        if (isDir) return "dir";
        String mime = guessMimeType(name);
        if (mime.startsWith("image/")) return "image";
        if (mime.startsWith("video/")) return "video";
        if (mime.startsWith("audio/")) return "audio";
        String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".zip") || lower.endsWith(".7z") || lower.endsWith(".rar") || lower.endsWith(".tar") || lower.endsWith(".gz")) {
            return "archive";
        }
        if (mime.startsWith("text/") || mime.equals("application/pdf") || lower.endsWith(".doc") || lower.endsWith(".docx")
                || lower.endsWith(".xls") || lower.endsWith(".xlsx") || lower.endsWith(".ppt") || lower.endsWith(".pptx")) {
            return "document";
        }
        return "other";
    }

    private static boolean matchesFilter(File f, String filterType) {
        if (filterType == null || filterType.isEmpty() || "all".equalsIgnoreCase(filterType)) return true;
        String kind = classifyFile(f.getName(), f.isDirectory());
        if ("media".equalsIgnoreCase(filterType)) return "image".equals(kind) || "video".equals(kind);
        return filterType.equalsIgnoreCase(kind);
    }

    private static JSONObject fileToJson(File root, File f) throws JSONException {
        boolean isDir = f.isDirectory();
        return new JSONObject()
                .put("name", f.getName())
                .put("path", relativePath(root, f))
                .put("type", classifyFile(f.getName(), isDir))
                .put("is_dir", isDir)
                .put("size", isDir ? 0L : f.length())
                .put("mtime_ms", f.lastModified())
                .put("mime", isDir ? "inode/directory" : guessMimeType(f.getName()));
    }

    JSONObject fsListDir(String path, String filterType, String sortBy, int limit) throws Exception {
        File root = storageRoot();
        File dir = resolvePath(path);
        if (!dir.exists() || !dir.isDirectory()) throw new IllegalArgumentException("目录不存在：" + path);
        File[] children = dir.listFiles();
        if (children == null) throw new IOException("无法列出目录内容");
        List<File> filtered = new ArrayList<>();
        long totalBytes = 0;
        int dirCount = 0, fileCount = 0;
        for (File child : children) {
            if ("Android".equalsIgnoreCase(child.getName()) && dir.equals(root)) continue;
            if (child.isDirectory()) dirCount++;
            else { fileCount++; totalBytes += child.length(); }
            if (matchesFilter(child, filterType)) filtered.add(child);
        }
        sortFiles(filtered, sortBy);
        int cap = Math.min(Math.max(1, limit <= 0 ? 100 : limit), 500);
        JSONArray items = new JSONArray();
        for (int i = 0; i < Math.min(filtered.size(), cap); i++) {
            items.put(fileToJson(root, filtered.get(i)));
        }
        return new JSONObject()
                .put("path", relativePath(root, dir))
                .put("total_dirs_in_folder", dirCount)
                .put("total_files_in_folder", fileCount)
                .put("folder_files_bytes", totalBytes)
                .put("matching_count", filtered.size())
                .put("returned_count", items.length())
                .put("items", items);
    }

    private static void sortFiles(List<File> files, String sortBy) {
        final String mode = sortBy == null ? "mtime_desc" : sortBy.toLowerCase(Locale.ROOT);
        Collections.sort(files, new Comparator<File>() {
            @Override public int compare(File a, File b) {
                if ("size_desc".equals(mode)) return Long.compare(b.isDirectory() ? -1 : b.length(), a.isDirectory() ? -1 : a.length());
                if ("mtime_asc".equals(mode)) return Long.compare(a.lastModified(), b.lastModified());
                if ("name_asc".equals(mode)) return a.getName().compareToIgnoreCase(b.getName());
                return Long.compare(b.lastModified(), a.lastModified());
            }
        });
    }

    JSONObject fsSearch(String basePath, String query, String filterType, long minSizeBytes, int maxDepth, int limit) throws Exception {
        File root = storageRoot();
        File start = resolvePath(basePath);
        if (!start.exists() || !start.isDirectory()) throw new IllegalArgumentException("搜索起始目录不存在：" + basePath);
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        int depthLimit = Math.min(Math.max(1, maxDepth <= 0 ? 4 : maxDepth), 8);
        int cap = Math.min(Math.max(1, limit <= 0 ? 100 : limit), 500);
        List<File> matches = new ArrayList<>();
        searchWalk(root, start, q, filterType, minSizeBytes, 0, depthLimit, matches);
        sortFiles(matches, "mtime_desc");
        JSONArray items = new JSONArray();
        for (int i = 0; i < Math.min(matches.size(), cap); i++) {
            items.put(fileToJson(root, matches.get(i)));
        }
        return new JSONObject()
                .put("base_path", relativePath(root, start))
                .put("total_matches", matches.size())
                .put("returned_count", items.length())
                .put("items", items);
    }

    private void searchWalk(File root, File dir, String q, String filterType, long minSize, int depth, int maxDepth, List<File> out) {
        if (depth > maxDepth || out.size() >= 2000) return;
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File f : children) {
            String name = f.getName();
            if (dir.equals(root) && "Android".equalsIgnoreCase(name)) continue;
            if (name.equals(".thumbnails") || name.equals(".PhoneAgentTrash") || name.equals(".cache")) continue;
            boolean ok = true;
            if (!q.isEmpty() && !name.toLowerCase(Locale.ROOT).contains(q)) ok = false;
            if (ok && minSize > 0 && (f.isDirectory() || f.length() < minSize)) ok = false;
            if (ok && !matchesFilter(f, filterType)) ok = false;
            if (ok) out.add(f);
            if (f.isDirectory() && depth + 1 <= maxDepth) {
                searchWalk(root, f, q, filterType, minSize, depth + 1, maxDepth, out);
            }
        }
    }

    JSONObject albumSummary() throws Exception {
        File root = storageRoot();
        String[] scanRoots = {"DCIM", "Pictures", "Movies", "Download", "Tencent/MicroMsg/WeiXin"};
        JSONArray albums = new JSONArray();
        Set<String> visited = new HashSet<>();
        for (String rel : scanRoots) {
            File d = new File(root, rel);
            if (d.exists() && d.isDirectory()) collectAlbums(root, d, 0, 3, visited, albums);
        }
        return new JSONObject()
                .put("storage_total_bytes", root.getTotalSpace())
                .put("storage_free_bytes", root.getUsableSpace())
                .put("album_count", albums.length())
                .put("albums", albums);
    }

    private void collectAlbums(File root, File dir, int depth, int maxDepth, Set<String> visited, JSONArray out) throws Exception {
        String canonical = dir.getCanonicalPath();
        if (!visited.add(canonical)) return;
        String name = dir.getName();
        if (name.startsWith(".") || "thumbnails".equalsIgnoreCase(name)) return;
        File[] children = dir.listFiles();
        if (children == null) return;
        int imgCount = 0, vidCount = 0, otherCount = 0;
        long bytes = 0, newest = 0, oldest = Long.MAX_VALUE;
        List<File> subdirs = new ArrayList<>();
        for (File f : children) {
            if (f.isDirectory()) {
                if (depth < maxDepth) subdirs.add(f);
            } else {
                String kind = classifyFile(f.getName(), false);
                if ("image".equals(kind)) imgCount++;
                else if ("video".equals(kind)) vidCount++;
                else otherCount++;
                if ("image".equals(kind) || "video".equals(kind)) {
                    bytes += f.length();
                    long m = f.lastModified();
                    if (m > newest) newest = m;
                    if (m > 0 && m < oldest) oldest = m;
                }
            }
        }
        if (imgCount + vidCount > 0) {
            out.put(new JSONObject()
                    .put("album_path", relativePath(root, dir))
                    .put("name", dir.getName())
                    .put("image_count", imgCount)
                    .put("video_count", vidCount)
                    .put("other_file_count", otherCount)
                    .put("media_bytes", bytes)
                    .put("newest_mtime_ms", newest)
                    .put("oldest_mtime_ms", oldest == Long.MAX_VALUE ? 0 : oldest));
        }
        for (File sub : subdirs) collectAlbums(root, sub, depth + 1, maxDepth, visited, out);
    }

    JSONObject fsMkdir(String path) throws Exception {
        File root = storageRoot();
        File dir = resolvePath(path);
        if (dir.exists()) {
            if (!dir.isDirectory()) throw new IllegalStateException("同名文件已存在：" + path);
            return new JSONObject().put("path", relativePath(root, dir)).put("created", false).put("exists", true);
        }
        if (!dir.mkdirs()) throw new IOException("创建目录失败：" + path);
        return new JSONObject().put("path", relativePath(root, dir)).put("created", true).put("exists", true);
    }

    JSONObject fsMove(JSONArray sources, String destinationDir, String destinationPath, boolean overwrite) throws Exception {
        File root = storageRoot();
        if (sources == null || sources.length() == 0) throw new IllegalArgumentException("未提供源文件列表");
        boolean singleRename = destinationPath != null && !destinationPath.trim().isEmpty();
        if (singleRename && sources.length() != 1) {
            throw new IllegalArgumentException("指定精确目标文件路径 destination_path 时只能移动单个源文件");
        }
        File destDir = null;
        if (!singleRename) {
            if (destinationDir == null || destinationDir.trim().isEmpty()) throw new IllegalArgumentException("必须提供 destination_dir 或 destination_path");
            destDir = resolvePath(destinationDir);
            if (!destDir.exists() && !destDir.mkdirs()) throw new IOException("无法创建目标目录：" + destinationDir);
            if (!destDir.isDirectory()) throw new IllegalArgumentException("目标路径不是目录：" + destinationDir);
        }
        JSONArray moved = new JSONArray();
        JSONArray skipped = new JSONArray();
        List<String> scanPaths = new ArrayList<>();
        for (int i = 0; i < sources.length(); i++) {
            String srcRel = sources.getString(i);
            File src = resolvePath(srcRel);
            if (!src.exists()) {
                skipped.put(new JSONObject().put("source", srcRel).put("reason", "源文件不存在"));
                continue;
            }
            String relCheck = relativePath(root, src);
            if (isProtectedTopDir(relCheck)) {
                skipped.put(new JSONObject().put("source", srcRel).put("reason", "禁止移动系统顶层目录"));
                continue;
            }
            File target = singleRename ? resolvePath(destinationPath) : resolvePath(relativePath(root, destDir) + "/" + src.getName());
            if (target.getParentFile() != null && !target.getParentFile().exists()) target.getParentFile().mkdirs();
            if (src.equals(target)) {
                skipped.put(new JSONObject().put("source", srcRel).put("reason", "源路径与目标路径相同"));
                continue;
            }
            if (target.exists()) {
                if (!overwrite) {
                    skipped.put(new JSONObject().put("source", srcRel).put("reason", "目标已存在（未开启 overwrite）"));
                    continue;
                }
                if (target.isDirectory()) throw new IllegalStateException("目标为已存在的目录，无法覆盖：" + relativePath(root, target));
                // Never remove an existing destination before the replacement is ready.
                if (src.isDirectory()) throw new IllegalStateException("不支持覆盖已有文件的目录移动");
            }
            String oldAbs = src.getAbsolutePath();
            boolean ok = false;
            if (target.exists()) {
                FileSafety.safeCopyFile(src, target, true);
                ok = src.delete();
                if (!ok) throw new IOException("目标已复制，但源文件未能删除；请检查两份文件");
            } else {
                ok = src.renameTo(target);
            }
            if (!ok && src.isFile()) {
                FileSafety.safeCopyFile(src, target, false);
                ok = src.delete();
            }
            if (ok) {
                moved.put(new JSONObject().put("from", relCheck).put("to", relativePath(root, target)));
                scanPaths.add(oldAbs);
                scanPaths.add(target.getAbsolutePath());
            } else {
                skipped.put(new JSONObject().put("source", srcRel).put("reason", "移动失败"));
            }
        }
        triggerMediaScan(scanPaths);
        return new JSONObject()
                .put("moved_count", moved.length())
                .put("skipped_count", skipped.length())
                .put("moved", moved)
                .put("skipped", skipped);
    }

    JSONObject fsCopy(String sourcePath, String destPath, boolean overwrite) throws Exception {
        File root = storageRoot();
        File src = resolvePath(sourcePath);
        if (!src.exists() || !src.isFile()) throw new IllegalArgumentException("源文件不存在或不是普通文件：" + sourcePath);
        File dst = resolvePath(destPath);
        if (dst.exists() && dst.isDirectory()) {
            dst = resolvePath(relativePath(root, dst) + "/" + src.getName());
        }
        if (src.equals(dst)) throw new IllegalArgumentException("源文件和目标文件相同");
        if (dst.exists() && !overwrite) throw new IllegalStateException("目标文件已存在（未开启 overwrite）：" + relativePath(root, dst));
        if (dst.getParentFile() != null && !dst.getParentFile().exists()) dst.getParentFile().mkdirs();
        FileSafety.safeCopyFile(src, dst, overwrite);
        triggerMediaScan(Collections.singletonList(dst.getAbsolutePath()));
        return new JSONObject()
                .put("from", relativePath(root, src))
                .put("to", relativePath(root, dst))
                .put("size", dst.length());
    }

    JSONObject fsDelete(JSONArray paths, boolean permanent) throws Exception {
        File root = storageRoot();
        if (paths == null || paths.length() == 0) throw new IllegalArgumentException("未提供要删除的路径");
        File trashDir = new File(root, "Download/.PhoneAgentTrash");
        if (!permanent && !trashDir.exists()) trashDir.mkdirs();
        JSONArray processed = new JSONArray();
        JSONArray failed = new JSONArray();
        List<String> scanPaths = new ArrayList<>();
        long ts = System.currentTimeMillis();
        for (int i = 0; i < paths.length(); i++) {
            String raw = paths.getString(i);
            File target = resolvePath(raw);
            if (!target.exists()) {
                failed.put(new JSONObject().put("path", raw).put("reason", "文件或目录不存在"));
                continue;
            }
            String rel = relativePath(root, target);
            if (isProtectedTopDir(rel)) {
                failed.put(new JSONObject().put("path", rel).put("reason", "安全保护：禁止删除系统顶层目录"));
                continue;
            }
            String oldAbs = target.getAbsolutePath();
            if (!permanent) {
                File trashed = new File(trashDir, ts + "_" + i + "_" + target.getName());
                if (target.renameTo(trashed)) {
                    processed.put(new JSONObject().put("path", rel).put("mode", "trashed").put("trash_path", relativePath(root, trashed)));
                    scanPaths.add(oldAbs);
                } else {
                    failed.put(new JSONObject().put("path", rel).put("reason", "移入回收站失败"));
                }
            } else {
                if (target.isDirectory() && target.listFiles() != null && target.listFiles().length > 0) {
                    failed.put(new JSONObject().put("path", rel).put("reason", "永久删除仅支持空目录或单个文件；非空目录请先移入回收站（permanent=false）"));
                    continue;
                }
                if (target.delete()) {
                    processed.put(new JSONObject().put("path", rel).put("mode", "deleted"));
                    scanPaths.add(oldAbs);
                } else {
                    failed.put(new JSONObject().put("path", rel).put("reason", "删除失败"));
                }
            }
        }
        triggerMediaScan(scanPaths);
        return new JSONObject()
                .put("permanent", permanent)
                .put("processed_count", processed.length())
                .put("failed_count", failed.length())
                .put("processed", processed)
                .put("failed", failed);
    }

    String fsReadTextPath(String path) throws Exception {
        File f = resolvePath(path);
        if (!f.exists() || !f.isFile()) throw new IllegalArgumentException("文件不存在：" + path);
        try (InputStream in = new FileInputStream(f)) {
            return decodeUtf8Stream(in, 1024 * 1024);
        }
    }

    JSONObject fsWriteTextPath(String path, String content, boolean append) throws Exception {
        File root = storageRoot();
        if (content == null) content = "";
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 2 * 1024 * 1024) throw new IllegalArgumentException("写入内容超过 2 MiB 限制");
        File f = resolvePath(path);
        if (f.equals(root)) throw new IllegalArgumentException("不能直接写入存储根目录");
        if (f.exists() && f.isDirectory()) throw new IllegalArgumentException("目标路径是目录，不能写入文本");
        if (java.nio.file.Files.isSymbolicLink(f.toPath())) throw new SecurityException("安全限制：禁止写入符号链接");
        File parent = f.getParentFile();
        if (parent == null) throw new IllegalArgumentException("目标路径缺少父目录");
        if (!parent.exists()) parent.mkdirs();
        if (append) {
            long existingSize = f.exists() ? f.length() : 0L;
            if (existingSize + bytes.length > 2L * 1024 * 1024) {
                throw new IllegalArgumentException("追加写入后文件总大小超过 2 MiB 安全上限");
            }
            try (FileOutputStream out = new FileOutputStream(f, true)) {
                out.write(bytes);
            }
        } else {
            File tmp = File.createTempFile(".phoneagent-write-", ".tmp", parent);
            try {
                try (FileOutputStream out = new FileOutputStream(tmp, false)) {
                    out.write(bytes);
                }
                try {
                    java.nio.file.Files.move(tmp.toPath(), f.toPath(),
                            java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException atomicFallback) {
                    java.nio.file.Files.move(tmp.toPath(), f.toPath(),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                if (tmp.exists()) tmp.delete();
            }
        }
        triggerMediaScan(Collections.singletonList(f.getAbsolutePath()));
        return new JSONObject()
                .put("path", relativePath(root, f))
                .put("bytes_written", bytes.length)
                .put("total_size", f.length());
    }

    JSONObject fsZip(JSONArray sourcePaths, String zipPath, int compressionLevel) throws Exception {
        File root = storageRoot();
        if (sourcePaths == null || sourcePaths.length() == 0) throw new IllegalArgumentException("未提供要压缩的源路径");
        if (zipPath == null || zipPath.trim().isEmpty()) throw new IllegalArgumentException("zip_path 不能为空");
        String normalizedZipPath = zipPath.trim();
        if (!normalizedZipPath.toLowerCase(Locale.ROOT).endsWith(".zip")) {
            normalizedZipPath = normalizedZipPath + ".zip";
        }
        File outZip = resolvePath(normalizedZipPath);
        if (outZip.equals(root) || outZip.getParentFile() == null) {
            throw new IllegalArgumentException("无效的输出压缩包路径：" + zipPath);
        }
        if (!outZip.getParentFile().exists()) outZip.getParentFile().mkdirs();
        int level = (compressionLevel < 0 || compressionLevel > 9) ? 1 : compressionLevel;
        long started = System.currentTimeMillis();
        int[] count = {0};
        String outCanonical = outZip.getCanonicalPath();
        try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(FileSafety.openNew(outZip), 65536))) {
            zos.setLevel(level);
            for (int i = 0; i < sourcePaths.length(); i++) {
                File src = resolvePath(sourcePaths.getString(i));
                if (!src.exists()) throw new IllegalArgumentException("压缩源不存在：" + sourcePaths.getString(i));
                addToZip(zos, src, src.getName(), outCanonical, count);
            }
        }
        triggerMediaScan(Collections.singletonList(outZip.getAbsolutePath()));
        return new JSONObject()
                .put("zip_path", relativePath(root, outZip))
                .put("entries_count", count[0])
                .put("zip_size_bytes", outZip.length())
                .put("elapsed_ms", System.currentTimeMillis() - started);
    }

    private void addToZip(ZipOutputStream zos, File file, String entryName, String skipCanonical, int[] count) throws IOException {
        if (count[0] >= 5000) throw new IOException("单次压缩文件数超过 5000 上限");
        if (java.nio.file.Files.isSymbolicLink(file.toPath())) return;
        if (file.getCanonicalPath().equals(skipCanonical)) return;
        String normalizedName = entryName.replace('\\', '/');
        if (file.isDirectory()) {
            if (!normalizedName.endsWith("/")) normalizedName += "/";
            zos.putNextEntry(new ZipEntry(normalizedName));
            zos.closeEntry();
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    addToZip(zos, child, normalizedName + child.getName(), skipCanonical, count);
                }
            }
        } else {
            ZipEntry entry = new ZipEntry(normalizedName);
            entry.setTime(file.lastModified());
            zos.putNextEntry(entry);
            try (InputStream in = new BufferedInputStream(new FileInputStream(file), 65536)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) != -1) zos.write(buf, 0, n);
            }
            zos.closeEntry();
            count[0]++;
        }
    }

    JSONObject fsUnzip(String zipPath, String destinationDir, boolean listOnly) throws Exception {
        File root = storageRoot();
        File zipFile = resolvePath(zipPath);
        if (!zipFile.exists() || !zipFile.isFile()) throw new IllegalArgumentException("压缩包不存在：" + zipPath);
        File destRoot = null;
        if (!listOnly) {
            if (destinationDir == null || destinationDir.trim().isEmpty()) {
                String baseName = zipFile.getName();
                int dot = baseName.lastIndexOf('.');
                if (dot > 0) baseName = baseName.substring(0, dot);
                String parentRel = relativePath(root, zipFile.getParentFile());
                String defaultDest = parentRel.isEmpty() ? baseName : (parentRel + "/" + baseName);
                destRoot = resolvePath(defaultDest);
            } else {
                destRoot = resolvePath(destinationDir);
            }
            if (!destRoot.exists() && !destRoot.mkdirs()) throw new IOException("无法创建解压目标目录");
        }
        JSONArray entries = new JSONArray();
        List<String> extractedScan = new ArrayList<>();
        long totalExtractedBytes = 0;
        int extractedFiles = 0;
        int totalEntries = 0;
        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(zipFile), 65536))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                totalEntries++;
                if (totalEntries > 5000) {
                    throw new IOException("压缩包条目数超过 5000 安全上限");
                }
                if (entries.length() < 500) {
                    entries.put(new JSONObject()
                            .put("name", entry.getName())
                            .put("is_dir", entry.isDirectory())
                            .put("size", entry.getSize()));
                }
                if (!listOnly) {
                    File outFile = new File(destRoot, entry.getName()).getCanonicalFile();
                    if (!outFile.getPath().startsWith(destRoot.getPath() + File.separator) && !outFile.equals(destRoot)) {
                        throw new SecurityException("拦截到不安全的压缩包路径穿越条目：" + entry.getName());
                    }
                    if (entry.isDirectory()) {
                        outFile.mkdirs();
                    } else {
                        if (outFile.getParentFile() != null && !outFile.getParentFile().exists()) {
                            outFile.getParentFile().mkdirs();
                        }
                        try (BufferedOutputStream bos = new BufferedOutputStream(FileSafety.openNew(outFile), 65536)) {
                            byte[] buf = new byte[65536];
                            int n;
                            while ((n = zis.read(buf)) != -1) {
                                totalExtractedBytes += n;
                                if (totalExtractedBytes > 512L * 1024 * 1024) {
                                    throw new IOException("解压总大小超过 512 MiB 安全上限");
                                }
                                bos.write(buf, 0, n);
                            }
                        }
                        if (entry.getTime() > 0) outFile.setLastModified(entry.getTime());
                        extractedFiles++;
                        extractedScan.add(outFile.getAbsolutePath());
                    }
                }
                zis.closeEntry();
            }
        }
        if (!extractedScan.isEmpty()) triggerMediaScan(extractedScan);
        JSONObject res = new JSONObject()
                .put("zip_path", relativePath(root, zipFile))
                .put("list_only", listOnly)
                .put("entries_preview", entries);
        if (!listOnly && destRoot != null) {
            res.put("destination_dir", relativePath(root, destRoot))
               .put("extracted_files", extractedFiles)
               .put("extracted_bytes", totalExtractedBytes);
        }
        return res;
    }

    JSONObject openFileOnPhone(String path) throws Exception {
        File root = storageRoot();
        File file = resolvePath(path);
        if (!file.exists() || !file.isFile()) throw new IllegalArgumentException("要打开的文件不存在：" + path);
        String lowerName = file.getName().toLowerCase(Locale.ROOT);
        String mime = guessMimeType(file.getName());
        if (lowerName.endsWith(".apk") || lowerName.endsWith(".xapk") || lowerName.endsWith(".apks")
                || "application/vnd.android.package-archive".equalsIgnoreCase(mime)) {
            throw new SecurityException("安全限制：禁止远程调用系统打开安装包文件（.apk）");
        }
        Uri uri = LocalFileProvider.uriForFile(file);
        Intent view = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        Intent chooser = Intent.createChooser(view, "打开 " + file.getName())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        boolean launchedDirectly = false;
        Context launcher = ScreenControlService.instance != null ? ScreenControlService.instance : context;
        try {
            launcher.startActivity(view);
            launchedDirectly = true;
        } catch (Exception e1) {
            try {
                launcher.startActivity(chooser);
                launchedDirectly = true;
            } catch (Exception ignored) { }
        }
        JSONObject out = new JSONObject()
                .put("path", relativePath(root, file))
                .put("mime", mime)
                .put("launched_directly", launchedDirectly);
        if (launchedDirectly && ScreenControlService.instance != null) {
            try {
                Thread.sleep(800);
                out.put("screen", ScreenControlService.authorized().captureSnapshot());
            } catch (Exception ignored) { }
        }
        return out;
    }

    /** 闭环定闹钟：优先通过 Shizuku (adb_shell) 执行原生 am start SET_ALARM，再轮询 AlarmManager + dumpsys alarm 核验 */
    JSONObject setAlarmAndVerify(int hour, int minute, String label) throws Exception {
        Intent alarm = alarmIntent(hour, minute, label);
        android.app.AlarmManager am = context.getSystemService(android.app.AlarmManager.class);
        long beforeNext = getNextAlarmTriggerMs(am);

        long expectedTriggerMs = computeExpectedAlarmMs(hour, minute);
        String expectedFull = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
                .format(new java.util.Date(expectedTriggerMs));
        String expectedHhMm = String.format(Locale.ROOT, "%02d:%02d", hour, minute);

        String preferredPkg = resolvePreferredAlarmPackage(alarm);
        boolean shizukuAuth = ShizukuBridge.isAuthorized();
        String channel = "app_intent";
        boolean dispatchOk = false;
        String amOutput = "";
        String dispatchError = "";

        if (shizukuAuth) {
            try {
                JSONObject shellRes = ShizukuBridge.setAlarmViaShell(hour, minute, label, preferredPkg);
                channel = "shizuku_am_start";
                dispatchOk = shellRes.optBoolean("ok", false);
                amOutput = shellRes.optString("am_output", "");
            } catch (Exception e) {
                dispatchError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
        }

        if (!dispatchOk) {
            try {
                if (!preferredPkg.isEmpty()) alarm.setPackage(preferredPkg);
                Context launcher = ScreenControlService.instance != null ? ScreenControlService.instance : context;
                launcher.startActivity(alarm);
                if (!"shizuku_am_start".equals(channel)) {
                    channel = "app_intent";
                } else {
                    channel = "shizuku_fallback_app_intent";
                }
                dispatchOk = true;
            } catch (Exception e) {
                String fallbackErr = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                dispatchError = dispatchError.isEmpty() ? fallbackErr : (dispatchError + "；回退失败：" + fallbackErr);
            }
        }

        long afterNext = beforeNext;
        boolean matchedTargetTime = false;
        boolean nextAlarmUpdated = false;
        String verifiedBy = "none";

        if (dispatchOk) {
            long waitBudget = shizukuAuth ? 1500L : 2500L;
            long deadline = System.currentTimeMillis() + waitBudget;
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(250);
                afterNext = getNextAlarmTriggerMs(am);
                if (afterNext > 0 && Math.abs(afterNext - expectedTriggerMs) <= 90_000L) {
                    matchedTargetTime = true;
                    nextAlarmUpdated = (afterNext != beforeNext);
                    verifiedBy = "alarm_manager_exact";
                    break;
                }
                if (afterNext > 0 && afterNext != beforeNext) {
                    nextAlarmUpdated = true;
                    verifiedBy = "alarm_manager_changed";
                    // HyperOS 往往会把 AlarmManager 最近一项登记为提前 1 小时的 ALARM_ARRIVING，留给后续 dumpsys 精确核验真实响铃时间
                    if (shizukuAuth && Math.abs(afterNext - (expectedTriggerMs - 3600_000L)) <= 90_000L) {
                        break;
                    }
                }
            }
        }

        JSONObject dumpCheck = null;
        if (dispatchOk && !matchedTargetTime && ShizukuBridge.isAuthorized()) {
            dumpCheck = ShizukuBridge.queryAlarmClockDump(expectedFull, expectedHhMm);
            if (dumpCheck.optBoolean("matched_exact_datetime", false)) {
                matchedTargetTime = true;
                verifiedBy = "dumpsys_alarm_exact";
            } else if (dumpCheck.optBoolean("matched_hhmm", false)) {
                matchedTargetTime = true;
                verifiedBy = "dumpsys_alarm_hhmm";
            }
        }

        boolean verified = dispatchOk && (matchedTargetTime || (nextAlarmUpdated && afterNext > 0));
        String formattedNext = afterNext > 0
                ? new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(new java.util.Date(afterNext))
                : "无";

        String feedback;
        if (verified) {
            feedback = "✅ 已通过 " + channel + " 设置 " + expectedHhMm + " 闹钟并核验生效（核验来源：" + verifiedBy
                    + "）；系统当前最近响铃时间：" + formattedNext;
        } else if (!dispatchOk) {
            feedback = "❌ 闹钟指令下发失败（" + channel + "）：" + (dispatchError.isEmpty() ? amOutput : dispatchError)
                    + "。请先检查 Shizuku 状态，或改走离屏副屏打开时钟设置。";
        } else if (beforeNext > 0 && afterNext == beforeNext) {
            feedback = "⚠️ 已通过 " + channel + " 下发 " + expectedHhMm + " 闹钟请求，但系统最近闹钟未变化（仍为 " + formattedNext
                    + "），且未在系统闹钟列表中核验到目标时间；可能被系统时钟限制或需要通过离屏副屏打开时钟核验。";
        } else {
            feedback = "⚠️ 已通过 " + channel + " 下发 " + expectedHhMm + " 闹钟请求，但系统 AlarmManager"
                    + (shizukuAuth ? " 与 dumpsys alarm" : "")
                    + " 均未检测到激活闹钟；请检查 Shizuku / 系统时钟权限，或改走离屏副屏打开时钟核验。";
        }

        JSONObject out = new JSONObject()
                .put("ok", verified)
                .put("channel", channel)
                .put("shizuku_authorized", shizukuAuth)
                .put("target_package", preferredPkg)
                .put("requested_time", expectedHhMm)
                .put("expected_time", expectedFull)
                .put("label", label == null ? "" : label)
                .put("intent_dispatched", dispatchOk)
                .put("verified", verified)
                .put("verified_by", verifiedBy)
                .put("matched_target_time", matchedTargetTime)
                .put("system_has_active_alarm", afterNext > 0)
                .put("next_alarm_trigger_ms", afterNext)
                .put("next_alarm_formatted", formattedNext)
                .put("next_alarm_updated", nextAlarmUpdated)
                .put("feedback", feedback);
        if (!amOutput.isEmpty()) out.put("am_output", amOutput);
        if (!dispatchError.isEmpty()) out.put("dispatch_error", dispatchError);
        if (dumpCheck != null) out.put("dumpsys_alarm", dumpCheck);
        return out;
    }

    private long getNextAlarmTriggerMs(android.app.AlarmManager am) {
        if (am == null) return 0L;
        android.app.AlarmManager.AlarmClockInfo info = am.getNextAlarmClock();
        return info != null ? info.getTriggerTime() : 0L;
    }

    private long computeExpectedAlarmMs(int hour, int minute) {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        long now = cal.getTimeInMillis();
        cal.set(java.util.Calendar.HOUR_OF_DAY, hour);
        cal.set(java.util.Calendar.MINUTE, minute);
        cal.set(java.util.Calendar.SECOND, 0);
        cal.set(java.util.Calendar.MILLISECOND, 0);
        if (cal.getTimeInMillis() <= now) {
            cal.add(java.util.Calendar.DAY_OF_YEAR, 1);
        }
        return cal.getTimeInMillis();
    }

    private String resolvePreferredAlarmPackage(Intent alarm) {
        try {
            PackageManager pm = context.getPackageManager();
            List<android.content.pm.ResolveInfo> handlers = pm.queryIntentActivities(alarm, 0);
            if (handlers == null || handlers.isEmpty()) return "";
            if (handlers.size() == 1) {
                return handlers.get(0).activityInfo != null ? handlers.get(0).activityInfo.packageName : "";
            }
            List<String> preferred = Arrays.asList(
                    "com.android.deskclock",
                    "com.google.android.deskclock",
                    "com.coloros.alarmclock",
                    "com.oneplus.deskclock",
                    "com.sec.android.app.clockpackage",
                    "com.android.BBKClock",
                    "com.huawei.deskclock",
                    "com.hihonor.deskclock"
            );
            for (String p : preferred) {
                for (android.content.pm.ResolveInfo ri : handlers) {
                    if (ri.activityInfo != null && p.equals(ri.activityInfo.packageName)) return p;
                }
            }
            return handlers.get(0).activityInfo != null ? handlers.get(0).activityInfo.packageName : "";
        } catch (Exception ignored) {
            return "";
        }
    }

    /** 只读查询系统当前所有闹钟（AlarmManager + Shizuku dumpsys alarm） */
    JSONObject listAlarms() throws Exception {
        android.app.AlarmManager am = context.getSystemService(android.app.AlarmManager.class);
        long nextMs = getNextAlarmTriggerMs(am);
        String nextFmt = nextMs > 0
                ? new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(new java.util.Date(nextMs))
                : "无";
        JSONObject dump = ShizukuBridge.listAlarmsViaDump();
        JSONObject out = new JSONObject()
                .put("ok", true)
                .put("system_has_active_alarm", nextMs > 0)
                .put("next_alarm_trigger_ms", nextMs)
                .put("next_alarm_formatted", nextFmt)
                .put("shizuku_authorized", ShizukuBridge.isAuthorized());
        if (dump.optBoolean("available", false)) {
            out.put("alarm_count", dump.optInt("alarm_count", 0))
                    .put("alarms", dump.optJSONArray("alarms"))
                    .put("pre_alerts", dump.optJSONArray("pre_alerts"));
        } else {
            out.put("note", "Shizuku 未授权，当前仅返回 AlarmManager 最近一项；授权 Shizuku 后可列出完整系统闹钟明细。");
        }
        return out;
    }

    /** 原生取消/关闭闹钟（优先 Shizuku am start DISMISS_ALARM + dumpsys alarm 前后对比核验） */
    JSONObject dismissAlarmAndVerify(String searchMode, String label, int hour, int minute, boolean skipUi) throws Exception {
        android.app.AlarmManager am = context.getSystemService(android.app.AlarmManager.class);
        long beforeNext = getNextAlarmTriggerMs(am);
        JSONObject beforeDump = ShizukuBridge.isAuthorized() ? ShizukuBridge.listAlarmsViaDump() : null;
        int beforeCount = beforeDump != null ? beforeDump.optInt("alarm_count", -1) : -1;

        Intent probeIntent = new Intent(AlarmClock.ACTION_DISMISS_ALARM);
        String preferredPkg = resolvePreferredAlarmPackage(probeIntent);
        boolean shizukuAuth = ShizukuBridge.isAuthorized();
        String channel = "app_intent";
        boolean dispatchOk = false;
        String amOutput = "";
        String dispatchError = "";
        String usedMode = searchMode == null ? "" : searchMode;

        if (shizukuAuth) {
            try {
                JSONObject res = ShizukuBridge.dismissAlarmViaShell(searchMode, label, hour, minute, skipUi, preferredPkg);
                channel = "shizuku_am_start";
                dispatchOk = res.optBoolean("ok", false);
                amOutput = res.optString("am_output", "");
                usedMode = res.optString("search_mode", usedMode);
            } catch (Exception e) {
                dispatchError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
        }

        if (!dispatchOk) {
            try {
                Intent dismiss = new Intent(AlarmClock.ACTION_DISMISS_ALARM)
                        .putExtra(AlarmClock.EXTRA_SKIP_UI, skipUi)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
                String normMode = (label != null && !label.trim().isEmpty() && (searchMode == null || searchMode.trim().isEmpty()))
                        ? AlarmClock.ALARM_SEARCH_MODE_LABEL
                        : (hour >= 0 && minute >= 0 && (searchMode == null || searchMode.trim().isEmpty())
                        ? AlarmClock.ALARM_SEARCH_MODE_TIME
                        : ("label".equalsIgnoreCase(searchMode) || AlarmClock.ALARM_SEARCH_MODE_LABEL.equalsIgnoreCase(searchMode)
                        ? AlarmClock.ALARM_SEARCH_MODE_LABEL
                        : ("time".equalsIgnoreCase(searchMode) || AlarmClock.ALARM_SEARCH_MODE_TIME.equalsIgnoreCase(searchMode)
                        ? AlarmClock.ALARM_SEARCH_MODE_TIME
                        : ("all".equalsIgnoreCase(searchMode) || AlarmClock.ALARM_SEARCH_MODE_ALL.equalsIgnoreCase(searchMode)
                        ? AlarmClock.ALARM_SEARCH_MODE_ALL : AlarmClock.ALARM_SEARCH_MODE_NEXT))));
                usedMode = normMode;
                dismiss.putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, normMode);
                if (label != null && !label.trim().isEmpty()) dismiss.putExtra(AlarmClock.EXTRA_MESSAGE, label.trim());
                if (hour >= 0 && hour <= 23) dismiss.putExtra(AlarmClock.EXTRA_HOUR, hour);
                if (minute >= 0 && minute <= 59) dismiss.putExtra(AlarmClock.EXTRA_MINUTES, minute);
                if (!preferredPkg.isEmpty()) dismiss.setPackage(preferredPkg);

                Context launcher = ScreenControlService.instance != null ? ScreenControlService.instance : context;
                launcher.startActivity(dismiss);
                channel = shizukuAuth ? "shizuku_fallback_app_intent" : "app_intent";
                dispatchOk = true;
            } catch (Exception e) {
                String fallbackErr = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                dispatchError = dispatchError.isEmpty() ? fallbackErr : (dispatchError + "；回退失败：" + fallbackErr);
            }
        }

        if (dispatchOk) {
            Thread.sleep(900);
        }

        long afterNext = getNextAlarmTriggerMs(am);
        JSONObject afterDump = ShizukuBridge.isAuthorized() ? ShizukuBridge.listAlarmsViaDump() : null;
        int afterCount = afterDump != null ? afterDump.optInt("alarm_count", -1) : -1;
        boolean countDropped = (beforeCount > 0 && afterCount >= 0 && afterCount < beforeCount);
        boolean nextChanged = (beforeNext > 0 && afterNext != beforeNext);
        boolean verified = dispatchOk && (countDropped || nextChanged || (beforeNext == 0 && afterNext == 0));

        String formattedAfter = afterNext > 0
                ? new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(new java.util.Date(afterNext))
                : "无";

        String feedback;
        if (!dispatchOk) {
            feedback = "❌ 取消闹钟指令下发失败（" + channel + "）：" + (dispatchError.isEmpty() ? amOutput : dispatchError);
        } else if (countDropped || nextChanged) {
            feedback = "✅ 已通过 " + channel + " 下发取消闹钟指令并核验生效，当前系统最近响铃时间：" + formattedAfter;
        } else {
            feedback = "⚠️ 已通过 " + channel + " 下发 ACTION_DISMISS_ALARM（模式：" + usedMode
                    + "），但系统活跃闹钟数未减少（部分 ROM 时钟仅允许 DISMISS_ALARM 关闭正在/即将响铃的闹钟，或需在时钟内关闭开关）。";
        }

        JSONObject out = new JSONObject()
                .put("ok", dispatchOk)
                .put("verified", verified)
                .put("channel", channel)
                .put("search_mode", usedMode)
                .put("target_package", preferredPkg)
                .put("before_alarm_count", beforeCount)
                .put("after_alarm_count", afterCount)
                .put("next_alarm_changed", nextChanged)
                .put("next_alarm_formatted", formattedAfter)
                .put("feedback", feedback);
        if (!amOutput.isEmpty()) out.put("am_output", amOutput);
        if (!dispatchError.isEmpty()) out.put("dispatch_error", dispatchError);
        if (afterDump != null && afterDump.optBoolean("available", false)) {
            out.put("remaining_alarms", afterDump.optJSONArray("alarms"));
        }
        return out;
    }

    /** 原生设置系统倒计时（优先 Shizuku am start SET_TIMER） */
    JSONObject setTimer(int seconds, String label, boolean skipUi) throws Exception {
        if (seconds < 1 || seconds > 86400) {
            throw new IllegalArgumentException("倒计时秒数必须在 1 到 86400 之间");
        }
        Intent probeIntent = new Intent(AlarmClock.ACTION_SET_TIMER);
        String preferredPkg = resolvePreferredAlarmPackage(probeIntent);
        boolean shizukuAuth = ShizukuBridge.isAuthorized();
        String channel = "app_intent";
        boolean dispatchOk = false;
        String amOutput = "";
        String dispatchError = "";

        if (shizukuAuth) {
            try {
                JSONObject res = ShizukuBridge.setTimerViaShell(seconds, label, skipUi, preferredPkg);
                channel = "shizuku_am_start";
                dispatchOk = res.optBoolean("ok", false);
                amOutput = res.optString("am_output", "");
            } catch (Exception e) {
                dispatchError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
        }

        if (!dispatchOk) {
            try {
                Intent timer = new Intent(AlarmClock.ACTION_SET_TIMER)
                        .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                        .putExtra(AlarmClock.EXTRA_SKIP_UI, skipUi)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
                if (label != null && !label.trim().isEmpty()) timer.putExtra(AlarmClock.EXTRA_MESSAGE, label.trim());
                if (!preferredPkg.isEmpty()) timer.setPackage(preferredPkg);
                Context launcher = ScreenControlService.instance != null ? ScreenControlService.instance : context;
                launcher.startActivity(timer);
                channel = shizukuAuth ? "shizuku_fallback_app_intent" : "app_intent";
                dispatchOk = true;
            } catch (Exception e) {
                String fallbackErr = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                dispatchError = dispatchError.isEmpty() ? fallbackErr : (dispatchError + "；回退失败：" + fallbackErr);
            }
        }

        JSONObject out = new JSONObject()
                .put("ok", dispatchOk)
                .put("channel", channel)
                .put("seconds", seconds)
                .put("label", label == null ? "" : label.trim())
                .put("target_package", preferredPkg)
                .put("feedback", dispatchOk
                        ? "✅ 已通过 " + channel + " 启动 " + seconds + " 秒倒计时" + (label != null && !label.trim().isEmpty() ? "（" + label.trim() + "）" : "")
                        : "❌ 启动倒计时失败：" + (dispatchError.isEmpty() ? amOutput : dispatchError));
        if (!amOutput.isEmpty()) out.put("am_output", amOutput);
        if (!dispatchError.isEmpty()) out.put("dispatch_error", dispatchError);
        return out;
    }

    /** 轻量只读设备状态（电量、亮屏/锁屏、各通道音量与响铃模式、网络与本地时间，0 切屏） */
    JSONObject deviceStatus() throws Exception {
        JSONObject out = new JSONObject().put("ok", true);

        android.content.IntentFilter ifilter = new android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent batt = context.registerReceiver(null, ifilter);
        if (batt != null) {
            int level = batt.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1);
            int scale = batt.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100);
            int status = batt.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1);
            int plugged = batt.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0);
            int tempTenths = batt.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, 0);
            int pct = (level >= 0 && scale > 0) ? Math.round(level * 100f / scale) : -1;
            boolean charging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING
                    || status == android.os.BatteryManager.BATTERY_STATUS_FULL;
            String chargeType = "none";
            if ((plugged & android.os.BatteryManager.BATTERY_PLUGGED_AC) != 0) chargeType = "ac";
            else if ((plugged & android.os.BatteryManager.BATTERY_PLUGGED_USB) != 0) chargeType = "usb";
            else if ((plugged & android.os.BatteryManager.BATTERY_PLUGGED_WIRELESS) != 0) chargeType = "wireless";
            out.put("battery_percent", pct)
                    .put("is_charging", charging)
                    .put("charge_type", chargeType)
                    .put("battery_temp_c", tempTenths / 10.0);
        }

        android.os.PowerManager pm = (android.os.PowerManager) context.getSystemService(Context.POWER_SERVICE);
        android.app.KeyguardManager km = (android.app.KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
        out.put("screen_on", pm != null && pm.isInteractive())
                .put("device_locked", km != null && km.isDeviceLocked());

        android.media.AudioManager audio = (android.media.AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audio != null) {
            int rm = audio.getRingerMode();
            String ringerStr = rm == android.media.AudioManager.RINGER_MODE_SILENT ? "silent"
                    : (rm == android.media.AudioManager.RINGER_MODE_VIBRATE ? "vibrate" : "normal");
            JSONObject vols = new JSONObject()
                    .put("music", volumeSnapshot(audio, android.media.AudioManager.STREAM_MUSIC))
                    .put("ring", volumeSnapshot(audio, android.media.AudioManager.STREAM_RING))
                    .put("alarm", volumeSnapshot(audio, android.media.AudioManager.STREAM_ALARM))
                    .put("notification", volumeSnapshot(audio, android.media.AudioManager.STREAM_NOTIFICATION));
            out.put("ringer_mode", ringerStr)
                    .put("music_active", audio.isMusicActive())
                    .put("volumes", vols);
        }

        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            android.net.Network active = cm != null ? cm.getActiveNetwork() : null;
            android.net.NetworkCapabilities caps = (cm != null && active != null) ? cm.getNetworkCapabilities(active) : null;
            boolean connected = caps != null && caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET);
            String netType = "none";
            if (caps != null) {
                if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) netType = "wifi";
                else if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)) netType = "cellular";
                else netType = "other";
            }
            out.put("network_connected", connected).put("network_type", netType);
        } catch (Exception ignored) { }

        long now = System.currentTimeMillis();
        out.put("timestamp_ms", now)
                .put("local_time", new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(new java.util.Date(now)))
                .put("timezone", java.util.TimeZone.getDefault().getID());

        return out;
    }

    private JSONObject volumeSnapshot(android.media.AudioManager audio, int streamType) throws Exception {
        int cur = audio.getStreamVolume(streamType);
        int max = Math.max(1, audio.getStreamMaxVolume(streamType));
        return new JSONObject()
                .put("current", cur)
                .put("max", max)
                .put("percent", Math.round(cur * 100f / max));
    }

    /** 原生设置音量或响铃模式（0 切屏 + 即时回读核验） */
    JSONObject setVolume(String stream, int percent, String ringerMode) throws Exception {
        android.media.AudioManager audio = (android.media.AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audio == null) throw new IllegalStateException("无法获取 AudioManager");
        boolean didSomething = false;
        String warning = "";

        if (ringerMode != null && !ringerMode.trim().isEmpty()) {
            String rm = ringerMode.trim().toLowerCase(Locale.ROOT);
            int targetMode;
            if ("normal".equals(rm)) targetMode = android.media.AudioManager.RINGER_MODE_NORMAL;
            else if ("vibrate".equals(rm)) targetMode = android.media.AudioManager.RINGER_MODE_VIBRATE;
            else if ("silent".equals(rm)) targetMode = android.media.AudioManager.RINGER_MODE_SILENT;
            else throw new IllegalArgumentException("不支持的 ringer_mode：" + ringerMode + "（可选 normal / vibrate / silent）");
            try {
                audio.setRingerMode(targetMode);
                didSomething = true;
            } catch (SecurityException se) {
                warning = "系统勿扰策略禁止应用直接切换静音模式：" + se.getMessage();
            }
        }

        int streamType = -1;
        String streamName = stream == null ? "" : stream.trim().toLowerCase(Locale.ROOT);
        if (!streamName.isEmpty() || percent >= 0) {
            if (streamName.isEmpty() || "music".equals(streamName) || "media".equals(streamName)) {
                streamType = android.media.AudioManager.STREAM_MUSIC;
                streamName = "music";
            } else if ("ring".equals(streamName)) {
                streamType = android.media.AudioManager.STREAM_RING;
            } else if ("alarm".equals(streamName)) {
                streamType = android.media.AudioManager.STREAM_ALARM;
            } else if ("notification".equals(streamName)) {
                streamType = android.media.AudioManager.STREAM_NOTIFICATION;
            } else if ("call".equals(streamName)) {
                streamType = android.media.AudioManager.STREAM_VOICE_CALL;
            } else {
                throw new IllegalArgumentException("不支持的音量通道：" + stream + "（可选 music / ring / alarm / notification / call）");
            }
            if (percent < 0 || percent > 100) {
                throw new IllegalArgumentException("percent 必须在 0 到 100 之间");
            }
            int max = audio.getStreamMaxVolume(streamType);
            int targetIdx = Math.round(max * (percent / 100f));
            audio.setStreamVolume(streamType, targetIdx, 0);
            didSomething = true;
        }

        if (!didSomething && warning.isEmpty()) {
            throw new IllegalArgumentException("必须提供 stream+percent 或 ringer_mode 至少一项");
        }

        int actualRm = audio.getRingerMode();
        String ringerStr = actualRm == android.media.AudioManager.RINGER_MODE_SILENT ? "silent"
                : (actualRm == android.media.AudioManager.RINGER_MODE_VIBRATE ? "vibrate" : "normal");
        JSONObject out = new JSONObject()
                .put("ok", didSomething)
                .put("ringer_mode", ringerStr);
        if (streamType >= 0) {
            out.put("stream", streamName)
                    .put("volume", volumeSnapshot(audio, streamType));
        }
        if (!warning.isEmpty()) out.put("warning", warning);
        out.put("feedback", didSomething
                ? "✅ 已调整系统音量/响铃模式（当前响铃模式：" + ringerStr + "）"
                : "⚠️ 未能修改响铃模式：" + warning);
        return out;
    }

    /** 原生媒体播放控制（play / pause / play_pause / next / previous / stop，0 切屏） */
    JSONObject mediaControl(String action) throws Exception {
        String key = action == null ? "" : action.trim().toLowerCase(Locale.ROOT);
        int keyCode;
        switch (key) {
            case "play": keyCode = android.view.KeyEvent.KEYCODE_MEDIA_PLAY; break;
            case "pause": keyCode = android.view.KeyEvent.KEYCODE_MEDIA_PAUSE; break;
            case "play_pause": keyCode = android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE; break;
            case "next": keyCode = android.view.KeyEvent.KEYCODE_MEDIA_NEXT; break;
            case "previous": keyCode = android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS; break;
            case "stop": keyCode = android.view.KeyEvent.KEYCODE_MEDIA_STOP; break;
            default: throw new IllegalArgumentException("不支持的媒体控制动作：" + action + "（可选 play / pause / play_pause / next / previous / stop）");
        }

        if (ShizukuBridge.isAuthorized()) {
            try {
                JSONObject shellRes = ShizukuBridge.dispatchMediaViaShell(key);
                if (shellRes.optBoolean("ok", false)) {
                    return shellRes.put("channel", "shizuku_media_session")
                            .put("feedback", "✅ 已通过系统媒体会话发送 " + key + " 指令");
                }
            } catch (Exception ignored) { }
        }

        android.media.AudioManager audio = (android.media.AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audio == null) throw new IllegalStateException("无法获取 AudioManager");
        long now = android.os.SystemClock.uptimeMillis();
        audio.dispatchMediaKeyEvent(new android.view.KeyEvent(now, now, android.view.KeyEvent.ACTION_DOWN, keyCode, 0));
        audio.dispatchMediaKeyEvent(new android.view.KeyEvent(now, now, android.view.KeyEvent.ACTION_UP, keyCode, 0));
        return new JSONObject()
                .put("ok", true)
                .put("channel", "audio_manager_key")
                .put("action", key)
                .put("feedback", "✅ 已发送媒体控制按键：" + key);
    }

    /** 列出手机上已安装的可启动应用（支持按应用名称或包名关键字过滤） */
    JSONObject listInstalledApps(String query) throws Exception {
        PackageManager pm = context.getPackageManager();
        Intent mainIntent = new Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER);
        List<android.content.pm.ResolveInfo> list = pm.queryIntentActivities(mainIntent, 0);
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        JSONArray apps = new JSONArray();
        Set<String> seenPkg = new HashSet<>();
        for (android.content.pm.ResolveInfo ri : list) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (seenPkg.contains(pkg)) continue;
            CharSequence labelCs = ri.loadLabel(pm);
            String label = labelCs != null ? labelCs.toString().trim() : pkg;
            if (!q.isEmpty() && !label.toLowerCase(Locale.ROOT).contains(q) && !pkg.toLowerCase(Locale.ROOT).contains(q)) {
                continue;
            }
            seenPkg.add(pkg);
            apps.put(new JSONObject()
                    .put("app_name", label)
                    .put("package_name", pkg)
                    .put("activity", ri.activityInfo.name));
        }
        return new JSONObject()
                .put("count", apps.length())
                .put("apps", apps);
    }

    JSONObject openApp(String queryOrPkg, int waitMs) throws Exception {
        return openApp(queryOrPkg, waitMs, android.view.Display.DEFAULT_DISPLAY, null);
    }

    /**
     * 按应用名称或包名在指定 display_id 上启动应用（支持可选 URL），
     * 虚拟屏失败时直接报错，禁止自动切回主屏。
     */
    JSONObject openApp(String queryOrPkg, int waitMs, int displayId, String url) throws Exception {
        if (queryOrPkg == null || queryOrPkg.trim().isEmpty()) {
            throw new IllegalArgumentException("必须提供要打开的应用名称或包名");
        }
        String q = queryOrPkg.trim();
        PackageManager pm = context.getPackageManager();
        Intent mainIntent = new Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER);
        List<android.content.pm.ResolveInfo> list = pm.queryIntentActivities(mainIntent, 0);

        String targetPkg = null;
        String targetLabel = null;
        String lower = q.toLowerCase(Locale.ROOT);

        for (android.content.pm.ResolveInfo ri : list) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            String label = ri.loadLabel(pm) != null ? ri.loadLabel(pm).toString().trim() : pkg;
            if (pkg.equalsIgnoreCase(q) || label.equalsIgnoreCase(q)) {
                targetPkg = pkg;
                targetLabel = label;
                break;
            }
        }
        if (targetPkg == null) {
            for (android.content.pm.ResolveInfo ri : list) {
                if (ri.activityInfo == null) continue;
                String pkg = ri.activityInfo.packageName;
                String label = ri.loadLabel(pm) != null ? ri.loadLabel(pm).toString().trim() : pkg;
                if (label.toLowerCase(Locale.ROOT).contains(lower) || pkg.toLowerCase(Locale.ROOT).contains(lower)) {
                    targetPkg = pkg;
                    targetLabel = label;
                    break;
                }
            }
        }
        if (targetPkg == null) {
            throw new IllegalArgumentException("未找到匹配「" + q + "」的已安装应用，可先调用 list_installed_apps 查询");
        }
        if (ScreenControlService.isProtectedPackage(targetPkg)) {
            throw new SecurityException("安全保护：禁止远程启动本应用、系统权限弹窗、安装器或 Shizuku（" + targetPkg + "）");
        }
        String safeUrl = null;
        if (url != null && !url.trim().isEmpty()) {
            safeUrl = ShizukuBridge.sanitizeHttpUrl(url);
        }

        int sleepMs = Math.max(300, Math.min(5000, waitMs));
        if (displayId > android.view.Display.DEFAULT_DISPLAY) {
            android.hardware.display.DisplayManager dm = (android.hardware.display.DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
            if (dm == null || dm.getDisplay(displayId) == null) {
                throw new IllegalStateException("目标虚拟屏 display_id=" + displayId + " 不存在，已中止启动（禁止回退到主屏）");
            }
            JSONObject shizukuLaunch = ShizukuBridge.startAppOnDisplay(
                    targetPkg,
                    safeUrl != null ? Intent.ACTION_VIEW : null,
                    safeUrl,
                    displayId
            );
            Thread.sleep(sleepMs);

            JSONObject result = new JSONObject()
                    .put("action", "open_app")
                    .put("display_id", displayId)
                    .put("requested", q)
                    .put("launched_app", targetLabel)
                    .put("launched_package", targetPkg)
                    .put("wait_ms", sleepMs)
                    .put("shizuku_launch", shizukuLaunch);

            boolean landed = shizukuLaunch.optBoolean("landed_on_target_display", false);
            boolean hijacked = shizukuLaunch.optBoolean("hijacked_primary_display", false);
            result.put("ok", landed && !hijacked)
                    .put("action_delivered", true)
                    .put("goal_reached", landed && !hijacked)
                    .put("verified_in_foreground", landed);

            if (hijacked) {
                result.put("error_code", "system_rejected")
                        .put("stage", "virtual_display_launch")
                        .put("feedback", "⚠️ 应用「" + targetLabel + "」拒绝留在虚拟屏或抢占了主屏 (display 0)，未独立运行在 display_id=" + displayId + "。");
            } else if (!landed) {
                result.put("error_code", "action_failed")
                        .put("stage", "virtual_display_launch")
                        .put("feedback", "❌ 未能在虚拟屏 display_id=" + displayId + " 上检测到「" + targetLabel + "」，禁止回退到主屏。");
            } else {
                result.put("feedback", "✅ 已在虚拟屏 display_id=" + displayId + " 启动「" + targetLabel + "」，主屏未受影响。");
            }

            if (ScreenControlService.instance != null) {
                try {
                    result.put("screen", ScreenControlService.authorized().captureSnapshot(displayId));
                } catch (Exception e) {
                    result.put("screen_error", e.getMessage());
                }
            }
            return result;
        }

        Intent launch;
        if (safeUrl != null) {
            launch = new Intent(Intent.ACTION_VIEW, Uri.parse(safeUrl));
            launch.setPackage(targetPkg);
        } else {
            launch = pm.getLaunchIntentForPackage(targetPkg);
        }
        if (launch == null) {
            throw new IllegalStateException("无法获取应用启动 Intent：" + targetPkg);
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Context launcher = ScreenControlService.instance != null ? ScreenControlService.instance : context;
        launcher.startActivity(launch);

        Thread.sleep(sleepMs);

        JSONObject result = new JSONObject()
                .put("action", "open_app")
                .put("display_id", displayId)
                .put("requested", q)
                .put("launched_app", targetLabel)
                .put("launched_package", targetPkg)
                .put("wait_ms", sleepMs)
                .put("action_delivered", true);

        if (ScreenControlService.instance != null) {
            try {
                JSONObject snap = ScreenControlService.authorized().captureSnapshot(displayId);
                boolean inTarget = targetPkg.equals(snap.optString("foreground_package", ""));
                result.put("ok", true)
                      .put("goal_reached", inTarget)
                      .put("verified_in_foreground", inTarget)
                      .put("feedback", inTarget
                              ? "✅ 已成功打开「" + targetLabel + "」并获取首页元素，请根据下方 screen 继续执行下一步操作。"
                              : "⚠️ 已发送启动指令，当前前台为 " + snap.optString("foreground_app", "") + "，请查看 screen 状态。")
                      .put("screen", snap);
            } catch (Exception e) {
                result.put("screen_error", e.getMessage());
            }
        }
        return result;
    }

    private void triggerMediaScan(List<String> paths) {
        if (paths == null || paths.isEmpty()) return;
        try {
            MediaScannerConnection.scanFile(context, paths.toArray(new String[0]), null, null);
        } catch (Exception ignored) { }
    }
}
