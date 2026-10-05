class Inner {
    int value = 99;
}

class Input {
    Inner inner;

    Input(Inner i) {
        this.inner = i;
    }

    public static void main(String[] args) {
        Input obj = new Input(new Inner());
        int x = obj.inner.value;
    }
}
