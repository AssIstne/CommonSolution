package com.common.solution;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.support.annotation.NonNull;
import android.util.Log;
import java.util.concurrent.TimeUnit;

/**
 * 辅助使用{@link Handler}处理轻量级的定时任务
 *
 * @author assistne
 * @see LightTimer.Builder
 * @since 2018/11/12
 */
public final class LightTimer {

    // 执行间隔, 单位毫秒
    private final long mInterval;
    // 重复次数, 负数或0表示无限重复
    private final int mRepeatCount;
    // 执行任务的线程
    private Handler mWorkerHandler;
    private Runnable mWorker;

    private volatile State mState = State.IDLE;

    private int mWorkCount;

    private Runnable mRepeatRunnable = new Runnable() {
        @Override
        public void run() {
            switch (mState) {
                case WORKING:
                    if (mRepeatCount <= 0 || mWorkCount <= mRepeatCount) {
                        long startTime = SystemClock.elapsedRealtime();
                        mWorker.run();
                        long cost = SystemClock.elapsedRealtime() - startTime;
                        if (cost > 10) {
                            Log.w("VsoonTimer", "任务执行" + cost + "ms, 注意不要在循环中执行耗时操作");
                        } else if (cost > mInterval) {
                            throw new IllegalStateException("执行时间(" + cost
                                + ")比执行间隔(" + mInterval + ")长, 不要在循环中执行耗时操作");
                        }
                        mWorkCount += 1;
                        // 避免在执行过程状态发生改变
                        if (mState == State.WORKING) {
                            if (mRepeatCount <= 0 || mWorkCount <= mRepeatCount) {
                                postWorkerDelay(mInterval);
                            } else {
                                // 执行次数够了, 暂停执行
                                pause();
                            }
                        }
                    } else {
                        // 执行次数够了, 暂停执行
                        pause();
                    }
                    break;
                default:
                    break;
            }
        }
    };

    /**
     * 主线程无限执行
     */
    public LightTimer(long interval, @NonNull Runnable worker) {
        this(interval, -1, main(), worker);
    }

    /**
     * 主线程限定执行次数
     */
    public LightTimer(long interval, int repeatCount, @NonNull Runnable worker) {
        this(interval, repeatCount, main(), worker);
    }

    /**
     * 指定线程, 无限执行
     */
    public LightTimer(long interval, @NonNull Handler workerHandler, @NonNull Runnable worker) {
        this(interval, -1, workerHandler, worker);
    }

    /**
     * @param interval 执行间隔, 单位毫秒
     * @param repeatCount 执行次数, 负数或0表示不限制次数, n则表示会重复n次, 共执行n+1次
     * @param workerHandler 指定执行线程
     * @param worker 执行内容
     */
    public LightTimer(long interval, int repeatCount,
        @NonNull Handler workerHandler, @NonNull Runnable worker) {
        if (interval <= 10) {
            throw new IllegalArgumentException("执行间隔不能小于10ms, 太频繁执行可能有问题");
        }
        mInterval = interval;
        mRepeatCount = repeatCount;
        mWorkerHandler = workerHandler;
        mWorker = worker;
    }

    /**
     * 立即开始执行任务
     */
    public void start() {
        startDelay(-1);
    }

    /**
     * 延迟一会开始执行任务
     */
    public void startDelay(long delay) {
        if (mRepeatCount <= 0 || mWorkCount <= mRepeatCount) {
            switch (mState) {
                case IDLE:
                    postWorkerDelay(delay);
                    break;
                default:
                    break;
            }
        }
    }

    /**
     * 尝试从暂停状态恢复执行, 其他状态下调用无效
     *
     * @see #isPaused()
     */
    public void resume() {
        if (mRepeatCount <= 0 || mWorkCount <= mRepeatCount) {
            switch (mState) {
                case PAUSED:
                    postWorkerDelay(-1);
                    break;
                default:
                    break;
            }
        }
    }

    /**
     * 暂停执行
     *
     * @see #isWorking()
     * @see #isPaused()
     */
    public void pause() {
        switch (mState) {
            case WORKING:
                mWorkerHandler.removeCallbacks(mRepeatRunnable);
                mState = State.PAUSED;
                break;
            default:
                break;
        }
    }

    /**
     * 销毁定时器, 表明不再使用
     *
     * @see #isDestroy()
     */
    public void destroy() {
        if (mState != State.DESTROY) {
            mWorkerHandler.removeCallbacks(mRepeatRunnable);
            mState = State.DESTROY;

            // 清空引用
            mWorkerHandler = null;
            mWorker = null;
        }
    }

    /**
     * @return 定时器处于暂停状态, 可以恢复执行
     * @see #resume()
     */
    public boolean isPaused() {
        return mState == State.PAUSED;
    }

    /**
     * @return 定时器正在执行, 可以暂停执行
     * @see #pause()
     */
    public boolean isWorking() {
        return mState == State.WORKING;
    }

    /**
     * @return 定时器已经被销毁, 调用任何方法都无效
     * @see #destroy()
     */
    public boolean isDestroy() {
        return mState == State.DESTROY;
    }

    private void postWorkerDelay(long delay) {
        switch (mState) {
            case IDLE:
            case PAUSED:
            case WORKING:
                mState = State.WORKING;
                if (delay <= 0) {
                    mWorkerHandler.post(mRepeatRunnable);
                } else {
                    mWorkerHandler.postDelayed(mRepeatRunnable, delay);
                }
                break;
            default:
                throw new IllegalStateException(mState + "状态下不能执行任务");
        }
    }

    /**
     * 描述状态
     *
     * @author assistne
     * @since 2018/11/12
     */
    private enum State {
        /**
         * 起始状态
         */
        IDLE,
        /**
         * 暂停状态
         *
         * @see #pause()
         */
        PAUSED,
        /**
         * 执行中
         */
        WORKING,
        /**
         * 被销毁, 不能进行任何操作
         *
         * @see #destroy()
         */
        DESTROY,
    }

    private static Handler sMainHandler;

    /**
     * @return 主线程的handler
     */
    public static Handler main() {
        if (sMainHandler == null) {
            synchronized (LightTimer.class) {
                if (sMainHandler == null) {
                    sMainHandler = new Handler(Looper.getMainLooper());
                }
            }
        }
        return sMainHandler;
    }

    private static Handler sBackgroundHandler;

    /**
     * @return 后台线程的handler
     */
    public static Handler background() {
        if (sBackgroundHandler == null) {
            synchronized (LightTimer.class) {
                if (sBackgroundHandler == null) {
                    HandlerThread bg = new HandlerThread("VsoonTimer-Background");
                    bg.start();
                    sBackgroundHandler = new Handler(bg.getLooper());
                }
            }
        }
        return sBackgroundHandler;
    }

    /**
     * @return builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 辅助创建实例
     *
     * @author assistne
     * @since 2018/11/12
     */
    public static class Builder {

        private Handler mHandler;
        private long mInterval;
        private int mCount = -1;
        private Runnable mWorker;

        public static Builder create() {
            return new Builder();
        }

        /**
         * 在主线程执行
         */
        public Builder atMain() {
            return atThread(LightTimer.main());
        }

        /**
         * 在后台线程执行
         */
        public Builder atBackground() {
            return atThread(LightTimer.background());
        }

        /**
         * 在指定线程执行
         *
         * @param handler 指定线程
         */
        public Builder atThread(@NonNull Handler handler) {
            mHandler = handler;
            return this;
        }

        /**
         * @param mills 执行间隔, 单位毫秒
         */
        public Builder interval(long mills) {
            mInterval = mills;
            return this;
        }

        /**
         * 设置执行间隔
         *
         * @param duration 时间值
         * @param unit 时间单位
         */
        public Builder interval(long duration, TimeUnit unit) {
            return interval(unit.toMillis(duration));
        }

        /**
         * @param count 设置重复次数, 总执行数为count+1
         */
        public Builder repeatCount(int count) {
            mCount = count;
            return this;
        }

        public Builder work(@NonNull Runnable worker) {
            mWorker = worker;
            return this;
        }

        public LightTimer build() {
            // 默认主线程
            if (mHandler == null) {
                mHandler = main();
            }
            if (mWorker == null) {
                throw new IllegalArgumentException();
            }
            return new LightTimer(mInterval, mCount, mHandler, mWorker);
        }
    }
}
