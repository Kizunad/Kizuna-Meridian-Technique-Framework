package dev.kizuna.meridian.technique.server;

/** Fabric 可信宿主的进程内字节交换入口；实体归属和世界副作用由宿主负责。 */
@FunctionalInterface
public interface TechniqueHostEntrypoint {
    String ENTRYPOINT_ID = "meridian_technique_host";

    /** 交换一份完整的 TechniqueHostRequest 或 TechniqueHostResponse 字节载荷。 */
    byte[] exchange(byte[] request);
}
