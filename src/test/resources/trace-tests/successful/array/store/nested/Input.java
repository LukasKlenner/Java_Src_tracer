class Input {

    public int getIndex() {
        return 0;
    }

    public static void main(String[] args) {
        Input obj = new Input();
        int[] arr = new int[3];
        arr[obj.getIndex()] = 42;
    }
}
