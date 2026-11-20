package dev.kizuna.meridian.technique.server;

import com.google.protobuf.Any;
import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.core.MeridianState;
import dev.kizuna.meridian.core.QiAccounting;
import dev.kizuna.meridian.core.QiConsumption;
import dev.kizuna.meridian.core.QiInventory;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.pb.ActivityMessages.BodyActivity;
import dev.kizuna.meridian.protocol.pb.ActivityMessages.QiAccountChange;
import dev.kizuna.meridian.server.BreathingModifiers;
import dev.kizuna.meridian.technique.protocol.pb.MaintainedBreathingMessages.BreathingPractice;
import dev.kizuna.meridian.technique.protocol.pb.MaintainedBreathingMessages.MaintainedBreathing;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueFailure;

import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

/** 运功的收支只用身体时钟和当前全身内腔；开脉状态、外壁和在途货物不参与。 */
public final class TechniqueBreathingPractice {
    public static final String KEY = "meridian_technique:breathing_practice";
    private static final int MILLIS_PER_SECOND = 1000;
    private static final int MAX_COLOR = 0xFFFFFF;

    private TechniqueBreathingPractice() {
        // 运功状态只存在身体快照上。
    }

    public static void validate(MaintainedBreathing definition) {
        // 效果注册和实际使用共用同一套身份、颜色、费率与倍率边界。
        TechniqueValues.id(definition.getEffectId(), "maintained_breathing.effect_id");
        double rate = definition.getQiPerSecond();
        if (!Double.isFinite(rate) || rate <= 0 || rate > TechniqueValues.MAX_QUANTITY
                || definition.getColorRgb() < 0 || definition.getColorRgb() > MAX_COLOR) {
            throw TechniqueErrors.invalid("maintained_breathing", "运功费率或状态颜色超出范围");
        }
        // 构造时校验两个倍率，非法定义不能装入目录。
        modifiers(definition);
    }

    private static BreathingModifiers modifiers(MaintainedBreathing definition) {
        // 由身体框架校验摄入和呼吸功率的有效范围。
        var factors = definition.getFactors();
        return new BreathingModifiers(factors.getIntakeMultiplier(), factors.getRespiratoryWorkMultiplier());
    }

    public static Optional<BreathingPractice> active(BodySnapshot body) {
        // 已结束的施放身份留给去重，不再参加付款。
        return saved(body).filter(state -> !state.getEnded());
    }

    public static boolean canPay(BodySnapshot body) {
        // 允许未开脉，只有已经入内腔的库存可以支撑运功。
        return body.meridianStates().stream().anyMatch(state -> state.lumenQi().totalQi() > 0);
    }

    private static Optional<BreathingPractice> saved(BodySnapshot body) {
        // 旧档没有运功记录；错型或损坏时不能当作刚开始收费。
        Any payload = body.addonProgress().get(KEY);
        if (payload == null) {
            return Optional.empty();
        }
        if (!payload.is(BreathingPractice.class)) {
            throw TechniqueErrors.invalid(KEY, "运功记录类型错误");
        }
        var state = TechniqueProtocol.parse(payload.getValue().toByteArray(), BreathingPractice.parser());
        TechniqueValues.castId(state.getCastId());
        TechniqueValues.id(state.getTechniqueId(), "technique_id");
        validate(state.getDefinition());
        if (state.getPaidMillis() < 0 || state.getPaidMillis() > body.settledAt().elapsedMillis()) {
            throw TechniqueErrors.invalid(KEY, "运功付讫时间越界");
        }
        return Optional.of(state);
    }

    public static Optional<BodyActivity> begin(BodySnapshot body, String castId, String technique,
                                               MaintainedBreathing definition) {
        // 只允许一次独立维持；迟到的同一施放重放不能重新发动已经收掉的法。
        validate(definition);
        TechniqueValues.castId(castId);
        TechniqueValues.id(technique, "technique_id");
        var previous = saved(body);
        if (previous.isPresent() && previous.get().getCastId().equals(castId)) {
            return Optional.empty();
        }
        if (previous.isPresent() && !previous.get().getEnded()) {
            throw TechniqueErrors.rejected(TechniqueFailure.ALREADY_CASTING);
        }
        if (!canPay(body)) {
            throw TechniqueErrors.rejected(TechniqueFailure.INSUFFICIENT_QI);
        }
        var state = BreathingPractice.newBuilder()
                .setCastId(castId)
                .setTechniqueId(technique)
                .setDefinition(definition)
                .setPaidMillis(body.settledAt().elapsedMillis())
                .build();
        var activity = BodyActivity.newBuilder()
                .putProgress(KEY, Any.pack(state))
                .putProgress(BreathingModifiers.KEY, modifiers(definition).pack())
                .build();
        return Optional.of(activity);
    }

    public enum Settlement { MAINTAIN, END }

    public static OptionalLong expiresAt(BodySnapshot body) {
        // 余额能支付到的第一个整毫秒边界。长 tick 在这里切段，再用普通因子处理尾段。
        var practice = active(body);
        if (practice.isEmpty()) {
            return OptionalLong.empty();
        }
        var state = practice.orElseThrow();
        double available = body.meridianStates().stream().mapToDouble(meridian -> meridian.lumenQi().totalQi()).sum();
        double duration = Math.ceil(available * MILLIS_PER_SECOND / state.getDefinition().getQiPerSecond());
        if (duration >= Long.MAX_VALUE - state.getPaidMillis()) {
            return OptionalLong.of(Long.MAX_VALUE);
        }
        return OptionalLong.of(state.getPaidMillis() + (long) duration);
    }

    public static Optional<Any> abandoned(BodySnapshot body) {
        // 离线和死体只撤旧效果，不把离开后经过的时间算成在线维持费用。
        return active(body).map(state -> Any.pack(state.toBuilder().setEnded(true /* ended */).build()));
    }

    public static Optional<BodyActivity> settle(BodySnapshot body, Settlement mode) {
        // 游标和扣款同笔发布；重复结同一毫秒没有费用。
        var active = active(body);
        if (active.isEmpty()) {
            return Optional.empty();
        }
        BreathingPractice state = active.orElseThrow();
        long now = body.settledAt().elapsedMillis();
        long elapsed = now - state.getPaidMillis();
        double rate = state.getDefinition().getQiPerSecond();
        double cost = rate * elapsed / MILLIS_PER_SECOND;
        var balances = body.meridianStates().stream().map(MeridianState::lumenQi).toList();
        double available = balances.stream().mapToDouble(QiInventory::totalQi).sum();
        boolean exhausted = cost >= available;
        if (elapsed == 0 && !exhausted && mode == Settlement.MAINTAIN) {
            return Optional.empty();
        }
        List<QiInventory> debits = balances;
        long paidUntil = now;
        if (!exhausted) {
            debits = QiConsumption.byVolume(balances, cost);
        } else if (cost > available) {
            long fundedMillis = (long) (available / rate * MILLIS_PER_SECOND);
            paidUntil = state.getPaidMillis() + Math.min(elapsed, fundedMillis);
        }
        var activity = debit(body.meridianStates(), debits);
        var updated = state.toBuilder().setPaidMillis(paidUntil);
        if (exhausted || mode == Settlement.END) {
            updated.setEnded(true /* ended */);
            activity.putProgress(BreathingModifiers.KEY, BreathingModifiers.NORMAL.pack());
        }
        activity.putProgress(KEY, Any.pack(updated.build()));
        return Optional.of(activity.build());
    }

    private static BodyActivity.Builder debit(List<MeridianState> states, List<QiInventory> debits) {
        // 各份保留原密度和属性，向环境释放的正是从这些内腔扣出的货物。
        var activity = BodyActivity.newBuilder();
        for (int index = 0; index < states.size(); index++) {
            QiInventory cost = debits.get(index);
            if (cost.entries().isEmpty()) {
                continue;
            }
            MeridianState state = states.get(index);
            QiInventory remaining = QiAccounting.debit(state.lumenQi(), cost);
            var change = QiAccountChange.newBuilder()
                    .setLumenId(state.id().value())
                    .setExpected(BodyProtobuf.toInventory(state.lumenQi()))
                    .setReplacement(BodyProtobuf.toInventory(remaining))
                    .build();
            activity.addAccounts(change);
        }
        activity.setReleasedQi(BodyProtobuf.toInventory(QiInventory.combine(debits)));
        return activity;
    }
}
