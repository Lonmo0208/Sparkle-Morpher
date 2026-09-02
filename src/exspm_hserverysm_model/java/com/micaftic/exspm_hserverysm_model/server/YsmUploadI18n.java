package com.micaftic.exspm_hserverysm_model.server;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.micaftic.exspm_hserverysm_model.YsmUploadMod;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Minimal server-side i18n for player-facing messages.
 *
 * <p>Clients do not ship the upload mod's language files, so a translatable
 * component would render its raw key on the client. Instead the server resolves
 * each message against its own bundled language bundle ({@code lang/*.json})
 * using the sending player's selected locale ({@link ServerPlayer#getLanguage()}),
 * falling back to English, then to a plain code. Messages with placeholders use
 * {@code {0}}, {@code {1}} … indexes which get substituted here.</p>
 */
public final class YsmUploadI18n {

    private static final String LANG_DIR = "/assets/exspm_hserverysm_model/lang/";
    private static final String DEFAULT_LOCALE = "en_us";

    private static final Map<String, Map<String, String>> BUNDLES = new ConcurrentHashMap<>();

    private YsmUploadI18n() {
    }

    /** Loads the bundled language files from the mod jar. Call once at server start. */
    public static void load() {
        BUNDLES.clear();
        for (String locale : new String[]{"en_us", "zh_cn", "zh_tw", "ja_jp", "ko_kr"}) {
            Map<String, String> bundle = readBundle(locale);
            if (!bundle.isEmpty()) {
                BUNDLES.put(locale, bundle);
            }
        }
        YsmUploadMod.LOGGER.info("Loaded {} server language bundle(s)", BUNDLES.size());
    }

    /** Translates {@code key} for the given player's locale. */
    public static String t(ServerPlayer player, String key, Object... args) {
        return t(player == null ? DEFAULT_LOCALE : player.getLanguage(), key, args);
    }

    /** Translates {@code key} for the given locale code (e.g. {@code zh_cn}). */
    public static String t(String locale, String key, Object... args) {
        String pattern = lookup(patternFor(locale), key);
        if (pattern == null) {
            pattern = lookup(patternFor(DEFAULT_LOCALE), key);
        }
        if (pattern == null) {
            return key;
        }
        return format(pattern, args);
    }

    private static Map<String, String> patternFor(String locale) {
        if (locale == null) {
            return null;
        }
        String normalized = locale.replace('-', '_').toLowerCase(Locale.ROOT);
        return BUNDLES.get(normalized);
    }

    private static String lookup(Map<String, String> bundle, String key) {
        return bundle == null ? null : bundle.get(key);
    }

    private static Map<String, String> readBundle(String locale) {
        Map<String, String> result = new ConcurrentHashMap<>();
        String path = LANG_DIR + locale + ".json";
        try (InputStream in = YsmUploadI18n.class.getResourceAsStream(path)) {
            if (in == null) {
                return result;
            }
            JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            root.entrySet().forEach(entry -> result.put(entry.getKey(), entry.getValue().getAsString()));
        } catch (IOException | RuntimeException e) {
            YsmUploadMod.LOGGER.warn("Failed to read language bundle {}", path, e);
        }
        return result;
    }

    /** Replaces {@code {n}} placeholders with the corresponding argument. */
    public static String format(String pattern, Object... args) {
        if (args == null || args.length == 0) {
            return pattern;
        }
        StringBuilder sb = new StringBuilder(pattern.length() + 16);
        int i = 0;
        while (i < pattern.length()) {
            char c = pattern.charAt(i);
            if (c == '{' && i + 1 < pattern.length()) {
                int end = pattern.indexOf('}', i + 1);
                if (end > 0) {
                    String token = pattern.substring(i + 1, end).trim();
                    if (token.chars().allMatch(Character::isDigit)) {
                        int idx = Integer.parseInt(token);
                        if (idx >= 0 && idx < args.length) {
                            sb.append(String.valueOf(args[idx]));
                            i = end + 1;
                            continue;
                        }
                    }
                }
            }
            sb.append(c);
            i++;
        }
        return sb.toString();
    }
}