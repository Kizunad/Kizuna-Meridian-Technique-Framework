package dev.kizuna.meridian.technique.fabric.world;

import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.fabric.mixin.TechniqueEffectAccess;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TissueBenefitLease;
import dev.kizuna.meridian.technique.server.TechniqueHost;
import dev.kizuna.meridian.technique.server.TechniqueStatusCurves.Phase;
import dev.kizuna.meridian.technique.server.TechniqueStatusTissues;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 原版效果生命周期适配；登记、刷新和重载才提交身体活动。 */
public final class TechniqueTissueEffects {
    private static final Logger LOG = LoggerFactory.getLogger(TechniqueTissueEffects.class);
    private static final Set<LivingEntity> attached = Collections.newSetFromMap(new WeakHashMap<>());
    private final LivingEntity actor;
    private final Map<String, TissueBenefitLease> leases = new HashMap<>();
    private final Map<String, Integer> segmentEnds = new HashMap<>();
    private boolean internal;
    private boolean needsReconcile;

    public TechniqueTissueEffects(LivingEntity actor) {
        // 控制器只随实体存活，持久数据由原版实体存档保存。
        this.actor = actor;
    }

    public static TechniqueTissueEffects of(LivingEntity actor) {
        // 所有类人生物和玩家共用相同接线。
        return ((TechniqueTissueAccess) actor).meridian$tissueEffects();
    }

    public boolean handles(MobEffectInstance effect) {
        // 客户端只显示原版效果，不解析身体、提交活动或维护倒计时副本。
        return !internal && !actor.level().isClientSide() && TechniqueStatusBenefits.tissue(effect).isPresent();
    }

    public boolean add(MobEffectInstance incoming) {
        // 先让原版合并算法决定实际实例；被拒绝的短时覆盖不得重启能力曲线。
        if (!actor.canBeAffected(incoming)) {
            return false;
        }
        MobEffectInstance normalized = normalize(incoming);
        MobEffectInstance current = actor.getEffect(incoming.getEffect());
        if (current == null) {
            return accept(normalized);
        }
        MobEffectInstance merged = copy(current);
        boolean accepted = merged.update(normalized);
        if (accepted && (merged.getDuration() != current.getDuration()
                || merged.getAmplifier() != current.getAmplifier())) {
            return accept(merged);
        }
        // 原版即使不替换当前等级，也可能保存待恢复的隐藏实例。
        publish(merged);
        return accepted;
    }

    public void apply(MobEffectInstance instance) {
        // 功法刷新明确重启零级实例；拒绝向上返回，使宿主不能回报仅有图标的成功。
        if (!accept(normalize(instance))) {
            throw new IllegalStateException("状态组织修正提交失败");
        }
    }

    private boolean accept(MobEffectInstance instance) {
        // 身体事务先成功，原版状态才发布；失败保留刷新前的状态和修正。
        var registered = TechniqueStatusBenefits.tissue(instance).orElseThrow();
        var phase = new Phase(instance.getDuration(), instance.getDuration());
        try {
            replace(registered, phase);
            publish(instance);
            if (!leases.isEmpty()) {
                attached.add(actor);
            }
            return true;
        } catch (RuntimeException exception) {
            LOG.warn("实体 {} 的状态 {} 被身体宿主拒绝", actor.getUUID(), registered.benefit().getId(), exception);
            return false;
        }
    }

    private void replace(TechniqueStatusBenefits.Registered registered, Phase phase) {
        // 无身体实体保留普通命令效果，但不伪造身体；有效身体的失败必须向调用方报告。
        TechniqueHost host = TechniqueFabric.service().statusHost(actor.getUUID()).orElse(null);
        if (host == null) {
            return;
        }
        String id = registered.benefit().getId();
        TissueBenefitLease previous = leases.getOrDefault(id, TissueBenefitLease.getDefaultInstance());
        TissueBenefitLease lease = TechniqueStatusTissues.replace(host, registered.technique(),
                registered.benefit(), previous, phase);
        leases.put(id, lease);
        segmentEnds.put(id, phase.remainingTicks() - phase.segmentTicks());
    }

    private MobEffectInstance normalize(MobEffectInstance instance) {
        // 无限命令只在接受实例时规范为目录时长，避免含乏力的曲线永久停在某个相位。
        if (!instance.isInfiniteDuration()) {
            return instance;
        }
        var registered = TechniqueStatusBenefits.tissue(instance).orElseThrow();
        int ticks = Math.toIntExact(registered.benefit().getDurationMillis()
                / dev.kizuna.meridian.technique.server.TechniqueStatusCurves.MILLIS_PER_TICK);
        return new MobEffectInstance(instance.getEffect(), ticks, instance.getAmplifier(),
                instance.isAmbient(), false /* showParticles */, instance.showIcon());
    }

    private void publish(MobEffectInstance instance) {
        // 复用原版同步与隐藏效果处理，递归回调不再提交第二笔身体活动。
        internal = true;
        try {
            actor.forceAddEffect(instance, actor);
        } finally {
            internal = false;
        }
    }

    private static MobEffectInstance copy(MobEffectInstance instance) {
        // 原版拷贝构造只复制当前等级，预检必须另外复制隐藏链，不能修改原始实例。
        MobEffectInstance copied = new MobEffectInstance(instance);
        MobEffectInstance hidden = ((TechniqueEffectAccess) instance).meridian$hiddenEffect();
        if (hidden != null) {
            ((TechniqueEffectAccess) copied).meridian$setHiddenEffect(copy(hidden));
        }
        return copied;
    }

    public void removed(Collection<MobEffectInstance> effects) {
        // 牛奶、命令和自然到期共用撤销入口；不存在的修正由纯 Java 服务过滤。
        if (internal || actor.level().isClientSide()) {
            return;
        }
        for (MobEffectInstance effect : effects) {
            TechniqueStatusBenefits.tissue(effect).ifPresent(registered -> clear(registered.benefit().getId()));
        }
    }

    private void clear(String id) {
        // 只在身体接受清理后丢弃归属；失败保留元数据供下一次对账重试。
        TissueBenefitLease lease = leases.get(id);
        if (lease == null) {
            return;
        }
        TechniqueHost host = TechniqueFabric.service().statusHost(actor.getUUID()).orElse(null);
        if (host != null) {
            TechniqueStatusTissues.remove(host, lease);
        }
        leases.remove(id);
        segmentEnds.remove(id);
        if (leases.isEmpty()) {
            attached.remove(actor);
        }
    }

    public void tick() {
        // 正常 tick 只比较原版剩余刻数；仅加载或长效果跨曲线窗口时重新登记。
        if (actor.level().isClientSide() || (!needsReconcile && leases.isEmpty())) {
            return;
        }
        for (var registered : TechniqueStatusBenefits.tissues()) {
            String id = registered.benefit().getId();
            MobEffectInstance effect = actor.getEffect(registered.effect());
            if (effect == null || !actor.isAlive()) {
                clear(id);
            } else if (needsReconcile || effect.getDuration() <= segmentEnds.getOrDefault(id, 0)) {
                resume(registered, effect);
            }
        }
        needsReconcile = false;
    }

    private void resume(TechniqueStatusBenefits.Registered registered, MobEffectInstance effect) {
        // 相位取保存的总时长与原版剩余时长，重载不重新给完整时间或补发起手。
        String id = registered.benefit().getId();
        TissueBenefitLease lease = leases.get(id);
        int remainingTicks = effect.getDuration();
        int totalTicks = remainingTicks;
        if (lease != null) {
            totalTicks = Math.max(remainingTicks, lease.getTotalTicks());
        }
        try {
            replace(registered, new Phase(totalTicks, remainingTicks));
            attached.add(actor);
        } catch (RuntimeException exception) {
            LOG.warn("实体 {} 的状态 {} 恢复失败，撤销原版效果", actor.getUUID(), id, exception);
            actor.removeEffect(registered.effect());
        }
    }

    public void suspend() {
        // 卸载前撤下曲线，保留相位元数据；离线身体结算不会继续消费已暂停的状态曲线。
        if (actor.level().isClientSide() || leases.isEmpty() || segmentEnds.isEmpty()) {
            return;
        }
        TechniqueHost host = TechniqueFabric.service().statusHost(actor.getUUID()).orElse(null);
        if (host != null) {
            for (TissueBenefitLease lease : leases.values()) {
                TechniqueStatusTissues.remove(host, lease);
            }
        }
        needsReconcile = true;
        segmentEnds.clear();
        attached.remove(actor);
    }

    public static void suspendAll() {
        // 停服先撤销加载中实体的状态修正，再由身体宿主保存权威快照。
        for (LivingEntity actor : List.copyOf(attached)) {
            of(actor).suspend();
        }
        attached.clear();
    }

    public List<TissueBenefitLease> leases() {
        // 存档只输出不可变 Protobuf 归属，不保存第二份剩余时间。
        return List.copyOf(leases.values());
    }

    public void restore(List<TissueBenefitLease> saved) {
        // 实体读取时身体宿主可能尚未认领，延后到首个实体 tick 对账。
        leases.clear();
        segmentEnds.clear();
        for (TissueBenefitLease lease : saved) {
            TechniqueStatusTissues.validateLease(lease);
            if (leases.putIfAbsent(lease.getStatusId(), lease) != null) {
                throw new IllegalArgumentException("实体状态归属重复");
            }
        }
        needsReconcile = true;
    }
}
