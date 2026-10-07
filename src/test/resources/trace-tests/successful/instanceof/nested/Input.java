class Input {

    Object getObject() {
        return new Input();
    }

    public static void main(String[] args) {
        Input obj = new Input();
        if (obj.getObject() instanceof Input) {
            int x = 1;
        }
    }
}
