import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class NativeScheduler {

    static class NamedThreadFactory implements ThreadFactory {
        private final AtomicInteger threadCount = new AtomicInteger(1);
        private final String namePrefix;

        public NamedThreadFactory(String namePrefix) {
            this.namePrefix = namePrefix;
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, namePrefix + "-" + threadCount.getAndIncrement());
            t.setDaemon(false);
            t.setPriority(Thread.NORM_PRIORITY);
            return t;
        }
    }

    static class LoggingRejectionHandler implements RejectedExecutionHandler {
        @Override
        public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
            if (executor.isShutdown())
                return;

            System.err.printf("[%s] Task rejected: %s%n",
                    currentTime(), r.toString());

            try {
                executor.getQueue().put(r);
                System.out.printf("[%s] Task re-queued successfully%n", currentTime());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.err.printf("[%s] Re-queue interrupted: %s%n",
                        currentTime(), e.getMessage());
            }
        }
    }

    static class ScheduledTask {
        private final String id;
        private final Runnable task;
        private ScheduledFuture<?> future;
        private volatile TaskState state = TaskState.CREATED;
        private final boolean periodic; // 添加周期性标记

        enum TaskState {
            CREATED, SCHEDULED, RUNNING, COMPLETED, CANCELLED, FAILED
        }

        // 添加periodic参数
        public ScheduledTask(String id, Runnable task, boolean periodic) {
            this.id = id;
            this.task = task;
            this.periodic = periodic; // 保存周期性标记
        }

        public void setFuture(ScheduledFuture<?> future) {
            this.future = future;
            state = TaskState.SCHEDULED;
        }

        public void run() {
            state = TaskState.RUNNING;
            try {
                task.run();
                state = TaskState.COMPLETED;
            } catch (Exception e) {
                state = TaskState.FAILED;
                throw e;
            }
        }

        public boolean cancel() {
            if (state == TaskState.SCHEDULED && future != null) {
                boolean cancelled = future.cancel(false);
                if (cancelled) state = TaskState.CANCELLED;
                return cancelled;
            }
            return false;
        }

        public String getId() {
            return id;
        }

        public TaskState getState() {
            return state;
        }

        // 添加判断是否是周期性任务的方法
        public boolean isPeriodic() {
            return periodic;
        }

        @Override
        public String toString() {
            return "ScheduledTask{" +
                    "id='" + id + '\'' +
                    ", state=" + state +
                    ", periodic=" + periodic +
                    '}';
        }
    }

    public static class DelayedTaskScheduler {
        private final ScheduledThreadPoolExecutor executor;
        private final ConcurrentMap<String, ScheduledTask> taskRegistry = new ConcurrentHashMap<>();
        private final AtomicLong taskCounter = new AtomicLong(0);
        private final AtomicLong completedTasks = new AtomicLong(0);
        private final AtomicLong failedTasks = new AtomicLong(0);
        private final AtomicLong cancelledTasks = new AtomicLong(0);

        public DelayedTaskScheduler(int corePoolSize) {
            this.executor = new ScheduledThreadPoolExecutor(
                    corePoolSize,
                    new NamedThreadFactory("Scheduler"),
                    new LoggingRejectionHandler()
            );

            executor.setRemoveOnCancelPolicy(true);
            executor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
            executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        }

        public void schedule(String taskId, Runnable task, long delay, TimeUnit unit) {
            // 非周期性任务
            ScheduledTask scheduledTask = new ScheduledTask(taskId, task, false);
            taskRegistry.put(taskId, scheduledTask);

            ScheduledFuture<?> future = executor.schedule(() -> {
                executeTask(scheduledTask);
            }, delay, unit);

            scheduledTask.setFuture(future);
        }

        public void scheduleAtFixedRate(String taskId, Runnable task,
                                        long initialDelay, long period, TimeUnit unit) {
            // 周期性任务
            ScheduledTask scheduledTask = new ScheduledTask(taskId, task, true);
            taskRegistry.put(taskId, scheduledTask);

            ScheduledFuture<?> future = executor.scheduleAtFixedRate(() -> {
                executeTask(scheduledTask);
            }, initialDelay, period, unit);

            scheduledTask.setFuture(future);
        }

        public void scheduleWithFixedDelay(String taskId, Runnable task,
                                           long initialDelay, long delay, TimeUnit unit) {
            // 周期性任务
            ScheduledTask scheduledTask = new ScheduledTask(taskId, task, true);
            taskRegistry.put(taskId, scheduledTask);

            ScheduledFuture<?> future = executor.scheduleWithFixedDelay(() -> {
                executeTask(scheduledTask);
            }, initialDelay, delay, unit);

            scheduledTask.setFuture(future);
        }

        public void scheduleConditional(String taskId, Runnable task, long delay,
                                        TimeUnit unit, Callable<Boolean> condition) {
            schedule(taskId, () -> {
                try {
                    if (condition.call()) {
                        task.run();
                    } else {
                        System.out.printf("[%s] Condition not met for task: %s%n",
                                currentTime(), taskId);
                    }
                } catch (Exception e) {
                    System.err.printf("[%s] Condition check failed: %s%n",
                            currentTime(), e.getMessage());
                }
            }, delay, unit);
        }

        private void executeTask(ScheduledTask scheduledTask) {
            try {
                scheduledTask.run();
                completedTasks.incrementAndGet();
            } catch (Exception e) {
                scheduledTask.state = ScheduledTask.TaskState.FAILED;
                failedTasks.incrementAndGet();
                System.err.printf("[%s] Task %s failed: %s%n",
                        currentTime(), scheduledTask.id, e.getMessage());
            } finally {
                // 使用自定义的isPeriodic()方法
                if (!scheduledTask.isPeriodic()) {
                    taskRegistry.remove(scheduledTask.id);
                }
            }
        }

        public boolean cancelTask(String taskId) {
            ScheduledTask task = taskRegistry.get(taskId);
            if (task != null && task.cancel()) {
                taskRegistry.remove(taskId);
                cancelledTasks.incrementAndGet();
                return true;
            }
            return false;
        }

        public ScheduledTask.TaskState getTaskState(String taskId) {
            ScheduledTask task = taskRegistry.get(taskId);
            return task != null ? task.getState() : null;
        }

        public void shutdown() {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        public void printStats() {
            System.out.println("\n===== Scheduler Stats =====");
            System.out.printf("Active Tasks: %d%n", executor.getActiveCount());
            System.out.printf("Pool Size: %d%n", executor.getPoolSize());
            System.out.printf("Queued Tasks: %d%n", executor.getQueue().size());
            System.out.printf("Completed Tasks: %d%n", completedTasks.get());
            System.out.printf("Failed Tasks: %d%n", failedTasks.get());
            System.out.printf("Cancelled Tasks: %d%n", cancelledTasks.get());
            System.out.printf("Total Scheduled Tasks: %d%n", taskRegistry.size());
            System.out.println("==========================\n");
        }
    }

    private static String currentTime() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS"));
    }

    public static void main(String[] args) throws InterruptedException {
        DelayedTaskScheduler scheduler = new DelayedTaskScheduler(2);

        // 单次任务（非周期性）
        scheduler.schedule("task1", () -> {
            System.out.printf("[%s] Task 1 executed%n", currentTime());
        }, 3, TimeUnit.SECONDS);

        // 周期性任务
        scheduler.scheduleAtFixedRate("task2", () -> {
            System.out.printf("[%s] Task 2 executed%n", currentTime());
            try {
                Thread.sleep(500);
            } catch (InterruptedException ignored) {
            }
        }, 1, 2, TimeUnit.SECONDS);

        // 带条件的单次任务
        scheduler.scheduleConditional("task4", () -> {
            System.out.printf("[%s] Task 4 executed (condition met)%n", currentTime());
        }, 0, TimeUnit.SECONDS, () -> Math.random() > 0.5);

        // 取消任务
        scheduler.schedule("task5", () -> {
            System.out.printf("[%s] Task 5 should not be executed%n", currentTime());
        }, 5, TimeUnit.SECONDS);
        Thread.sleep(2000);
        boolean cancelled = scheduler.cancelTask("task5");
        System.out.printf("[%s] Task 5 cancelled: %b%n", currentTime(), cancelled);

        scheduler.printStats();
        Thread.sleep(10_000);
        scheduler.printStats();
        scheduler.shutdown();
    }
}