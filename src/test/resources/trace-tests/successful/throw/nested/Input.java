class Input {

    public static void main(String[] args) {
        Input obj = new Input();
        throw obj.getException();
    }

    public RuntimeException getException() {
        throw new RuntimeException();
    }
}
