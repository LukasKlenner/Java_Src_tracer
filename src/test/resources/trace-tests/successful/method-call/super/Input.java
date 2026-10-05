class Base {
    int getLength() {
        return 1;
    }
}

class Input extends Base{

    int getLength() {
        return super.getLength();
    }

    public static void main(String[] args) {
        Input obj = new Input();
        int len = obj.getLength();
    }
}
