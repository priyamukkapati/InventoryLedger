import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
public final class Demo {
    public static void main(String[] args)throws Exception {
        Path file=Files.createTempDirectory("inventory-demo").resolve("ledger.tsv");InventoryLedger ledger=new InventoryLedger(file);ledger.receive("seed","DEMO","A",100);
        AtomicInteger accepted=new AtomicInteger(),rejected=new AtomicInteger();ExecutorService pool=Executors.newFixedThreadPool(8);var futures=new java.util.ArrayList<Future<?>>();long start=System.nanoTime();
        for(int i=0;i<1000;i++){final int id=i;futures.add(pool.submit(()->{try{ledger.transfer("move-"+id,"DEMO","A","B",1);accepted.incrementAndGet();}catch(IllegalStateException e){rejected.incrementAndGet();}catch(Exception e){throw new RuntimeException(e);}}));}
        for(var f:futures)f.get();pool.shutdown();InventoryLedger replay=new InventoryLedger(file);
        System.out.printf("attempts=1000 accepted=%d rejected=%d source=%d destination=%d replayed_events=%d elapsed_ms=%.2f%n",accepted.get(),rejected.get(),replay.balance("DEMO","A"),replay.balance("DEMO","B"),replay.eventCount(),(System.nanoTime()-start)/1e6);
        if(accepted.get()!=100||rejected.get()!=900||replay.balance("DEMO","B")!=100)throw new AssertionError("Stock invariant failed");
    }
}
