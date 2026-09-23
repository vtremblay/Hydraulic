package org.geysermc.hydraulic.lang;

import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Translations collected from the language files of the mods being converted.
 * <p>
 * Geyser resolves translatable components server side against the vanilla
 * language files, so a key belonging to a mod is never found and the key itself
 * is sent to the Bedrock client. These translations are consulted only once the
 * vanilla lookup has come up empty, so vanilla keys keep their vanilla values
 * even if a mod also defines them.
 */
public final class ModTranslations {
    private static final String FALLBACK_LOCALE = "en_us";

    private static final Map<String, Map<String, String>> TRANSLATIONS = new ConcurrentHashMap<>();

    private ModTranslations() {
    }

    /**
     * Register the translations a mod provides for a locale.
     *
     * @param locale the locale, named as the Java language file is (eg {@code en_us})
     * @param translations the translations to register
     */
    public static void register(String locale, Map<String, String> translations) {
        TRANSLATIONS.computeIfAbsent(locale.toLowerCase(Locale.ROOT), key -> new ConcurrentHashMap<>())
                .putAll(translations);
    }

    /**
     * Get the translation a mod provides for a key, falling back to American
     * English the way Geyser does for the vanilla files.
     *
     * @param key the translation key
     * @param locale the locale to translate into
     * @return the translation, or {@code null} if no mod provides one
     */
    public static @Nullable String translation(String key, String locale) {
        Map<String, String> translations = TRANSLATIONS.get(locale.toLowerCase(Locale.ROOT));
        if (translations != null) {
            String translation = translations.get(key);
            if (translation != null) {
                return translation;
            }
        }

        translations = TRANSLATIONS.get(FALLBACK_LOCALE);
        return translations == null ? null : translations.get(key);
    }
}
