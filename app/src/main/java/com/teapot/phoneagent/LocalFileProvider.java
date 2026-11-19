package com.teapot.phoneagent;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;

/**
 * 只读安全文件提供器（ContentProvider），用于向系统查看器授权打开单个共享存储文件。
 * 默认不导出（exported=false），仅在触发 ACTION_VIEW 且附带 FLAG_GRANT_READ_URI_PERMISSION 时单次生效。
 */
public final class LocalFileProvider extends ContentProvider {
    static final String AUTHORITY = "com.teapot.phoneagent.files";

    static Uri uriForFile(File file) throws Exception {
        File root = Environment.getExternalStorageDirectory().getCanonicalFile();
        File target = file.getCanonicalFile();
        String rootPath = root.getPath();
        String targetPath = target.getPath();
        if (!targetPath.startsWith(rootPath + File.separator)) {
            throw new SecurityException("文件不在共享存储目录内");
        }
        String rel = targetPath.substring(rootPath.length() + 1).replace(File.separatorChar, '/');
        if ("android".equalsIgnoreCase(rel) || rel.toLowerCase(java.util.Locale.ROOT).startsWith("android/")) {
            throw new SecurityException("安全限制：禁止共享系统 Android 目录下的文件");
        }
        Uri.Builder b = new Uri.Builder().scheme("content").authority(AUTHORITY);
        for (String seg : rel.split("/")) {
            if (!seg.isEmpty()) b.appendPath(seg);
        }
        return b.build();
    }

    private File fileForUri(Uri uri) throws FileNotFoundException {
        try {
            File root = Environment.getExternalStorageDirectory().getCanonicalFile();
            String path = uri.getPath();
            if (path == null || path.isEmpty()) throw new FileNotFoundException("路径为空");
            File target = new File(root, path).getCanonicalFile();
            String rootPath = root.getPath();
            String targetPath = target.getPath();
            if (!targetPath.startsWith(rootPath + File.separator) || !target.isFile()) {
                throw new FileNotFoundException("文件不存在或越界");
            }
            String rel = targetPath.substring(rootPath.length() + 1).replace(File.separatorChar, '/');
            if ("android".equalsIgnoreCase(rel) || rel.toLowerCase(java.util.Locale.ROOT).startsWith("android/")) {
                throw new FileNotFoundException("安全限制：禁止读取系统 Android 目录");
            }
            return target;
        } catch (FileNotFoundException e) {
            throw e;
        } catch (Exception e) {
            throw new FileNotFoundException("无效路径");
        }
    }

    @Override public boolean onCreate() { return true; }

    @Override public String getType(Uri uri) {
        try {
            File f = fileForUri(uri);
            return SystemTools.guessMimeType(f.getName());
        } catch (Exception e) {
            return "application/octet-stream";
        }
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        try {
            File f = fileForUri(uri);
            String[] cols = projection != null ? projection : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
            MatrixCursor cursor = new MatrixCursor(cols, 1);
            Object[] row = new Object[cols.length];
            for (int i = 0; i < cols.length; i++) {
                if (OpenableColumns.DISPLAY_NAME.equals(cols[i])) row[i] = f.getName();
                else if (OpenableColumns.SIZE.equals(cols[i])) row[i] = f.length();
                else row[i] = null;
            }
            cursor.addRow(row);
            return cursor;
        } catch (Exception e) {
            return null;
        }
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new SecurityException("仅支持只读打开");
        File f = fileForUri(uri);
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { throw new UnsupportedOperationException(); }
}
