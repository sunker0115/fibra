package com.sstlfsj.fibra.artifact;

/** 一个已核 package revision 的固定配置文档惰性读取句柄；调用方负责关闭。 */
public interface PluginConfigurationResource extends AutoCloseable {
    /**
     * 读取至多 1 MiB 的文档并核验原 revision 的字节身份，返回防御副本。
     * 重复调用（包括成功关闭之后）观察同一读终态；尚未开始读取就已关闭的句柄拒绝读取。
     *
     * @throws java.util.NoSuchElementException 仅当绑定 revision 原本没有配置文档
     * @throws ArtifactException 原资源非普通文件、实际读取失败或字节身份不符
     * @throws IllegalStateException 未开始读取就已关闭
     */
    byte[] read();

    /** 等待实际读取结束并关闭资源；关闭失败保留资源，重复调用不重试物理关闭。 */
    @Override
    void close();
}
