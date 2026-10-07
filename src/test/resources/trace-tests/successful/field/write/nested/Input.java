class Inner {
    int value;
}

class Input {
    Inner inner;

    Input(Inner i) {
        this.inner = i;
    }

    int getValue() {
        return 1;
    }

    public static void main(String[] args) {
        Input obj = new Input(new Inner());
        obj.inner.value = obj.getValue();
    }
}
