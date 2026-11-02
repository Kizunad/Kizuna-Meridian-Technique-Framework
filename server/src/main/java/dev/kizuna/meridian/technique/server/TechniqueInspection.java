package dev.kizuna.meridian.technique.server;

import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueHostFacts;

/** 宿主 inspect 的移动状态与靴子事实校验。 */
final class TechniqueInspection {
    private static final double MAX_BOOT_ARMOR = 1024;
    private static final int MAX_BOOT_DURABILITY = 1_000_000;

    private TechniqueInspection() {
        // inspect 事实必须由宿主采集，本类只负责边界和腿法条件判断。
    }

    static TechniqueHostFacts defaults() {
        // 旧 Java 宿主没有额外事实时保持普通功法兼容，腿法仍会要求显式接入。
        return TechniqueHostFacts.newBuilder()
                .setGrounded(true)
                .setInWater(false)
                .setClimbing(false)
                .setRiding(false)
                .setGliding(false)
                .build();
    }

    static void validate(TechniqueHostFacts facts) {
        // 五项移动状态必须同时出现，靴子两项要么同时出现，要么都表示光脚。
        if (!facts.hasGrounded() || !facts.hasInWater() || !facts.hasClimbing()
                || !facts.hasRiding() || !facts.hasGliding()) {
            throw TechniqueErrors.invalid(
                    "inspect_facts", "着地、水中、攀爬、骑乘和滑翔状态必须显式提供");
        }
        boolean armorProvided = facts.hasBootArmor();
        boolean durabilityProvided = facts.hasBootDurability();
        if (armorProvided != durabilityProvided) {
            throw TechniqueErrors.invalid("inspect_facts.boot", "靴子护甲值和耐久必须同时提供");
        }
        if (armorProvided && (!Double.isFinite(facts.getBootArmor())
                || facts.getBootArmor() < 0 || facts.getBootArmor() > MAX_BOOT_ARMOR)) {
            throw TechniqueErrors.invalid("inspect_facts.boot_armor", "靴子护甲值超出范围");
        }
        if (durabilityProvided && (facts.getBootDurability() < 0
                || facts.getBootDurability() > MAX_BOOT_DURABILITY)) {
            throw TechniqueErrors.invalid("inspect_facts.boot_durability", "靴子耐久超出范围");
        }
    }

    static boolean canCastLeg(TechniqueHostFacts facts) {
        // 腿法起手要求站地，水中、攀爬、骑乘和滑翔均视为未满足条件。
        return facts.getGrounded() && !facts.getInWater() && !facts.getClimbing()
                && !facts.getRiding() && !facts.getGliding();
    }
}
