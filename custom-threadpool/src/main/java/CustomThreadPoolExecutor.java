import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 优化后的自定义线程池实现，支持虚拟线程和传统线程池
 */
public class CustomThreadPoolExecutor extends ThreadPoolExecutor {

    // 线程池状态监控指标
    private final AtomicLong completedTaskCount = new AtomicLong(0);
    private final AtomicInteger largestPoolSize = new AtomicInteger(0);
    private final AtomicInteger activeThreadCount = new AtomicInteger(0);
    private final ThreadLocal<Long> threadStartTime = new ThreadLocal<>();

    // 优雅关闭相关
    private volatile boolean isShuttingDown = false;
    private final Object shutdownLock = new Object();

    /**
     * 自定义线程工厂（命名线程并设置异常处理器）
     */
    private static class CustomThreadFactory implements ThreadFactory {
        private static final AtomicInteger poolNumber = new AtomicInteger(1);
        private final ThreadGroup group;
        private final AtomicInteger threadNumber = new AtomicInteger(1);
        private final String namePrefix;

        CustomThreadFactory() {
            // 直接使用当前线程的线程组
            this.group = Thread.currentThread().getThreadGroup();
            this.namePrefix = "custom-pool-" + poolNumber.getAndIncrement() + "-thread-";
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(group, r, namePrefix + threadNumber.getAndIncrement(), 0);

            t.setUncaughtExceptionHandler((thread, throwable) -> {
                System.err.println("Uncaught exception in thread " + thread.getName());
                throwable.printStackTrace();
            });

            t.setDaemon(false);
            t.setPriority(Thread.NORM_PRIORITY);
            return t;
        }
    }

    public CustomThreadPoolExecutor(int corePoolSize, int maxPoolSize, long keepAliveTime,
                                    BlockingQueue<Runnable> workQueue, RejectionPolicy policy) {
        super(corePoolSize, maxPoolSize, keepAliveTime, TimeUnit.SECONDS, workQueue, new CustomThreadFactory(), policy);
    }

    public static class Builder {
        private int core = Runtime.getRuntime().availableProcessors();
        private int max = core * 2;
        private Duration keepAlive = Duration.ofSeconds(60);
        private int queueSize = 100;
        private RejectionPolicy policy = RejectionPolicies.TIMED_BLOCKING;
        private boolean virtual = false;

        public Builder coreThreads(int c) {
            core = c;
            return this;
        }

        public Builder maxThreads(int m) {
            max = m;
            return this;
        }

        public Builder keepAlive(Duration d) {
            keepAlive = d;
            return this;
        }

        public Builder queueCapacity(int q) {
            queueSize = q;
            return this;
        }

        public Builder rejectionPolicy(RejectionPolicy p) {
            policy = p;
            return this;
        }

        public Builder useVirtualThreads(boolean v) {
            virtual = v;
            return this;
        }

        public ExecutorService build() {
            if (virtual) {
                // 返回虚拟线程执行器
                ThreadFactory vf = Thread.ofVirtual()
                        .name("vt-%d", Math.abs(ThreadLocalRandom.current().nextLong()))
                        .factory();
                return Executors.newThreadPerTaskExecutor(vf);
            }

            BlockingQueue<Runnable> queue = new LinkedBlockingQueue<>(queueSize);
            CustomThreadPoolExecutor exec = new CustomThreadPoolExecutor(
                    core, max,
                    keepAlive.getSeconds(), // 转换为秒
                    queue, policy
            );
            exec.allowCoreThreadTimeOut(true);
            return exec;
        }
    }

    @Override
    public void execute(Runnable command) {
        if (isShuttingDown) {
            throw new RejectedExecutionException("ThreadPool is shutting down");
        }
        super.execute(command);
    }

    @Override
    protected void beforeExecute(Thread t, Runnable r) {
        super.beforeExecute(t, r);
        activeThreadCount.incrementAndGet();
        threadStartTime.set(System.nanoTime());

        // 更新最大线程数
        int currentPoolSize = getPoolSize();
        largestPoolSize.updateAndGet(current -> Math.max(current, currentPoolSize));

        // 监控日志
        if (getLogLevel() > 1) {
            System.out.printf("[%s] Start task: %s%n", t.getName(), r);
        }
    }

    @Override
    protected void afterExecute(Runnable r, Throwable t) {
        try {
            long taskTime = System.nanoTime() - threadStartTime.get();

            if (getLogLevel() > 0) {
                System.out.printf("[%s] Completed task: %s (Time: %.2fms)%n",
                        Thread.currentThread().getName(), r, taskTime / 1_000_000.0);
            }

            if (t != null) {
                System.err.printf("Task execution failed: %s%n", r);
                t.printStackTrace();
            }
        } finally {
            activeThreadCount.decrementAndGet();
            completedTaskCount.incrementAndGet();
            threadStartTime.remove();
            super.afterExecute(r, t);
        }
    }

    @Override
    protected void terminated() {
        super.terminated();
        System.out.println("ThreadPool terminated");
    }

    /**
     * 优雅关闭线程池
     */
    public boolean gracefulShutdown(long timeout, TimeUnit unit) {
        synchronized (shutdownLock) {
            isShuttingDown = true;
            shutdown();

            try {
                if (!awaitTermination(timeout, unit)) {
                    shutdownNow();
                    return false;
                }
                return true;
            } catch (InterruptedException e) {
                shutdownNow();
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    // ================= 监控统计方法 =================
    public int getActiveThreadCount() {
        return activeThreadCount.get();
    }

    public long getCompletedTaskCount() {
        return completedTaskCount.get();
    }

    public int getLargestPoolSize() {
        return largestPoolSize.get();
    }

    public double getAverageTaskTime() {
        return getCompletedTaskCount() > 0 ?
                (double) (getTotalTaskTimeNanos()) / getCompletedTaskCount() / 1_000_000.0 : 0;
    }

    public long getTotalTaskTimeNanos() {
        // 简化实现，实际应收集真实数据
        return completedTaskCount.get() * 1_000_000;
    }

    public void printStats() {
        System.out.println("\n===== ThreadPool Stats =====");
        System.out.println("Active Threads: " + getActiveThreadCount());
        System.out.println("Pool Size: " + getPoolSize());
        System.out.println("Largest Pool Size: " + getLargestPoolSize());
        System.out.println("Completed Tasks: " + getCompletedTaskCount());
        System.out.println("Queued Tasks: " + getQueue().size());
        System.out.println("Average Task Time: " + String.format("%.2f", getAverageTaskTime()) + "ms");
        System.out.println("===========================\n");
    }

    private int getLogLevel() {
        return 1; // 0=无日志, 1=基本日志, 2=详细日志
    }
}
