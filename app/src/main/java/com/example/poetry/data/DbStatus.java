package com.example.poetry.data;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * 诗库状态的不可变快照。
 *
 * <p>存在的理由是「界面不知道该重新查一遍」：库是从服务器下下来的，装好之后当前打开的
 * 列表还停在 15 首内置示例上。这个快照就是广播这件事的载体。
 *
 * <p><b>构造时不做任何磁盘或数据库操作</b>——它会在主线程被创建、被投递给每一个界面，
 * 一旦在这里碰 IO，下载进度回调（每秒好几次）就会变成每秒好几次磁盘访问。
 * 文件在不在、库开没开，都由创建者（{@link PoetryRepository}）提前问好再传进来。
 */
public final class DbStatus {

    public enum State {
        /** 什么都没发生。库可用时这是稳态。 */
        IDLE,
        /** 正在拉 version.json */
        CHECKING,
        /** 确认需要下载，等用户点 */
        NEEDS_DOWNLOAD,
        /** 策略要求等 Wi-Fi（自动模式才会出现） */
        WAITING_NETWORK,
        /** 下载中 */
        DOWNLOADING,
        /** 下载完，正在校验字节数与 sha256 */
        VERIFYING,
        /** 校验通过，正在替换文件并重开数据库 */
        INSTALLING,
        /** 刚装好。跃迁到它的那一次回调就是「该重新查一遍」的信号。 */
        INSTALLED,
        /** 出错了，{@link #error} 里有原因 */
        FAILED
    }

    @NonNull
    public final State state;

    /** 本地诗库此刻是否真的开着（等价于 {@code poetry.db} 已在服务） */
    public final boolean localReady;

    /** 磁盘上有没有 {@code poetry.db} 文件——注意它和 {@link #localReady} 不是一回事：
     *  文件在但还没打开时，{@code localReady} 仍是 false。 */
    public final boolean hasFile;

    /** 0..100；总数未知时是 -1，界面该走不确定进度 */
    public final int percent;

    public final long done;

    /** 预期总字节数；未知时是 -1 */
    public final long total;

    /** 失败原因，仅 {@link State#FAILED} 时非空 */
    @Nullable
    public final String error;

    public DbStatus(@NonNull State state, boolean localReady, boolean hasFile,
                    long done, long total, @Nullable String error) {
        this.state = state;
        this.localReady = localReady;
        this.hasFile = hasFile;
        this.done = done;
        this.total = total;
        this.error = error;
        this.percent = total > 0 ? (int) Math.min(100L, done * 100L / total) : -1;
    }

    /** 最常用的那个：只有「库开没开」这一个信息。 */
    @NonNull
    public static DbStatus idle(boolean localReady, boolean hasFile) {
        return new DbStatus(State.IDLE, localReady, hasFile, 0L, -1L, null);
    }

    /** 拷贝一份但换状态，用于只改状态、不动进度数字的场合。 */
    @NonNull
    public DbStatus withState(@NonNull State next) {
        return new DbStatus(next, localReady, hasFile, done, total, error);
    }

    @NonNull
    public DbStatus withError(@Nullable String message) {
        return new DbStatus(State.FAILED, localReady, hasFile, done, total, message);
    }

    /** 是否处在「正在忙」的状态：界面据此把可点项禁掉。 */
    public boolean busy() {
        switch (state) {
            case CHECKING:
            case DOWNLOADING:
            case VERIFYING:
            case INSTALLING:
                return true;
            default:
                return false;
        }
    }

    @NonNull
    @Override
    public String toString() {
        return "DbStatus{" + state + " ready=" + localReady + " file=" + hasFile
                + " " + done + "/" + total + (error == null ? "" : " err=" + error) + "}";
    }
}
