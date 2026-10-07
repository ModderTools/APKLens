package com.apklens.app.data;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** JSON-file-backed project history (filesDir/projects.json). */
public final class ProjectStore {

    private ProjectStore() {}

    private static File file(Context c) {
        return new File(c.getFilesDir(), "projects.json");
    }

    public static synchronized List<ProjectRecord> load(Context c) {
        List<ProjectRecord> out = new ArrayList<>();
        File f = file(c);
        if (!f.isFile()) return out;
        try (InputStream in = new FileInputStream(f)) {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            while ((n = in.read(chunk)) > 0) bos.write(chunk, 0, n); // read() may return fewer bytes than asked
            if (bos.size() == 0) return out;
            JSONArray arr = new JSONArray(new String(bos.toByteArray(), StandardCharsets.UTF_8));
            for (int i = 0; i < arr.length(); i++) {
                out.add(ProjectRecord.fromJson(arr.getJSONObject(i)));
            }
        } catch (Exception ignored) {}
        return out;
    }

    public static synchronized void save(Context c, List<ProjectRecord> list) {
        JSONArray arr = new JSONArray();
        for (ProjectRecord r : list) arr.put(r.toJson());
        File f = file(c);
        // Write to a temp file and rename: a crash/kill mid-write must never wipe the whole history.
        File tmp = new File(f.getParentFile(), f.getName() + ".tmp");
        try (FileOutputStream os = new FileOutputStream(tmp)) {
            os.write(arr.toString().getBytes(StandardCharsets.UTF_8));
            os.getFD().sync();
        } catch (Exception e) {
            tmp.delete();
            return;
        }
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f); }
    }

    public static synchronized void upsert(Context c, ProjectRecord rec) {
        List<ProjectRecord> list = load(c);
        list.removeIf(r -> rec.id.equals(r.id));
        list.add(0, rec);
        if (list.size() > 100) list.subList(100, list.size()).clear();
        save(c, list);
    }

    public static synchronized void clear(Context c) { file(c).delete(); }

    public static synchronized boolean remove(Context c, String id) {
        List<ProjectRecord> list = load(c);
        boolean changed = list.removeIf(r -> id.equals(r.id));
        if (changed) save(c, list);
        return changed;
    }
}
