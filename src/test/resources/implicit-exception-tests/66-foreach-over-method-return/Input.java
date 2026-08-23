class Input {
    int[] getArray() {
        return new int[]{1, 2, 3};
    }

    public static void main(String[] args) {
        Input obj = new Input();
        int sum = 0;
        for (int x : obj.getArray()) {
            sum += x;
        }
    }
}
