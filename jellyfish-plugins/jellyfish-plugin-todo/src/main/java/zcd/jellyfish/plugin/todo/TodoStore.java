package zcd.jellyfish.plugin.todo;

import zcd.jellyfish.api.JellyfishException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 待办仓库：一个会话一个 JSON 文件，读时懒加载、写时原子替换。
 * <p>
 * <b>为什么一个会话一个文件</b>：与内核会话同构，会话之间互不影响；将来要 git 管理或搜索也是按会话走。
 * <p>
 * <b>为什么要有一层内存缓存</b>：提示词贡献在<b>每一轮</b>请求组装时都会被问到，每次都读盘既慢又无谓；
 * 而待办只有经 {@link #replace} 才会变，缓存与文件因此天然一致。
 * <p>
 * <b>读不出来就抛，不当作空</b>：如果把坏文件当成「没有待办」，紧接着的一次 {@code todo_write} 就会把
 * 坏文件覆盖掉——那是真正的数据丢失。抛出去让调用点决定（提示词贡献跳过、命令报错、工具把错误回给模型），
 * 坏文件至少还在原处。
 * <p>
 * <b>先写临时文件再原子替换</b>：直接覆盖写时进程被杀死会留下半截 JSON。临时文件用 {@code .json.tmp}
 * 后缀，因此不会被读路径当成待办文件。
 * <p>
 * 文件操作同步阻塞在调用线程上（ReAct 回合线程或外壳线程）——待办变更本就该「写完才算数」。
 *
 * @author zcd
 */
final class TodoStore {

    /** 待办文件后缀。 */
    private static final String SUFFIX = ".json";

    /** 原子替换用的临时后缀，刻意不匹配 {@link #SUFFIX}。 */
    private static final String TEMP_SUFFIX = ".tmp";

    /** 待办文件所在目录。 */
    private final Path directory;

    /** sessionId → 该会话的待办列表；只由本类的方法访问，全部在实例锁内。 */
    private final Map<String, List<TodoItem>> cache = new HashMap<String, List<TodoItem>>();

    /**
     * 构造仓库。
     *
     * @param directory 待办文件目录，不可为 {@code null}
     */
    TodoStore(Path directory) {
        this.directory = directory;
    }

    /**
     * 取某会话的待办列表，首次访问时从文件懒加载。
     *
     * @param sessionId 会话标识，不可为空白且需可安全作为文件名
     * @return 不可修改的待办列表，没有待办时为空列表
     * @throws JellyfishException 会话标识非法，或文件存在但读不出来时抛出
     */
    synchronized List<TodoItem> itemsOf(String sessionId) {
        String key = requireSafeFileName(sessionId);
        List<TodoItem> cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        List<TodoItem> loaded = load(key);
        cache.put(key, loaded);
        return loaded;
    }

    /**
     * 用整份新列表覆盖某会话的待办。
     * <p>
     * 覆盖而不是增量：这是模型的写入语义（它看不到稳定的编号），也顺带让「删除」与「重排」无需额外命令。
     *
     * @param sessionId 会话标识，不可为空白且需可安全作为文件名
     * @param items     新的待办列表，不可为 {@code null}
     * @return 覆盖后的不可修改列表，保证非 {@code null}
     * @throws JellyfishException 会话标识非法或落盘失败时抛出
     */
    synchronized List<TodoItem> replace(String sessionId, List<TodoItem> items) {
        String key = requireSafeFileName(sessionId);
        List<TodoItem> copy = immutableCopy(items);
        Path file = fileOf(key);
        if (copy.isEmpty()) {
            // 空列表不留空文件：删掉比留一个 [] 更干净，读路径本来就把「文件不存在」当空
            deleteIfExists(file);
            cache.put(key, copy);
            return copy;
        }
        writeAtomically(file, TodoJson.write(copy));
        cache.put(key, copy);
        return copy;
    }

    /**
     * 取某会话待办文件路径。
     *
     * @param sessionId 会话标识
     * @return 文件路径
     */
    Path fileOf(String sessionId) {
        return directory.resolve(sessionId + SUFFIX);
    }

    /**
     * 从文件加载待办。
     *
     * @param sessionId 已校验的会话标识
     * @return 不可修改的待办列表
     * @throws JellyfishException 读取或解析失败时抛出
     */
    private List<TodoItem> load(String sessionId) {
        Path file = fileOf(sessionId);
        if (!Files.isRegularFile(file)) {
            // 从没写过待办：没有文件不是错误
            return Collections.emptyList();
        }
        String json;
        try {
            json = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new JellyfishException("读取待办文件失败: " + file + " (" + e.getMessage() + ')', e);
        }
        return immutableCopy(TodoJson.read(json, file.toString()));
    }

    /**
     * 删除文件，不存在时静默跳过。
     *
     * @param file 目标文件
     * @throws JellyfishException 删除失败时抛出
     */
    private static void deleteIfExists(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new JellyfishException("删除待办文件失败: " + file + " (" + e.getMessage() + ')', e);
        }
    }

    /**
     * 原子写入：先写临时文件，再替换目标文件。
     *
     * @param file 目标文件
     * @param json 内容
     * @throws JellyfishException 写入失败时抛出
     */
    private void writeAtomically(Path file, String json) {
        Path temp = file.resolveSibling(file.getFileName() + TEMP_SUFFIX);
        try {
            Files.createDirectories(directory);
            Files.write(temp, json.getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                // 少数文件系统不支持原子替换，退化到普通替换：至少目标路径上不会留下半截文件
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new JellyfishException("待办落盘失败: " + file + " (" + e.getMessage() + ')', e);
        }
    }

    /**
     * 复制成不可修改列表并拒绝 {@code null} 元素。
     *
     * @param items 原始列表，不可为 {@code null}
     * @return 不可修改副本，保证非 {@code null}
     * @throws JellyfishException 存在 {@code null} 元素时抛出
     */
    private static List<TodoItem> immutableCopy(List<TodoItem> items) {
        if (items.isEmpty()) {
            return Collections.emptyList();
        }
        List<TodoItem> copy = new ArrayList<TodoItem>(items.size());
        for (TodoItem item : items) {
            if (item == null) {
                throw new JellyfishException("todo item must not be null");
            }
            copy.add(item);
        }
        return Collections.unmodifiableList(copy);
    }

    /**
     * 校验会话标识可以安全地当作文件名。
     * <p>
     * 会话标识会直接拼进文件路径。不校验的话，一个写着 {@code ../../x} 的标识就能把文件写到目录之外。
     *
     * @param sessionId 会话标识
     * @return 校验通过的标识
     * @throws JellyfishException 为空白、含路径分隔符或含上跳片段时抛出
     */
    private static String requireSafeFileName(String sessionId) {
        if (sessionId == null || sessionId.trim().isEmpty()) {
            throw new JellyfishException("会话标识不能为空");
        }
        if (sessionId.indexOf('/') >= 0 || sessionId.indexOf('\\') >= 0 || sessionId.contains("..")) {
            throw new JellyfishException("会话标识不能作为文件名: " + sessionId);
        }
        return sessionId;
    }
}
