package com.micaftic.morpher.client.animation.condition;

import com.micaftic.morpher.core.compat.touhoulittlemaid.TouhouLittleMaidCompat;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.world.entity.Entity;

public class ConditionChair {

    private static final String EMPTY = "";

    private final ObjectOpenHashSet<String> idTest = new ObjectOpenHashSet<>();

    private final String idPre;

    public ConditionChair() {
        this.idPre = "chair$";
    }

    public void addTest(String name) {
        int preSize = this.idPre.length();
        if (name.length() <= preSize) {
            return;
        }
        String strSubstring = name.substring(preSize);
        // 椅子/坐骑 id 形如 chair$touhou_little_maid:moto（带命名空间）：
        // isValidPath 会把冒号判为非法，导致这类动画永远注册不上。
        if (name.startsWith(this.idPre) && !strSubstring.isBlank()) {
            this.idTest.add(strSubstring);
        }
    }

    public String doTest(Entity entity) {
        Entity vehicle = entity.getVehicle();
        if (TouhouLittleMaidCompat.isSimplePlanesEntity(vehicle)) {
            return doIdTest(vehicle);
        }
        return EMPTY;
    }

    private String doIdTest(Entity entity) {
        if (this.idTest.isEmpty()) {
            return EMPTY;
        }
        String modelId = TouhouLittleMaidCompat.getMaidEntityId(entity);
        if (this.idTest.contains(modelId)) {
            return this.idPre + modelId;
        }
        return EMPTY;
    }
}