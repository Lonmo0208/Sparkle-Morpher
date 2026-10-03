package com.micaftic.morpher.client.animation.condition;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import it.unimi.dsi.fastutil.objects.ReferenceArrayList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;

public class ConditionPassenger {

    private static final String EMPTY = "";

    private final ObjectOpenHashSet<ResourceLocation> idTest = new ObjectOpenHashSet<>();

    /** 声明名（如 {@code passenger$touhou_little_maid:maid}）——播放时必须用作者写下的原名字。 */
    private final Object2ObjectOpenHashMap<ResourceLocation, String> idNames = new Object2ObjectOpenHashMap<>();

    private final ReferenceArrayList<TagKey<EntityType<?>>> tagTest = new ReferenceArrayList<>();

    private final Object2ObjectOpenHashMap<TagKey<EntityType<?>>, String> tagNames = new Object2ObjectOpenHashMap<>();

    private final String idPre;
    private final String tagPre;

    public ConditionPassenger() {
        this.idPre = "passenger$";
        this.tagPre = "passenger#";
    }

    public void doTest(String name) {
        int preSize = this.idPre.length();
        if (name.length() <= preSize) {
            return;
        }
        String strSubstring = name.substring(preSize);
        // 实体类型按 "命名空间:路径" 声明：isValidPath 会把带冒号的声明判为非法，
        // 导致载客动画永远注册不上（详见 ConditionVehicle 同名注释）。
        if (name.startsWith(this.idPre)) {
            ResourceLocation id = ResourceLocation.tryParse(strSubstring);
            if (id != null) {
                this.idTest.add(id);
                this.idNames.put(id, name);
            }
        }
        if (!name.startsWith(this.tagPre)) {
            return;
        }
        ResourceLocation tag = ResourceLocation.tryParse(strSubstring);
        if (tag == null) {
            return;
        }
        TagKey<EntityType<?>> key = TagKey.create(Registries.ENTITY_TYPE, tag);
        this.tagTest.add(key);
        this.tagNames.put(key, name);
    }

    public String doTest(LivingEntity entity) {
        Entity firstPassenger = entity.getFirstPassenger();
        if (firstPassenger == null || !firstPassenger.isAlive()) {
            return EMPTY;
        }
        String result = doIdTest(firstPassenger);
        if (result.isEmpty()) {
            return doTagTest(firstPassenger);
        }
        return result;
    }

    private String doIdTest(Entity entity) {
        ResourceLocation key;
        if (!this.idTest.isEmpty() && (key = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType())) != null && this.idTest.contains(key)) {
            return this.idNames.getOrDefault(key, this.idPre + key);
        }
        return EMPTY;
    }

    private String doTagTest(Entity entity) {
        if (this.tagTest.isEmpty()) {
            return EMPTY;
        }
        return this.tagTest.stream().filter(tagKey -> entity.getType().is(tagKey)).findFirst()
                .map(tagKey2 -> this.tagNames.getOrDefault(tagKey2, this.tagPre + tagKey2.location())).orElse(EMPTY);
    }
}
