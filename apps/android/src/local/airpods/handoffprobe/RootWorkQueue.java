package local.airpods.handoffprobe;
import java.util.concurrent.*;
/** Serialize preparation and explicit cleanup, including noninterruptible I/O. */
public final class RootWorkQueue implements AutoCloseable {
    private final ExecutorService worker=Executors.newSingleThreadExecutor(task->{
        Thread thread=new Thread(task,"LinkPodsRootWork");thread.setDaemon(true);return thread;
    });
    public Future<?> submit(Runnable task){return worker.submit(task);}
    public void close(){worker.shutdownNow();}
}
