package local.airpods.handoffprobe;
import java.util.*;
import java.util.concurrent.*;
public final class RootWorkQueueTest {
    public static void main(String[] args) throws Exception {
        List<String> order=Collections.synchronizedList(new ArrayList<>());
        CountDownLatch started=new CountDownLatch(1),release=new CountDownLatch(1);
        try(RootWorkQueue queue=new RootWorkQueue()) {
            Future<?> prepare=queue.submit(()->{
                started.countDown();boolean done=false;
                while(!done)try{release.await();done=true;}catch(InterruptedException ignored){}
                order.add("prepare-finished");
            });
            if(!started.await(2,TimeUnit.SECONDS))throw new AssertionError("worker did not start");
            prepare.cancel(true);
            queue.submit(()->order.add("stop"));
            Future<?> next=queue.submit(()->order.add("new-enable"));
            release.countDown();next.get(2,TimeUnit.SECONDS);
            if(!order.equals(Arrays.asList("prepare-finished","stop","new-enable")))throw new AssertionError(order);
        }
        System.out.println("PASS: stop follows in-flight preparation and precedes a later enable");
    }
}
