class Base {

    int value = 42;

    int compute() {
        return 42;
    }
}

class Input extends Base {
    int compute() {
        int a = super.compute() + 1;
        return super.value;
    }

    public static void main(String[] args) {
        Input obj = new Input();
        int x = obj.compute();
    }
}
