class Outer {
    class Inner {
        int value;
        Inner(int v) {
            this.value = v;
        }
    }
}

class Input {
    public static void main(String[] args) {
        Outer outer = new Outer();
        Outer.Inner inner = outer.new Inner(42);
    }
}
