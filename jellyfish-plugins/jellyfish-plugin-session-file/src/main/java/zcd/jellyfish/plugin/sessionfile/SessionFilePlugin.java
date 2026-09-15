package zcd.jellyfish.plugin.sessionfile;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zcd.jellyfish.api.extension.SessionDeleteRequest;
import zcd.jellyfish.api.extension.SessionPersistRequest;
import zcd.jellyfish.api.extension.SessionRestoreRequest;
import zcd.jellyfish.api.extension.SessionRestoreResult;
import zcd.jellyfish.api.extension.SessionSnapshot;
import zcd.jellyfish.api.plugin.JellyfishPlugin;
import zcd.jellyfish.api.plugin.PluginContext;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 官方会话持久化插件：把会话写成文件，并用 git 管理历史。
 * <p>
 * <b>它只做两件事</b>：收到 {@link SessionPersistRequest} 就落盘，收到 {@link SessionRestoreRequest}
 * 就把目录里的会话交回去。会话的内核状态它一概不碰——插件拿不到 {@code SessionManager}，
 * 这正是这条边界的价值：持久化插件的故障范围被限制在「存」与「取」。
 * <p>
 * <b>失败语义</b>：文件落盘失败上抛（内核据此让本次变更失败，「不可丢」）；git 与单个坏文件只记告警。
 * 取舍的理由是「文件是真相，git 是附加的版本化层」——详见 {@link GitRepository} 与
 * {@link SessionStore} 的类注释。
 * <p>
 * 配置见 {@link PluginConfig}：{@code sessionDir} 与 {@code gitEnabled}。
 *
 * @author zcd
 */
public final class SessionFilePlugin implements JellyfishPlugin {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(SessionFilePlugin.class);

    /** 提交信息里会话标识保留的字符数。 */
    private static final int SHORT_ID_LENGTH = 8;

    /** 会话文件仓库，在 {@link #start(PluginContext)} 中装配。 */
    private volatile SessionStore store;

    /** git 封装，在 {@link #start(PluginContext)} 中装配。 */
    private volatile GitRepository git;

    @Override
    public void start(PluginContext context) {
        PluginConfig config = PluginConfig.from(context.configuration());
        Path directory = config.sessionDirectory();
        // 先装配再注册：处理器一旦注册就可能被调用，字段必须已经就绪
        this.store = new SessionStore(directory);
        this.git = new GitRepository(directory, config.gitEnabled());
        context.contribute(SessionPersistRequest.class, this::persist);
        context.contribute(SessionDeleteRequest.class, this::delete);
        context.contribute(SessionRestoreRequest.class, this::restore);
        LOG.info("会话文件插件已启动: dir={} git={}", directory, config.gitEnabled());
    }

    /**
     * 落盘一个会话，内容有变化时留一次 git 提交。
     *
     * @param request 持久化请求
     * @return 恒为 {@code null}（结果类型是 {@code Void}）
     */
    private Void persist(SessionPersistRequest request) {
        SessionSnapshot snapshot = request.getSnapshot();
        String json = SnapshotJson.write(snapshot);
        if (!store.writeIfChanged(snapshot.getSessionId(), json)) {
            // 内容没变：既不用重写文件，也不该在历史里留一条空提交
            return null;
        }
        git.commit(store.fileOf(snapshot.getSessionId()), commitMessage(snapshot));
        return null;
    }

    /**
     * 删除会话文件，并留一次 git 提交。
     * <p>
     * <b>为什么复用 {@link GitRepository#commit(Path, String)} 而不加专门的删除方法</b>：
     * 文件已经从工作区删掉，{@code git add -- <file>} 本来就会把这次删除记进索引（Git 2.0 起
     * 不带 {@code -A} 的 pathspec 形式也包含删除），因此提交路径与普通落盘完全一样。
     * <p>
     * 文件不存在时不是错误：删除一个已经没了的会话应当是幂等的。
     * 文件删除失败由 {@link SessionStore#delete(String)} 上抛——那一刻「删掉了」就是假的，
     * 内核会据此保留内存里的会话并告诉用户删除失败。
     *
     * @param request 删除请求
     * @return 恒为 {@code null}（结果类型是 {@code Void}）
     */
    private Void delete(SessionDeleteRequest request) {
        String sessionId = request.getSessionId();
        if (!store.delete(sessionId)) {
            return null;
        }
        git.commit(store.fileOf(sessionId), deleteMessage(sessionId));
        return null;
    }

    /**
     * 读回目录里的全部会话。
     *
     * @param request 恢复请求
     * @return 恢复结果，保证非 {@code null}
     */
    private SessionRestoreResult restore(SessionRestoreRequest request) {
        List<SessionSnapshot> sessions = new ArrayList<>();
        for (Path file : store.files()) {
            try {
                sessions.add(SnapshotJson.read(store.read(file), file.toString()));
            } catch (RuntimeException e) {
                // 单个坏文件不该让同目录其它会话也回不来
                LOG.warn("跳过无法解析的会话文件: file={} reason={}", file, e.getMessage());
            }
        }
        return SessionRestoreResult.of(sessions);
    }

    /**
     * 生成提交信息：会话短标识 + 消息数，够用来在历史里认人。
     *
     * @param snapshot 会话快照
     * @return 提交信息
     */
    private static String commitMessage(SessionSnapshot snapshot) {
        return "session(" + shortId(snapshot.getSessionId()) + "): " + snapshot.getMessages().size() + " 条消息";
    }

    /**
     * 生成删除会话的提交信息。
     *
     * @param sessionId 会话标识
     * @return 提交信息
     */
    private static String deleteMessage(String sessionId) {
        return "session(" + shortId(sessionId) + "): 删除";
    }

    /**
     * 取会话短标识。
     *
     * @param sessionId 会话标识
     * @return 前 {@value #SHORT_ID_LENGTH} 个字符（不足则原样）
     */
    private static String shortId(String sessionId) {
        return sessionId.length() > SHORT_ID_LENGTH ? sessionId.substring(0, SHORT_ID_LENGTH) : sessionId;
    }
}
