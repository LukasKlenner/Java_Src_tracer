class Input {
    private int secret() {
        return 42;
    }

    int getSecret() {
        return secret();
    }

    public static void main(String[] args) {
        Input obj = new Input();
        int x = obj.getSecret();
    }
}
