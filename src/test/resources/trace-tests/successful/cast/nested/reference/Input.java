class Input {

    public Object m() {
        return "hello";
    }

    public static void main(String[] args) {
        Input obj = new Input();
        String result = (String) obj.m();
    }
}
