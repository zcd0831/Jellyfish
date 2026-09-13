package zcd.jellyfish.api.event.callback;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 回调开放标记：被标注的回调类型允许插件注册处理器。
 * <p>
 * 安全边界：未标注的回调类型（如权限检查回调）只允许核心组件注册，插件注册时直接失败，
 * 防止插件覆盖核心回调绕过权限控制。
 *
 * @author zcd
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface PluginExtensible {
}
