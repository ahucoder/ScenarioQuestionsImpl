import java.time.Duration;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class App {
    // ================= 使用示例 =================
    public static void main(String[] args) {
        // 使用Builder创建传统线程池
        ExecutorService traditionalExecutor = new CustomThreadPoolExecutor.Builder()
                .coreThreads(2)
                .maxThreads(5)
                .keepAlive(Duration.ofSeconds(30))
                .queueCapacity(10)
                .build();

        // 使用Builder创建虚拟线程执行器
        ExecutorService virtualExecutor = new CustomThreadPoolExecutor.Builder()
                .useVirtualThreads(true)
                .build();


        // 示例1：使用lambda表达式创建简单任务
        CustomTaskExecutor.submitTasks(traditionalExecutor, "Traditional",
                (id, type) -> () -> {
                    try {
                        Thread.sleep(new Random().nextInt(400));
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                    System.out.printf("[%s] Lambda task %d by %s%n", type, id, Thread.currentThread().getName());
                },
                5
        );

        // 示例2：使用预定义的任务类
        CustomTaskExecutor.submitTasks(virtualExecutor, "Virtual",
                CustomTaskExecutor.SimpleTask::new,
                10
        );

        // 示例3：提交带返回值的任务
        List<Future<String>> futures = CustomTaskExecutor.submitCallableTasks(
                traditionalExecutor,
                (id, _) -> new CustomTaskExecutor.ResultTask(id),
                5
        );

        // 获取任务结果
        futures.forEach(f -> {
            try {
                System.out.println("Task result: " + f.get());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        // 关闭传统线程池
        if (traditionalExecutor instanceof CustomThreadPoolExecutor customExec) {
            customExec.printStats();
            boolean success = customExec.gracefulShutdown(10, TimeUnit.SECONDS);
            System.out.println("Traditional shutdown " + (success ? "successful" : "timed out"));
        } else {
            traditionalExecutor.shutdown();
        }

        // 关闭虚拟线程池
        virtualExecutor.shutdown();
    }
}
