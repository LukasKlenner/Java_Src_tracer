class Base {
    public int value = 42;
}

class Input extends Base {

    public void fieldAccess() {
        int a = super.value;
    }

    public static void main(String[] args) {
        Input obj = new Input();
        obj.fieldAccess();
    }
}
