package doTest.old.ir;


import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class IRIndexConce {

  public static final String DELIMITER = "#";
  
  static class KeyValue {
    public String key;
    public String value;
    public boolean isIndex;

    
    public KeyValue(String key, String value, boolean isIndex) {
      this.key = key;
      this.value = value;
      this.isIndex = isIndex;
    }

    @Override
    public String toString() {
      return key + ": " + value + ", " + (isIndex ? "indexed" : "not indexed");
    }

    public String toIndexKey() {
      return value + DELIMITER + key;
    }

    public static String getRowKeyFromFullIndexKey(String indexKey) {
      String temp[] = indexKey.split(DELIMITER);
      return temp[1];
    }

    public static String getRawValueFromFullIndexKey(String indexKey) {
      String temp[] = indexKey.split(DELIMITER);
      return temp[0];
    }
  }
  
  

  void fillKVList(List<KeyValue> kvList, String[] values) {
    for (int i = 1; i < values.length; ++i) {
      if (i != values.length - 1) {
        kvList.add(new KeyValue(values[0], values[i], true));
      } else {
        kvList.add(new KeyValue(values[0], values[i], false));
      }
    }
  }

  public void work() {
    String[] row1 = { "k1", "A-1", "B-1", "C-1", "info-1" };
    String[] row2 = { "k2", "A-2", "B-2", "C-2", "info-2" };
    List<KeyValue> kvList = new ArrayList<KeyValue>();
    fillKVList(kvList, row1);
    fillKVList(kvList, row2);
    printList(kvList);
    ArrayList<KeyValue> results = generateIndexedKVList(kvList);
    System.out.println("*********");
    printList(results);
  }

  private ArrayList<KeyValue> generateIndexedKVList(List<KeyValue> kvList) {
    // this maps of rowkeys. the value is a list of all possible new keys generated by index
    Map<String, ArrayList<String>> rowkeyMap = new HashMap<String, ArrayList<String>>();
    ArrayList<KeyValue> results = new ArrayList<IRIndexConce.KeyValue>();
    for (KeyValue kv : kvList) {
      if (rowkeyMap.containsKey(kv.key)) { // already has the rowkey, first get the list
        ArrayList<String> listOfIndexedKeys = rowkeyMap.get(kv.key);
        for (String str : listOfIndexedKeys) {
          results.add(new KeyValue(str, kv.value, false));
          if (kv.isIndex) {
            results.add(new KeyValue(kv.toIndexKey(), KeyValue.getRawValueFromFullIndexKey(str),
                false));
          }
        }
        if (kv.isIndex) { // add new KeyValue back
          listOfIndexedKeys.add(kv.toIndexKey());
        }
      } else { // new added rowkey
        ArrayList<String> listOfIndexedKeys = new ArrayList<String>();
        listOfIndexedKeys.add(kv.toIndexKey());
        rowkeyMap.put(kv.key, listOfIndexedKeys);
        // results.add(new KeyValue(kv.toIndexKey(), "", false));
      }
    }
    return results;
  }

  void printList(List<KeyValue> kvList) {
    for (KeyValue kv : kvList) {
      // System.out.println(kv);
    }
  }

  public static void main(String[] args) {
    double d = 005.23;
    String str = Double.toString(d);
    System.out.println(d);
    String s = "005.321";
    double aDouble = Double.parseDouble(s);

    System.out.println(aDouble);
    // new AID().work();
  }
}

