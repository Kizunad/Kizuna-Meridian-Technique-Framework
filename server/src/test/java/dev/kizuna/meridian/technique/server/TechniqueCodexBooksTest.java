package dev.kizuna.meridian.technique.server;

import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueCatalog;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueReference;
import org.junit.Test;

import java.util.List;
import java.util.Set;

import static dev.kizuna.meridian.technique.server.Fixtures.addons;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class TechniqueCodexBooksTest {
    private static final TechniqueReference MOVE = reference("sample:move", 1);
    private static final TechniqueReference GUARD = reference("sample:guard", 1);
    private static final TechniqueReference STEP = reference("sample:step", 1);

    @Test
    public void bookCountsOnlyWhenEveryVolumeIsLearned() {
        // 两卷的书学了一卷不算；两卷都学了才算，另一本没学的不出现。
        var scrolls = scrolls();
        assertEquals(List.of(), TechniqueCodexBooks.completed(scrolls, Set.of(MOVE)));
        assertEquals(List.of("sample:first_book"), TechniqueCodexBooks.completed(scrolls, Set.of(MOVE, GUARD)));
    }

    @Test
    public void volumeNeedsTheExactVersionOfEveryTechnique() {
        // 旧版本的式不算学会这卷，与卷轴物品判定一致。
        var scrolls = scrolls();
        var oldGuard = reference("sample:guard", 0);
        assertEquals(List.of(), TechniqueCodexBooks.completed(scrolls, Set.of(MOVE, oldGuard)));
    }

    @Test
    public void everyBookNeedsEachBookWithScrolls() {
        // 两本都凑齐才算全谱；没有卷轴时不算凑齐。
        var scrolls = scrolls();
        var oneBook = TechniqueCodexBooks.completed(scrolls, Set.of(MOVE, GUARD));
        var bothBooks = TechniqueCodexBooks.completed(scrolls, Set.of(MOVE, GUARD, STEP));
        assertFalse(TechniqueCodexBooks.everyBook(scrolls, oneBook));
        assertEquals(List.of("sample:first_book", "sample:second_book"), bothBooks);
        assertTrue(TechniqueCodexBooks.everyBook(scrolls, bothBooks));
        assertFalse(TechniqueCodexBooks.everyBook(List.of(), List.of()));
    }

    private static List<TechniqueScrollCatalog.Scroll> scrolls() {
        // 第一本书两卷各一式，第二本书一卷一式。
        TechniqueCatalog catalog = TechniqueCatalogManifest.parseText("fixture", """
                schema_version: 1
                namespace: "sample"
                books { id: "sample:first_book" }
                books { id: "sample:second_book" }
                volumes { id: "sample:upper" book_id: "sample:first_book" techniques { id: "sample:move" version: 1 } }
                volumes { id: "sample:lower" book_id: "sample:first_book" techniques { id: "sample:guard" version: 1 } }
                volumes { id: "sample:only" book_id: "sample:second_book" techniques { id: "sample:step" version: 1 } }
                definitions { id: "sample:move" version: 1 }
                definitions { id: "sample:guard" version: 1 }
                definitions { id: "sample:step" version: 1 }
                """);
        var source = new TechniqueCatalogSource("sample:catalog.txtpb", addons(), catalog);
        return new TechniqueScrollCatalog(List.of(source)).registrationOrder();
    }

    private static TechniqueReference reference(String id, int version) {
        // 式的精确版本引用。
        return TechniqueReference.newBuilder().setId(id).setVersion(version).build();
    }
}
