package zcd.jellyfish.plugin.tools;

import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.extension.ToolCallRequest;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * 工具测试的公共辅助：造参数、调工具、断言失败。
 * <p>
 * 三种写法在五个工具里重复出现，抽出来是为了让每个用例只剩「给什么、期望什么」。
 *
 * @author zcd
 */
final class ToolTestSupport {

    /**
     * 工具类，禁止实例化。
     */
    private ToolTestSupport() {
    }

    /**
     * 按「参数名 / 参数值」交替的行文构造参数映射。
     *
     * @param namesAndValues 参数名与值交替出现
     * @return 有序参数映射
     */
    static Map<String, Object> args(Object... namesAndValues) {
        Map<String, Object> arguments = new LinkedHashMap<String, Object>();
        for (int i = 0; i < namesAndValues.length; i += 2) {
            arguments.put((String) namesAndValues[i], namesAndValues[i + 1]);
        }
        return arguments;
    }

    /**
     * 以指定参数调用工具，返回输出文本。
     *
     * @param tool      工具
     * @param arguments 参数
     * @return 输出文本
     * @throws Exception 工具抛出的异常
     */
    static String invoke(PluginTool tool, Map<String, Object> arguments) throws Exception {
        return String.valueOf(tool.handle(new ToolCallRequest(tool.name(), arguments)).getOutput());
    }

    /**
     * 断言一次调用以 {@link JellyfishException} 失败。
     *
     * @param invocation 待执行的调用
     * @return 捕获到的异常，便于继续断言错误文案
     */
    static JellyfishException expectFailure(ToolInvocation invocation) {
        try {
            invocation.call();
        } catch (JellyfishException e) {
            return e;
        } catch (Exception e) {
            throw new AssertionError("期望 JellyfishException，实际抛出 " + e, e);
        }
        return fail("期望抛出 JellyfishException，但调用成功返回");
    }

    /**
     * 可抛受检异常的调用，用于把工具调用包成 lambda。
     */
    @FunctionalInterface
    interface ToolInvocation {

        /**
         * 执行调用。
         *
         * @return 任意返回值
         * @throws Exception 调用失败时抛出
         */
        Object call() throws Exception;
    }
}
