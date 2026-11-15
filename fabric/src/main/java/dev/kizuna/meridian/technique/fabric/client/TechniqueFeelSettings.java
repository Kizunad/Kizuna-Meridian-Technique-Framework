package dev.kizuna.meridian.technique.fabric.client;

import net.fabricmc.loader.api.FabricLoader;
import org.tomlj.Toml;
import org.tomlj.TomlParseResult;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** 本机的打击反馈开关，读 config/meridian-technique-feel.toml；文件不在就写一份默认的。只影响自己看到的画面。 */
record TechniqueFeelSettings(boolean hitParticles, boolean blood) {
    private static final String FILE = "meridian-technique-feel.toml";
    private static final String DEFAULTS = """
            # 打击反馈（只影响你自己的画面，改完重进世界生效）

            # 打中时要不要出粒子。
            hit_particles = true

            # 打中有血肉的目标时出不出血。关掉以后换成普通的冲击粒子。
            blood = true
            """;

    static TechniqueFeelSettings load() {
        Path path = FabricLoader.getInstance().getConfigDir().resolve(FILE);
        try {
            if (Files.notExists(path)) {
                Files.createDirectories(path.getParent());
                Files.writeString(path, DEFAULTS);
            }
            return parse(Files.readString(path));
        } catch (IOException failure) {
            throw new UncheckedIOException("读不了打击反馈配置 " + path, failure);
        }
    }

    static TechniqueFeelSettings parse(String text) {
        // 缺的键用默认值；写错类型直接报错，不悄悄当成默认。
        TomlParseResult toml = Toml.parse(text);
        if (toml.hasErrors()) {
            throw new IllegalArgumentException(FILE + " 写错了：" + toml.errors().getFirst());
        }
        Boolean particles = toml.getBoolean("hit_particles");
        Boolean blood = toml.getBoolean("blood");
        return new TechniqueFeelSettings(particles == null || particles, blood == null || blood);
    }
}
