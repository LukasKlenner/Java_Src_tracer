import java.util.List;
import java.util.ArrayList;

class Input {
    public static void main(String[] args) {
        List<String> list = new ArrayList<>();
        list.add("a");
        list.add("b");
        int count = 0;
        for (String s : list) {
            count++;
        }
    }
}
