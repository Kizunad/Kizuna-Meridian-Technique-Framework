package dev.kizuna.meridian.technique.server;

import com.google.protobuf.Any;
import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.pb.AddonMessages.*;
import dev.kizuna.meridian.protocol.pb.BodyMessages.*;
import dev.kizuna.meridian.server.AddonRegistry;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.*;

import java.util.List;

/** 功法与身体样本只存在于整体回归测试中。 */
final class Fixtures {
    static final double HP = 100;
    static final double STRENGTH = 200;
    static final double POWER = 400;
    static final double ENDURANCE = 1000;
    static final double QI_AMOUNT = 40;
    static final double WATER_AMOUNT = 30;
    static final double GAS_DENSITY = 0.2;
    static final double LIQUID_DENSITY = 1.2;
    static final double COST_QI = 10;
    static final double COST_POWER = 20;
    static final double COST_SECONDS = 0.5;
    static final double COST_FORCE = 50;
    static final double HALF_LIFE = 10;
    static final double LIFESPAN = 100_000;
    static final double MINIMUM_STRENGTH = 20;
    static final long WARMUP = 100;
    static final long ACTIVE = 200;
    static final long RECOVERY = 300;
    static final long COOLDOWN = 2000;
    static final long CURVE_MIDDLE = 500;
    static final long CURVE_END = 1000;
    static final double PEAK = 1.3;
    static final String TECHNIQUE_ID = "sample:technique";
    static final String SOURCE_TAG = "sample:source";
    static final String MUSCLE_TAG = "sample:actuator";
    static final String ORGAN_TAG = "sample:organ";

    enum Shape { PAIRED, TENDRIL }

    private Fixtures() {
        // 所有样例都通过真实 Framework 编解码与注册校验。
    }

    static BodySnapshot body(Shape shape) {
        // 两种构型使用不同局部模块 ID，保证绑定依赖标签而非人体名称。
        BodyStructure structure = structure(shape);
        TemplateReference reference = templateReference(shape);
        VitalityState vitality = VitalityState.newBuilder()
                .setAgeSeconds(0)
                .setLifespanLimitSeconds(LIFESPAN)
                .setSpentLifespanSeconds(0)
                .setDead(false /* dead */)
                .build();
        TimeCursor time = TimeCursor.newBuilder()
                .setDomain("sample:world")
                .setEpoch("test")
                .setElapsedMillis(0)
                .build();
        PartState part = PartState.newBuilder()
                .setId("body")
                .setHp(HP)
                .build();
        var body = dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot.newBuilder()
                .setSchemaVersion(BodySnapshot.CURRENT_SCHEMA_VERSION)
                .setId("body-" + shape.name())
                .setTemplate(reference)
                .setStructure(structure)
                .setRealm(Realm.TRUE_QI)
                .setVitality(vitality)
                .setSettledAt(time)
                .setRevision(0)
                .addPartStates(part);
        addModuleStates(body, structure);
        TechniqueProgress progress = progress(definition());
        body.putAddonProgress(TechniqueState.KEY, TechniqueState.pack(progress));
        return BodyProtobuf.fromMessage(body.build());
    }

    private static BodyStructure structure(Shape shape) {
        // 对称样本有两个执行组织，触须样本只有一个，均带可独立做功的器官。
        PartDefinition part = PartDefinition.newBuilder()
                .setId("body")
                .setDisplayName("测试主体")
                .setMaxHp(HP)
                .build();
        var structure = BodyStructure.newBuilder().addParts(part);
        List<String> names = List.of("a", "b");
        if (shape == Shape.TENDRIL) {
            names = List.of("tendril");
        }
        for (String name : names) {
            structure.addTissues(tissue(name));
            structure.addMeridians(meridian(name));
        }
        structure.addOrgans(organ());
        return structure.build();
    }

    private static TissueDefinition tissue(String id) {
        // 基础能力与恢复参数明确，便于验证起手做功和动作占用联动。
        TissueCapacity capacity = TissueCapacity.newBuilder()
                .setStrengthNewtons(STRENGTH)
                .setBurstWatts(POWER)
                .setEnduranceJoules(ENDURANCE)
                .build();
        return TissueDefinition.newBuilder()
                .setId(id)
                .setDisplayName("测试组织")
                .setKind("sample:muscle")
                .setPartId("body")
                .setMaxHp(HP)
                .setFatigueCapacity(ENDURANCE)
                .setCapacity(capacity)
                .setRecoveryHalfLifeSeconds(HALF_LIFE)
                .addTags(MUSCLE_TAG)
                .build();
    }

    private static MeridianDefinition meridian(String id) {
        // 内腔和外壁分开保存，扣费测试须确认只改变内腔。
        return MeridianDefinition.newBuilder()
                .setId(id)
                .setDisplayName("测试经脉")
                .addPartIds("body")
                .setLumenVolume(ENDURANCE)
                .setWallVolume(ENDURANCE)
                .setThroughputPerSecond(POWER)
                .setResilience(HP)
                .setFatigueCapacity(ENDURANCE)
                .addTags(SOURCE_TAG)
                .build();
    }

    private static OrganDefinition organ() {
        // 器官费用也走 Framework 的活动做功，不使用虚构的独立疲劳记录。
        OrganEndurance endurance = OrganEndurance.newBuilder()
                .setEnduranceJoules(ENDURANCE)
                .setMaximumWatts(POWER)
                .setRecoveryHalfLifeSeconds(HALF_LIFE)
                .build();
        return OrganDefinition.newBuilder()
                .setId("control")
                .setDisplayName("测试器官")
                .setKind("sample:control")
                .setPartId("body")
                .setMaxHp(HP)
                .setVolumeCapacity(ENDURANCE)
                .setThroughputPerSecond(POWER)
                .setEndurance(endurance)
                .addTags(ORGAN_TAG)
                .build();
    }

    private static void addModuleStates(
            dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot.Builder body, BodyStructure structure) {
        // 状态逐定义构造，组织数量变化不会依赖固定左右索引。
        for (TissueDefinition tissue : structure.getTissuesList()) {
            TissueState state = TissueState.newBuilder()
                    .setId(tissue.getId())
                    .setHp(HP)
                    .setFatigue(0)
                    .build();
            body.addTissueStates(state);
        }
        OrganState organ = OrganState.newBuilder()
                .setId("control")
                .setHp(HP)
                .setBuffer(QiInventory.getDefaultInstance())
                .build();
        body.addOrganStates(organ);
        for (MeridianDefinition meridian : structure.getMeridiansList()) {
            MeridianOpening opening = MeridianOpening.newBuilder()
                    .setStage(MeridianOpeningStage.OPENED)
                    .setProgress(1)
                    .build();
            QiInventory inventory = inventory();
            MeridianState state = MeridianState.newBuilder()
                    .setId(meridian.getId())
                    .setLumenQi(inventory)
                    .setWallQi(inventory)
                    .setFatigue(0)
                    .setCondition(MeridianCondition.INTACT)
                    .setOpening(opening)
                    .build();
            body.addMeridianStates(state);
        }
    }

    static QiInventory inventory() {
        // 非基准密度验证费用释放不会改写密度或把其他属性充作余额。
        Qi fire = Qi.newBuilder()
                .setAttribute("meridian:fire")
                .setAmount(QI_AMOUNT)
                .setDensity(GAS_DENSITY)
                .build();
        Qi water = Qi.newBuilder()
                .setAttribute("meridian:water")
                .setAmount(WATER_AMOUNT)
                .setDensity(LIQUID_DENSITY)
                .build();
        return QiInventory.newBuilder().addEntries(fire).addEntries(water).build();
    }

    static TemplateReference templateReference(Shape shape) {
        // 模板是测试内容符号，不进入生产资源。
        String id = "sample:paired";
        if (shape == Shape.TENDRIL) {
            id = "sample:tendril";
        }
        return TemplateReference.newBuilder()
                .setId(id)
                .setVersion(1)
                .build();
    }

    static AddonRegistry addons() {
        // 同一个附属登记两种构型，使目录可以验证跨身体标签词汇。
        ContentVersionRange versions = ContentVersionRange.newBuilder()
                .setMinimum(1)
                .setMaximum(1)
                .build();
        AddonManifest manifest = AddonManifest.newBuilder()
                .setNamespace("sample")
                .setContentVersion(1)
                .setApiVersions(versions)
                .addSupportedHosts("test:server")
                .build();
        var bundle = AddonBundle.newBuilder()
                .setSchemaVersion(1)
                .setManifest(manifest)
                .addSymbols(symbol("sample:muscle", ContentSymbolKind.TISSUE_KIND))
                .addSymbols(symbol("sample:control", ContentSymbolKind.ORGAN_KIND));
        for (Shape shape : Shape.values()) {
            BodyTemplate template = BodyTemplate.newBuilder()
                    .setReference(templateReference(shape))
                    .setStructure(structure(shape))
                    .build();
            bundle.addTemplates(template);
        }
        AddonHostProfile host = AddonHostProfile.newBuilder()
                .setHostId("test:server")
                .setApiVersion(1)
                .addCapabilities(AddonRegistry.BODY_TEMPLATES)
                .addCapabilities(AddonRegistry.ORGAN_ENDURANCE)
                .addCapabilities(AddonRegistry.TISSUE_RECOVERY)
                .build();
        AddonBundle builtBundle = bundle.build();
        AddonRegistration registration = AddonRegistration.newBuilder()
                .setProtocolVersion(1)
                .setHost(host)
                .addBundles(builtBundle)
                .build();
        return AddonRegistry.load(registration);
    }

    private static ContentSymbol symbol(String id, ContentSymbolKind kind) {
        // 组织和器官类型都通过真实注册表解析。
        return ContentSymbol.newBuilder()
                .setId(id)
                .setKind(kind)
                .build();
    }

    static Quantity quantity(double value, Unit unit) {
        // 单位始终显式编码，缺单位测试单独构造非法消息。
        return Quantity.newBuilder()
                .setValue(value)
                .setUnit(unit)
                .build();
    }

    static TechniqueDefinition definition() {
        // 无具体玩法名称的测试功法，费用覆盖真元、组织和器官三类预算。
        TechniqueSlot source = slot("source", ModuleKind.MERIDIAN, SOURCE_TAG);
        Quantity strength = quantity(MINIMUM_STRENGTH, Unit.NEWTON);
        TechniqueSlot actuator = slot("actuator", ModuleKind.TISSUE, MUSCLE_TAG).toBuilder()
                .setMinimumStrength(strength)
                .build();
        TechniqueSlot organ = slot("organ", ModuleKind.ORGAN, ORGAN_TAG);
        Quantity amount = quantity(COST_QI, Unit.QI);
        QiCost qi = QiCost.newBuilder()
                .setSlotId("source")
                .setAttribute("meridian:fire")
                .setPhase(QiPhase.GAS)
                .setAmount(amount)
                .setDistribution(QiDistribution.EACH)
                .build();
        WorkCost tissueWork = work("actuator");
        WorkCost organWork = work("organ").toBuilder().clearForce().build();
        TechniqueCost cost = TechniqueCost.newBuilder().addQi(qi).addWork(tissueWork).addWork(organWork).build();
        CastPhases phases = CastPhases.newBuilder()
                .setWarmupMillis(WARMUP)
                .setActiveMillis(ACTIVE)
                .setRecoveryMillis(RECOVERY)
                .setInterruptibleByDamage(true /* interruptibleByDamage */)
                .build();
        return TechniqueDefinition.newBuilder()
                .setId(TECHNIQUE_ID)
                .setVersion(1)
                .setCategory(Category.BUFF)
                .setMinimumRealm(Realm.TRUE_QI)
                .addSlots(source)
                .addSlots(actuator)
                .addSlots(organ)
                .setCost(cost)
                .setPhases(phases)
                .setCooldownMillis(COOLDOWN)
                .build();
    }

    static TechniqueSlot slot(String id, ModuleKind module, String tag) {
        // 必须槽位选一个稳定候选；可选与多选在具体回归中按需调整。
        var slot = TechniqueSlot.newBuilder()
                .setId(id)
                .setModule(module)
                .addTags(tag)
                .setMinimumCount(1)
                .setMaximumCount(1);
        if (module == ModuleKind.MERIDIAN) {
            slot.setRequireOpened(true /* requireOpened */);
        }
        return slot.build();
    }

    static WorkCost work(String slot) {
        // 组织做功和器官疲劳共享功率、持续时间，器官调用方移除力量字段。
        Quantity force = quantity(COST_FORCE, Unit.NEWTON);
        Quantity power = quantity(COST_POWER, Unit.WATT);
        Quantity duration = quantity(COST_SECONDS, Unit.SECOND);
        return WorkCost.newBuilder()
                .setSlotId(slot)
                .setForce(force)
                .setPower(power)
                .setDuration(duration)
                .build();
    }

    static TechniqueCatalog catalog(TechniqueDefinition definition) {
        // 目录声明完整标签来源，不复制 Framework 标签目录。
        return TechniqueCatalog.newBuilder()
                .setSchemaVersion(1)
                .setNamespace("sample")
                .addBindingTemplates(templateReference(Shape.PAIRED))
                .addBindingTemplates(templateReference(Shape.TENDRIL))
                .addDefinitions(definition)
                .build();
    }

    static TechniqueRegistry registry(TechniqueDefinition definition) {
        // 所有运行测试也必须先过真实目录加载校验。
        return TechniqueRegistry.load(addons(), List.of(catalog(definition)));
    }

    static TechniqueProgress progress(TechniqueDefinition definition) {
        // 可信宿主预置学会记录，生产库不实现学会命令。
        TechniqueReference reference = TechniqueRegistry.reference(definition);
        Quantity proficiency = quantity(0, Unit.RATIO);
        KnownTechnique known = KnownTechnique.newBuilder()
                .setTechnique(reference)
                .setProficiency(proficiency)
                .setSource("sample:trusted")
                .setLearnedMillis(0)
                .build();
        return TechniqueState.empty().toBuilder().addKnown(known).build();
    }

    static CastRequest request(BodySnapshot body, String castId) {
        // 客户端意图只包含精确功法引用，不持有可信费用或进度。
        TechniqueReference reference = TechniqueRegistry.reference(definition());
        return CastRequest.newBuilder()
                .setBodyId(body.id().value())
                .setTechnique(reference)
                .setCastId(castId)
                .build();
    }

    static TissueModifier modifier() {
        // 首尾归零并包含峰值，冷却测试会独立改变生效偏移或末点。
        Quantity peak = quantity(PEAK, Unit.RATIO);
        Quantity unchanged = quantity(1, Unit.RATIO);
        CurvePoint first = point(0, 0);
        CurvePoint middle = point(CURVE_MIDDLE, 1);
        CurvePoint end = point(CURVE_END, 0);
        return TissueModifier.newBuilder()
                .setSlotId("actuator")
                .setStrengthPeak(peak)
                .setBurstPeak(unchanged)
                .setEndurancePeak(unchanged)
                .setRecoveryPeak(unchanged)
                .addCurve(first)
                .addCurve(middle)
                .addCurve(end)
                .build();
    }

    static CurvePoint point(long millis, double value) {
        // 直接复用 Framework 曲线协议，但不登记或执行任何组织修正。
        return CurvePoint.newBuilder()
                .setOffsetMillis(millis)
                .setValue(value)
                .build();
    }

    static BodySnapshot withProgress(BodySnapshot body, TechniqueProgress progress) {
        // 仅测试准备替换进度，真实施放仍经过宿主活动入口。
        Any payload = TechniqueState.pack(progress);
        var message = BodyProtobuf.toMessage(body).toBuilder().putAddonProgress(TechniqueState.KEY, payload).build();
        return BodyProtobuf.fromMessage(message);
    }
}
