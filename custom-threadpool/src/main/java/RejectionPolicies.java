import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public enum RejectionPolicies implements RejectionPolicy {
    ABORT {
        @Override
        public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
            throw new RejectedExecutionException(
                    String.format("Task %s rejected from %s", r, executor));
        }
    },
    DISCARD {
        @Override
        public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
            // 直接丢弃
        }
    },
    DISCARD_OLDEST {
        @Override
        public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
            if (!executor.isShutdown()) {
                Runnable oldest = executor.getQueue().poll();
                if (oldest != null) {
                    System.err.println("Discarded oldest task: " + oldest);
                }
                executor.execute(r);
            }
        }
    },
    CALLER_RUNS {
        @Override
        public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
            if (!executor.isShutdown()) {
                r.run();
            }
        }
    },
    TIMED_BLOCKING {
        private final long timeout = 1;
        private final TimeUnit unit = TimeUnit.SECONDS;

        @Override
        public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
            if (executor.isShutdown()) return;
            try {
                if (!executor.getQueue().offer(r, timeout, unit)) {
                    System.err.println("Failed to enqueue after " + timeout + " " + unit);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.err.println("Interrupted while retrying enqueue: " + e.getMessage());
            }
        }
    };

    @Override
    public abstract void rejectedExecution(Runnable r, ThreadPoolExecutor executor);
}
