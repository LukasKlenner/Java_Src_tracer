class Base {
    int compute() {
        return 42;
    }
}

class Input extends Base {
    int compute() {
        return super.compute() + 1;
    }

    public static void main(String[] args) {
        Input obj = new Input();
        int x = obj.compute();
    }
}
