class Input {
    static Input instance = new Input();
    int value = 0;

    public static void main(String[] args) {
        int x = Input.instance.value;
    }
}
