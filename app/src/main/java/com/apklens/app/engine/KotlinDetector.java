package com.apklens.app.engine;

/**
 * Heuristic detection of classes that ORIGINATED from Kotlin.
 * The emitted code is always Java-syntax decompilation — this only annotates
 * the analysis report (see the honesty notes in the README/report).
 */
public final class KotlinDetector {

    private KotlinDetector() {}

    public static boolean looksLikeKotlin(String fullName, String code) {
        if (code == null) return false;
        if (code.contains("kotlin.jvm.internal.Intrinsics")) return true;
        if (code.contains("@kotlin.Metadata") || code.contains("kotlin.Metadata")) return true;
        int dot = fullName.lastIndexOf('.');
        String simple = dot >= 0 ? fullName.substring(dot + 1) : fullName;
        return simple.endsWith("Kt");
    }
}
