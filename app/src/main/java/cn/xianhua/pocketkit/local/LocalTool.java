package cn.xianhua.pocketkit.local;

import java.util.Map;

import cn.xianhua.pocketkit.LocalToolsActivity;

/**
 * 本地工具（002 003 035–066 与 B02 B05）的入口合同。
 *
 * 每个实现类负责一批入口：
 *   ids()         —— 本类覆盖的 tool_id
 *   limitations() —— 每个 id 的未实现原因或已实现功能的边界说明（可为空字符串）
 *   install()     —— 把自己的界面装到宿主上
 *
 * 主界面判断「可用 / 未实现」走 LocalToolsActivity.supports / limitation，
 * 那里只对本接口真正注册过的 id 返回 true。
 *
 * 本接口不持有 Activity 引用，避免泄漏。
 */
public interface LocalTool {

    /** 本类覆盖的 tool_id，例如 {"002", "003"}。 */
    String[] ids();

    /** id -> 说明。未实现的入口必须写清原因；已实现的写已知边界。 */
    Map<String, String> limitations();

    /** 把工具界面装到宿主上。 */
    void install(LocalToolsActivity host);

    /**
     * 宿主销毁时释放占用（例如 TTS 引擎连接）。
     * 工具实例在多个 Activity 之间共享，因此实现必须清掉自己持有的 Activity 引用。
     */
    default void release() {
    }
}
