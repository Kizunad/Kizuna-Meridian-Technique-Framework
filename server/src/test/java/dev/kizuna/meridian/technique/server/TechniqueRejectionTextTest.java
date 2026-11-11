package dev.kizuna.meridian.technique.server;

import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.pb.BodyMessages.*;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.*;
import org.junit.Test;

import static dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueFailure.*;
import static dev.kizuna.meridian.technique.server.Fixtures.*;
import static org.junit.Assert.*;

/** 起手拒绝说清缺的是哪一样：力竭的筋肉、没有的器官、没有余力的器官；失败码保持原样且不落账。 */
public final class TechniqueRejectionTextTest {
    private static final double TIRED_ORGAN_JOULES = 995;

    @Test
    public void exhaustedTissueIsNamed() {
        // 疲劳到上限的组织不可绑定，说明写出组织显示名和「力竭」。
        var message = BodyProtobuf.toMessage(body(Shape.TENDRIL));
        TissueState tired = message.getTissueStates(0).toBuilder().setFatigue(ENDURANCE).build();
        BodySnapshot body = BodyProtobuf.fromMessage(message.toBuilder().setTissueStates(0, tired).build());
        BindingOutcome result = TechniqueBinder.bind(body, definition());
        assertEquals(MODULE_UNAVAILABLE, result.getFailure());
        assertEquals("测试组织力竭", result.getReason());
    }

    @Test
    public void missingOrganNamesTheModuleKind() {
        // 构型里根本没有带标签的器官时，说明缺的是器官，不再写「身体尚未准备好」。
        var message = BodyProtobuf.toMessage(body(Shape.TENDRIL));
        var structure = message.getStructure().toBuilder().clearOrgans().build();
        BodySnapshot body = BodyProtobuf.fromMessage(message.toBuilder().setStructure(structure)
                .clearOrganStates().build());
        BindingOutcome result = TechniqueBinder.bind(body, definition());
        assertEquals(MISSING_MODULE, result.getFailure());
        assertEquals("这具身体没有这一式要用的器官", result.getReason());
    }

    @Test
    public void tiredOrganIsNamedWithTheRequiredWorkAndNothingIsCharged() {
        // 器官剩 5 J、功率上限 2 W，付不起 20 W × 0.5 s；起手前就拒绝，并写出器官名和要的功。
        var message = BodyProtobuf.toMessage(body(Shape.TENDRIL));
        OrganState tired = message.getOrganStates(0).toBuilder().setFatigueJoules(TIRED_ORGAN_JOULES).build();
        BodySnapshot before = BodyProtobuf.fromMessage(message.toBuilder().setOrganStates(0, tired).build());
        var host = new MemoryHost(before);
        CastOutcome outcome = new TechniqueCasting(registry(definition())).cast(host, request(before, "tired"));
        assertEquals(INSUFFICIENT_CAPABILITY, outcome.getFailure());
        assertEquals("测试器官没有余力：要 20 W × 0.5 s", outcome.getReason());
        assertEquals(before, host.current());
        assertEquals(0, host.activitySubmissions);
    }
}
