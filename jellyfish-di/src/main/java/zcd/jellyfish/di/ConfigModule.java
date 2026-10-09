package zcd.jellyfish.di;

import dagger.Module;
import dagger.Provides;
import zcd.jellyfish.infra.config.AppConfig;
import zcd.jellyfish.infra.config.ConfigLoader;
import zcd.jellyfish.infra.config.ProjectConfigTrust;
import zcd.jellyfish.infra.config.SettingsBinder;
import zcd.jellyfish.infra.config.SettingsReader;

import javax.inject.Singleton;

/**
 * 应用级配置的 Dagger2 模块。
 * <p>
 * {@link AppConfig} 是纯数据类，读取 classpath 的 {@code config.json} 需要文件 IO 与解析器，
 * 因此把「如何得到 AppConfig」放在最外层的 composition root，由 {@link ConfigLoader} 创建，
 * infra 只暴露不带副作用的构造器。组件与 Module 只存在于 {@code jellyfish-di}。
 * <p>
 * <b>{@code AppConfig} 本身由组件构建者注入</b>（见 {@link JellyfishComponent.Builder}），
 * 模块里没有它的 {@code @Provides}：配置来源是<b>部署事实</b>，两种装法都应当由各自的调用方决定——
 * 这也是报告 `N-07` 的结论（此前 Dagger 侧固定从 classpath 读，与手工装配侧不等价）。
 * CLI 要的是最常用的那一份来源（classpath），用 {@link #loadDefault()} 拿。
 *
 * @author zcd
 */
@Module
public final class ConfigModule {

    private ConfigModule() {
    }

    /**
     * 取最常用的配置来源：读 {@code classpath:config.json}。
     * <p>
     * <b>为什么它是普通静态方法而不是 {@code @Provides}</b>：{@code AppConfig} 现在由组件构建者以
     * {@code @BindsInstance} 注入，模块里不能再有第二条绑定（会重复绑定）。但「最常用的那一份来源」
     * 仍需要一个落点——否则每个只想要缺省行为的调用方（CLI、测试）都要自己把 {@link SettingsReader}
     * 与 {@link SettingsBinder} 拼一遍，那种重复必然漂移。
     *
     * @return 应用级配置；文件缺失或解析为 {@code null} 时为缺省配置
     * @throws zcd.jellyfish.api.JellyfishException 配置里的必需环境变量占位符缺失时抛出
     */
    public static AppConfig loadDefault() {
        return new ConfigLoader(new SettingsReader(), new SettingsBinder()).loadAppConfig();
    }

    /**
     * 提供项目级配置的信任裁决。
     * <p>
     * <b>必须唯一</b>：授予信任发生在启动期（启动参数 / 交互确认），而读取它发生在
     * {@code RuntimeConfig.refresh()}；两边必须看到同一份状态，否则确认了却不生效。
     * 与 {@code JellyfishAssembler} 里那一行是同一件事的两种装法。
     *
     * @return 信任裁决
     */
    @Provides
    @Singleton
    static ProjectConfigTrust provideProjectConfigTrust() {
        return new ProjectConfigTrust();
    }
}
