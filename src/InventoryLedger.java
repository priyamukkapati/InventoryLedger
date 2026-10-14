import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Local append-only journal with synchronized transfers and replay. */
public final class InventoryLedger {
    public record Event(String requestId, String sku, String from, String to, long quantity) {}
    private record Bin(String sku, String location) {}
    private final Path file;
    private final Map<String,Event> events = new LinkedHashMap<>();
    private final Map<Bin,Long> stock = new HashMap<>();
    public InventoryLedger(Path file) throws IOException {
        this.file=file;
        if(Files.exists(file)) {
            String text=Files.readString(file,StandardCharsets.UTF_8);
            if(!text.isEmpty()&&!text.endsWith("\n"))throw new IOException("Incomplete journal tail; restore or repair before replay");
            for(String line:text.split("\n")) {
                if(line.isEmpty())continue;
                try {
                    String[] f=line.split("\t",-1);if(f.length!=5)throw new IllegalArgumentException();
                    Event event=new Event(decode(f[0]),decode(f[1]),decode(f[2]),decode(f[3]),Long.parseLong(f[4]));
                    validate(event);if(events.containsKey(event.requestId()))throw new IllegalArgumentException("Duplicate journal ID");
                    apply(event);events.put(event.requestId(),event);
                }catch(RuntimeException e){throw new IOException("Invalid inventory journal",e);}
            }
        }
    }
    public synchronized long balance(String sku,String location) {return stock.getOrDefault(new Bin(sku,location),0L);}
    public synchronized int eventCount(){return events.size();}
    public synchronized boolean receive(String requestId,String sku,String location,long qty)throws IOException {
        return record(new Event(requestId,sku,"",location,qty));
    }
    public synchronized boolean transfer(String requestId,String sku,String from,String to,long qty)throws IOException {
        if(from==null||from.isBlank())throw new IllegalArgumentException("Source required");
        return record(new Event(requestId,sku,from,to,qty));
    }
    private boolean record(Event event)throws IOException {
        validate(event);
        Event previous=events.get(event.requestId());
        if(previous!=null){if(!previous.equals(event))throw new IllegalArgumentException("Conflicting retry");return false;}
        long destination=Math.addExact(balance(event.sku(),event.to()),event.quantity());
        long source=event.from().isEmpty()?0:Math.subtractExact(balance(event.sku(),event.from()),event.quantity());
        if(source<0)throw new IllegalStateException("Insufficient stock");
        Files.createDirectories(file.toAbsolutePath().getParent());
        String line=encode(event.requestId())+"\t"+encode(event.sku())+"\t"+encode(event.from())+"\t"+encode(event.to())+"\t"+event.quantity()+"\n";
        // A successful append precedes the in-memory mutation. No cross-process locking or fsync guarantee.
        Files.writeString(file,line,StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.APPEND);
        if(!event.from().isEmpty())stock.put(new Bin(event.sku(),event.from()),source);
        stock.put(new Bin(event.sku(),event.to()),destination);events.put(event.requestId(),event);return true;
    }
    private void apply(Event event){
        long destination=Math.addExact(balance(event.sku(),event.to()),event.quantity());
        if(!event.from().isEmpty()){
            long source=Math.subtractExact(balance(event.sku(),event.from()),event.quantity());
            if(source<0)throw new IllegalArgumentException("Negative journal balance");
            stock.put(new Bin(event.sku(),event.from()),source);
        }
        stock.put(new Bin(event.sku(),event.to()),destination);
    }
    private static void validate(Event e){
        if(e.requestId()==null||e.requestId().isBlank()||e.requestId().length()>100||e.sku()==null||e.sku().isBlank()||e.sku().length()>100
           ||e.from()==null||e.to()==null||e.to().isBlank()||e.from().equals(e.to())||e.quantity()<=0
           ||e.from().length()>100||e.to().length()>100)throw new IllegalArgumentException("Invalid inventory event");
    }
    private static String encode(String s){return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));}
    private static String decode(String s){return new String(Base64.getDecoder().decode(s),StandardCharsets.UTF_8);}
    public static void main(String[] args)throws Exception {
        if(args.length<2){System.out.println("Usage: InventoryLedger <file> receive <id> <sku> <location> <quantity> | transfer <id> <sku> <from> <to> <quantity> | balance <sku> <location>");return;}
        InventoryLedger ledger=new InventoryLedger(Path.of(args[0]));
        switch(args[1]){
            case "receive" -> {if(args.length!=6)throw new IllegalArgumentException("receive needs four arguments");System.out.println("recorded="+ledger.receive(args[2],args[3],args[4],Long.parseLong(args[5])));}
            case "transfer" -> {if(args.length!=7)throw new IllegalArgumentException("transfer needs five arguments");System.out.println("recorded="+ledger.transfer(args[2],args[3],args[4],args[5],Long.parseLong(args[6])));}
            case "balance" -> {if(args.length!=4)throw new IllegalArgumentException("balance needs two arguments");System.out.println(ledger.balance(args[2],args[3]));}
            default -> throw new IllegalArgumentException("Unknown command");
        }
    }
}
