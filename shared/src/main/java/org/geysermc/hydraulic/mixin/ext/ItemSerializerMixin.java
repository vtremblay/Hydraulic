package org.geysermc.hydraulic.mixin.ext;

import com.google.gson.JsonObject;
import net.kyori.adventure.key.Key;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import team.unnamed.creative.item.ConditionItemModel;
import team.unnamed.creative.item.RangeDispatchItemModel;
import team.unnamed.creative.item.SelectItemModel;
import team.unnamed.creative.serialize.minecraft.item.ItemSerializer;

/**
 * Keeps an item model that selects on a modded property readable.
 * <p>
 * An item definition may switch between models on a property the mod registers
 * itself, for example a bow's draw frames or a shield's sneaking variant:
 *
 * <pre>
 * "property": "examplemod:bow/pull"
 * "property": "examplemod:is_sneaking"
 * </pre>
 *
 * The property readers reject any property outside the {@code minecraft}
 * namespace before they look it up, and the throw takes the <i>whole</i> item
 * definition with it rather than just the property -- the item keeps its
 * converted texture but loses its model entirely.
 * <p>
 * Bedrock cannot evaluate any of these properties in the first place, so the
 * useful reading of a modded property is "always the default". Swapping it for
 * {@code minecraft:custom_model_data}, which every one of these readers already
 * handles and which is absent from the items in question, gives exactly that:
 * the condition reads false, the range dispatch reads zero, and the model
 * resolves to its {@code on_false} branch or its {@code fallback}. The rest of
 * the definition is then read as normal.
 */
@Mixin(value = ItemSerializer.class, remap = false)
public class ItemSerializerMixin {
    private static final Logger LOGGER = LoggerFactory.getLogger("ItemSerializerMixin");

    private static final String CUSTOM_MODEL_DATA = "minecraft:custom_model_data";

    @Inject(method = "readCondition(Lcom/google/gson/JsonObject;)Lteam/unnamed/creative/item/ConditionItemModel;", at = @At("HEAD"))
    private void readCondition(JsonObject node, CallbackInfoReturnable<ConditionItemModel> cir) {
        hydraulic$defaultModdedProperty(node, "condition");
    }

    @Inject(method = "readSelect(Lcom/google/gson/JsonObject;)Lteam/unnamed/creative/item/SelectItemModel;", at = @At("HEAD"))
    private void readSelect(JsonObject node, CallbackInfoReturnable<SelectItemModel> cir) {
        hydraulic$defaultModdedProperty(node, "select");
    }

    @Inject(method = "readRangeDispatch(Lcom/google/gson/JsonObject;)Lteam/unnamed/creative/item/RangeDispatchItemModel;", at = @At("HEAD"))
    private void readRangeDispatch(JsonObject node, CallbackInfoReturnable<RangeDispatchItemModel> cir) {
        hydraulic$defaultModdedProperty(node, "range_dispatch");
    }

    /**
     * Replaces a modded property with one that reads as its default, leaving a
     * vanilla property untouched.
     */
    private static void hydraulic$defaultModdedProperty(JsonObject node, String modelType) {
        if (!node.has("property")) {
            return;
        }

        Key property = Key.key(node.get("property").getAsString());
        if (property.namespace().equals(Key.MINECRAFT_NAMESPACE)) {
            return;
        }

        LOGGER.debug("Defaulting modded {} property {}, Bedrock cannot evaluate it", modelType, property);

        node.addProperty("property", CUSTOM_MODEL_DATA);

        // custom_model_data reads an index out of the item's component. The
        // index the modded property carried, if any, means nothing here, and
        // leaving it in place would point at an unrelated entry.
        node.remove("index");
    }
}
