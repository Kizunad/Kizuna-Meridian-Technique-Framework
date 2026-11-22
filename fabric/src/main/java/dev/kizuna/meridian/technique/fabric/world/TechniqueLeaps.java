package dev.kizuna.meridian.technique.fabric.world;

import dev.kizuna.meridian.error.MeridianErrors.CapabilityRejected;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.EffectIntent;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.LeapProfile;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.LeapMotion;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.PresentationAction;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.PresentationIntent;
import dev.kizuna.meridian.technique.server.TechniqueLeap;
import dev.kizuna.meridian.technique.server.TechniqueService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.Comparator;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;

/** 玩家与类人共用的起跳、真实做功、碰撞和落地宿主。 */
public final class TechniqueLeaps {
    public enum Jump { LEAP, ORDINARY, WAIT }
    public enum LeapResult { LANDED, BLOCKED, CANCELLED }
    private static final Logger LOG = LoggerFactory.getLogger(TechniqueLeaps.class);
    private static final long MILLIS_PER_TICK = 50;
    private static final int MAX_FLIGHT_TICKS = 200;
    private static final float STEP_VOLUME = 0.15F;
    private static final double DIRECTION_EPSILON = 0.000001;
    private static final double LANDING_CONTACT_REACH = 0.5;
    private static final double HALF_WIDTH = 0.5;
    private static final List<Predicate<Mob>> directors = new CopyOnWriteArrayList<>();
    private static final List<Consumer<LeapAttempt>> leapListeners = new CopyOnWriteArrayList<>();

    /** 大脑预估的一次跃步：求解出的落点与飞行毫秒。 */
    public record Preview(Vec3 landing, long flightMillis) { }

    public record DirectedLeap(LeapProfile profile, String effectId, Vec3 direction, double distance,
                               TechniqueLeap.Landing landing, String castId,
                               Consumer<LeapResult> completion, Consumer<LivingEntity> contact) {
        public DirectedLeap {
            Objects.requireNonNull(profile);
            Objects.requireNonNull(effectId);
            Objects.requireNonNull(direction);
            Objects.requireNonNull(landing);
            Objects.requireNonNull(castId);
            Objects.requireNonNull(completion);
            Objects.requireNonNull(contact);
        }
    }

    private record FlightStart(LeapProfile profile, String effectId, TechniqueLeap.Landing landing, String castId,
                               TechniqueLeapFlight.WallCollisionPolicy wallCollisionPolicy,
                               Consumer<LeapResult> completion, Consumer<LivingEntity> contact) { }

    /** 服务端一次付费起跳的结果：成功为 LEAP 并带实际做功，被拒为 ORDINARY 并带原因；调试用途，不进正式玩法。 */
    public record LeapAttempt(LivingEntity actor, TechniqueLeap.Landing landing, Jump result,
                              TechniqueService.LeapExertion exertion) {
        public LeapAttempt {
            // 事件只转述本次起跳，监听器不能借此改写飞行或身体。
            Objects.requireNonNull(actor);
            Objects.requireNonNull(landing);
            Objects.requireNonNull(result);
            Objects.requireNonNull(exertion);
        }
    }

    private TechniqueLeaps() {
        // 飞行状态挂在实体上，卸载自动释放。
    }

    public static Jump jump(LivingEntity actor) {
        // 潜行、液体和骑乘使用原版跳跃，不消费额外体力。
        var registered = TechniqueStatusBenefits.activeLeap(actor).orElse(null);
        if (registered == null || actor.isShiftKeyDown() || actor.isPassenger() || actor.isInWater()
                || actor.isInLava() || !actor.isAlive()) {
            return Jump.ORDINARY;
        }
        if (!ready(actor)) {
            return Jump.WAIT;
        }
        var landing = TechniqueLeapTargets.aimed(actor, registered.benefit().getLeap());
        return start(actor, registered, landing);
    }

    private static boolean ready(LivingEntity actor) {
        // 落地间隔按服务端世界时间计，本地玩家使用服务端回执里的同一刻度。
        var access = (TechniqueLeapAccess) actor;
        return access.meridian$getLeap() == null && actor.onGround()
                && actor.level().getGameTime() >= access.meridian$getLeapReadyTick();
    }

    public static Jump start(LivingEntity actor, TechniqueStatusBenefits.Registered registered,
                              TechniqueLeap.Landing landing) {
        // 只有服务端可扣体力，客户端先预测相同轨迹再与权威起点和初速度比对。
        if (!registered.benefit().hasLeap()) {
            return Jump.ORDINARY;
        }
        if (!ready(actor)) {
            return Jump.WAIT;
        }
        String castId = "leap_" + UUID.randomUUID().toString().replace("-", "");
        if (actor.level().isClientSide()) {
            launch(actor, new FlightStart(registered.benefit().getLeap(), registered.benefit().getId(), landing,
                    castId, TechniqueLeapFlight.WallCollisionPolicy.CONTINUE, null, null));
            return Jump.LEAP;
        }
        TechniqueService.LeapExertion exertion = pay(actor, registered, castId);
        if (!exertion.accepted()) {
            weakNotice(actor, registered);
            publishLeap(new LeapAttempt(actor, landing, Jump.ORDINARY, exertion));
            return Jump.ORDINARY;
        }
        TechniqueLeapFlight flight = launch(actor, new FlightStart(registered.benefit().getLeap(),
                registered.benefit().getId(), landing, castId, TechniqueLeapFlight.WallCollisionPolicy.CONTINUE,
                null, null));
        present(actor, flight, PresentationAction.START);
        // 起跳声只在服务端付费成功后播一次；客户端预测分支不播，施放者本人不会双响。
        LeapProfile profile = registered.benefit().getLeap();
        if (profile.hasTakeoffSound()) {
            TechniqueSounds.play(actor, profile.getTakeoffSound());
        }
        TechniqueLeapNetworking.send(actor, LeapMotion.Action.START, flight);
        publishLeap(new LeapAttempt(actor, landing, Jump.LEAP, exertion));
        return Jump.LEAP;
    }

    public static Jump startDirected(LivingEntity actor, DirectedLeap request) {
        // 施放者已付起手费用；该入口只启动固定方向的轨迹，不再次调用做功宿主。
        if (actor.level().isClientSide() || !ready(actor)) {
            return actor.level().isClientSide() ? Jump.ORDINARY : Jump.WAIT;
        }
        Vec3 horizontal = new Vec3(request.direction().x, 0, request.direction().z);
        double length = horizontal.length();
        if (!Double.isFinite(length) || length < DIRECTION_EPSILON || !Double.isFinite(request.distance())
                || request.distance() <= 0) {
            return Jump.ORDINARY;
        }
        TechniqueLeap.Landing landing = request.landing();
        if (!landing.supported()) {
            return Jump.ORDINARY;
        }
        var flightStart = new FlightStart(request.profile(), request.effectId(), landing, request.castId(),
                TechniqueLeapFlight.WallCollisionPolicy.FINISH, request.completion(), request.contact());
        TechniqueLeapFlight flight = launch(actor, flightStart);
        present(actor, flight, PresentationAction.START);
        if (request.profile().hasTakeoffSound()) {
            TechniqueSounds.play(actor, request.profile().getTakeoffSound());
        }
        TechniqueLeapNetworking.send(actor, LeapMotion.Action.START, flight);
        return Jump.LEAP;
    }

    public static Optional<TechniqueLeap.Landing> planDirected(LivingEntity actor, LeapProfile profile,
                                                                Vec3 direction, double distance) {
        // 付款前冻结方向、落点和起跳净空，付款后的启动阶段只消费这份计划。
        if (!ready(actor)) {
            return Optional.empty();
        }
        Vec3 horizontal = new Vec3(direction.x, 0, direction.z);
        double length = horizontal.length();
        if (!Double.isFinite(length) || length < DIRECTION_EPSILON || !Double.isFinite(distance)
                || distance <= 0 || !TechniqueLeapTargets.clearTakeoff(actor)) {
            return Optional.empty();
        }
        Vec3 unit = horizontal.scale(1 / length);
        TechniqueLeap.Landing landing = TechniqueLeapTargets.directed(actor, profile, unit, distance);
        if (!landing.supported()) {
            return Optional.empty();
        }
        return Optional.of(landing);
    }

    private static TechniqueLeapFlight launch(LivingEntity actor, FlightStart request) {
        // 两端按同一落点求解初速度并挂上飞行状态，服务端随后广播权威起点。
        var origin = TechniqueLeapTargets.point(actor.position());
        var target = request.landing().point();
        int ticks = TechniqueLeap.flightTicks(request.profile(), origin, target);
        Vec3 velocity = TechniqueLeapTargets.vector(TechniqueLeap.velocity(origin, target, ticks));
        var flightStart = new TechniqueLeapFlight.Start(request.profile(), request.effectId(), target.y(),
                actor.position(), velocity, request.castId(), request.wallCollisionPolicy(), request.completion(),
                request.contact());
        var flight = new TechniqueLeapFlight(flightStart);
        var access = (TechniqueLeapAccess) actor;
        access.meridian$setLeap(flight);
        access.meridian$clearWeakNotice();
        actor.setDeltaMovement(velocity);
        actor.setOnGround(false /* onGround */);
        actor.fallDistance = 0;
        return flight;
    }

    private static TechniqueService.LeapExertion pay(LivingEntity actor, TechniqueStatusBenefits.Registered registered,
                                                     String requestId) {
        // 拒绝不会制造速度或局部扣费，异常留日志以便发现宿主接线故障。
        try {
            return TechniqueFabric.service().submitLeap(actor.getUUID(), registered.technique(),
                    registered.benefit().getLeap(), requestId);
        } catch (IllegalArgumentException | CapabilityRejected rejected) {
            return TechniqueService.LeapExertion.rejected(String.valueOf(rejected.getMessage()));
        } catch (RuntimeException failure) {
            LOG.warn("跃步做功被宿主拒绝：{}", actor.getUUID(), failure);
            return TechniqueService.LeapExertion.rejected(failure.getClass().getSimpleName());
        }
    }

    public static void addLeapListener(Consumer<LeapAttempt> listener) {
        // 调试用途，不进正式玩法：服务端每次付费起跳（成功或被拒）回调一次，等待落地间隔不回调。
        leapListeners.add(Objects.requireNonNull(listener));
    }

    public static void removeLeapListener(Consumer<LeapAttempt> listener) {
        // 服务器停止时移除调试监听器，避免保留旧世界里的实体。
        leapListeners.remove(listener);
    }

    private static void publishLeap(LeapAttempt attempt) {
        // 观察者故障不能撤销已经提交的做功或已经开始的飞行。
        for (Consumer<LeapAttempt> listener : leapListeners) {
            try {
                listener.accept(attempt);
            } catch (RuntimeException failure) {
                LOG.warn("跃步观察者处理失败：{}", attempt.actor().getUUID(), failure);
            }
        }
    }

    private static void weakNotice(LivingEntity actor, TechniqueStatusBenefits.Registered registered) {
        // 连按仅提示一次，成功跃步后才允许下一次乏力提示。
        var access = (TechniqueLeapAccess) actor;
        if (actor instanceof ServerPlayer player && !access.meridian$getWeakNotice()) {
            player.sendOverlayMessage(
                    Component.translatable(registered.benefit().getLeap().getFailureTranslationKey()));
            access.meridian$markWeakNotice();
        }
    }

    public static boolean travel(LivingEntity actor) {
        // 仅起跳轨迹接管加速度，位移仍经 Entity.move 的原版方块碰撞和 fall 路径。
        // 玩家的原版移动包比较基准随服务端位移同步，落地后的首个移动包不会按空中旧位置判为移动异常。
        var access = (TechniqueLeapAccess) actor;
        TechniqueLeapFlight flight = access.meridian$getLeap();
        if (flight == null) {
            return false;
        }
        if (!actor.isAlive() || actor.isPassenger() || actor.isInWater() || actor.isInLava()
                || flight.ticks >= MAX_FLIGHT_TICKS) {
            finish(actor, flight, LeapResult.CANCELLED);
            return false;
        }
        Vec3 before = actor.position();
        actor.setDeltaMovement(flight.velocity);
        actor.move(MoverType.SELF, flight.velocity);
        if (flight.preLandingContact == null) {
            flight.preLandingContact = preLandingContact(actor, flight, before, actor.position());
        }
        flight.ticks++;
        Vec3 moved = actor.position().subtract(before);
        if (actor instanceof ServerPlayer player) {
            player.doCheckFallDamage(moved.x, moved.y, moved.z, actor.onGround());
            player.connection.resetPosition();
        }
        if (flight.wallCollisionPolicy == TechniqueLeapFlight.WallCollisionPolicy.FINISH
                && (actor.horizontalCollision || actor.verticalCollision && flight.velocity.y > 0)) {
            actor.setDeltaMovement(Vec3.ZERO);
            finish(actor, flight, LeapResult.BLOCKED);
            return true;
        }
        if (actor.onGround()) {
            contactAtLanding(actor, flight);
            landed(actor, flight);
            return true;
        }
        flight.velocity = nextVelocity(actor, flight.velocity);
        actor.setDeltaMovement(flight.velocity);
        actor.calculateEntityAnimation(false /* flutter */);
        return true;
    }

    private static void contactAtLanding(LivingEntity actor, TechniqueLeapFlight flight) {
        // 仅在实际落地后沿冻结水平正前方探半格，飞行途中不检测实体。
        if (flight.contact == null || flight.contacted) {
            return;
        }
        if (flight.preLandingContact != null && canContact(actor, flight.preLandingContact)) {
            flight.contacted = true;
            flight.contact.accept(flight.preLandingContact);
            return;
        }
        Vec3 direction = new Vec3(flight.launch.x, 0, flight.launch.z).normalize();
        AABB contactBox = actor.getBoundingBox().move(direction.scale(LANDING_CONTACT_REACH));
        actor.level().getEntitiesOfClass(LivingEntity.class, contactBox, target -> canContact(actor, target))
                .stream()
                .sorted(Comparator.comparingDouble((LivingEntity target) -> actor.distanceToSqr(target))
                        .thenComparing(target -> target.getUUID().toString()))
                .findFirst()
                .ifPresent(target -> {
                    flight.contacted = true;
                    flight.contact.accept(target);
                });
    }

    private static LivingEntity preLandingContact(LivingEntity actor, TechniqueLeapFlight flight,
                                                   Vec3 start, Vec3 end) {
        if (flight.contact == null) {
            return null;
        }
        double halfWidth = actor.getBbWidth() * HALF_WIDTH;
        double halfHeight = actor.getBbHeight() * HALF_WIDTH;
        return actor.level().getEntitiesOfClass(LivingEntity.class,
                        actor.getBoundingBox().expandTowards(flight.velocity), target -> canContact(actor, target)
                                && target.getBoundingBox().inflate(halfWidth, halfHeight, halfWidth)
                                        .clip(start, end).isPresent())
                .stream()
                .sorted(Comparator.comparingDouble((LivingEntity target) ->
                                target.getBoundingBox().inflate(halfWidth, halfHeight, halfWidth)
                                        .clip(start, end).orElseThrow().distanceToSqr(start))
                        .thenComparing(target -> target.getUUID().toString()))
                .findFirst()
                .orElse(null);
    }

    public static void lockDirectedActions(LivingEntity actor) {
        // 尸修定向施放在起手前锁住 Mob 自主移动和普通攻击。
        ((TechniqueLeapAccess) actor).meridian$lockDirectedActions();
    }

    public static void unlockDirectedActions(LivingEntity actor) {
        // 结束或取消定向施放后恢复 Mob 的自主动作。
        ((TechniqueLeapAccess) actor).meridian$unlockDirectedActions();
    }

    private static boolean canContact(LivingEntity actor, LivingEntity target) {
        // 只让活着且可攻击的接触对象进入招式回调。
        if (target == actor || !target.isAlive()) {
            return false;
        }
        return !(actor instanceof Mob mob) || mob.canAttack(target);
    }

    private static Vec3 nextVelocity(LivingEntity actor, Vec3 current) {
        // 碰墙消除水平速度，顶头从零速度开始下降，其余情况延续离散重力。
        double x;
        double z;
        if (actor.horizontalCollision) {
            x = 0;
            z = 0;
        } else {
            x = current.x;
            z = current.z;
        }
        double vertical;
        if (actor.verticalCollision && current.y > 0) {
            vertical = TechniqueLeap.nextVertical(0);
        } else {
            vertical = TechniqueLeap.nextVertical(current.y);
        }
        return new Vec3(x, vertical, z);
    }

    public static double fallDistance(LivingEntity actor, double vanillaDistance) {
        // 安全落点以上的抛物线下降免伤；低于求解落点的额外落差照常结算。26.2 的摔落距离是 double。
        TechniqueLeapFlight flight = ((TechniqueLeapAccess) actor).meridian$getLeap();
        if (flight == null) {
            return vanillaDistance;
        }
        return Math.min(vanillaDistance, Math.max(0, flight.landingY - actor.getY()));
    }

    private static void landed(LivingEntity actor, TechniqueLeapFlight flight) {
        // 落地声沿用脚下方块，只有服务端广播，避免预测双响；类别与功法声音一样跟施放者。
        if (!actor.level().isClientSide()) {
            var group = actor.level().getBlockState(actor.blockPosition().below()).getSoundType();
            actor.level().playSound(null, actor.getX(), actor.getY(), actor.getZ(), group.getStepSound(),
                    TechniqueSounds.source(actor), group.getVolume() * STEP_VOLUME, group.getPitch());
        }
        actor.setDeltaMovement(Vec3.ZERO);
        actor.fallDistance = 0;
        finish(actor, flight, LeapResult.LANDED);
    }

    public static void cancel(LivingEntity actor) {
        // 卸载或死亡后宿主可能已不可访问，只释放轨迹并清理依赖方状态。
        var access = (TechniqueLeapAccess) actor;
        TechniqueLeapFlight flight = access.meridian$getLeap();
        if (flight == null) {
            return;
        }
        access.meridian$setLeap(null);
        flight.end = actor.position();
        complete(actor, flight, LeapResult.CANCELLED);
    }

    private static void finish(LivingEntity actor, TechniqueLeapFlight flight, LeapResult result) {
        // 先停止本次动画再释放轨迹；间隔只从服务端真实落地或中断时刻计，玩家由 FINISH 回执自行对账。
        var access = (TechniqueLeapAccess) actor;
        if (access.meridian$getLeap() != flight) {
            return;
        }
        access.meridian$setLeap(null);
        flight.end = actor.position();
        if (!actor.level().isClientSide() && TechniqueFabric.running()) {
            int delay = flight.profile.getLandingDelayTicks();
            access.meridian$setLeapReadyTick(actor.level().getGameTime() + delay);
            present(actor, flight, PresentationAction.STOP);
            TechniqueLeapNetworking.send(actor, LeapMotion.Action.FINISH, flight);
        }
        complete(actor, flight, result);
    }

    private static void complete(LivingEntity actor, TechniqueLeapFlight flight, LeapResult result) {
        if (flight.completion != null) {
            try {
                flight.completion.accept(result);
            } catch (RuntimeException failure) {
                LOG.warn("跃步完成回调处理失败：{}", actor.getUUID(), failure);
            }
        }
    }

    private static void present(LivingEntity actor, TechniqueLeapFlight flight, PresentationAction action) {
        // 复用宿主的 PA 映射和 STOP 回执，不把动画实现依赖放进功法库。
        var presentation = PresentationIntent.newBuilder()
                .setId(flight.profile.getPresentationId())
                .setAction(action)
                .build();
        var effect = EffectIntent.newBuilder()
                .setCastId(flight.castId)
                .setEffectId(action.name().toLowerCase(Locale.ROOT))
                .setScheduledMillis(actor.level().getServer().overworld().getGameTime() * MILLIS_PER_TICK)
                .setPresentation(presentation)
                .build();
        TechniqueFabric.service().presentLeap(actor.getUUID(), effect);
    }

    public static void addDirector(Predicate<Mob> directs) {
        // 宿主大脑登记它指挥跃步的实体；被指挥者不走自动追击，由大脑调用 leapToward。
        directors.add(Objects.requireNonNull(directs));
    }

    public static Jump leapToward(Mob actor, Vec3 desired) {
        // 大脑给出期望落点，求解、做功、碰撞与自动追击同一实现；没有轻身时按普通跳跃处理。
        var registered = TechniqueStatusBenefits.activeLeap(actor).orElse(null);
        if (registered == null) {
            return Jump.ORDINARY;
        }
        var landing = TechniqueLeapTargets.toward(actor, registered.benefit().getLeap(), desired);
        Jump jump = start(actor, registered, landing);
        if (jump == Jump.LEAP) {
            actor.getNavigation().stop();
        }
        return jump;
    }

    public static Optional<Preview> preview(LivingEntity actor, Vec3 desired) {
        // 只求解不起跳：不扣体力、不改实体；没有轻身或尚不能起跳时为空。
        var registered = TechniqueStatusBenefits.activeLeap(actor).orElse(null);
        if (registered == null || !ready(actor)) {
            return Optional.empty();
        }
        var profile = registered.benefit().getLeap();
        var landing = TechniqueLeapTargets.toward(actor, profile, desired).point();
        int ticks = TechniqueLeap.flightTicks(profile, TechniqueLeapTargets.point(actor.position()), landing);
        return Optional.of(new Preview(TechniqueLeapTargets.vector(landing), ticks * MILLIS_PER_TICK));
    }

    public static void tickMob(Mob actor) {
        // 原版目标决定追击对象；已施放、无目标、未落地或由宿主大脑指挥的 tick 不绑定身体或寻找落点。
        if (actor.level().isClientSide() || actor.isNoAi() || !actor.onGround()) {
            return;
        }
        LivingEntity target = actor.getTarget();
        var registered = TechniqueStatusBenefits.activeLeap(actor).orElse(null);
        if (target == null || !target.isAlive() || registered == null || directed(actor)) {
            return;
        }
        var profile = registered.benefit().getLeap();
        if (actor.distanceTo(target) < profile.getAiMinimumDistance()
                || Math.floorMod(actor.tickCount + actor.getId(), profile.getAiDecisionTicks()) != 0) {
            return;
        }
        var landing = TechniqueLeapTargets.toward(actor, profile, target.position());
        if (start(actor, registered, landing) == Jump.LEAP) {
            actor.getNavigation().stop();
        }
    }

    private static boolean directed(Mob actor) {
        // 任一宿主大脑认领即视为被指挥。
        for (Predicate<Mob> director : directors) {
            if (director.test(actor)) {
                return true;
            }
        }
        return false;
    }
}
