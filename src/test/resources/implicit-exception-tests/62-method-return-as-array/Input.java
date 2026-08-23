class Input {
    int[] getArray() {
        return new int[]{10, 20, 30};
    }

    public static void main(String[] args) {
        Input obj = new Input();
        int x = obj.getArray()[0];
    }
}
