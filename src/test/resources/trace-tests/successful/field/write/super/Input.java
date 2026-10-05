class Base {
    public int value;
}

class Input extends Base {

    public void fieldWrite() {
        super.value = 1;
    }

    public static void main(String[] args) {
        Input obj = new Input();
        obj.fieldWrite();
    }
}
