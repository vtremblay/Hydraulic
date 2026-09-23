package org.geysermc.hydraulic.item;

import com.google.auto.service.AutoService;
import net.kyori.adventure.key.Key;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.CrossbowItem;
import org.geysermc.hydraulic.pack.PackModule;
import org.geysermc.hydraulic.pack.TexturePackModule;
import org.geysermc.hydraulic.pack.context.PackPostProcessContext;
import org.geysermc.pack.bedrock.resource.BedrockResourcePack;
import org.geysermc.pack.bedrock.resource.attachables.Attachable;
import org.geysermc.pack.bedrock.resource.attachables.Attachables;
import org.geysermc.pack.bedrock.resource.attachables.attachable.Description;
import org.geysermc.pack.bedrock.resource.attachables.attachable.description.Scripts;
import org.geysermc.pack.bedrock.resource.render_controllers.RenderControllers;
import org.geysermc.pack.bedrock.resource.render_controllers.rendercontrollers.Arrays;
import org.jetbrains.annotations.NotNull;
import team.unnamed.creative.ResourcePack;
import team.unnamed.creative.item.Item;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts modded crossbows, which Bedrock draws from an attachable rather than
 * from the item's icon.
 * <p>
 * Modelled on the vanilla Bedrock crossbow, which steps through its frames with
 * {@code query.get_animation_frame}: standby, the three draw frames, then the
 * loaded arrow and loaded rocket.
 */
@AutoService(PackModule.class)
public class CrossbowPackModule extends TexturePackModule<CrossbowPackModule> {
    private static final Map<String, String> ATTACHABLE_MATERIALS = new HashMap<>() {
        {
            put("default", "entity_alphatest");
            put("enchanted", "entity_alphatest_glint");
        }
    };
    private static final Map<String, String> ATTACHABLE_GEOMETRY = new HashMap<>() {
        {
            put("default", "geometry.crossbow_standby");
            put("crossbow_pulling_0", "geometry.crossbow_pulling_0");
            put("crossbow_pulling_1", "geometry.crossbow_pulling_1");
            put("crossbow_pulling_2", "geometry.crossbow_pulling_2");
            put("crossbow_arrow", "geometry.crossbow_arrow");
            put("crossbow_rocket", "geometry.crossbow_rocket");
        }
    };
    private static final Map<String, String> ATTACHABLE_ANIMATIONS = new HashMap<>() {
        {
            put("wield", "animation.crossbow.wield");
            put("wield_first_person_pull", "animation.crossbow.wield_first_person_pull");
        }
    };
    private static final List<String> PULLING_TEXTURES = List.of("crossbow_pulling_0", "crossbow_pulling_1", "crossbow_pulling_2");
    private static final Scripts ATTACHABLE_SCRIPTS = new Scripts();

    static {
        ATTACHABLE_SCRIPTS.preAnimation(new String[] {
            "variable.charge_amount = math.clamp((query.main_hand_item_max_duration - (query.main_hand_item_use_duration - query.frame_alpha + 1.0)) / 10.0, 0.0, 1.0f);"
        });
        ATTACHABLE_SCRIPTS.animate(List.of(
            Map.of("wield", "c.is_first_person"),
            Map.of("wield_first_person_pull", "query.main_hand_item_use_duration > 0.0f && c.is_first_person")
        ));
    }

    public CrossbowPackModule() {
        this.postProcess(this::postProcess);
    }

    private void postProcess(@NotNull PackPostProcessContext<CrossbowPackModule> context) {
        ResourcePack assets = context.javaResourcePack();
        BedrockResourcePack bedrockPack = context.bedrockResourcePack();

        List<CrossbowItem> crossbowItems = context.registryValues(BuiltInRegistries.ITEM).stream()
                .filter(item -> item instanceof CrossbowItem)
                .map(item -> (CrossbowItem) item)
                .toList();

        context.logger().info("Crossbows to convert: " + crossbowItems.size() + " in mod " + context.mod().id());

        for (CrossbowItem crossbowItem : crossbowItems) {
            Identifier crossbowLocation = BuiltInRegistries.ITEM.getKey(crossbowItem);

            Item itemDefinition = assets.item(Key.key(crossbowLocation.getNamespace(), crossbowLocation.getPath()));
            if (itemDefinition == null) {
                context.logger().warn("Crossbow {} has no item definition, skipping", crossbowLocation);
                continue;
            }

            // The model shown when the crossbow is neither drawn nor loaded.
            Key standby = ItemModels.firstReference(itemDefinition.model());
            String standbyOutputLoc = standby == null ? null : getOutputFromModelTexture(context, assets, standby);
            if (standbyOutputLoc == null) {
                context.logger().warn("Crossbow {} has no layer0 texture, skipping", crossbowLocation);
                continue;
            }

            Map<String, String> textures = new HashMap<>() {
                {
                    put("enchanted", "textures/misc/enchanted_item_glint");
                }
            };
            textures.put("default", standbyOutputLoc);

            List<Key> frames = ItemModels.frames(itemDefinition.model());
            for (int frame = 0; frame < frames.size() && frame < PULLING_TEXTURES.size(); frame++) {
                String outputLoc = getOutputFromModelTexture(context, assets, frames.get(frame));
                if (outputLoc == null) {
                    context.logger().warn("Crossbow pulling model {} has no layer0 texture, skipping", frames.get(frame));
                    continue;
                }

                textures.put(PULLING_TEXTURES.get(frame), outputLoc);
            }

            // What a loaded crossbow shows. Both vanilla and mods select these
            // on the charged projectile, naming the cases "arrow" and "rocket".
            putLoadedTexture(context, assets, itemDefinition, textures, "arrow", "crossbow_arrow");
            putLoadedTexture(context, assets, itemDefinition, textures, "rocket", "crossbow_rocket");

            Attachables crossbowAttachable = new Attachables();
            crossbowAttachable.formatVersion("1.10.0");

            Description description = new Description();
            description.identifier(crossbowLocation.toString());
            description.materials(ATTACHABLE_MATERIALS);
            description.geometry(ATTACHABLE_GEOMETRY);
            description.animations(ATTACHABLE_ANIMATIONS);
            description.scripts(ATTACHABLE_SCRIPTS);
            description.renderControllers(new String[] {"controller.render.crossbow_custom"});
            description.textures(textures);

            Attachable attachable = new Attachable();
            attachable.description(description);
            crossbowAttachable.attachable(attachable);

            bedrockPack.addAttachable(crossbowAttachable, "attachables/" + crossbowLocation.getPath() + ".json");
        }

        RenderControllers renderController = new RenderControllers();
        renderController.formatVersion("1.10.0");

        org.geysermc.pack.bedrock.resource.render_controllers.rendercontrollers.RenderControllers crossbowCustomRenderController =
                new org.geysermc.pack.bedrock.resource.render_controllers.rendercontrollers.RenderControllers();
        crossbowCustomRenderController.arrays(new Arrays());

        crossbowCustomRenderController.arrays().textures().put("array.crossbow_texture_frames", new String[] {
                "texture.default",
                "texture.crossbow_pulling_0",
                "texture.crossbow_pulling_1",
                "texture.crossbow_pulling_2",
                "texture.crossbow_arrow",
                "texture.crossbow_rocket"
        });

        crossbowCustomRenderController.arrays().geometries().put("array.crossbow_geo_frames", new String[] {
                "geometry.default",
                "geometry.crossbow_pulling_0",
                "geometry.crossbow_pulling_1",
                "geometry.crossbow_pulling_2",
                "geometry.crossbow_arrow",
                "geometry.crossbow_rocket"
        });

        crossbowCustomRenderController.geometry("array.crossbow_geo_frames[query.get_animation_frame]");
        crossbowCustomRenderController.materials().add(Map.of("*", "variable.is_enchanted ? material.enchanted : material.default"));
        crossbowCustomRenderController.textures(new String[] {
                "array.crossbow_texture_frames[query.get_animation_frame]",
                "texture.enchanted"
        });

        renderController.renderControllers().put("controller.render.crossbow_custom", crossbowCustomRenderController);
        bedrockPack.addRenderController(renderController, "render_controllers/crossbow_custom.render_controllers.json");
    }

    private void putLoadedTexture(
            @NotNull PackPostProcessContext<CrossbowPackModule> context,
            @NotNull ResourcePack assets,
            @NotNull Item itemDefinition,
            @NotNull Map<String, String> textures,
            @NotNull String when,
            @NotNull String textureName
    ) {
        Key model = ItemModels.selected(itemDefinition.model(), when);
        if (model == null) {
            return;
        }

        String outputLoc = getOutputFromModelTexture(context, assets, model);
        if (outputLoc != null) {
            textures.put(textureName, outputLoc);
        }
    }

    @Override
    public boolean test(@NotNull PackPostProcessContext<CrossbowPackModule> context) {
        return context.registryValues(BuiltInRegistries.ITEM).stream().anyMatch(item -> item instanceof CrossbowItem);
    }
}
