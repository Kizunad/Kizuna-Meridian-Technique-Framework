package dev.kizuna.meridian.technique.server;

import com.google.protobuf.Any;
import dev.kizuna.meridian.core.AddonProgress;
import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.*;

import java.util.HashSet;

/** 附属状态只随身体快照保存，不另建知识、冷却或施放账本。 */
public final class TechniqueState {
    public static final String KEY = "meridian_technique:state";
    private static final int MAX_KNOWN = 1024;
    private static final int MAX_PROGRESS_BYTES = 65_536;

    private TechniqueState() {
        // 进度读写统一通过版本化消息执行格式校验。
    }

    public static TechniqueProgress empty() {
        // schema 字段保证空进度也有明确类型与非空载荷。
        return TechniqueProgress.newBuilder().setSchemaVersion(TechniqueValues.SCHEMA_VERSION).build();
    }

    public static TechniqueProgress read(BodySnapshot body) {
        // 未存本库进度表示尚未学会；类型错或损坏则拒绝，不能覆盖原存档。
        Any payload = body.addonProgress().get(KEY);
        if (payload == null) {
            return empty();
        }
        AddonProgress.validateEntry(KEY, payload);
        if (!payload.is(TechniqueProgress.class)) {
            throw TechniqueErrors.invalid(KEY, "附属进度的 Any 类型不匹配");
        }
        TechniqueProgress progress = decode(payload.getValue().toByteArray());
        if (progress.hasActiveCast()) {
            ActiveCast active = progress.getActiveCast();
            if (!active.getRequest().getBodyId().equals(body.id().value())
                    || active.getStartedMillis() > body.settledAt().elapsedMillis()
                    || active.getBinding().getRevision() >= body.revision()) {
                throw TechniqueErrors.invalid(KEY, "当前施放的身体、时间或绑定 revision 不一致");
            }
        }
        return progress;
    }

    public static Any pack(TechniqueProgress progress) {
        // 进度在写入活动前完整校验，大小遵循 Framework 单项 64 KiB 上限。
        validate(progress);
        Any payload = Any.pack(progress);
        AddonProgress.validateEntry(KEY, payload);
        return payload;
    }

    public static TechniqueProgress decode(byte[] payload) {
        // 二进制存档也走同一语义校验，不因 parseFrom 成功而接受非法熟练度或边界。
        TechniqueProgress progress = TechniqueProtocol.parse(payload, TechniqueProgress.parser());
        validate(progress);
        return progress;
    }

    public static void validate(TechniqueProgress progress) {
        // 可信宿主写入的状态仍有规模、唯一性、单位与时间约束。
        TechniqueValues.knownFields(progress);
        if (progress.getSchemaVersion() != TechniqueValues.SCHEMA_VERSION
                || progress.getKnownCount() > MAX_KNOWN || progress.getCooldownsCount() > MAX_KNOWN
                || Any.pack(progress).getSerializedSize() > MAX_PROGRESS_BYTES) {
            throw TechniqueErrors.invalid(KEY, "进度版本不支持、记录数量或载荷大小超限");
        }
        var identities = new HashSet<TechniqueReference>();
        for (KnownTechnique known : progress.getKnownList()) {
            TechniqueProtocol.validateReference(known.getTechnique());
            TechniqueValues.quantity(known.getProficiency(), Unit.RATIO, 0, 1, "proficiency");
            TechniqueValues.id(known.getSource(), "known.source");
            if (!identities.add(known.getTechnique()) || !known.hasLearnedMillis() || known.getLearnedMillis() < 0) {
                throw TechniqueErrors.invalid("known", "学会记录重复或缺少合法学会时刻");
            }
        }
        for (var cooldown : progress.getCooldownsMap().entrySet()) {
            TechniqueValues.id(cooldown.getKey(), "cooldowns.id");
            if (cooldown.getValue() < 0) {
                throw TechniqueErrors.invalid("cooldowns", "冷却结束的身体时刻不能为负");
            }
        }
        if (progress.hasActiveCast()) {
            validateActive(progress.getActiveCast());
        }
    }

    private static void validateActive(ActiveCast active) {
        // 固定三段边界允许缺包后收势，不需要重新读取可能升级的功法定义。
        TechniqueProtocol.validateCast(active.getRequest());
        long started = active.getStartedMillis();
        long warmupEnd = active.getWarmupEndMillis();
        long activeEnd = active.getActiveEndMillis();
        long recoveryEnd = active.getRecoveryEndMillis();
        if (started < 0 || warmupEnd < started || activeEnd < warmupEnd || recoveryEnd <= started
                || recoveryEnd < activeEnd || recoveryEnd - started > TechniqueValues.MAX_MILLIS
                || active.getActionInstanceId().isBlank() || !active.hasInterruptibleByDamage()
                || !active.getBinding().getBodyId().equals(active.getRequest().getBodyId())
                || active.getBinding().getRevision() < 0) {
            throw TechniqueErrors.invalid("active_cast", "施放边界、动作身份或绑定信息非法");
        }
        var slots = new HashSet<String>();
        for (BoundSlot slot : active.getBinding().getSlotsList()) {
            TechniqueValues.localId(slot.getSlotId(), "binding.slot_id");
            if (!slots.add(slot.getSlotId()) || slot.getModuleValue() <= 0
                    || slot.getModule() == ModuleKind.UNRECOGNIZED
                    || new HashSet<>(slot.getModuleIdsList()).size() != slot.getModuleIdsCount()) {
                throw TechniqueErrors.invalid("binding", "槽位重复、模块种类非法或模块身份重复");
            }
        }
    }
}
