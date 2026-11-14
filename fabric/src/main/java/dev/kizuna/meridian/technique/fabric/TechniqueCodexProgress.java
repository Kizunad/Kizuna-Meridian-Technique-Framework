package dev.kizuna.meridian.technique.fabric;

import dev.kizuna.meridian.technique.server.TechniqueCodexBooks;
import dev.kizuna.meridian.technique.server.TechniqueService;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** 已学列表一变、玩家进服时核对凑齐了哪些书、是否凑齐全谱，交给两个进度触发器。 */
final class TechniqueCodexProgress {
    private TechniqueCodexProgress() {
        // 只有静态入口。
    }

    static void initialize() {
        // 进服补查一遍：老档早已凑齐的、装新包前凑齐的，都在这里补上进度。
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> check(handler.player));
    }

    static void knownChanged(MinecraftServer server, UUID entityUuid, List<TechniqueService.KnownEntry> known) {
        // 功法服务对每个实体都发通知，只有在线玩家才有进度。
        ServerPlayer player = server.getPlayerList().getPlayer(entityUuid);
        if (player != null) {
            report(player, known);
        }
    }

    private static void check(ServerPlayer player) {
        // 没有功法目录的服务器不核对。
        if (TechniqueFabric.running()) {
            report(player, TechniqueFabric.service().known(player.getUUID()));
        }
    }

    private static void report(ServerPlayer player, List<TechniqueService.KnownEntry> known) {
        // 每次都按当前已学全量核对；原版只看还没完成的进度，已完成的不会重复授予。
        var references = known.stream().map(TechniqueService.KnownEntry::technique).collect(Collectors.toSet());
        var scrolls = TechniqueScrollItems.scrolls().values().stream().map(TechniqueScrollItem::scroll).toList();
        var completed = TechniqueCodexBooks.completed(scrolls, references);
        completed.forEach(book -> BookCompletedTrigger.TRIGGER.trigger(player, book));
        if (TechniqueCodexBooks.everyBook(scrolls, completed)) {
            CodexCompletedTrigger.TRIGGER.trigger(player);
        }
    }
}
