package me.erykczy.colorfullighting.client;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.neoforged.fml.ModList;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;

/** Registry-backed, UI-only families. No family relationship is written to config. */
public final class LightFamilyIndex {
    public static final ResourceLocation BURNING_ID = ResourceLocation.fromNamespaceAndPath(
            "colorful_lighting", "burning_entity"
    );

    private LightFamilyIndex() {
    }

    public enum TargetKind {
        BLOCK,
        ITEM,
        ENTITY,
        BURNING
    }

    public record Target(
            TargetKind kind,
            ResourceLocation id,
            String localizedName,
            String modName,
            String searchable
    ) {
    }

    public record Family(
            ResourceLocation representativeId,
            String localizedName,
            String modName,
            List<Target> members,
            String searchable,
            String modSearchable
    ) {
        public Family {
            members = List.copyOf(members);
        }

        public boolean hasIndividualSettings() {
            return members.size() > 1;
        }
    }

    public static List<Family> blockAndItemFamilies() {
        IdentityHashMap<Item, List<Block>> byItem = IdentityFamilyGrouping.group(
                BuiltInRegistries.BLOCK,
                Block::asItem,
                item -> item != Items.AIR
        );
        ArrayList<Block> itemlessBlocks = new ArrayList<>();
        for(Block block : BuiltInRegistries.BLOCK) {
            Item item = block.asItem();
            if(item == Items.AIR) itemlessBlocks.add(block);
        }

        ArrayList<Family> families = new ArrayList<>();
        for(Item item : BuiltInRegistries.ITEM) {
            if(item == Items.AIR) continue;
            ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(item);
            if(itemId == null) continue;
            String name = new ItemStack(item).getHoverName().getString();
            ArrayList<Target> members = new ArrayList<>();
            members.add(target(TargetKind.ITEM, itemId, name));
            for(Block block : byItem.getOrDefault(item, List.of())) {
                ResourceLocation blockId = BuiltInRegistries.BLOCK.getKey(block);
                if(blockId != null)
                    members.add(target(TargetKind.BLOCK, blockId, block.getName().getString()));
            }
            families.add(family(itemId, name, members));
        }

        for(Block block : itemlessBlocks) {
            ResourceLocation blockId = BuiltInRegistries.BLOCK.getKey(block);
            if(blockId == null) continue;
            String name = block.getName().getString();
            families.add(family(
                    blockId,
                    name,
                    List.of(target(TargetKind.BLOCK, blockId, name))
            ));
        }
        return sorted(families);
    }

    public static List<Family> entityFamilies() {
        ArrayList<Family> families = new ArrayList<>();
        String burningName = Component.translatable("colorful_lighting.config.burning").getString();
        Target burning = target(TargetKind.BURNING, BURNING_ID, burningName);
        families.add(family(BURNING_ID, burningName, List.of(burning)));

        for(EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
            ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
            if(id == null) continue;
            String name = type.getDescription().getString();
            Target target = target(TargetKind.ENTITY, id, name);
            families.add(family(id, name, List.of(target)));
        }
        return sorted(families);
    }

    private static Target target(TargetKind kind, ResourceLocation id, String name) {
        String modName = modName(id.getNamespace());
        String searchable = (name + " " + id + " " + modName).toLowerCase(Locale.ROOT);
        return new Target(kind, id, name, modName, searchable);
    }

    private static Family family(ResourceLocation id, String name, List<Target> members) {
        String modName = modName(id.getNamespace());
        StringBuilder searchable = new StringBuilder(name).append(' ').append(id).append(' ').append(modName);
        StringBuilder modSearchable = new StringBuilder(id.getNamespace()).append(' ').append(modName);
        for(Target member : members) {
            searchable.append(' ').append(member.searchable());
            modSearchable.append(' ').append(member.id().getNamespace()).append(' ').append(member.modName());
        }
        return new Family(
                id,
                name,
                modName,
                members,
                searchable.toString().toLowerCase(Locale.ROOT),
                modSearchable.toString().toLowerCase(Locale.ROOT)
        );
    }

    private static List<Family> sorted(List<Family> families) {
        families.sort(Comparator
                .comparing(Family::localizedName, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(family -> family.representativeId().toString()));
        return List.copyOf(families);
    }

    private static String modName(String namespace) {
        if(namespace.equals("minecraft")) return "Minecraft";
        return ModList.get().getModContainerById(namespace)
                .map(container -> container.getModInfo().getDisplayName())
                .orElse(namespace);
    }
}
