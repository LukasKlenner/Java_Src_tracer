import java.util.List;
import java.util.ArrayList;

class Input {

    public List getList() {
        List list = new ArrayList();
        list.add("a");
        list.add("b");
        return list;
    }

    public static void main(String[] args) {
        Input obj = new Input();
        List list = obj.getList();
        int count = 0;
        for (String s : list) {
            count++;
        }
    }
}
