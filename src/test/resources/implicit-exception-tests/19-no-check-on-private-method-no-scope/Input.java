class Input {
    private int helper(int x) {
        return x + 1;
    }

    int compute() {
        return helper(5);
    }

    public static void main(String[] args) {
        Input obj = new Input();
        int x = obj.compute();
    }
}
