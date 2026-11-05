package com.qiushaonan.mcstock;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * **事件入口的统一护栏**：本模组的代码在加载 / 重载 / 服务端启动的任何阶段都不允许抛异常。
 *
 * <h2>为什么必须有它</h2>
 * 整合包（尤其是 Sinytra Connector + 上百个模组那种）里，一个模组在 reload / 启动阶段抛出的异常
 * 会顺着 {@code ServerLifecycleHooks.handleServerStarted} 一路炸掉整个服务端初始化，
 * **别的模组跟着一起崩**（线上那份崩溃报告就是这类形态：某一家的 reload 监听器挂了，
 * 另一家的 {@code ServerStarted} 里 INSTANCE 还是 null）。
 * 我们控制不了别人，但能保证「自己绝不成为那个源头」。
 *
 * <h2>两条纪律</h2>
 * <ol>
 *   <li><b>只记一次</b>：同一个 key 的异常只在**第一次**打 ERROR，之后静默计数
 *       （{@link #suppressedCount(String)} 能查到被压掉多少次）—— 行情每 2 秒跳一次，
 *       一个持续性的错误如果每次都打日志，几分钟就能把日志刷爆、把真正的原因埋掉；</li>
 *   <li><b>虚拟机的致命错误照抛</b>：{@link VirtualMachineError}（OOM / StackOverflow）
 *       不是「某个模组的数据写坏了」，吞掉它只会让服务器带着坏状态继续跑。
 *       其它 {@link Throwable}（含 {@code NoClassDefFoundError} —— 缺前置模组的配方
 *       抛的正是它，只 catch {@code Exception} 根本接不住）一律接住。</li>
 * </ol>
 */
public final class Errors {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 已经打过日志的 key。 */
    private static final Set<String> LOGGED = ConcurrentHashMap.newKeySet();

    /** 每个 key 被压掉的次数（诊断用：日志里说「只记一次」，这里能看出实际出了多少次）。 */
    private static final Map<String, AtomicInteger> SUPPRESSED = new ConcurrentHashMap<>();

    private Errors() {
    }

    /**
     * 跑一段「绝不允许把异常抛出去」的代码。
     *
     * @param key     去重用的稳定标识（建议 {@code "类名#入口"}，例如 {@code "StockEvents#onServerTick"}）
     * @param what    出问题时那句话（第一次会连堆栈一起打 ERROR）
     */
    public static void guard(String key, String what, Runnable body) {
        try {
            body.run();
        } catch (Throwable t) {
            handle(key, what, t);
        }
    }

    /**
     * 同 {@link #guard(String, String, Runnable)}，但带一个返回值：出错时返回 {@code fallback}。
     *
     * <p>用于「建不起来就降级」的路径（估值表建不出来 → 返回 {@code null} → 上层走「暂不可用」提示）。
     */
    public static <T> T guard(String key, String what, Supplier<T> body, T fallback) {
        try {
            return body.get();
        } catch (Throwable t) {
            handle(key, what, t);
            return fallback;
        }
    }

    /** 记录一次「已知会失败但不该刷屏」的问题；同样按 key 去重。 */
    public static void logOnce(String key, String what, Throwable cause) {
        handle(key, what, cause);
    }

    /** 同 {@link #logOnce(String, String, Throwable)}，但没有异常对象（只描述事实）。 */
    public static void logOnce(String key, String what) {
        handle(key, what, null);
    }

    /** 立刻打一条 ERROR（不受去重影响；只给「必须每次都看见」的极少数场景用）。 */
    public static void error(String what, Throwable cause) {
        LOGGER.error("[mcstock] {}", what, cause);
    }

    /** 某个 key 被压掉的次数（0 表示没出现过，或只出现过一次）。 */
    public static int suppressedCount(String key) {
        AtomicInteger counter = SUPPRESSED.get(key);
        return counter == null ? 0 : counter.get();
    }

    /** 有没有为这个 key 打过日志。 */
    public static boolean hasLogged(String key) {
        return LOGGED.contains(key);
    }

    /** 测试用：清空去重状态（生产代码不要调）。 */
    public static void resetForTests() {
        LOGGED.clear();
        SUPPRESSED.clear();
    }

    private static void handle(String key, String what, Throwable t) {
        if (t instanceof VirtualMachineError fatal) {
            // OOM / StackOverflow：不是「数据坏了」，继续跑只会更糟，照抛
            throw fatal;
        }
        if (LOGGED.add(key)) {
            LOGGER.error("[mcstock] {}（同类问题之后只计数、不再刷屏；本次 key={}）", what, key, t);
        } else {
            SUPPRESSED.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
        }
    }
}
