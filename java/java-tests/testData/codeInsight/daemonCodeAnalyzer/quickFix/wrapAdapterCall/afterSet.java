// "Adapt 1st argument using 'Collections.singleton()'" "true"
import java.util.*;

class Test {

  void method(Set<Long> set, double val) {

  }

  void m(long l) {
    method(Collections.singleton(l), l);
  }

}