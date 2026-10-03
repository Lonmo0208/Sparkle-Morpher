package com.micaftic.morpher.client.animation.condition;

import com.micaftic.morpher.util.EquipmentUtil;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import it.unimi.dsi.fastutil.objects.ReferenceArrayList;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.UseAnim;
import org.apache.commons.lang3.StringUtils;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

public class ConditionHold {

    private static final String EMPTY_MAINHAND = "hold_mainhand:empty";

    private static final String EMPTY_OFFHAND = "hold_offhand:empty";

    private static final String EMPTY = "";

    private final int preSize;

    private final String idPre;

    private final String tagPre;

    private final String extraPre;

    private final ObjectOpenHashSet<ResourceLocation> idTest = new ObjectOpenHashSet<>();

    /** 声明名（如 {@code hold_offhand$minecraft:mace}）——播放时必须用作者写下的原名字。 */
    private final Object2ObjectOpenHashMap<ResourceLocation, String> idNames = new Object2ObjectOpenHashMap<>();

    private final ReferenceArrayList<TagKey<Item>> tagTest = new ReferenceArrayList<>();

    private final Object2ObjectOpenHashMap<TagKey<Item>, String> tagNames = new Object2ObjectOpenHashMap<>();

    private final ReferenceOpenHashSet<UseAnim> extraTes = new ReferenceOpenHashSet<>();

    private final ObjectOpenHashSet<String> innerTest = new ObjectOpenHashSet<>();

    public ConditionHold(InteractionHand hand) {
        if (hand == InteractionHand.MAIN_HAND) {
            this.idPre = "hold_mainhand$";
            this.tagPre = "hold_mainhand#";
            this.extraPre = "hold_mainhand:";
            this.preSize = 14;
        } else {
            this.idPre = "hold_offhand$";
            this.tagPre = "hold_offhand#";
            this.extraPre = "hold_offhand:";
            this.preSize = 13;
        }
    }

    public void addTest(String name) {
        if (name.length() <= this.preSize) {
            return;
        }
        String strSubstring = name.substring(this.preSize);
        // 物品/标签按 "命名空间:路径" 声明（如 hold_offhand$minecraft:mace / hold_offhand#c:tools）。
        // 这里不能用 isValidPath 校验：路径模式不含冒号，会把所有带命名空间的声明判为非法，
        // 导致"指定物品"动画（内置模型 hold_offhand$immersive_melodies:trumpet 等）永远注册不上。
        if (name.startsWith(this.idPre)) {
            ResourceLocation id = ResourceLocation.tryParse(strSubstring);
            if (id != null) {
                this.idTest.add(id);
                this.idNames.put(id, name);
            }
        }
        if (name.startsWith(this.tagPre)) {
            ResourceLocation tag = ResourceLocation.tryParse(strSubstring);
            if (tag != null) {
                TagKey<Item> key = TagKey.create(Registries.ITEM, tag);
                this.tagTest.add(key);
                this.tagNames.put(key, name);
            }
        }
        if (!name.startsWith(this.extraPre) || strSubstring.equals(UseAnim.NONE.name().toLowerCase(Locale.US))) {
            return;
        }
        Optional<UseAnim> optional = EquipmentUtil.getUseAnimByName(strSubstring);
        Objects.requireNonNull(this.extraTes);
        optional.ifPresent(extraTes::add);
        this.innerTest.add(name);
    }

    public String doTest(LivingEntity entity, InteractionHand hand) {
        if (entity.getItemInHand(hand).isEmpty()) {
            return hand == InteractionHand.MAIN_HAND ? EMPTY_MAINHAND : EMPTY_OFFHAND;
        }
        String result = doIdTest(entity, hand);
        if (result.isEmpty()) {
            result = doTagTest(entity, hand);
            if (result.isEmpty()) {
                return doExtraTest(entity, hand);
            }
            return result;
        }
        return result;
    }

    private String doIdTest(LivingEntity livingEntity, InteractionHand interactionHand) {
        if (this.idTest.isEmpty()) {
            return EMPTY;
        }
        ResourceLocation key = BuiltInRegistries.ITEM.getKey(livingEntity.getItemInHand(interactionHand).getItem());
        if (key != null && this.idTest.contains(key)) {
            return this.idNames.getOrDefault(key, this.idPre + key);
        }
        return EMPTY;
    }

    private String doTagTest(LivingEntity livingEntity, InteractionHand interactionHand) {
        if (this.tagTest.isEmpty()) {
            return EMPTY;
        }
        ItemStack itemInHand = livingEntity.getItemInHand(interactionHand);
        Stream<TagKey<Item>> stream = this.tagTest.stream();
        Objects.requireNonNull(itemInHand);
        return stream.filter(itemInHand::is).findFirst()
                .map(tagKey -> this.tagNames.getOrDefault(tagKey, this.tagPre + tagKey.location())).orElse("");
    }

    private String doExtraTest(LivingEntity entity, InteractionHand hand) {
        if (this.extraTes.isEmpty() && this.innerTest.isEmpty()) {
            return EMPTY;
        }
        String innerName = InnerClassify.doClassifyTest(this.extraPre, entity, hand);
        if (StringUtils.isNotBlank(innerName) && this.innerTest.contains(innerName)) {
            return innerName;
        }
        UseAnim anim = entity.getItemInHand(hand).getUseAnimation();
        if (this.extraTes.contains(anim)) {
            return this.extraPre + anim.name().toLowerCase(Locale.US);
        }
        return EMPTY;
    }
}