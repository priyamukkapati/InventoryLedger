import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
public final class InventoryLedgerTest {
    static int checks;
    static void check(boolean b,String name){if(!b)throw new AssertionError(name);checks++;}
    public static void main(String[] args)throws Exception {
        Path directory=Files.createTempDirectory("inventory-test"),file=directory.resolve("ledger.tsv");InventoryLedger ledger=new InventoryLedger(file);
        check(ledger.receive("seed","SKU1","A",100),"receipt");check(!ledger.receive("seed","SKU1","A",100),"retry");
        try{ledger.receive("seed","SKU1","A",200);throw new AssertionError();}catch(IllegalArgumentException expected){check(ledger.balance("SKU1","A")==100,"conflict unchanged");}
        ledger.transfer("move","SKU1","A","B",25);check(ledger.balance("SKU1","A")==75&&ledger.balance("SKU1","B")==25,"transfer");
        try{ledger.transfer("bad","SKU1","A","B",76);throw new AssertionError();}catch(IllegalStateException expected){check(ledger.eventCount()==2,"oversell not journaled");}
        InventoryLedger replay=new InventoryLedger(file);check(replay.balance("SKU1","B")==25,"replay");check(!replay.transfer("move","SKU1","A","B",25),"retry after restart");
        ledger.receive("race-seed","RACE","A",10);ExecutorService pool=Executors.newFixedThreadPool(8);AtomicInteger successes=new AtomicInteger();var futures=new java.util.ArrayList<Future<?>>();
        for(int i=0;i<100;i++){final int id=i;futures.add(pool.submit(()->{try{ledger.transfer("race-"+id,"RACE","A","B",1);successes.incrementAndGet();}catch(IllegalStateException expected){}catch(Exception e){throw new RuntimeException(e);}}));}
        for(var f:futures)f.get();pool.shutdown();check(successes.get()==10,"concurrent oversell prevention");check(ledger.balance("RACE","A")==0&&ledger.balance("RACE","B")==10,"conserved race stock");
        ledger.receive("max","MAX","A",Long.MAX_VALUE);try{ledger.receive("overflow","MAX","A",1);throw new AssertionError();}catch(ArithmeticException expected){check(ledger.balance("MAX","A")==Long.MAX_VALUE,"overflow unchanged");}
        Path corrupt=directory.resolve("corrupt.tsv");Files.writeString(corrupt,"partial");try{new InventoryLedger(corrupt);throw new AssertionError();}catch(java.io.IOException expected){check(true,"partial tail detected");}
        Path blocked=directory.resolve("blocked");InventoryLedger bad=new InventoryLedger(blocked);Files.createDirectory(blocked);
        try{bad.receive("io","SKU","A",5);throw new AssertionError();}catch(java.io.IOException expected){check(bad.eventCount()==0&&bad.balance("SKU","A")==0,"IO failure unchanged");}
        System.out.println("InventoryLedger: "+checks+" checks passed");
    }
}
