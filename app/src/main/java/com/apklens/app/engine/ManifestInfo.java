package com.apklens.app.engine;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Facts pulled out of the DECODED AndroidManifest.xml (+ strings.xml for the app label).
 * Pure java.* / org.w3c.* — no android imports, so it is unit-testable on a plain JVM.
 */
public final class ManifestInfo {

    private static final String NS = "http://schemas.android.com/apk/res/android";

    public String pkg = "";
    public String versionName = "";
    public String versionCode = "";
    public String label = "";
    public int minSdk, targetSdk;
    public boolean debuggable, allowBackup = true, cleartext, testOnly, hasNetworkSecurityConfig;
    public boolean legacyStorage;
    public String launcher = "";

    public final List<String> permissions = new ArrayList<>();
    public final List<String> dangerous = new ArrayList<>();
    public final List<String> special = new ArrayList<>();

    public int activities, services, receivers, providers;
    /** "type name" strings for components that other apps can reach. */
    public final List<String> exported = new ArrayList<>();

    /** Runtime ("dangerous") permissions as documented by Android. */
    private static final Set<String> DANGEROUS = new HashSet<>(Arrays.asList(
            "READ_CALENDAR", "WRITE_CALENDAR", "CAMERA", "READ_CONTACTS", "WRITE_CONTACTS",
            "GET_ACCOUNTS", "ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION",
            "ACCESS_BACKGROUND_LOCATION", "RECORD_AUDIO", "READ_PHONE_STATE", "READ_PHONE_NUMBERS",
            "CALL_PHONE", "ANSWER_PHONE_CALLS", "READ_CALL_LOG", "WRITE_CALL_LOG", "ADD_VOICEMAIL",
            "USE_SIP", "PROCESS_OUTGOING_CALLS", "BODY_SENSORS", "BODY_SENSORS_BACKGROUND",
            "ACTIVITY_RECOGNITION", "SEND_SMS", "RECEIVE_SMS", "READ_SMS", "RECEIVE_WAP_PUSH",
            "RECEIVE_MMS", "READ_EXTERNAL_STORAGE", "WRITE_EXTERNAL_STORAGE", "ACCESS_MEDIA_LOCATION",
            "READ_MEDIA_IMAGES", "READ_MEDIA_VIDEO", "READ_MEDIA_AUDIO",
            "READ_MEDIA_VISUAL_USER_SELECTED", "POST_NOTIFICATIONS", "NEARBY_WIFI_DEVICES",
            "BLUETOOTH_CONNECT", "BLUETOOTH_SCAN", "BLUETOOTH_ADVERTISE", "UWB_RANGING"));

    /** Not runtime prompts, but powerful enough that a reverse engineer wants to see them first. */
    private static final Set<String> SPECIAL = new HashSet<>(Arrays.asList(
            "SYSTEM_ALERT_WINDOW", "REQUEST_INSTALL_PACKAGES", "MANAGE_EXTERNAL_STORAGE",
            "BIND_ACCESSIBILITY_SERVICE", "BIND_DEVICE_ADMIN", "QUERY_ALL_PACKAGES", "WRITE_SETTINGS",
            "PACKAGE_USAGE_STATS", "READ_LOGS", "INSTALL_PACKAGES", "DELETE_PACKAGES",
            "REQUEST_DELETE_PACKAGES", "BIND_NOTIFICATION_LISTENER_SERVICE", "USE_FULL_SCREEN_INTENT",
            "SCHEDULE_EXACT_ALARM", "USE_EXACT_ALARM", "READ_PRIVILEGED_PHONE_STATE",
            "BIND_VPN_SERVICE", "CAPTURE_AUDIO_OUTPUT", "MODIFY_PHONE_STATE", "DISABLE_KEYGUARD"));

    private ManifestInfo() {}

    /** Parses the decoded manifest; never throws (returns whatever could be read). */
    public static ManifestInfo parse(File manifest, File stringsXml) {
        ManifestInfo m = new ManifestInfo();
        if (manifest == null || !manifest.isFile()) return m;
        try {
            Element root = parseXml(manifest).getDocumentElement();
            m.pkg = root.getAttribute("package");
            m.versionName = attr(root, "versionName");
            m.versionCode = attr(root, "versionCode");

            Element sdk = first(root, "uses-sdk");
            if (sdk != null) {
                m.minSdk = intAttr(sdk, "minSdkVersion");
                m.targetSdk = intAttr(sdk, "targetSdkVersion");
            }

            m.readPermissions(root);

            Element app = first(root, "application");
            if (app != null) {
                m.debuggable = boolAttr(app, "debuggable", false);
                m.allowBackup = boolAttr(app, "allowBackup", true);
                m.cleartext = boolAttr(app, "usesCleartextTraffic", false);
                m.testOnly = boolAttr(app, "testOnly", false);
                m.legacyStorage = boolAttr(app, "requestLegacyExternalStorage", false);
                m.hasNetworkSecurityConfig = !attr(app, "networkSecurityConfig").isEmpty();
                m.label = resolveLabel(attr(app, "label"), stringsXml);
                m.readComponents(app);
            }
        } catch (Throwable ignored) {
            // partial info is fine
        }
        return m;
    }

    // ------------------------------------------------------------------

    private void readPermissions(Element root) {
        Set<String> seen = new LinkedHashSet<>();
        for (String tag : new String[]{"uses-permission", "uses-permission-sdk-23"}) {
            NodeList nl = root.getElementsByTagName(tag);
            for (int i = 0; i < nl.getLength(); i++) {
                String n = attr((Element) nl.item(i), "name");
                if (!n.isEmpty()) seen.add(n);
            }
        }
        for (String full : seen) {
            permissions.add(full);
            String shortName = full.substring(full.lastIndexOf('.') + 1);
            boolean platform = full.startsWith("android.permission.");
            if (platform && DANGEROUS.contains(shortName)) dangerous.add(full);
            else if (platform && SPECIAL.contains(shortName)) special.add(full);
        }
    }

    private void readComponents(Element app) {
        NodeList kids = app.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node n = kids.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element e = (Element) n;
            String tag = e.getTagName();
            boolean activity = tag.equals("activity") || tag.equals("activity-alias");
            if (activity) activities++;
            else if (tag.equals("service")) services++;
            else if (tag.equals("receiver")) receivers++;
            else if (tag.equals("provider")) providers++;
            else continue;

            String name = attr(e, "name");
            if (name.isEmpty()) name = attr(e, "targetActivity");
            if (activity && launcher.isEmpty() && isLauncher(e)) launcher = name;

            String exp = attr(e, "exported");
            boolean hasFilter = e.getElementsByTagName("intent-filter").getLength() > 0;
            // Pre-Android-12 a component with an intent-filter is exported unless stated otherwise.
            boolean isExported = exp.isEmpty() ? hasFilter : "true".equals(exp);
            if (tag.equals("provider") && exp.isEmpty() && targetSdk > 0 && targetSdk < 17) isExported = true;
            if (isExported) exported.add(tag + " " + name + (activity && name.equals(launcher) ? "  (launcher)" : ""));
        }
    }

    private static boolean isLauncher(Element activity) {
        NodeList filters = activity.getElementsByTagName("intent-filter");
        for (int i = 0; i < filters.getLength(); i++) {
            Element f = (Element) filters.item(i);
            boolean main = false, launcher = false;
            NodeList a = f.getElementsByTagName("action");
            for (int j = 0; j < a.getLength(); j++) {
                if ("android.intent.action.MAIN".equals(attr((Element) a.item(j), "name"))) main = true;
            }
            NodeList c = f.getElementsByTagName("category");
            for (int j = 0; j < c.getLength(); j++) {
                if ("android.intent.category.LAUNCHER".equals(attr((Element) c.item(j), "name"))) launcher = true;
            }
            if (main && launcher) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------

    private static Document parseXml(File f) throws Exception {
        DocumentBuilderFactory fac = DocumentBuilderFactory.newInstance();
        fac.setNamespaceAware(true);
        // The decoded manifest derives from an untrusted APK — refuse DTDs/external entities.
        try { fac.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); }
        catch (Throwable ignored) {}
        DocumentBuilder db = fac.newDocumentBuilder();
        return db.parse(f);
    }

    private static Element first(Element root, String tag) {
        NodeList nl = root.getElementsByTagName(tag);
        return nl.getLength() == 0 ? null : (Element) nl.item(0);
    }

    /** android:xyz attribute (namespace-aware, with a prefix-less fallback). */
    private static String attr(Element e, String name) {
        String v = e.getAttributeNS(NS, name);
        if (v == null || v.isEmpty()) v = e.getAttribute("android:" + name);
        return v == null ? "" : v;
    }

    private static int intAttr(Element e, String name) {
        try { return Integer.parseInt(attr(e, name).trim()); } catch (Exception ex) { return 0; }
    }

    private static boolean boolAttr(Element e, String name, boolean def) {
        String v = attr(e, name);
        if (v.isEmpty()) return def;
        return "true".equalsIgnoreCase(v);
    }

    /** "@string/app_name" → the literal from res/values/strings.xml when present. */
    private static String resolveLabel(String raw, File stringsXml) {
        if (raw == null || raw.isEmpty()) return "";
        if (!raw.startsWith("@")) return raw;
        int slash = raw.indexOf('/');
        if (!raw.startsWith("@string/") || slash < 0 || stringsXml == null || !stringsXml.isFile()) return "";
        String key = raw.substring(slash + 1);
        try {
            NodeList nl = parseXml(stringsXml).getElementsByTagName("string");
            for (int i = 0; i < nl.getLength(); i++) {
                Element s = (Element) nl.item(i);
                if (key.equals(s.getAttribute("name"))) return s.getTextContent().trim();
            }
        } catch (Throwable ignored) {}
        return "";
    }
}
