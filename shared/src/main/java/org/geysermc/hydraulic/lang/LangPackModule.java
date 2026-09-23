package org.geysermc.hydraulic.lang;

import com.google.auto.service.AutoService;
import org.geysermc.hydraulic.pack.PackModule;
import org.geysermc.hydraulic.pack.context.PackPreProcessContext;
import org.jetbrains.annotations.NotNull;
import team.unnamed.creative.ResourcePack;
import team.unnamed.creative.lang.Language;

/**
 * Collects the translations mods ship so Geyser can resolve their translatable
 * components, rather than sending the raw key on to the Bedrock client.
 * <p>
 * This runs as a pre-processor because pre-processing happens for every mod on
 * every start, while pack conversion is skipped for packs that are already
 * converted.
 */
@AutoService(PackModule.class)
public class LangPackModule extends PackModule<LangPackModule> {
    public LangPackModule() {
        this.preProcess(this::preProcess);
    }

    private void preProcess(@NotNull PackPreProcessContext<LangPackModule> context) {
        for (Language language : context.assets(ResourcePack::languages)) {
            // A language's key is <mod namespace>:<locale>, so the locale is the value
            ModTranslations.register(language.key().value(), language.translations());
        }
    }
}
