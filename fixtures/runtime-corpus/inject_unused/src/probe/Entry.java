package probe;
public class Entry {
 public static class DormantService { @javax.inject.Inject public DormantService(){new DormantDependency();} }
 public static class DormantDependency {}
 public static void main(String[] args){System.out.println("ONLY_ENTRY_EXECUTED");}
}
