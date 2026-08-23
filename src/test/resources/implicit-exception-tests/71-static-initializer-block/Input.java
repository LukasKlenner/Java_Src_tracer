class Input {
    static int[] data;

    static {
        data = new int[5];
        data[0] = 42;
    }

    public static void main(String[] args) {
        int x = data[0];
    }
}
