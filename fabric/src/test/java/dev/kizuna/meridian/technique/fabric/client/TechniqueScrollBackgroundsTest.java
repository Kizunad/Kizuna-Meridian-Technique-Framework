package dev.kizuna.meridian.technique.fabric.client;

import org.junit.Test;

import javax.imageio.ImageIO;
import net.minecraft.resources.ResourceLocation;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public final class TechniqueScrollBackgroundsTest {
    @Test
    public void missingVolumeBackgroundUsesBundledPaperAndWarnsOnce() throws IOException {
        // 缺失同一背景多次时始终回退，告警只记录第一次。
        ResourceLocation missing = new ResourceLocation("scroll_test", "textures/gui/scroll/absent.png");
        var warnings = new ArrayList<ResourceLocation>();
        ResourceLocation first = TechniqueScrollBackgrounds.resolve(missing, resource -> false, warnings::add);
        ResourceLocation second = TechniqueScrollBackgrounds.resolve(missing, resource -> false, warnings::add);

        assertEquals(TechniqueScrollBackgrounds.FALLBACK, first);
        assertEquals(TechniqueScrollBackgrounds.FALLBACK, second);
        assertEquals(List.of(missing), warnings);
        var resource = TechniqueScrollBackgroundsTest.class.getResource(
                "/assets/meridian_technique/textures/gui/scroll/generic.png");
        assertNotNull(resource);
        var image = ImageIO.read(resource);
        assertNotNull(image);
        assertEquals(TechniqueScrollLayout.TEXTURE_WIDTH, image.getWidth());
        assertEquals(TechniqueScrollLayout.TEXTURE_HEIGHT, image.getHeight());
    }

    @Test
    public void availableVolumeBackgroundIsKeptWithoutWarning() {
        // 已存在的卷专属图直接返回，不触发缺图警告。
        ResourceLocation background = new ResourceLocation("scroll_test", "textures/gui/scroll/upper.png");
        var warnings = new ArrayList<ResourceLocation>();
        ResourceLocation selected = TechniqueScrollBackgrounds.resolve(
                background, background::equals, warnings::add);

        assertEquals(background, selected);
        assertEquals(List.of(), warnings);
    }

    @Test
    public void restoredResourceOverridesFallbackAndMissingWarningsStayPerResource() {
        // 重载补图后立即选回专属图；另一个缺失资源仍有自己的首次告警。
        ResourceLocation restored = new ResourceLocation("scroll_test", "textures/gui/scroll/book/restored.png");
        ResourceLocation missing = new ResourceLocation("scroll_test", "textures/gui/scroll/book/other.png");
        var warnings = new ArrayList<ResourceLocation>();
        assertEquals(TechniqueScrollBackgrounds.FALLBACK,
                TechniqueScrollBackgrounds.resolve(restored, resource -> false, warnings::add));
        assertEquals(restored,
                TechniqueScrollBackgrounds.resolve(restored, restored::equals, warnings::add));
        assertEquals(TechniqueScrollBackgrounds.FALLBACK,
                TechniqueScrollBackgrounds.resolve(missing, restored::equals, warnings::add));
        assertEquals(List.of(restored, missing), warnings);
    }
}
