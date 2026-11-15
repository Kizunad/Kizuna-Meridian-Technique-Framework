package dev.kizuna.meridian.technique.fabric.world;

import net.fabricmc.loader.api.FabricLoader;
import org.tomlj.Toml;
import org.tomlj.TomlParseResult;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 服务端的手感数值，和客户端的打击反馈开关同在 config/meridian-technique-feel.toml 里。
 * 文件或键不在时用默认值；单机里这份文件由客户端第一次进世界时写出。
 */
record TechniqueFeelServerSettings(long inputBufferMillis) {
    private static final String FILE = "meridian-technique-feel.toml";
    static final long DEFAULT_INPUT_BUFFER_MILLIS = 400;
    private static final long MAX_INPUT_BUFFER_MILLIS = 2000;

    static TechniqueFeelServerSettings load() {
        Path path = FabricLoader.getInstance().getConfigDir().resolve(FILE);
        if (Files.notExists(path)) {
            return new TechniqueFeelServerSettings(DEFAULT_INPUT_BUFFER_MILLIS);
        }
        try {
            return parse(Files.readString(path));
        } catch (IOException failure) {
            throw new UncheckedIOException("读不了手感配置 " + path, failure);
        }
    }

    static TechniqueFeelServerSettings parse(String text) {
        // 缺键用默认值；写错类型或越界直接报错，不悄悄当成默认。0 是关掉输入缓冲。
        TomlParseResult toml = Toml.parse(text);
        if (toml.hasErrors()) {
            throw new IllegalArgumentException(FILE + " 写错了：" + toml.errors().getFirst());
        }
        Long buffer = toml.getLong("input_buffer_millis");
        long millis = buffer == null ? DEFAULT_INPUT_BUFFER_MILLIS : buffer;
        if (millis < 0 || millis > MAX_INPUT_BUFFER_MILLIS) {
            throw new IllegalArgumentException(FILE + " 的 input_buffer_millis 要在 0 到 "
                    + MAX_INPUT_BUFFER_MILLIS + " 之间：" + millis);
        }
        return new TechniqueFeelServerSettings(millis);
    }
}
