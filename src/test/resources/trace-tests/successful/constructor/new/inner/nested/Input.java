class Outer {
    class Inner {
        int value;
        Inner(int v) {
            this.value = v;
        }
    }
}

class Input {

    Outer getOuter() {
        return new Outer();
    }

    public static void main(String[] args) {
        Input obj = new Input();
        Outer.Inner inner = obj.getOuter().new Inner(42);
    }
}
