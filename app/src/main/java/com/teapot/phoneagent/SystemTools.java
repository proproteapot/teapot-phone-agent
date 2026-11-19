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
            throw new SecurityException("尚未授予【所有文件访问权限】(MANAGE_EXTERNAL_STORAGE)，请在手机助手桥主界面点击授权");
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

    /** 闭环定闹钟：触发系统时钟并在 550ms 后读取 AlarmManager 验证系统下一闹钟状态 */
    JSONObject setAlarmAndVerify(int hour, int minute, String label) throws Exception {
        android.app.AlarmManager am = context.getSystemService(android.app.AlarmManager.class);
        long beforeNext = (am != null && am.getNextAlarmClock() != null) ? am.getNextAlarmClock().getTriggerTime() : 0L;

        Intent alarm = alarmIntent(hour, minute, label);
        Context launcher = ScreenControlService.instance != null ? ScreenControlService.instance : context;
        launcher.startActivity(alarm);
        Thread.sleep(550);

        long afterNext = (am != null && am.getNextAlarmClock() != null) ? am.getNextAlarmClock().getTriggerTime() : 0L;
        String formattedNext = afterNext > 0
                ? new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(new java.util.Date(afterNext))
                : "无";

        return new JSONObject()
                .put("requested_time", String.format(Locale.ROOT, "%02d:%02d", hour, minute))
                .put("label", label == null ? "" : label)
                .put("intent_dispatched", true)
                .put("system_has_active_alarm", afterNext > 0)
                .put("next_alarm_trigger_ms", afterNext)
                .put("next_alarm_formatted", formattedNext)
                .put("next_alarm_updated", afterNext != beforeNext)
                .put("feedback", afterNext > 0
                        ? "✅ 已向系统时钟提交 " + String.format(Locale.ROOT, "%02d:%02d", hour, minute) + " 闹钟，系统当前登记的最近响铃时间为：" + formattedNext
                        : "⚠️ 已发送闹钟请求，但系统 AlarmManager 未检测到激活闹钟；可使用 open_app 打开「时钟」通过屏幕控制核验。");
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
