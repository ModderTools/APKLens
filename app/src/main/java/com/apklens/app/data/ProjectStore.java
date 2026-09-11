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
            byte[] buf = new byte[(int) f.length()];
            int read = in.read(buf);
            if (read <= 0) return out;
            JSONArray arr = new JSONArray(new String(buf, StandardCharsets.UTF_8));
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
        try (OutputStream os = new FileOutputStream(f)) {
            os.write(arr.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {}
    }

    public static synchronized void upsert(Context c, ProjectRecord rec) {
        List<ProjectRecord> list = load(c);
        list.removeIf(r -> rec.id.equals(r.id));
        list.add(0, rec);
        if (list.size() > 100) list.subList(100, list.size()).clear();
        save(c, list);
    }

    public static synchronized boolean remove(Context c, String id) {
        List<ProjectRecord> list = load(c);
        boolean changed = list.removeIf(r -> id.equals(r.id));
        if (changed) save(c, list);
        return changed;
    }
}
