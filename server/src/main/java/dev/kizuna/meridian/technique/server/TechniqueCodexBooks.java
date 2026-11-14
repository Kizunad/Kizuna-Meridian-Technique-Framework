package dev.kizuna.meridian.technique.server;

import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueReference;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 功法谱的完成度：按卷轴清单看哪些书的每一卷都学会了；进度触发器和测试共用同一个判定。 */
public final class TechniqueCodexBooks {
    private TechniqueCodexBooks() {
        // 只有静态判定。
    }

    public static List<String> completed(Collection<TechniqueScrollCatalog.Scroll> scrolls,
                                         Set<TechniqueReference> known) {
        // 书按卷轴首次出现的顺序；一卷算学会 = 这卷每一式的精确版本都已学，与卷轴物品的判定一致。
        var books = new LinkedHashMap<String, Boolean>();
        for (var scroll : scrolls) {
            boolean learned = known.containsAll(scroll.volume().getTechniquesList());
            books.merge(scroll.volume().getBookId(), learned, Boolean::logicalAnd);
        }
        return books.entrySet().stream().filter(Map.Entry::getValue).map(Map.Entry::getKey).toList();
    }

    public static boolean everyBook(Collection<TechniqueScrollCatalog.Scroll> scrolls, List<String> completed) {
        // 全谱：有卷轴的书一本不落；一卷卷轴都没登记时不算凑齐。
        long books = scrolls.stream().map(scroll -> scroll.volume().getBookId()).distinct().count();
        return books > 0 && completed.size() == books;
    }
}
