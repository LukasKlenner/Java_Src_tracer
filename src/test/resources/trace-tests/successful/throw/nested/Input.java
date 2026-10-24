class Input {

    public static void main(String[] args) {
        Input obj = new Input();
        try {
            throw obj.getException();
        } catch (RuntimeException e) {
        }
    }

    public RuntimeException getException() {
        return new RuntimeException();
    }
}
