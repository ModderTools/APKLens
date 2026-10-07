package com.apklens.app.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Thread-safe collector fed by the decompile workers (one call per reconstructed class).
 * It reads strings that are ALREADY in the decompiled code — nothing is executed or fetched.
 */
public final class CodeScanner {

    private static final int MAX_URLS = 3000;
    private static final int MAX_SECRETS = 60;

    private static final Pattern URL = Pattern.compile(
            "https?://[A-Za-z0-9._~:/?#\\[\\]@!$&'()*+,;=%\\-]{4,220}");

    /** Boilerplate hosts that appear in nearly every app and carry no information. */
    private static final String[] URL_NOISE = {
            "schemas.android.com", "www.w3.org", "www.apache.org", "xmlpull.org", "java.sun.com",
            "ns.adobe.com", "purl.org", "schemas.microsoft.com", "www.opengis.net",
            "developer.android.com", "source.android.com", "example.com", "localhost"
    };

    private static final class SecretRule {
        final String name; final Pattern p;
        SecretRule(String name, String regex) { this.name = name; this.p = Pattern.compile(regex); }
    }

    private static final SecretRule[] SECRET_RULES = {
            new SecretRule("Google API key", "AIza[0-9A-Za-z_\\-]{35}"),
            new SecretRule("AWS access key id", "\\b(?:AKIA|ASIA)[0-9A-Z]{16}\\b"),
            new SecretRule("Private key block", "-----BEGIN (?:RSA |EC |DSA |OPENSSH )?PRIVATE KEY-----"),
            new SecretRule("Slack token", "xox[baprs]-[0-9A-Za-z\\-]{10,}"),
            new SecretRule("GitHub token", "\\bgh[pousr]_[0-9A-Za-z]{36,}"),
            new SecretRule("Stripe live key", "\\b[sr]k_live_[0-9A-Za-z]{16,}"),
    };

    /** package prefix → friendly name. Order matters only for display (first match per prefix). */
    private static final String[][] LIBS = {
            {"okhttp3.", "OkHttp"}, {"retrofit2.", "Retrofit"}, {"com.squareup.picasso.", "Picasso"},
            {"com.bumptech.glide.", "Glide"}, {"com.google.gson.", "Gson"},
            {"com.fasterxml.jackson.", "Jackson"}, {"io.reactivex.", "RxJava"},
            {"kotlinx.coroutines.", "Kotlin Coroutines"}, {"kotlin.", "Kotlin stdlib"},
            {"androidx.room.", "Room"}, {"androidx.", "AndroidX"}, {"android.support.", "Android Support Library"},
            {"com.google.firebase.", "Firebase"}, {"com.google.android.gms.", "Google Play Services"},
            {"com.google.android.material.", "Material Components"}, {"dagger.", "Dagger"},
            {"butterknife.", "ButterKnife"}, {"org.greenrobot.eventbus.", "EventBus"},
            {"com.facebook.", "Facebook SDK"}, {"com.google.android.exoplayer2.", "ExoPlayer"},
            {"io.sentry.", "Sentry"}, {"com.crashlytics.", "Crashlytics"},
            {"com.onesignal.", "OneSignal"}, {"com.appsflyer.", "AppsFlyer"}, {"com.adjust.sdk.", "Adjust"},
            {"com.mixpanel.", "Mixpanel"}, {"com.amplitude.", "Amplitude"},
            {"com.applovin.", "AppLovin (ads)"}, {"com.unity3d.ads.", "Unity Ads"},
            {"com.ironsource.", "ironSource (ads)"}, {"com.vungle.", "Vungle (ads)"},
            {"com.chartbeat.", "Chartbeat"}, {"org.apache.http.", "Apache HTTP (legacy)"},
            {"net.sqlcipher.", "SQLCipher"}, {"io.realm.", "Realm"},
            {"com.stripe.", "Stripe"}, {"com.google.zxing.", "ZXing"},
    };

    private final ConcurrentHashMap<String, AtomicInteger> urls = new ConcurrentHashMap<>();
    private final List<String[]> secrets = Collections.synchronizedList(new ArrayList<>());
    private final ConcurrentHashMap<String, AtomicInteger> libs = new ConcurrentHashMap<>();
    private final AtomicInteger scanned = new AtomicInteger();
    private final AtomicInteger shortNames = new AtomicInteger();

    /** @param className fully-qualified (possibly obfuscated) name of the top-level class. */
    public void scan(String className, String rawSimpleName, String code) {
        scanned.incrementAndGet();
        if (rawSimpleName != null && isShortName(rawSimpleName)) shortNames.incrementAndGet();

        for (String[] lib : LIBS) {
            if (className.startsWith(lib[0])) {
                libs.computeIfAbsent(lib[1], k -> new AtomicInteger()).incrementAndGet();
                break;
            }
        }
        if (code == null || code.isEmpty()) return;

        // Cheap pre-checks keep the regex engine away from the vast majority of classes.
        if (code.contains("http://") || code.contains("https://")) {
            Matcher m = URL.matcher(code);
            while (m.find()) {
                String u = cleanUrl(m.group());
                if (u == null || noisy(u)) continue;
                if (urls.size() >= MAX_URLS && !urls.containsKey(u)) continue;
                urls.computeIfAbsent(u, k -> new AtomicInteger()).incrementAndGet();
            }
        }
        if (secrets.size() < MAX_SECRETS) {
            for (SecretRule rule : SECRET_RULES) {
                Matcher m = rule.p.matcher(code);
                if (m.find()) {
                    secrets.add(new String[]{rule.name, mask(m.group()), className});
                    if (secrets.size() >= MAX_SECRETS) break;
                }
            }
        }
    }

    // ------------------------------------------------------------------ results

    public int urlCount() { return urls.size(); }
    public int secretCount() { return secrets.size(); }
    public int scannedClasses() { return scanned.get(); }

    /** Percentage (0-100) of scanned classes whose name is 1-2 characters (a, b, aa, …). */
    public int obfuscationPercent() {
        int n = scanned.get();
        return n == 0 ? 0 : (int) Math.round(100.0 * shortNames.get() / n);
    }

    public static String obfuscationLevel(int pct) {
        if (pct >= 40) return "Heavy";
        if (pct >= 12) return "Partial";
        return "None / minimal";
    }

    /** URLs sorted by how often they occur, then alphabetically. */
    public List<Map.Entry<String, Integer>> sortedUrls() {
        List<Map.Entry<String, Integer>> out = new ArrayList<>();
        for (Map.Entry<String, AtomicInteger> e : urls.entrySet()) {
            out.add(new java.util.AbstractMap.SimpleEntry<>(e.getKey(), e.getValue().get()));
        }
        Collections.sort(out, new Comparator<Map.Entry<String, Integer>>() {
            @Override public int compare(Map.Entry<String, Integer> a, Map.Entry<String, Integer> b) {
                int c = Integer.compare(b.getValue(), a.getValue());
                return c != 0 ? c : a.getKey().compareTo(b.getKey());
            }
        });
        return out;
    }

    public List<String[]> secrets() { return new ArrayList<>(secrets); }

    /** Library display name → number of classes that belong to it (sorted by name). */
    public Map<String, Integer> libraries() {
        Map<String, Integer> out = new TreeMap<>();
        for (Map.Entry<String, AtomicInteger> e : libs.entrySet()) out.put(e.getKey(), e.getValue().get());
        return out;
    }

    // ------------------------------------------------------------------ helpers

    /** Names like a, b, aa, a$b — what ProGuard/R8 produce. Also the ones R8 hides in "$r8$…". */
    static boolean isShortName(String raw) {
        int dollar = raw.indexOf('$');
        String s = dollar > 0 ? raw.substring(0, dollar) : raw;
        return s.length() > 0 && s.length() <= 2 && !s.equals("R"); // R.java is generated, not obfuscated
    }

    private static String cleanUrl(String u) {
        // Strip trailing punctuation that is part of the surrounding code, not the URL.
        int end = u.length();
        while (end > 0 && ".,;:)'\"!".indexOf(u.charAt(end - 1)) >= 0) end--;
        u = u.substring(0, end);
        if (u.length() < 10) return null;
        return u;
    }

    private static boolean noisy(String u) {
        String host = u.substring(u.indexOf("//") + 2);
        for (String n : URL_NOISE) {
            if (host.startsWith(n)) return true;
        }
        return false;
    }

    /** Show enough to recognise a secret in the sources, never the whole value. */
    static String mask(String s) {
        if (s.startsWith("-----BEGIN")) return s;
        if (s.length() <= 10) return s.substring(0, 2) + "…";
        return s.substring(0, 6) + "…" + s.substring(s.length() - 3);
    }
}
