class Base {

    int value;

    Base(int v) {
        this.value = v;
    }

}

class Input extends Base {
    Input() {
        super(42);
    }

    public static void main(String[] args) {
        Input obj = new Input();
    }
}
