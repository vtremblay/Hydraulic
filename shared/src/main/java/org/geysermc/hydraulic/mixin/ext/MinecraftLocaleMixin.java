package org.geysermc.hydraulic.mixin.ext;

import org.geysermc.geyser.text.MinecraftLocale;
import org.geysermc.hydraulic.lang.ModTranslations;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = MinecraftLocale.class, remap = false)
public class MinecraftLocaleMixin {

    /**
     * Falls back to the translations mods ship when Geyser has none for a key.
     * <p>
     * Geyser only ever loads the vanilla language files, so a mod's key resolves
     * to null here, MinecraftTranslationRegistry then leaves the component
     * untranslated, and the Bedrock client is shown the key itself -- a modded
     * container opens with a title like
     * {@code container.enderitemod.enderiteShulkerBox}.
     * <p>
     * Injecting on return rather than replacing the lookup keeps vanilla keys
     * resolving against the vanilla files, even where a mod redefines one.
     */
    @Inject(
        method = "getLocaleStringIfPresent(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
        at = @At("RETURN"),
        cancellable = true
    )
    private static void getLocaleStringIfPresent(String messageText, String locale, CallbackInfoReturnable<String> cir) {
        if (cir.getReturnValue() != null) {
            return;
        }

        String translation = ModTranslations.translation(messageText, locale);
        if (translation != null) {
            cir.setReturnValue(translation);
        }
    }
}
