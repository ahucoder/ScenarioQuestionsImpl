import com.google.common.collect.Lists;

import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

public class CustomTaskExecutor {

    public interface Task extends Runnable {
        void execute() throws Exception;

        @Override
        default void run() {
            try {
                execute();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    public static class SimpleTask implements Task {
        private final int taskId;
        private final String executorType;

        public SimpleTask(int taskId, String executorType) {
            this.taskId = taskId;
            this.executorType = executorType;
        }

        @Override
        public void execute() throws InterruptedException {
            Thread.sleep(new Random().nextInt(300));
            System.out.printf("[%s] Task %d completed by %s, Thread is virtual: %s%n",
                    executorType, taskId, Thread.currentThread().getName(), Thread.currentThread().isVirtual());
        }
    }

    public static class ResultTask implements Callable<String> {
        private final int taskId;

        public ResultTask(int taskId) {
            this.taskId = taskId;
        }

        @Override
        public String call() throws Exception {
            Thread.sleep(new Random().nextInt(200));
            return "Result of task " + taskId + " executed by " + Thread.currentThread().getName();
        }
    }

    @FunctionalInterface
    public interface TaskGenerator<T> {
        T generate(int taskId, String executorType);
    }

    /**
     * 提交任务到执行器
     *
     * @param executor      执行器服务
     * @param executorType  执行器类型标识
     * @param taskGenerator 任务生成器
     * @param taskCount     任务数量
     */
    public static <T extends Runnable> void submitTasks(
            ExecutorService executor,
            String executorType,
            TaskGenerator<T> taskGenerator,
            int taskCount) {
        for (int i = 0; i < taskCount; i++) {
            T task = taskGenerator.generate(i, executorType);
            executor.execute(task);
        }
    }

    /**
     * 提交带返回值的任务
     *
     * @param executor      执行器服务
     * @param taskGenerator 任务生成器
     * @param taskCount     任务数量
     * @return Future列表用于获取结果
     */
    public static <T extends Callable<V>, V> List<Future<V>> submitCallableTasks(
            ExecutorService executor,
            TaskGenerator<T> taskGenerator,
            int taskCount) {
        List<Future<V>> futures = Lists.newArrayList();
        for (int i = 0; i < taskCount; i++) {
            T task = taskGenerator.generate(i, "Callable");
            futures.add(executor.submit(task));
        }
        return futures;
    }
}
