package org.geysermc.hydraulic.pack;

import net.kyori.adventure.key.Key;
import org.apache.commons.lang3.StringUtils;
import org.geysermc.hydraulic.Constants;
import org.geysermc.hydraulic.pack.context.PackContext;
import org.geysermc.pack.converter.type.texture.TextureConverter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import team.unnamed.creative.ResourcePack;
import team.unnamed.creative.model.Model;
import team.unnamed.creative.model.ModelTexture;

import java.util.List;

public abstract class TexturePackModule<T extends PackModule<T>> extends PackModule<T> {
    /**
     * Gets the output location of the given key.
     *
     * @param packContext the pack context
     * @param key the key
     * @return the output location
     */
    protected static <T extends PackModule<T>> String getOutputFromModel(@NotNull PackContext<T> packContext, @NotNull Key key) {
        String directory = StringUtils.substringBefore(key.value(), "/");
        String remaining = StringUtils.substringAfter(key.value(), "/");
        String finalDir = TextureConverter.DIRECTORY_LOCATIONS.getOrDefault(directory, directory) + "/" + packContext.mod().id();

        return String.format(Constants.BEDROCK_TEXTURE_LOCATION, finalDir + "/" + remaining);
    }

    /**
     * Gets the output location of the texture a model draws itself with.
     *
     * @param packContext the pack context
     * @param assets the pack the model lives in
     * @param modelKey the model to read the texture of
     * @return the output location, or {@code null} if the model is missing or
     *         has no layer to read
     */
    protected static <T extends PackModule<T>> @Nullable String getOutputFromModelTexture(
            @NotNull PackContext<T> packContext,
            @NotNull ResourcePack assets,
            @NotNull Key modelKey
    ) {
        Model model = assets.model(modelKey);
        if (model == null) {
            return null;
        }

        List<ModelTexture> layers = model.textures().layers();
        if (layers == null || layers.isEmpty()) {
            return null;
        }

        return getOutputFromModel(packContext, layers.getFirst().key()).replace(".png", "");
    }
}
