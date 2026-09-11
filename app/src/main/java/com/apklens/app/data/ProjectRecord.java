package com.apklens.app.data;

import org.json.JSONObject;

public class ProjectRecord {
    public String id = "";
    public String name = "";
    public String apkName = "";
    public String pkg = "";
    public String version = "";
    public long date;
    public String status = "";
    public String message = "";
    public String zipMode = "";   // "direct" | "media"
    public String zipPath = "";
    public String zipUri = "";
    public String zipDisplay = "";
    public long zipSize;
    public long duration;
    public int classes, smali, res, assets, libs, dex, errors;

    public String zipPathDisplay() {
        if (zipPath != null && !zipPath.isEmpty()) return zipPath;
        if (zipDisplay != null && !zipDisplay.isEmpty()) return zipDisplay;
        return zipUri;
    }

    public boolean isDirectFile() { return "direct".equals(zipMode); }

    public JSONObject toJson() {
        try {
            JSONObject o = new JSONObject();
            o.put("id", id); o.put("name", name); o.put("apkName", apkName);
            o.put("pkg", pkg); o.put("version", version); o.put("date", date);
            o.put("status", status); o.put("message", message == null ? "" : message);
            o.put("zipMode", zipMode); o.put("zipPath", zipPath); o.put("zipUri", zipUri);
            o.put("zipDisplay", zipDisplay); o.put("zipSize", zipSize); o.put("duration", duration);
            o.put("classes", classes); o.put("smali", smali); o.put("res", res);
            o.put("assets", assets); o.put("libs", libs); o.put("dex", dex); o.put("errors", errors);
            return o;
        } catch (Exception e) { return new JSONObject(); }
    }

    public static ProjectRecord fromJson(JSONObject o) {
        ProjectRecord r = new ProjectRecord();
        r.id = o.optString("id"); r.name = o.optString("name");
        r.apkName = o.optString("apkName"); r.pkg = o.optString("pkg");
        r.version = o.optString("version"); r.date = o.optLong("date");
        r.status = o.optString("status"); r.message = o.optString("message");
        r.zipMode = o.optString("zipMode"); r.zipPath = o.optString("zipPath");
        r.zipUri = o.optString("zipUri"); r.zipDisplay = o.optString("zipDisplay");
        r.zipSize = o.optLong("zipSize"); r.duration = o.optLong("duration");
        r.classes = o.optInt("classes"); r.smali = o.optInt("smali"); r.res = o.optInt("res");
        r.assets = o.optInt("assets"); r.libs = o.optInt("libs"); r.dex = o.optInt("dex");
        r.errors = o.optInt("errors");
        return r;
    }
}
