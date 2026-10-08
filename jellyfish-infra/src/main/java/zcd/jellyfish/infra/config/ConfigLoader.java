package zcd.jellyfish.infra.config;

import org.apache.commons.lang3.StringUtils;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.infra.support.ObjectMapperWrapper;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * 配置读取门面：把「路径 → 文本 → 环境变量替换 → 绑定类型」串起来。
 * <p>
 * 读取职责集中在此，配置类（{@link AppConfig} / {@link ModelSettings} 等）保持纯数据。
 * 读取器与绑定器均以构造器注入，便于替换与测试。
 *
 * @author zcd
 */
@Singleton
public class ConfigLoader {

    private final SettingsReader settingsReader;

    private final SettingsBinder settingsBinder;

    /**
     * 构造器注入。
     *
     * @param settingsReader 配置文本读取器
     * @param settingsBinder 配置文本绑定器
     */
    @Inject
    public ConfigLoader(SettingsReader settingsReader, SettingsBinder settingsBinder) {
        this.settingsReader = settingsReader;
        this.settingsBinder = settingsBinder;
    }

    /**
     * 读取并解析 {@code classpath:config.json}。
     *
     * @return 解析后的应用配置；文件缺失、内容为空或解析结果为 {@code null} 时返回缺省配置
     */
    public AppConfig loadAppConfig() {
        AppConfig loaded = read(AppConfig.CONFIG_PATH, AppConfig.class);
        return loaded == null ? new AppConfig(null, null, null, null, null) : loaded;
    }

    /**
     * 读取单个配置文件并绑定到目标类型。
     *
     * @param path 完整文件路径，可为空
     * @param type 绑定类型
     * @param <T>  配置类型
     * @return 绑定结果；路径为空、文件缺失或内容为空时返回 {@code null}
     * @throws JellyfishException JSON 非法、绑定失败或必需的环境变量缺失时抛出
     */
    public <T> T read(String path, Class<T> type) {
        return read(path, type, unknown -> { });
    }

    /**
     * 读取单个配置文件并绑定到目标类型，同时报告「绑不上的字段名」。
     * <p>
     * <b>为什么把未知字段单独交出去</b>：绑定本身刻意容忍未知字段（配置文件要向前兼容），
     * 因此「拼错了」与「内核不认识这个字段」在返回值上完全看不出来。读取这一层知道文件路径，
     * 是唯一能把「哪个文件的哪个字段」讲清楚的地方；至于报成告警还是直接忽略，由调用方决定。
     *
     * @param path               完整文件路径，可为空
     * @param type               绑定类型
     * @param unknownFieldReport 未知字段的接收方（每发现一个调用一次，参数是可定位的字段名），不可为 {@code null}
     * @param <T>                配置类型
     * @return 绑定结果；路径为空、文件缺失或内容为空时返回 {@code null}
     * @throws JellyfishException JSON 非法、绑定失败或必需的环境变量缺失时抛出
     */
    public <T> T read(String path, Class<T> type, Consumer<String> unknownFieldReport) {
        Objects.requireNonNull(unknownFieldReport, "unknownFieldReport must not be null");
        if (StringUtils.isBlank(path)) {
            return null;
        }
        String json = settingsReader.read(path);
        for (String field : ObjectMapperWrapper.unknownFields(json, type)) {
            unknownFieldReport.accept(field);
        }
        return settingsBinder.bind(json, type, path);
    }
}
