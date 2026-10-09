import com.simon.voiceime.T9Contract;import java.util.*;
public class T9ContractTest {
 public static void main(String[] args){
  List<String> alt=Arrays.asList("輕青清","","");
  assert T9Contract.filter("請付款",alt,"，輕付，，款。").equals("輕付，款。");
  assert T9Contract.filter("請付款",alt,"輕付錢。").equals("請付款");
  assert T9Contract.filter("請付款",alt,"1付款").equals("請付款");
  assert T9Contract.filter("請付款",alt,"請付款。\n說明").equals("請付款。");
  assert T9Contract.filter("請付款",alt,"請 付款").equals("請付款");
  assert T9Contract.draftPunctuation("請付款","輕付，款。").equals("請付，款。");
  assert T9Contract.changed("請付款","請付款。")==0;
  assert T9Contract.changed("請付款","輕付款。")==1;
  System.out.println("T9 prompt contract fixtures PASS");
 }
}
