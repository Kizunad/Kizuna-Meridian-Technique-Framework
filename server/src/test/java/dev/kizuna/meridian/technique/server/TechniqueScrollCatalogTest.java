package dev.kizuna.meridian.technique.server;

import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueCatalog;
import org.junit.Test;

import java.util.List;

import static dev.kizuna.meridian.technique.server.Fixtures.addons;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class TechniqueScrollCatalogTest {
    @Test
    public void groupsCreativeVolumesByBookWithoutChangingRegistrationOrder() {
        // 交错声明保持注册身份顺序，创造栏按书聚合且保留书内卷序。
        TechniqueCatalog catalog = TechniqueCatalogManifest.parseText("fixture", """
                schema_version: 1
                namespace: "sample"
                books { id: "sample:second_book" display_name: "第二本书" }
                books { id: "sample:first_book" display_name: "第一本书" }
                volumes {
                  id: "sample:first" book_id: "sample:first_book" display_name: "上卷"
                  techniques { id: "sample:move" version: 1 }
                }
                volumes {
                  id: "sample:second" book_id: "sample:second_book"
                  display_name: "第二本书·第二卷"
                }
                volumes {
                  id: "sample:third" book_id: "sample:first_book"
                  prerequisite_volume_ids: "sample:first"
                }
                definitions {
                  id: "sample:move" version: 1
                  presentation { display_name: "探门" description: "沿门缝探入刀锋" }
                }
                """);
        var source = new TechniqueCatalogSource("sample:catalog.txtpb", addons(), catalog);
        var scrolls = new TechniqueScrollCatalog(List.of(source));
        assertEquals(List.of("sample:first_scroll", "sample:second_scroll", "sample:third_scroll"),
                scrolls.registrationOrder().stream().map(TechniqueScrollCatalog.Scroll::itemId).toList());
        assertEquals(List.of("sample:second_scroll", "sample:first_scroll", "sample:third_scroll"),
                scrolls.creativeOrder().stream().map(TechniqueScrollCatalog.Scroll::itemId).toList());
        var third = scrolls.registrationOrder().get(2);
        assertEquals(List.of("上卷"), third.prerequisites());
        assertEquals("未命名卷", third.name());
        assertEquals("第一本书", third.bookName());
        assertEquals("第一本书·未命名卷", third.displayName());
        var first = scrolls.registrationOrder().get(0);
        assertEquals("第一本书·上卷", first.displayName());
        assertEquals("第二本书·第二卷", scrolls.registrationOrder().get(1).displayName());
        assertEquals("探门", first.techniques().get(0).fallbackName());
        assertEquals("沿门缝探入刀锋", first.techniques().get(0).description());
        assertThrows(UnsupportedOperationException.class, () -> scrolls.registrationOrder().clear());
        assertThrows(UnsupportedOperationException.class, () -> scrolls.creativeOrder().clear());
    }

    @Test
    public void duplicateScrollIdentityIncludesBothSourcesBeforeRegistration() {
        // 物品注册前的身份预检指出双方来源，不允许部分卷轴先写入全局注册表。
        TechniqueCatalog catalog = TechniqueCatalogManifest.parseText("fixture", """
                schema_version: 1
                namespace: "sample"
                volumes { id: "sample:volume" book_id: "sample:book" }
                """);
        var addons = addons();
        var first = new TechniqueCatalogSource("first:catalog.txtpb", addons, catalog);
        var second = new TechniqueCatalogSource("second:catalog.txtpb", addons, catalog);
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> new TechniqueScrollCatalog(List.of(first, second)));
        String message = failure.getMessage();
        assertTrue(message, message.contains("first:catalog.txtpb"));
        assertTrue(message, message.contains("second:catalog.txtpb"));
        assertTrue(message, message.contains("volumes[0]"));
    }
}
