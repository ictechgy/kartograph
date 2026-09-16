public class RuntimeWitness {
 public static void main(String[] args) throws Exception {
  probe.Entry.main(new String[]{args[0]});
  System.out.println(probe.Entry.Marker.live + "," + probe.Entry.Marker.dormant);
 }
}
