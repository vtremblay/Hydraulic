package org.geysermc.hydraulic.item;

import com.google.auto.service.AutoService;
import net.kyori.adventure.key.Key;
import net.minecraft.core.DefaultedRegistry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.SmithingTransformRecipe;
import net.minecraft.world.item.*;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import org.geysermc.geyser.api.event.lifecycle.GeyserDefineCustomItemsEvent;
import org.geysermc.geyser.api.item.custom.v2.CustomItemBedrockOptions;
import org.geysermc.geyser.api.item.custom.v2.CustomItemDefinition;
import org.geysermc.geyser.api.item.custom.v2.NonVanillaCustomItemDefinition;
import org.geysermc.geyser.api.item.custom.v2.component.geyser.GeyserBlockPlacer;
import org.geysermc.geyser.api.item.custom.v2.component.geyser.GeyserChargeable;
import org.geysermc.geyser.api.item.custom.v2.component.geyser.GeyserItemDataComponents;
import org.geysermc.hydraulic.Constants;
import org.geysermc.hydraulic.HydraulicImpl;
import org.geysermc.hydraulic.pack.PackLogListener;
import org.geysermc.hydraulic.pack.PackModule;
import org.geysermc.hydraulic.pack.TexturePackModule;
import org.geysermc.hydraulic.pack.context.PackEventContext;
import org.geysermc.hydraulic.pack.context.PackPostProcessContext;
import org.geysermc.hydraulic.pack.context.PackPreProcessContext;
import org.geysermc.hydraulic.component.ComponentConverter;
import org.geysermc.hydraulic.util.HydraulicKey;
import org.geysermc.hydraulic.util.PackUtil;
import org.geysermc.pack.bedrock.resource.BedrockResourcePack;
import org.geysermc.pack.converter.type.model.ModelStitcher;
import org.jetbrains.annotations.NotNull;
import org.geysermc.pack.converter.util.JsonMappings;
import team.unnamed.creative.ResourcePack;
import team.unnamed.creative.item.*;
import team.unnamed.creative.model.Model;
import team.unnamed.creative.model.ModelTexture;

import java.util.*;

@AutoService(PackModule.class)
public class ItemPackModule extends TexturePackModule<ItemPackModule> {
    private final Set<Identifier> itemsWith2dIcon = new LinkedHashSet<>();
    private final Set<Identifier> handheldItems = new LinkedHashSet<>();
    private final Map<String, String> itemBuiltinTexture = new HashMap<>();

    /**
     * Items we managed to read an item definition for (assets/&lt;namespace&gt;/items/&lt;name&gt;.json).
     * An item missing from this set had no readable definition, so {@link #handleModel} never ran
     * for it and it would otherwise be registered with no Bedrock icon at all.
     */
    private final Set<Identifier> itemsWithDefinition = new LinkedHashSet<>();

    public ItemPackModule() {
        this.listenOn(GeyserDefineCustomItemsEvent.class, this::onDefineCustomItems);

        this.preProcess(this::preProcess);
        this.postProcess(this::postProcess);
    }

    private void handleModel(@NotNull PackPreProcessContext<ItemPackModule> context, ItemModel itemModel, Identifier itemLocation) {
        if (itemModel instanceof ReferenceItemModel referenceModel) {
            Key modelKey = referenceModel.model();
            Model model = context.modelProvider().model(modelKey);
            if (model == null) {
                context.logger().debug("Could not resolve model {} for item {}", modelKey, itemLocation);
                return;
            }

            markIf2d(context, model, itemLocation);
        } else if (itemModel instanceof SelectItemModel selectModel) { // See if we can actually do select models here
            handleModel(context, selectModel.fallback(), itemLocation);
        } else if (itemModel instanceof ConditionItemModel conditionModel) {
            handleModel(context, conditionModel.onTrue(), itemLocation);
        } else if (itemModel instanceof CompositeItemModel compositeModel) { // TODO: See if we can stitch together item models, for now this will use just the first model
            List<ItemModel> models = compositeModel.models();
            if (!models.isEmpty()) {
                handleModel(context, models.getFirst(), itemLocation);
            }
        } else if (itemModel instanceof RangeDispatchItemModel rangeDispatchModel) {
            handleModel(context, rangeDispatchModel.fallback(), itemLocation);
        }
    }

    /**
     * Marks an item as having a flat Bedrock icon if its model is one of the 2D vanilla
     * archetypes, and returns whether it did. Shared by the item-definition path and the
     * model-file fallback below so both decide the same way.
     */
    private boolean markIf2d(@NotNull PackPreProcessContext<ItemPackModule> context, Model model, Identifier itemLocation) {
        List<Key> parents = PackUtil.modelParents(context.modelProvider(), model);

        if (parents.contains(Model.ITEM_HANDHELD)) {
            itemsWith2dIcon.add(itemLocation); // item/handheld has the parent item/generated, so lets assume it's 2D
            handheldItems.add(itemLocation);
            return true;
        } else if (parents.contains(Model.ITEM_GENERATED) || parents.contains(Model.BUILT_IN_GENERATED)) {
            itemsWith2dIcon.add(itemLocation);
            return true;
        }

        return false;
    }

    private void preProcess(@NotNull PackPreProcessContext<ItemPackModule> context) {
        int iconsBefore = itemsWith2dIcon.size();
        int recovered = 0;

        for (team.unnamed.creative.item.Item item : context.assets(ResourcePack::items)) {
            Identifier itemLocation = HydraulicKey.of(item.key()).identifier();
            itemsWithDefinition.add(itemLocation);
            handleModel(context, item.model(), itemLocation);
        }

        List<Item> items = context.registryValues(BuiltInRegistries.ITEM);
        PackLogListener packLogListener = new PackLogListener(context.logger());
        for (Item item : items) {
            Identifier itemLocation = BuiltInRegistries.ITEM.getKey(item);

            Model baseModel = context.modelProvider().model(Key.key(itemLocation.getNamespace(), "item/" + itemLocation.getPath()));
            if (baseModel == null) {
                continue;
            }

            Model model = new ModelStitcher(context.modelProvider(), baseModel, packLogListener).stitch();
            if (model == null) {
                continue;
            }

            List<ModelTexture> layers = model.textures().layers();
            if (layers == null || layers.isEmpty()) {
                continue;
            }

            Key layer0 = layers.getFirst().key();

            if (layer0 != null && layer0.namespace().equals(Key.MINECRAFT_NAMESPACE)) {
                itemBuiltinTexture.put(itemLocation.toString(), PackUtil.getTextureName(layer0.toString()));
            }

            // No item definition could be read for this item, so nothing has decided whether it
            // gets a Bedrock icon, and it would be registered without one - Bedrock then draws
            // nothing at all. It does have a flat model file with a layer0 texture, which is
            // what the item definition's own fallback almost always points at, so use that.
            //
            // The common cause is a modded `special` render type or tint source: the pack
            // library rejects any such type outside the minecraft namespace before it looks it
            // up, and the whole item definition is lost with it. A flat icon is much closer to
            // correct than no item at all.
            if (!itemsWithDefinition.contains(itemLocation) && !itemsWith2dIcon.contains(itemLocation)
                    && markIf2d(context, baseModel, itemLocation)) {
                context.logger().debug("No item definition for {}, falling back to a flat icon from its model file", itemLocation);
                recovered++;
            }
        }

        context.logger().info("2D item icons: {} in mod {}, {} recovered from an item's model file where its item definition could not be read",
            itemsWith2dIcon.size() - iconsBefore, context.mod().id(), recovered);
    }

    private void postProcess(@NotNull PackPostProcessContext<ItemPackModule> context) {
        ResourcePack assets = context.javaResourcePack();
        BedrockResourcePack bedrockPack = context.bedrockResourcePack();

        List<Item> items = context.registryValues(BuiltInRegistries.ITEM);

        context.logger().info("Items to convert: {} in mod {}", items.size(), context.mod().id());

        PackLogListener packLogListener = new PackLogListener(context.logger());
        for (Item item : items) {
            Identifier itemLocation = BuiltInRegistries.ITEM.getKey(item);

            Model baseModel = assets.model(Key.key(itemLocation.getNamespace(), "item/" + itemLocation.getPath()));
            if (baseModel == null) {
                context.logger().warn("Item {} has no item model, skipping", itemLocation);
                continue;
            }

            Model model = new ModelStitcher(context.modelProvider(), baseModel, packLogListener).stitch();

            List<ModelTexture> layers = model.textures().layers();
            if (layers == null || layers.isEmpty()) {
                // Don't warn if a block as they can use the block model
                if (!(item instanceof BlockItem)) {
                    context.logger().warn("Item {} has no layer0 texture, skipping", itemLocation);
                }

                continue;
            }

            ModelTexture layer0 = layers.getFirst();
            String outputLoc = getOutputFromModel(context, layer0.key()); // TODO: sort this out, layer0.key() can be null, but the method we use doesn't want that
            bedrockPack.addItemTexture(itemLocation.toString(), outputLoc.replace(".png", ""));
        }
    }

    @Override
    public boolean test(@NotNull PackPostProcessContext<ItemPackModule> context) {
        return !context.registryValues(BuiltInRegistries.ITEM).isEmpty();
    }

    private void onDefineCustomItems(PackEventContext<GeyserDefineCustomItemsEvent, ItemPackModule> context) {
        GeyserDefineCustomItemsEvent event = context.event();
        List<Item> items = context.registryValues(BuiltInRegistries.ITEM);

        // Which Bedrock smithing tag each item needs, derived from the server's own recipes.
        Map<Identifier, Set<String>> smithingTags = smithingSlotTags();

        DefaultedRegistry<Item> registry = BuiltInRegistries.ITEM;
        for (Item item : items) {
            Identifier itemLocation = registry.getKey(item);

            try {
                NonVanillaCustomItemDefinition.Builder customItemDefinition = NonVanillaCustomItemDefinition.builder(
                        org.geysermc.geyser.api.util.Identifier.of(itemLocation.toString()),
                        org.geysermc.geyser.api.util.Identifier.of(itemLocation.toString()),
                        registry.getId(item)
                )
                        .displayName("%" + item.getDescriptionId());

                CustomItemBedrockOptions.Builder customItemOptions = CustomItemBedrockOptions.builder()
                        .allowOffhand(true);

                // A smithing table decides what each slot accepts by item tag, not by recipe: an
                // untagged item cannot even be dropped into the slot, so a modded smithing recipe
                // is unusable however well it is translated. A mod's item is already a component
                // item on Bedrock, so tagging it here is enough.
                Set<String> slotTags = smithingTags.get(itemLocation);
                if (slotTags != null && !slotTags.isEmpty()) {
                    Set<org.geysermc.geyser.api.util.Identifier> tags = new LinkedHashSet<>();
                    for (String tag : slotTags) {
                        tags.add(org.geysermc.geyser.api.util.Identifier.of(tag));
                    }
                    customItemOptions.tags(tags);
                    context.logger().info("Tagging {} for the smithing table: {}", itemLocation, slotTags);
                }

                // Allow minecraft namespace texture to be used (remapped as hydraulic)
                if (itemBuiltinTexture.containsKey(itemLocation.toString())) {
                    customItemOptions.icon(itemBuiltinTexture.get(itemLocation.toString()));
                }

                // Add the icon if it should have an icon
                boolean is2d = itemsWith2dIcon.contains(itemLocation);
                if (is2d) {
                    customItemOptions.icon(itemLocation.toString());
                }

                // Make it handheld if need be
                if (handheldItems.contains(itemLocation)) {
                    customItemOptions.displayHandheld(true);
                }

                // Set the creative mappings
                CreativeMappings.setup(item, customItemOptions);

                // Set all bedrock components using what java components we have
                ComponentConverter.setGeyserComponents(
                        item.components(),
                        customItemDefinition,
                        customItemOptions
                );

                // Set the needed component for bows to work correctly
                if (item instanceof BowItem) {
                    customItemDefinition.component(
                            GeyserItemDataComponents.CHARGEABLE,
                            GeyserChargeable.builder()
                                    .maxDrawDuration(1f)
                                    .chargeOnDraw(false)
                    );

                    // Include the default icon, this won't change in the hotbar when used but this works the best for now
                    customItemOptions.icon(itemLocation.toString());
                }

                // Set the needed component for crossbows to work correctly
                if (item instanceof CrossbowItem) {
                    customItemDefinition.component(
                            GeyserItemDataComponents.CHARGEABLE,
                            GeyserChargeable.builder()
                                    .maxDrawDuration(0f)
                                    .chargeOnDraw(true)
                    );

                    // Include the default icon, this won't change in the hotbar when used but this works the best for now
                    customItemOptions.icon(itemLocation.toString());
                }

                if (item instanceof BlockItem blockItem) {
                    // Set the block_placer component to the correct block
                    // This fixes animations sometimes not showing
                    Block block = blockItem.getBlock();

                    customItemDefinition.component(
                            GeyserItemDataComponents.BLOCK_PLACER,
                            GeyserBlockPlacer.of(HydraulicKey.of(BuiltInRegistries.BLOCK.getKey(block)), !is2d)
                    );

                    CreativeMappings.setupBlock(block, customItemOptions);
                }

                customItemDefinition.bedrockOptions(customItemOptions);

                event.register(customItemDefinition.build());
            } catch (Exception e) {
                context.logger().error("Unable to register {}:", itemLocation, e);
            }
        }

        registerSmithingBases(context, event, smithingTags);
    }

    /**
     * Lets vanilla gear be used as the base of a modded smithing recipe.
     * <p>
     * A smithing table decides what each slot accepts by item tag: the base slot wants
     * {@code minecraft:transformable_items}, which on Bedrock only diamond-tier gear carries. A
     * recipe that upgrades netherite gear is therefore unusable there -- the item cannot even be
     * dropped into the slot, whatever the recipe says.
     * <p>
     * The tag cannot simply be sent: the client picks an item's class from its identifier, and for
     * a vanilla one it builds a code-defined class whose network initialisation never reads
     * {@code item_tags}. Sending the tag to a vanilla identifier is silently discarded -- verified
     * on a Bedrock client, where a netherite sword resent with the tag was still refused.
     * <p>
     * Registering the item under a new Bedrock identifier avoids that: an identifier the client
     * does not know is built as a component item, which does read {@code item_tags}.
     */
    private void registerSmithingBases(PackEventContext<GeyserDefineCustomItemsEvent, ItemPackModule> context,
                                       GeyserDefineCustomItemsEvent event,
                                       Map<Identifier, Set<String>> smithingTags) {
        for (Map.Entry<Identifier, Set<String>> entry : smithingTags.entrySet()) {
            Identifier itemLocation = entry.getKey();

            // Only vanilla items need standing in. A mod's own item is already a component item
            // client side, so it can simply be tagged where it is registered.
            if (!itemLocation.getNamespace().equals("minecraft")) {
                continue;
            }

            // And only when Bedrock's own copy lacks the tag the slot wants. Diamond gear, the
            // netherite ingot and the netherite upgrade template already carry theirs, so standing
            // them in would swap a working vanilla item for a replica for no gain -- and a replica
            // loses anything Geyser keys on the vanilla identity, such as the upgrade template it
            // matches trim recipes against.
            if (BEDROCK_ALREADY_TAGGED.getOrDefault(itemLocation.getPath(), Set.of()).containsAll(entry.getValue())) {
                continue;
            }

            // Some vanilla items cannot be stood in for at all, because the Bedrock client
            // recognises them by identifier rather than by component. An elytra is the proven
            // case: every "is this an elytra" test in the client is a comparison against the
            // literal "minecraft:elytra", and its back rendering comes from an attachable keyed
            // the same way, so a replica neither glides nor draws. A shield's blocking is a
            // client-side gesture on a code-defined class, so it gets the same treatment. The
            // rest are the items Geyser itself looks up by vanilla identity.
            if (NEVER_STAND_IN.contains(itemLocation.getPath())) {
                continue;
            }

            org.geysermc.geyser.api.util.Identifier javaItem =
                org.geysermc.geyser.api.util.Identifier.of(itemLocation.toString());

            // The icon must be a key this pack actually registers in item_texture.json. A key
            // nothing registered does not fall back to anything -- the item draws as an EMPTY
            // SLOT. Verified on a Bedrock client: a shulker box, a bow and a crossbow given to a
            // player were present in the server's inventory dump and invisible on screen, while
            // a netherite sword in the next slot drew correctly. The difference was exactly
            // whether the icon key existed.
            //
            // So there is no guessed fallback here. itemBuiltinTexture only holds items whose
            // flat model resolved to a real layer0 texture, which is the same set that ends up
            // in the atlas. An item without one has its Bedrock appearance from somewhere this
            // stand-in cannot reach -- a block model for the shulker boxes, predicate-selected
            // frames for the bow and crossbow -- and standing in would replace something that
            // works with something invisible. Leaving it alone costs only that one recipe.
            String icon = itemBuiltinTexture.get(itemLocation.toString());
            if (icon == null || !icon.contains(":")) {
                icon = Constants.MOD_ID + ":item/" + itemLocation.getPath();
            }
            if (!registeredItemTextures().contains(icon)) {
                context.logger().debug("No smithing stand-in for {}: {} is not a registered icon", javaItem, icon);
                continue;
            }

            Set<org.geysermc.geyser.api.util.Identifier> tags = new LinkedHashSet<>();
            for (String tag : entry.getValue()) {
                tags.add(org.geysermc.geyser.api.util.Identifier.of(tag));
            }

            try {
                CustomItemDefinition definition = CustomItemDefinition
                    .builder(org.geysermc.geyser.api.util.Identifier.of(
                        Constants.MOD_ID + ":" + itemLocation.getPath() + "_smithing"), javaItem)
                    .displayName("%item.minecraft." + itemLocation.getPath())
                    .bedrockOptions(CustomItemBedrockOptions.builder()
                        .icon(icon)
                        .allowOffhand(true)
                        // Without this a tool or weapon renders flat in the hand rather than
                        // held like a tool; Geyser defaults it to false for custom items.
                        .displayHandheld(handheldItems.contains(itemLocation) || BEDROCK_HANDHELD.contains(itemLocation.getPath()))
                        .tags(tags))
                    .build();

                event.register(javaItem, definition);
                context.logger().info("Smithing stand-in for {} with tags {}", javaItem, entry.getValue());
            } catch (Exception e) {
                context.logger().debug("Not registering a smithing stand-in for {}: {}", javaItem, e.getMessage());
            }
        }
    }

    /**
     * Works out which Bedrock smithing tag each item needs, from the part it plays in the
     * server's own smithing recipes. Derived rather than listed, so any mod's recipe is covered.
     */

    /**
     * Which of the smithing slot tags Bedrock's own copy of an item already carries, dumped from
     * a running Bedrock server. An item listed here needs no stand-in for those tags.
     */
    private static final Map<String, Set<String>> BEDROCK_ALREADY_TAGGED = Map.ofEntries(
        Map.entry("diamond_axe", Set.of("minecraft:transformable_items")),
        Map.entry("diamond_boots", Set.of("minecraft:transformable_items")),
        Map.entry("diamond_chestplate", Set.of("minecraft:transformable_items")),
        Map.entry("diamond_helmet", Set.of("minecraft:transformable_items")),
        Map.entry("diamond_hoe", Set.of("minecraft:transformable_items")),
        Map.entry("diamond_horse_armor", Set.of("minecraft:transformable_items")),
        Map.entry("diamond_leggings", Set.of("minecraft:transformable_items")),
        Map.entry("diamond_nautilus_armor", Set.of("minecraft:transformable_items")),
        Map.entry("diamond_pickaxe", Set.of("minecraft:transformable_items")),
        Map.entry("diamond_shovel", Set.of("minecraft:transformable_items")),
        Map.entry("diamond_spear", Set.of("minecraft:transformable_items")),
        Map.entry("diamond_sword", Set.of("minecraft:transformable_items")),
        Map.entry("golden_boots", Set.of("minecraft:transformable_items")),
        Map.entry("netherite_ingot", Set.of("minecraft:transform_materials")),
        Map.entry("netherite_upgrade_smithing_template", Set.of("minecraft:transform_templates"))
    );

    /**
     * Every icon key this pack registers in {@code item_texture.json}, which is the same set
     * {@link org.geysermc.hydraulic.pack.modules.HydraulicPackModule} publishes from the
     * converter's texture mappings. Built once, since the mappings are static.
     */
    private static Set<String> registeredItemTextures() {
        Set<String> cached = registeredItemTextures;
        if (cached == null) {
            cached = new HashSet<>();
            for (Map.Entry<String, List<String>> entry : JsonMappings.getMapping("textures").entrySet()) {
                if (entry.getKey().startsWith("item")) {
                    for (String name : entry.getValue()) {
                        cached.add(Constants.MOD_ID + ":" + name);
                    }
                }
            }
            registeredItemTextures = cached;
        }
        return cached;
    }

    private static volatile Set<String> registeredItemTextures;

    /**
     * Vanilla items that must keep their own Bedrock identity, whatever a recipe wants. The
     * client resolves these by identifier, so a stand-in silently loses the behaviour.
     */
    private static final Set<String> NEVER_STAND_IN = Set.of(
        // Identifier-bound: gliding and the back attachable both key off "minecraft:elytra".
        "elytra",
        // Blocking is a client-side gesture on a code-defined class.
        "shield",
        // Items Geyser resolves by vanilla identity (StoredItemMappings).
        "barrier", "compass", "glass_bottle", "milk_bucket",
        "netherite_upgrade_smithing_template", "powder_snow_bucket",
        "totem_of_undying", "wheat", "writable_book", "written_book"
    );

    /**
     * Items Bedrock itself treats as tools or weapons, from the same dump. A stand-in for one of
     * these has to say so, or it renders flat in the hand instead of being held like a tool.
     */
    private static final Set<String> BEDROCK_HANDHELD = Set.of(
        "copper_axe",
        "copper_hoe",
        "copper_pickaxe",
        "copper_shovel",
        "copper_sword",
        "diamond_axe",
        "diamond_hoe",
        "diamond_pickaxe",
        "diamond_shovel",
        "diamond_sword",
        "golden_axe",
        "golden_hoe",
        "golden_pickaxe",
        "golden_shovel",
        "golden_sword",
        "iron_axe",
        "iron_hoe",
        "iron_pickaxe",
        "iron_shovel",
        "iron_sword",
        "mace",
        "netherite_axe",
        "netherite_hoe",
        "netherite_pickaxe",
        "netherite_shovel",
        "netherite_sword",
        "stone_axe",
        "stone_hoe",
        "stone_pickaxe",
        "stone_shovel",
        "stone_sword",
        "wooden_axe",
        "wooden_hoe",
        "wooden_pickaxe",
        "wooden_shovel",
        "wooden_sword"
    );

    private Map<Identifier, Set<String>> smithingSlotTags() {
        Map<Identifier, Set<String>> tags = new LinkedHashMap<>();

        MinecraftServer server = HydraulicImpl.instance().server();
        if (server == null) {
            return tags;
        }

        for (RecipeHolder<?> holder : server.getRecipeManager().getRecipes()) {
            if (!(holder.value() instanceof SmithingTransformRecipe recipe)) {
                continue;
            }

            recipe.templateIngredient().ifPresent(i -> addSmithingTag(tags, i, "minecraft:transform_templates"));
            addSmithingTag(tags, recipe.baseIngredient(), "minecraft:transformable_items");
            recipe.additionIngredient().ifPresent(i -> addSmithingTag(tags, i, "minecraft:transform_materials"));
        }

        return tags;
    }

    private void addSmithingTag(Map<Identifier, Set<String>> tags, Ingredient ingredient, String tag) {
        ingredient.items().forEach(holder -> {
            Identifier key = BuiltInRegistries.ITEM.getKey(holder.value());
            if (key != null) {
                tags.computeIfAbsent(key, k -> new LinkedHashSet<>()).add(tag);
            }
        });
    }
}
